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

class PlaybackMarkerFreshnessTest {
    private val item = MediaItem(23, 8, "movie", "movie", viewItemId = 501)
    private val spec = PlaybackSpec("movie", "https://test.invalid/stream/90", itemId = 23, libraryId = 8,
        partId = 90, viewItemId = 501, durationMs = 60_000, sourceItem = item)
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setBody(body)
    private val credits = """{"item":{"id":23},"parts":[{"id":90,"metadata_item_id":23}],
        "markers":[{"segment_type":"credits","start_ms":90000,"end_ms":100000}]}"""

    @Test fun newMarkerLoadBypassesEarlierEmptyCachedMediaInfo() = runBlocking {
        val calls = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/libraries/8/media_info/90" -> if (calls.incrementAndGet() == 1) json("""{"markers":[]}""") else json(credits)
                else -> json("{}", 404)
            }
        }
        server.start()
        try {
            val client = ShyniClient(server.url("/").toString(), "account")
            assertTrue(client.playbackMarkers(spec, durationMs = 0).isEmpty())
            assertEquals(listOf(PlaybackMarker("credits", 90_000, 100_000)), client.playbackMarkers(spec, durationMs = 0))
            assertEquals(2, calls.get())
        } finally { server.shutdown() }
    }

    @Test fun staleInitialDurationDoesNotDiscardActualServerCreditBoundaries() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(json(credits))
            val client = ShyniClient(server.url("/").toString(), "account")
            assertEquals(listOf(PlaybackMarker("credits", 90_000, 100_000)), client.playbackMarkers(spec, durationMs = 0))
        } finally { server.shutdown() }
    }

    @Test fun accountChangeDuringMarkerLoadCannotPopulateTheSharedPartCache() = runBlocking {
        withTimeout(15_000) {
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val mediaCalls = AtomicInteger()
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/libraries/8/media_info/90" -> {
                        if (mediaCalls.incrementAndGet() == 1) { arrived.countDown(); release.await(5, TimeUnit.SECONDS) }
                        json(credits)
                    }
                    "/continue-watching/23/watch-state" -> json("""{"ok":true,"watched":true}""")
                    else -> json("{}", 404)
                }
            }
            server.start()
            try {
                val client = ShyniClient(server.url("/").toString(), "first-account")
                val pending = async(Dispatchers.Default) { runCatching { client.playbackMarkers(spec) } }
                try {
                    assertTrue(withContext(Dispatchers.IO) { arrived.await(5, TimeUnit.SECONDS) })
                    client.token = "second-account"
                    release.countDown()
                    assertEquals(401, (pending.await().exceptionOrNull() as ApiException).status)
                    client.markPlaybackWatched(spec)
                    assertEquals(2, mediaCalls.get())
                } finally { release.countDown(); pending.cancelAndJoin() }
            } finally { release.countDown(); server.shutdown() }
        }
    }

    @Test fun privateGrantRotationRejectsOldResponseBeforePartCacheWrite() = runBlocking {
        withTimeout(15_000) {
            SpoilerAccessRegistry.clearAll()
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val mediaCalls = AtomicInteger()
            val unlocks = AtomicInteger()
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/spoiler/client/unlock" -> json("""{"token":"spc_grant-${unlocks.incrementAndGet()}","expires_in":300}""")
                    "/libraries/8/media_info/90" -> {
                        if (mediaCalls.incrementAndGet() == 1) { arrived.countDown(); release.await(5, TimeUnit.SECONDS) }
                        json(credits)
                    }
                    "/continue-watching/23/watch-state" -> json("""{"ok":true,"watched":true}""")
                    else -> json("{}", 404)
                }
            }
            server.start()
            try {
                val client = ShyniClient(server.url("/").toString(), "account")
                client.unlockSpoiler("1234")
                val privateSpec = spec.copy(sourceItem = item.copy(isSpoiler = true))
                val pending = async(Dispatchers.Default) { runCatching { client.playbackMarkers(privateSpec) } }
                try {
                    assertTrue(withContext(Dispatchers.IO) { arrived.await(5, TimeUnit.SECONDS) })
                    client.clearSpoilerGrant()
                    client.unlockSpoiler("1234")
                    release.countDown()
                    assertEquals(403, (pending.await().exceptionOrNull() as ApiException).status)
                    client.markPlaybackWatched(privateSpec)
                    assertEquals(2, mediaCalls.get())
                } finally { release.countDown(); pending.cancelAndJoin(); client.clearSpoilerGrant() }
            } finally { release.countDown(); SpoilerAccessRegistry.clearAll(); server.shutdown() }
        }
    }
}
