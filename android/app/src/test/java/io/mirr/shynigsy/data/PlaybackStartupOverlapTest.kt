package io.mirr.shynigsy.data

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
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

/** Response barriers prove saved startup round trips without timing an overloaded test machine. */
class PlaybackStartupOverlapTest {
    private lateinit var server: MockWebServer
    private lateinit var address: String
    private val item = MediaItem(24, 9, "합성 초기 재생", "episode", durationMs = 10_000,
        resumeMs = 1_000, viewItemId = 601, seasonNumber = 1, episodeNumber = 4)
    private val requests = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val subtitlePath = "/subtitles/90"
    private val progressPath = "/playback/progress"

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        address = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    }
    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }
    private fun client(http: OkHttpClient = OkHttpClient()) = ShyniClient(address, "fixture-account", http, "fixture-device")
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun decision(mode: String? = null) = json("""{"selected":{"part_id":90,"duration_ms":1800000,"audio_codec":"eac3"},
        "library_view_id":9,"view_item_id":602,"playback_context_token":"fixture-context",
        "selected_audio_stream_index":2,"selected_audio_stream_ordinal":1,"selected_audio_language":"ko",
        "subtitle":{"id":4},"client_playback_hints":{"prefer_software_decode":true}
        ${if (mode == null) "" else ",\"transcode\":{\"mode\":\"$mode\",\"max_height\":720,\"audio_stream_index\":2}"}}""")
    private fun subtitles() = json("""[{"id":4,"source":"embedded","format":"ass","lang_code":"ko","stream_index":3,"playable":true}]""")
    private fun progress() = json("""{"24":{"position_ms":65000,"duration_ms":120000,"completed":false}}""")
    private fun dispatch(reply: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return reply(request) }
        }
    }
    private fun assertResult(result: PlaybackSpec) {
        assertEquals(24, result.itemId); assertEquals(90, result.partId)
        assertEquals(9, result.libraryId); assertEquals(602, result.viewItemId)
        assertEquals(65_000L, result.resumeMs); assertEquals(1_800_000L, result.durationMs)
        assertEquals(2, result.selectedAudioStreamIndex); assertEquals("ko", result.selectedAudioLanguage)
        assertEquals("eac3", result.selectedAudioCodec); assertEquals(4, result.selectedSubtitleId)
        assertTrue(result.preferSoftwareDecode); assertEquals("fixture-context", result.progressToken)
        assertTrue(result.headers.isEmpty()); assertFalse(result.toJson().contains("fixture-account"))
    }

    @Test fun originalTicketCanWaitForSubtitleListingWithoutAddingAnotherStartupRoundTrip() { runBlocking {
        withTimeout(20_000) {
            val subtitleArrived = CountDownLatch(1)
            val submitted = java.util.Collections.synchronizedList(mutableListOf<String>())
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { submitted += call.request().url.encodedPath }
            }).build()
            dispatch { request -> when (request.requestUrl!!.encodedPath) {
                "/playback/decision" -> decision()
                "/stream-ticket/90" -> {
                    if (!subtitleArrived.await(5, TimeUnit.SECONDS)) json("{}", 503)
                    else json("""{"ticket":"original-ticket"}""")
                }
                subtitlePath -> { subtitleArrived.countDown(); subtitles() }
                progressPath -> progress()
                else -> json("{}", 404)
            } }
            val result = client(http).playback(item)
            assertResult(result)
            assertEquals("original-ticket", result.url.toHttpUrl().queryParameter("ticket"))
            assertEquals("original-ticket", result.subtitles.single().url.toHttpUrl().queryParameter("ticket"))
            assertEquals("embedded", result.subtitles.single().source)
            assertEquals(listOf("/playback/decision", "/stream-ticket/90"), submitted.take(2))
            assertEquals(4, requests.size)
            requests.drop(1).forEach {
                assertEquals("Bearer fixture-account", it.getHeader("Authorization"))
                assertEquals("fixture-device", it.getHeader("X-Device-Id"))
                if (it.requestUrl!!.encodedPath != progressPath)
                    assertEquals("fixture-context", it.requestUrl!!.queryParameter("playback_context_token"))
            }
        }
    } }

    @Test fun automaticConversionsOverlapDistinctMediaAndSubtitleTicketsWithoutChangingDelivery() { runBlocking {
        withTimeout(30_000) {
            for (mode in listOf("remux", "audio", "video")) {
                val tickets = CountDownLatch(2)
                dispatch { request -> when (request.requestUrl!!.encodedPath) {
                    "/playback/decision" -> decision(mode)
                    "/playback/transcode-ticket/90", "/stream-ticket/90" -> {
                        tickets.countDown()
                        if (!tickets.await(5, TimeUnit.SECONDS)) json("{}", 503)
                        else json("""{"ticket":"${if (request.requestUrl!!.encodedPath.startsWith("/playback/")) "video-ticket" else "subtitle-ticket"}"}""")
                    }
                    subtitlePath -> subtitles()
                    progressPath -> progress()
                    else -> json("{}", 404)
                } }
                val result = client().playback(item)
                assertResult(result)
                assertEquals(mode, result.url.toHttpUrl().queryParameter("mode"))
                assertEquals("video-ticket", result.url.toHttpUrl().queryParameter("ticket"))
                assertEquals("subtitle-ticket", result.subtitles.single().url.toHttpUrl().queryParameter("ticket"))
                assertEquals("external", result.subtitles.single().source)
                assertEquals(0, result.selectedAudioStreamOrdinal)
                assertEquals(if (mode == "video") 720 else null, result.conversionHeight)
                assertEquals(0L, result.streamStartPositionMs)
                assertFalse("Automatic original selection must not invent an AAC output proof", result.aacConversionContext)
            }
            assertEquals(15, requests.size)
        }
    } }

    @Test fun aDeniedMediaTicketCancelsPendingSubtitleMetadataAndNeverReturnsAStream() { runBlocking {
        withTimeout(20_000) {
            val subtitleArrived = CountDownLatch(1)
            val subtitleCancelled = CountDownLatch(1)
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    if (call.request().url.encodedPath == subtitlePath) subtitleCancelled.countDown()
                }
            }).build()
            dispatch { request -> when (request.requestUrl!!.encodedPath) {
                "/playback/decision" -> decision()
                "/stream-ticket/90" -> {
                    if (!subtitleArrived.await(5, TimeUnit.SECONDS)) json("{}", 500)
                    else json("""{"detail":"synthetic ticket denial"}""", 403)
                }
                subtitlePath -> { subtitleArrived.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                progressPath -> progress()
                else -> json("{}", 404)
            } }
            val failure = runCatching { client(http).playback(item) }.exceptionOrNull() as ApiException
            assertEquals(403, failure.status); assertTrue(failure.message.orEmpty().contains("재생 티켓 발급"))
            assertTrue(withContext(Dispatchers.IO) { subtitleCancelled.await(5, TimeUnit.SECONDS) })
            assertTrue(requests.none { it.requestUrl!!.encodedPath == "/stream/90" })
        }
    } }

    @Test fun cancellationStopsTheMandatoryTicketAndBothEnqueuedMetadataReads() { runBlocking {
        withTimeout(20_000) {
            val submitted = CountDownLatch(3)
            val arrived = CountDownLatch(2)
            val cancelled = CountDownLatch(3)
            val paths = setOf("/stream-ticket/90", subtitlePath, progressPath)
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { if (call.request().url.encodedPath in paths) submitted.countDown() }
                override fun callFailed(call: Call, ioe: IOException) { if (call.request().url.encodedPath in paths) cancelled.countDown() }
            }).build()
            dispatch { request -> when (request.requestUrl!!.encodedPath) {
                "/playback/decision" -> decision()
                in paths -> { arrived.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                else -> json("{}", 404)
            } }
            val pending = async(Dispatchers.Default) { client(http).playback(item) }
            try {
                assertTrue(submitted.await(5, TimeUnit.SECONDS)); assertTrue(arrived.await(5, TimeUnit.SECONDS))
                pending.cancelAndJoin()
                assertTrue(cancelled.await(5, TimeUnit.SECONDS)); assertTrue(pending.isCancelled)
            } finally { pending.cancelAndJoin() }
        }
    } }

    @Test fun lateTicketsCannotSurviveAnAccountChangeLockOrReplacementPrivateGrant() { runBlocking {
        withTimeout(30_000) {
            for (change in listOf("account", "lock", "grant")) {
                val ticketArrived = CountDownLatch(1)
                val release = CountDownLatch(1)
                val unlocks = AtomicInteger()
                dispatch { request -> when (request.requestUrl!!.encodedPath) {
                    "/spoiler/client/unlock" -> json("""{"token":"spc_fixture-${unlocks.incrementAndGet()}","expires_in":1800}""")
                    "/playback/decision" -> decision()
                    "/stream-ticket/90" -> {
                        ticketArrived.countDown()
                        if (!release.await(5, TimeUnit.SECONDS)) json("{}", 500) else json("""{"ticket":"late-ticket"}""")
                    }
                    subtitlePath -> subtitles()
                    progressPath -> progress()
                    else -> json("{}", 404)
                } }
                val active = client()
                active.unlockSpoiler("1234")
                val pending = async(Dispatchers.Default) { runCatching { active.playback(item.copy(isSpoiler = true)) } }
                try {
                    assertTrue(ticketArrived.await(5, TimeUnit.SECONDS))
                    when (change) {
                        "account" -> active.token = "other-account"
                        "lock" -> active.clearSpoilerGrant()
                        else -> active.unlockSpoiler("5678")
                    }
                    release.countDown()
                    assertEquals(if (change == "account") 401 else 403, (pending.await().exceptionOrNull() as ApiException).status)
                    assertEquals(change == "grant", active.spoilerUnlocked)
                    if (change == "grant") assertEquals("spc_fixture-2", active.headersFor(address).getValue("X-Shyni-Spoiler"))
                } finally { release.countDown(); pending.cancelAndJoin(); active.clearSpoilerGrant() }
            }
        }
    } }

    @Test fun episodeRecoveryStillValidatesItsExactBoundTicketBeforeAnyMetadataIsEnqueued() { runBlocking {
        withTimeout(20_000) {
            val ticketArrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val metadataSubmitted = AtomicInteger()
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    if (call.request().url.encodedPath in setOf(subtitlePath, progressPath)) metadataSubmitted.incrementAndGet()
                }
            }).build()
            dispatch { request -> when (request.requestUrl!!.encodedPath) {
                "/playback/decision" -> json("""{"detail":"에피소드 정체성을 확인할 수 없습니다"}""", 404)
                "/stream-ticket/90" -> {
                    ticketArrived.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) json("{}", 500)
                    else json("""{"ticket":"recovery-ticket","library_view_id":9,"view_item_id":602,"part_id":90}""")
                }
                subtitlePath -> subtitles()
                progressPath -> progress()
                else -> json("{}", 404)
            } }
            val pending = async(Dispatchers.Default) { client(http).playback(item.copy(playbackPartId = 90)) }
            try {
                assertTrue(ticketArrived.await(5, TimeUnit.SECONDS))
                assertEquals(0, metadataSubmitted.get())
                release.countDown()
                val result = pending.await()
                assertEquals(90, result.partId); assertEquals(602, result.viewItemId)
                assertNull(result.progressToken); assertEquals(65_000L, result.resumeMs)
                assertEquals("recovery-ticket", result.url.toHttpUrl().queryParameter("ticket"))
                assertEquals(2, metadataSubmitted.get())
                val listed = requests.single { it.requestUrl!!.encodedPath == subtitlePath }.requestUrl!!
                assertEquals("9", listed.queryParameter("library_view_id")); assertEquals("602", listed.queryParameter("view_item_id"))
            } finally { release.countDown(); pending.cancelAndJoin() }
        }
    } }
}
