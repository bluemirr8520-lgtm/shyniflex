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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class DeferredPlaybackSubtitlesTest {
    private val item = MediaItem(23, 8, "movie", "movie", viewItemId = 501, resumeMs = 1000)
    private fun json(value: String, status: Int = 200) = MockResponse().setResponseCode(status).setBody(value)
    private val row = """[{"id":4,"source":"embedded","format":"ass","lang_code":"ko","playable":true,"stream_index":3}]"""

    @Test fun slowSubtitlesDoNotDelayAuthorizedStartAndSelectedTrackSurvivesSerialization() = runBlocking {
        withTimeout(15_000) {
            val subtitlesArrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/playback/decision" -> json("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"playback_context_token":"context","subtitle":{"id":4}}""")
                    "/stream-ticket/90" -> json("""{"ticket":"original"}""")
                    "/playback/progress" -> json("""{"23":{"position_ms":65000,"duration_ms":120000}}""")
                    "/subtitles/90" -> { subtitlesArrived.countDown(); release.await(5, TimeUnit.SECONDS); json(row) }
                    else -> json("{}", 404)
                }
            }
            server.start()
            try {
                val client = ShyniClient(server.url("/").toString(), "account")
                val spec = client.playback(item, deferSubtitles = true)
                assertTrue(spec.subtitlesPending); assertTrue(spec.subtitles.isEmpty())
                assertEquals(65000L, spec.resumeMs); assertEquals(4, spec.selectedSubtitleId)
                assertEquals(3, server.requestCount)
                val restored = PlaybackSpec.fromJson(spec.toJson())
                assertTrue(restored.subtitlesPending); assertEquals(4, restored.selectedSubtitleId)
                val pending = async(Dispatchers.Default) { client.playbackSubtitles(restored) }
                try {
                    assertTrue(withContext(Dispatchers.IO) { subtitlesArrived.await(5, TimeUnit.SECONDS) })
                    assertFalse(pending.isCompleted)
                    release.countDown()
                    val track = pending.await().single()
                    assertEquals(4, track.id); assertEquals("embedded", track.source); assertEquals(3, track.streamIndex)
                    assertTrue(track.url.contains("ticket=original"))
                } finally { release.countDown(); pending.cancelAndJoin() }
            } finally { release.countDown(); server.shutdown() }
        }
    }

    @Test fun convertedSubtitlesUseOriginalTicketAndKeepOriginalClock() = runBlocking {
        withTimeout(15_000) {
            val tickets = AtomicInteger()
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/playback/decision" -> json("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"playback_context_token":"context","transcode":{"mode":"video","max_height":720}}""")
                    "/playback/transcode-ticket/90" -> json("""{"ticket":"converted"}""")
                    "/stream-ticket/90" -> { tickets.incrementAndGet(); json("""{"ticket":"original","library_view_id":8,"view_item_id":501,"part_id":90}""") }
                    "/playback/progress" -> json("{}")
                    "/subtitles/90" -> json(row)
                    else -> json("{}", 404)
                }
            }
            server.start()
            try {
                val client = ShyniClient(server.url("/").toString(), "account")
                val spec = client.playback(item, deferSubtitles = true)
                assertEquals(0, tickets.get()); assertTrue(spec.isServerConversion)
                val track = client.playbackSubtitles(spec).single()
                assertEquals(1, tickets.get()); assertEquals("external", track.source)
                assertNull(track.streamIndex); assertTrue(track.url.contains("ticket=original"))
                assertFalse(track.url.contains("ticket=converted"))
            } finally { server.shutdown() }
        }
    }

    @Test fun rejectsMismatchedContextAndAccountChangesDuringSubtitleLoad() = runBlocking {
        withTimeout(15_000) {
            val arrived = CountDownLatch(1); val release = CountDownLatch(1)
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/playback/decision" -> json("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"playback_context_token":"context"}""")
                    "/stream-ticket/90" -> json("""{"ticket":"original"}""")
                    "/playback/progress" -> json("{}")
                    "/subtitles/90" -> { arrived.countDown(); release.await(5, TimeUnit.SECONDS); json(row) }
                    else -> json("{}", 404)
                }
            }
            server.start()
            try {
                val client = ShyniClient(server.url("/").toString(), "account")
                val spec = client.playback(item, deferSubtitles = true)
                assertTrue(runCatching { client.playbackSubtitles(spec.copy(partId = 91)) }.isFailure)
                assertEquals(3, server.requestCount)
                val pending = async(Dispatchers.Default) { runCatching { client.playbackSubtitles(spec) } }
                try {
                    assertTrue(withContext(Dispatchers.IO) { arrived.await(5, TimeUnit.SECONDS) })
                    client.token = "other"
                    release.countDown()
                    assertEquals(401, (pending.await().exceptionOrNull() as ApiException).status)
                } finally { release.countDown(); pending.cancelAndJoin() }
            } finally { release.countDown(); server.shutdown() }
        }
    }

    @Test fun privateGrantLockInvalidatesAnInFlightSubtitleResult() = runBlocking {
        withTimeout(15_000) {
            val arrived = CountDownLatch(1); val release = CountDownLatch(1)
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/spoiler/client/unlock" -> json("""{"token":"spc_fixture-grant","expires_in":1800}""")
                    "/playback/decision" -> json("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"playback_context_token":"context"}""")
                    "/stream-ticket/90" -> json("""{"ticket":"original"}""")
                    "/playback/progress" -> json("{}")
                    "/subtitles/90" -> {
                        if (request.getHeader("X-Shyni-Spoiler") != "spc_fixture-grant") json("{}", 403)
                        else { arrived.countDown(); release.await(5, TimeUnit.SECONDS); json(row) }
                    }
                    else -> json("{}", 404)
                }
            }
            server.start()
            try {
                val client = ShyniClient(server.url("/").toString(), "account")
                client.unlockSpoiler("1234")
                val spec = client.playback(item.copy(isSpoiler = true), deferSubtitles = true)
                val pending = async(Dispatchers.Default) { runCatching { client.playbackSubtitles(spec) } }
                try {
                    assertTrue(withContext(Dispatchers.IO) { arrived.await(5, TimeUnit.SECONDS) })
                    client.clearSpoilerGrant(); release.countDown()
                    assertEquals(403, (pending.await().exceptionOrNull() as ApiException).status)
                } finally { release.countDown(); pending.cancelAndJoin(); client.clearSpoilerGrant() }
            } finally { release.countDown(); server.shutdown() }
        }
    }

    @Test fun cancellingTheDeferredLookupDoesNotReturnLateSubtitles() = runBlocking {
        withTimeout(15_000) {
            val arrived = CountDownLatch(1); val release = CountDownLatch(1)
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/playback/decision" -> json("""{"selected":{"part_id":90},"library_view_id":8,"view_item_id":501,"playback_context_token":"context"}""")
                    "/stream-ticket/90" -> json("""{"ticket":"original"}""")
                    "/playback/progress" -> json("{}")
                    "/subtitles/90" -> { arrived.countDown(); release.await(5, TimeUnit.SECONDS); json(row) }
                    else -> json("{}", 404)
                }
            }
            server.start()
            try {
                val client = ShyniClient(server.url("/").toString(), "account")
                val spec = client.playback(item, deferSubtitles = true)
                val pending = async(Dispatchers.Default) { client.playbackSubtitles(spec) }
                try {
                    assertTrue(withContext(Dispatchers.IO) { arrived.await(5, TimeUnit.SECONDS) })
                    pending.cancelAndJoin(); release.countDown()
                    assertTrue(pending.isCancelled)
                } finally { release.countDown(); pending.cancelAndJoin() }
            } finally { release.countDown(); server.shutdown() }
        }
    }
}
