package io.mirr.shynigsy.data

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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ShyniRequestSchedulingTest {
    private lateinit var server: MockWebServer
    private val movie = MediaItem(24, 9, "합성 영화", "movie", durationMs = 600_000, viewItemId = 601)
    private val current = PlaybackSpec("합성 영화", "https://example.invalid/stream/90", itemId = 24,
        libraryId = 9, partId = 90, durationMs = 600_000, viewItemId = 601,
        progressToken = "fixture-context", sessionId = "fixture-session", sourceItem = movie)

    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun close() { server.shutdown() }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun playbackResponse(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
        "/playback/decision" -> json("""{"selected":{"part_id":90,"duration_ms":600000},
            "library_view_id":9,"view_item_id":601,"playback_context_token":"fixture-context"}""")
        "/stream-ticket/90" -> json("""{"ticket":"fixture-ticket"}""")
        "/subtitles/90" -> json("[]")
        "/playback/progress" -> if (request.method == "GET")
            json("""{"24":{"position_ms":15000,"duration_ms":600000,"completed":false}}""") else json("""{"ok":true}""")
        else -> json("{}", 404)
    }

    private suspend fun await(latch: CountDownLatch) {
        assertTrue("Expected requests must arrive before their responses are released",
            withContext(Dispatchers.IO) { latch.await(10, TimeUnit.SECONDS) })
    }

    @Test fun queuedSlowCatalogDoesNotStarvePlaybackMetadataOrHistoryWrites() = runBlocking {
        withTimeout(30_000) {
            val catalogSubmitted = CountDownLatch(5)
            val catalogArrived = CountDownLatch(2)
            val metadataArrived = CountDownLatch(2)
            val releaseMetadata = CountDownLatch(1)
            val catalogCount = AtomicInteger()
            val progressWrites = AtomicInteger()
            val completionWrites = AtomicInteger()
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    if (call.request().url.encodedPath.startsWith("/libraries/9/movies/")) catalogSubmitted.countDown()
                }
            }).build()
            val client = ShyniClient(server.url("/").toString(), "fixture-account", http, "fixture-device")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("Bearer fixture-account", request.getHeader("Authorization"))
                    assertEquals("fixture-device", request.getHeader("X-Device-Id"))
                    val path = request.requestUrl!!.encodedPath
                    if (path.startsWith("/libraries/9/movies/")) {
                        catalogCount.incrementAndGet(); catalogArrived.countDown()
                        return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    if (path == "/subtitles/90" || (path == "/playback/progress" && request.method == "GET")) {
                        metadataArrived.countDown()
                        if (!releaseMetadata.await(10, TimeUnit.SECONDS)) return json("{}", 500)
                    }
                    if (path == "/playback/progress" && request.method == "POST") {
                        val body = JSONObject(request.body.readUtf8())
                        assertEquals(90, body.getInt("part_id"))
                        assertEquals(9, body.getInt("library_view_id"))
                        assertEquals(601, body.getInt("view_item_id"))
                        assertEquals(15_000L, body.getLong("position_ms"))
                        assertFalse(body.getBoolean("completion_eligible"))
                        progressWrites.incrementAndGet()
                    }
                    if (path == "/libraries/9/media_info/90") return json(
                        """{"item":{"id":24},"parts":[{"id":90,"metadata_item_id":24}]}""")
                    if (path == "/continue-watching/24/watch-state") {
                        completionWrites.incrementAndGet()
                        return json("""{"ok":true,"watched":true}""")
                    }
                    return playbackResponse(request)
                }
            }
            val browsing = List(5) { index -> async { client.details(movie.copy(id = 100 + index)) } }
            val preparing = async {
                await(catalogSubmitted); await(catalogArrived)
                client.playback(movie)
            }
            try {
                // No latency threshold: both playback reads must arrive while all catalog
                // responses and both playback responses are deliberately still pending.
                await(metadataArrived)
                client.saveProgress(current, 15_000, 600_000, "paused", watchedMs = 0, completionEligible = false)
                client.markPlaybackWatched(current)
                assertEquals(1, progressWrites.get())
                assertEquals(1, completionWrites.get())
                assertEquals("Only two catalog reads may occupy the server", 2, catalogCount.get())
                assertTrue(browsing.none { it.isCompleted })
                releaseMetadata.countDown()
                val spec = preparing.await()
                assertEquals(90, spec.partId)
                assertEquals(15_000L, spec.resumeMs)
                assertEquals(601, spec.viewItemId)
                assertEquals("fixture-context", spec.progressToken)
            } finally {
                releaseMetadata.countDown()
                preparing.cancelAndJoin()
                browsing.forEach { it.cancel() }
                browsing.forEach { it.join() }
            }
        }
    }

    @Test fun cancelledCatalogRequestsNeverReachTheServerAfterTheirQueuedSlotOpens() = runBlocking {
        withTimeout(30_000) {
            val arrived = CountDownLatch(2)
            val submitted = CountDownLatch(5)
            val catalogCount = AtomicInteger()
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    if (call.request().url.encodedPath.startsWith("/libraries/9/movies/")) submitted.countDown()
                }
            }).build()
            val client = ShyniClient(server.url("/").toString(), "fixture-account", http)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.requestUrl!!.encodedPath.startsWith("/libraries/9/movies/") -> {
                        catalogCount.incrementAndGet(); arrived.countDown()
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    request.requestUrl!!.encodedPath == "/libraries" -> json("[]")
                    else -> json("{}", 404)
                }
            }
            val occupying = List(2) { index -> async { client.details(movie.copy(id = 100 + index)) } }
            val queued = mutableListOf<kotlinx.coroutines.Deferred<MediaDetails>>()
            try {
                await(arrived)
                repeat(3) { index -> queued += async { client.details(movie.copy(id = 200 + index)) } }
                await(submitted)
                queued.forEach { it.cancelAndJoin() }
                occupying.forEach { it.cancelAndJoin() }
                assertTrue(client.libraries().isEmpty())
                assertEquals("Cancelled queued reads must not be sent when capacity returns", 2, catalogCount.get())
            } finally {
                queued.forEach { it.cancelAndJoin() }
                occupying.forEach { it.cancelAndJoin() }
            }
        }
    }

    @Test fun blockedHistoryWriteDoesNotBlockPlaybackAndFollowingWritesRetainOrder() = runBlocking {
        withTimeout(30_000) {
            val firstWriteArrived = CountDownLatch(1)
            val releaseFirstWrite = CountDownLatch(1)
            val secondWriteSubmitted = CountDownLatch(1)
            val writes = java.util.Collections.synchronizedList(mutableListOf<Long>())
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    if (call.request().url.encodedPath == "/playback/progress" && call.request().method == "POST" &&
                        firstWriteArrived.count == 0L) secondWriteSubmitted.countDown()
                }
            }).build()
            val client = ShyniClient(server.url("/").toString(), "fixture-account", http)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.encodedPath == "/playback/progress" && request.method == "POST") {
                        val position = JSONObject(request.body.readUtf8()).getLong("position_ms")
                        writes += position
                        if (position == 10_000L) {
                            firstWriteArrived.countDown()
                            if (!releaseFirstWrite.await(10, TimeUnit.SECONDS)) return json("{}", 500)
                        }
                        return json("""{"ok":true}""")
                    }
                    return playbackResponse(request)
                }
            }
            val first = async { client.saveProgress(current, 10_000, 600_000, "playing", completionEligible = false) }
            await(firstWriteArrived)
            val second = async { client.saveProgress(current, 20_000, 600_000, "paused", completionEligible = false) }
            try {
                await(secondWriteSubmitted)
                val spec = client.playback(movie)
                assertEquals(15_000L, spec.resumeMs)
                assertEquals(listOf(10_000L), writes.toList())
                releaseFirstWrite.countDown()
                first.await(); second.await()
                assertEquals(listOf(10_000L, 20_000L), writes.toList())
            } finally {
                releaseFirstWrite.countDown()
                first.cancelAndJoin(); second.cancelAndJoin()
            }
        }
    }
}
