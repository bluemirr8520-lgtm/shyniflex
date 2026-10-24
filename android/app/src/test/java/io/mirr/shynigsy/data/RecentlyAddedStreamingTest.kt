package io.mirr.shynigsy.data

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecentlyAddedStreamingTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { start() }
        client = ShyniClient(server.url("/").toString(), "fixture-account", deviceId = "fixture-device")
    }

    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }

    private fun response(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun library(id: Int, privateItem: Boolean = false) = Library(id, "합성 라이브러리 $id", "movie", privateItem)
    private fun catalog(id: Int) = """[{"id":${id * 10 + 1},"title":"합성 작품","view_item_id":${id * 100 + 1},
        "added_at":"2026-01-01T00:00:00Z"}]"""
    private fun progress(id: Int, completed: Boolean = true) = """{"items":[{
        "metadata_item_id":${id * 10 + 1},"completed":$completed,"position_ms":45678,"duration_ms":120000}]}"""
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        check(latch.await(10, TimeUnit.SECONDS)) { "Synthetic request did not reach its gate" }
    }
    private fun request() = server.takeRequest(5, TimeUnit.SECONDS)
        ?: error("Synthetic request did not arrive")

    @Test fun hydratedPartialArrivesBeforeTheSlowCatalogAndFinalOrderIsTheLibraryOrder() = runBlocking {
        for (slowId in listOf(8, 9)) {
            val fastId = if (slowId == 8) 9 else 8
            val slowEntered = CountDownLatch(1)
            val releaseSlow = CountDownLatch(1)
            val partial = CompletableDeferred<List<MediaItem>>()
            val snapshots = Collections.synchronizedList(mutableListOf<List<MediaItem>>())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val id = if (url.encodedPath == "/playback/progress") url.queryParameter("library_view_id")!!.toInt()
                        else url.pathSegments[1].toInt()
                    return if (url.encodedPath == "/playback/progress") response(progress(id)) else {
                        if (id == slowId) {
                            slowEntered.countDown()
                            check(releaseSlow.await(10, TimeUnit.SECONDS))
                        }
                        response(catalog(id))
                    }
                }
            }
            val before = server.requestCount
            val pending = async(Dispatchers.Default) {
                client.recentlyAdded(listOf(library(8), library(9))) { items ->
                    snapshots.add(items.toList())
                    partial.complete(items.toList())
                }
            }
            try {
                await(slowEntered)
                val first = withTimeout(10_000) { partial.await() }
                assertEquals(listOf(fastId * 10 + 1), first.map { it.id })
                assertTrue("A public partial must already contain server watch state", first.single().watched)
                assertEquals(0L, first.single().resumeMs)
                assertEquals(120_000L, first.single().durationMs)
                assertFalse(pending.isCompleted)
                releaseSlow.countDown()
                val result = withTimeout(10_000) { pending.await() }
                assertEquals(listOf(81, 91), result.map { it.id })
                assertTrue(result.all { it.watched })
                assertEquals(2, snapshots.size)
                assertEquals(result, snapshots.last())
                assertEquals(before + 4, server.requestCount)
                val requests = List(4) { request() }
                for (id in listOf(8, 9)) {
                    val catalogRequest = requests.single { it.requestUrl!!.encodedPath == "/libraries/$id/movies" }
                    assertEquals("added", catalogRequest.requestUrl!!.queryParameter("sort"))
                    assertEquals("desc", catalogRequest.requestUrl!!.queryParameter("sort_dir"))
                    val hydration = requests.single { it.requestUrl!!.encodedPath == "/playback/progress" &&
                        it.requestUrl!!.queryParameter("library_view_id") == id.toString() }
                    assertEquals((id * 10 + 1).toString(), hydration.requestUrl!!.queryParameter("item_ids"))
                }
            } finally { releaseSlow.countDown() }
        }
    }

    @Test fun anAccountChangeWhileHydratingRejectsTheLatePartialWith401() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl!!.encodedPath == "/playback/progress") {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); response(progress(8))
                } else response(catalog(8))
        }
        val pending = async(Dispatchers.Default) {
            runCatching { client.recentlyAdded(listOf(library(8))) { callbacks.incrementAndGet() } }.exceptionOrNull()
        }
        try {
            await(entered)
            client.token = "fixture-other-account"
            release.countDown()
            val failure = withTimeout(10_000) { pending.await() }
            assertTrue(failure is ApiException)
            assertEquals(401, (failure as ApiException).status)
            assertEquals(0, callbacks.get())
            assertEquals(2, server.requestCount)
        } finally { release.countDown() }
    }

    @Test fun privateLockOrNewGrantCannotPublishTheOldGrantsHydratedResult() = runBlocking {
        for (replaceGrant in listOf(false, true)) {
            SpoilerAccessRegistry.clearAll()
            client = ShyniClient(server.url("/").toString(), "fixture-account", deviceId = "fixture-device")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val unlocks = AtomicInteger()
            val callbacks = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/spoiler/client/unlock" -> response("""{"token":"${if (unlocks.incrementAndGet() == 1) "spc_initial" else "spc_replacement"}","expires_in":1800}""")
                    "/playback/progress" -> {
                        entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); response(progress(8))
                    }
                    else -> response(catalog(8))
                }
            }
            client.unlockSpoiler("1234"); request()
            val before = server.requestCount
            val pending = async(Dispatchers.Default) {
                runCatching { client.recentlyAdded(listOf(library(8, true))) { callbacks.incrementAndGet() } }.exceptionOrNull()
            }
            try {
                await(entered)
                if (replaceGrant) client.unlockSpoiler("5678") else SpoilerAccessRegistry.clearAll()
                release.countDown()
                val failure = withTimeout(10_000) { pending.await() }
                assertTrue(failure is ApiException)
                assertEquals(403, (failure as ApiException).status)
                assertEquals(0, callbacks.get())
                assertEquals(replaceGrant, client.spoilerUnlocked)
                val count = if (replaceGrant) 3 else 2
                assertEquals(before + count, server.requestCount)
                val requests = List(count) { request() }
                requests.filter { it.method == "GET" }.forEach { assertEquals("spc_initial", it.getHeader("X-Shyni-Spoiler")) }
                if (replaceGrant) assertEquals("spc_replacement",
                    client.headersFor(server.url("/fixture-poster").toString())["X-Shyni-Spoiler"])
            } finally { release.countDown() }
        }
    }

    @Test fun unauthorizedCatalogCancelsTheHydratingSiblingWithoutALateCallback() = runBlocking {
        val siblingEntered = CountDownLatch(1)
        val releaseSibling = CountDownLatch(1)
        val callbacks = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/libraries/8/movies" -> {
                    check(siblingEntered.await(10, TimeUnit.SECONDS))
                    response("""{"detail":"synthetic unauthorized"}""", 401)
                }
                "/libraries/9/movies" -> response(catalog(9))
                "/playback/progress" -> {
                    siblingEntered.countDown(); check(releaseSibling.await(10, TimeUnit.SECONDS)); response(progress(9))
                }
                else -> response("{}", 404)
            }
        }
        val pending = async(Dispatchers.Default) {
            runCatching { client.recentlyAdded(listOf(library(8), library(9))) { callbacks.incrementAndGet() } }.exceptionOrNull()
        }
        try {
            await(siblingEntered)
            val failure = withTimeout(10_000) { pending.await() }
            assertTrue(failure is ApiException)
            assertEquals(401, (failure as ApiException).status)
            releaseSibling.countDown()
            assertEquals(0, callbacks.get())
            assertEquals(3, server.requestCount)
        } finally { releaseSibling.countDown() }
    }

    @Test fun aLaterFailureDoesNotEraseAnAlreadyDeliveredHydratedPartial() = runBlocking {
        val first = CompletableDeferred<List<MediaItem>>()
        val releaseFailure = CountDownLatch(1)
        val snapshots = Collections.synchronizedList(mutableListOf<List<MediaItem>>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/libraries/8/movies" -> response(catalog(8))
                "/playback/progress" -> response(progress(8))
                "/libraries/9/movies" -> {
                    check(releaseFailure.await(10, TimeUnit.SECONDS)); response("""{"detail":"synthetic unavailable"}""", 503)
                }
                else -> response("{}", 404)
            }
        }
        val pending = async(Dispatchers.Default) {
            runCatching { client.recentlyAdded(listOf(library(8), library(9))) {
                snapshots.add(it.toList()); first.complete(it.toList())
            } }.exceptionOrNull()
        }
        try {
            assertTrue(withTimeout(10_000) { first.await() }.single().watched)
            releaseFailure.countDown()
            val failure = withTimeout(10_000) { pending.await() }
            assertTrue(failure is ApiException)
            assertEquals(503, (failure as ApiException).status)
            assertEquals(1, snapshots.size)
            assertEquals(listOf(81), snapshots.single().map { it.id })
            assertEquals(3, server.requestCount)
        } finally { releaseFailure.countDown() }
    }

    @Test fun twoStagesKeepTheNextCatalogBehindHydrationAndOneCompletionOpensOneSlot() = runBlocking {
        // Use production's two-slot catalog lane, including progress reads.
        val progressEntered = CountDownLatch(2)
        val thirdCatalog = CountDownLatch(1)
        val releaseFirstProgress = CountDownLatch(1)
        val releaseOtherProgress = CountDownLatch(1)
        val firstProgress = AtomicInteger()
        val firstPartial = CompletableDeferred<List<MediaItem>>()
        val catalogs = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath == "/playback/progress") {
                    val id = url.queryParameter("library_view_id")!!.toInt()
                    val first = firstProgress.compareAndSet(0, id) || firstProgress.get() == id
                    progressEntered.countDown()
                    check((if (first) releaseFirstProgress else releaseOtherProgress).await(10, TimeUnit.SECONDS))
                    return response(progress(id))
                }
                if (catalogs.incrementAndGet() == 3) thirdCatalog.countDown()
                return response(catalog(url.pathSegments[1].toInt()))
            }
        }
        val pending = async(Dispatchers.Default) {
            client.recentlyAdded((8..10).map { library(it) }) { firstPartial.complete(it.toList()) }
        }
        try {
            await(progressEntered)
            assertFalse("A third catalog must wait until a view has hydrated", withContext(Dispatchers.IO) {
                thirdCatalog.await(300, TimeUnit.MILLISECONDS)
            })
            assertEquals(2, catalogs.get())
            releaseFirstProgress.countDown()
            await(thirdCatalog)
            assertEquals(3, catalogs.get())
            val partial = withTimeout(10_000) { firstPartial.await() }
            assertEquals(listOf(firstProgress.get() * 10 + 1), partial.map { it.id })
            assertTrue(partial.single().watched)
            assertFalse(pending.isCompleted)
            releaseOtherProgress.countDown()
            assertEquals(listOf(81, 91, 101), withTimeout(10_000) { pending.await() }.map { it.id })
            assertEquals(6, server.requestCount)
        } finally { releaseFirstProgress.countDown(); releaseOtherProgress.countDown() }
    }
}
