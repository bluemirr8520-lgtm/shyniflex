package io.mirr.shynigsy.data

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackPreparationConcurrencyTest {
    private lateinit var server: MockWebServer
    private val item = MediaItem(24, 9, "합성 4K 영상", "episode", viewItemId = 601,
        durationMs = 1_800_000, resumeMs = 5_000)
    private val subtitlePath = "/subtitles/90"
    private val progressPath = "/playback/progress"

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { start() }
    }

    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }

    private fun client(http: OkHttpClient = OkHttpClient()) = ShyniClient(
        server.url("/").toString(), "fixture-account", http, "fixture-device")

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun decision(converted: Boolean = false) = json("""{
        "selected":{"part_id":90,"duration_ms":1800000},"library_view_id":9,"view_item_id":602,
        "playback_context_token":"fixture-context","selected_audio_stream_index":2,
        "selected_audio_stream_ordinal":1,"selected_audio_language":"ko","subtitle":{"id":4}
        ${if (converted) ",\"decision\":\"video_transcode\",\"transcode\":{\"mode\":\"video\",\"max_height\":720,\"audio_stream_index\":2}" else ""}
    }""")

    private fun subtitle() = json("""[{"id":4,"source":"embedded","format":"ass",
        "lang_code":"ko","stream_index":3,"playable":true}]""")

    private fun progress() = json("""{"24":{"position_ms":65000,"duration_ms":1800000,"completed":false}}""")

    @Test fun independentReadsBothArriveBeforeEitherResponseIsReleased() = runBlocking {
        withTimeout(30_000) {
            for (converted in listOf(false, true)) {
                val reads = CountDownLatch(2)
                val ticketCount = AtomicInteger()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                        "/playback/decision" -> decision(converted)
                        "/playback/transcode-ticket/90", "/stream-ticket/90" -> {
                            ticketCount.incrementAndGet()
                            json("""{"ticket":"fixture-ticket"}""")
                        }
                        subtitlePath, progressPath -> {
                            assertTrue("Metadata must follow a scoped playback ticket", ticketCount.get() > 0)
                            if (request.requestUrl!!.encodedPath == subtitlePath) {
                                assertEquals("fixture-context", request.requestUrl!!.queryParameter("playback_context_token"))
                            } else {
                                assertEquals("9", request.requestUrl!!.queryParameter("library_view_id"))
                                assertEquals("24", request.requestUrl!!.queryParameter("item_ids"))
                            }
                            reads.countDown()
                            if (!reads.await(10, TimeUnit.SECONDS)) json("""{"detail":"Metadata requests were serialized"}""", 500)
                            else if (request.requestUrl!!.encodedPath == subtitlePath) subtitle() else progress()
                        }
                        else -> json("{}", 404)
                    }
                }
                val result = client().playback(item)
                assertEquals(0L, reads.count)
                assertEquals(65_000L, result.resumeMs)
                assertEquals(90, result.partId)
                assertEquals(602, result.viewItemId)
                assertEquals(2, result.selectedAudioStreamIndex)
                assertEquals("ko", result.selectedAudioLanguage)
                assertEquals(4, result.selectedSubtitleId)
                assertEquals(if (converted) 720 else null, result.conversionHeight)
                assertEquals(if (converted) "external" else "embedded", result.subtitles.single().source)
                assertEquals(if (converted) 2 else 1, ticketCount.get())
            }
        }
    }

    @Test fun eitherMetadataFailureCancelsItsPendingSiblingAndRetainsTheFailingStage() = runBlocking {
        withTimeout(30_000) {
            for (failedPath in listOf(subtitlePath, progressPath)) {
                val pendingPath = if (failedPath == subtitlePath) progressPath else subtitlePath
                val pendingArrived = CountDownLatch(1)
                val pendingCancelled = CompletableDeferred<Unit>()
                val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                    override fun callFailed(call: Call, ioe: IOException) {
                        if (call.request().url.encodedPath == pendingPath) pendingCancelled.complete(Unit)
                    }
                }).build()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                        "/playback/decision" -> decision()
                        "/stream-ticket/90" -> json("""{"ticket":"fixture-ticket"}""")
                        pendingPath -> {
                            pendingArrived.countDown()
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        }
                        failedPath -> {
                            if (!pendingArrived.await(10, TimeUnit.SECONDS)) json("{}", 500)
                            else json("""{"detail":"Synthetic preparation denial"}""", 403)
                        }
                        else -> json("{}", 404)
                    }
                }
                val failure = runCatching { client(http).playback(item) }.exceptionOrNull() as ApiException
                pendingCancelled.await()
                assertEquals(403, failure.status)
                assertTrue(failure.message.orEmpty().contains(if (failedPath == subtitlePath) "자막 정보 조회" else "이어보기 위치 조회"))
                assertEquals(403, (failure.cause as ApiException).status)
                assertFalse(failure.message.orEmpty().contains("fixture-context"))
            }
        }
    }

    @Test fun cancellingPreparationCancelsBothIndependentHttpCalls() = runBlocking {
        withTimeout(30_000) {
            val arrived = CountDownLatch(2)
            val cancelled = CountDownLatch(2)
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    if (call.request().url.encodedPath in setOf(subtitlePath, progressPath)) cancelled.countDown()
                }
            }).build()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/playback/decision" -> decision()
                    "/stream-ticket/90" -> json("""{"ticket":"fixture-ticket"}""")
                    subtitlePath, progressPath -> {
                        arrived.countDown()
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    else -> json("{}", 404)
                }
            }
            var returned = false
            val preparing = launch { client(http).playback(item); returned = true }
            try {
                assertTrue(withContext(Dispatchers.IO) { arrived.await(10, TimeUnit.SECONDS) })
                preparing.cancelAndJoin()
                assertTrue(withContext(Dispatchers.IO) { cancelled.await(10, TimeUnit.SECONDS) })
                assertTrue(preparing.isCancelled)
                assertFalse(returned)
            } finally { preparing.cancelAndJoin() }
        }
    }

    @Test fun lateParallelResponsesCannotSurviveLockAccountChangeOrReplaceANewerGrant() = runBlocking {
        withTimeout(45_000) {
            for (change in listOf("lock", "account", "unlock")) {
                val arrived = CountDownLatch(2)
                val release = CountDownLatch(1)
                val unlocks = AtomicInteger()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                        "/spoiler/client/unlock" -> json("""{"token":"spc_fixture-${unlocks.incrementAndGet()}","expires_in":1800}""")
                        "/playback/decision" -> decision()
                        "/stream-ticket/90" -> json("""{"ticket":"fixture-ticket"}""")
                        subtitlePath, progressPath -> {
                            assertEquals("spc_fixture-1", request.getHeader("X-Shyni-Spoiler"))
                            arrived.countDown()
                            if (!release.await(10, TimeUnit.SECONDS)) json("{}", 500)
                            else if (request.requestUrl!!.encodedPath == subtitlePath) subtitle() else progress()
                        }
                        else -> json("{}", 404)
                    }
                }
                val client = client()
                client.unlockSpoiler("1234")
                val result = async { runCatching { client.playback(item.copy(isSpoiler = true)) } }
                try {
                    assertTrue(withContext(Dispatchers.IO) { arrived.await(10, TimeUnit.SECONDS) })
                    when (change) {
                        "lock" -> client.clearSpoilerGrant()
                        "account" -> client.token = "different-account"
                        "unlock" -> client.unlockSpoiler("5678")
                    }
                    release.countDown()
                    val failure = result.await().exceptionOrNull() as ApiException
                    assertEquals(if (change == "account") 401 else 403, failure.status)
                    assertEquals(change == "unlock", client.spoilerUnlocked)
                    if (change == "unlock") assertEquals("spc_fixture-2",
                        client.headersFor(server.url("/poster").toString())["X-Shyni-Spoiler"])
                } finally {
                    release.countDown()
                    result.cancelAndJoin()
                    client.clearSpoilerGrant()
                }
            }
        }
    }
}
