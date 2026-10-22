package io.mirr.shynigsy.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LibrarySearchTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private lateinit var release: CountDownLatch
    private val searches = CopyOnWriteArrayList<RecordedRequest>()
    private val progress = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var reply: (RecordedRequest) -> MockResponse = { MockResponse().setBody("[]") }
    @Volatile private var progressReply: (RecordedRequest) -> MockResponse = { MockResponse().setBody("{}") }
    private val movies = Library(8, "합성 영화", "movie")
    private val shows = Library(9, "합성 TV", "tv")

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        release = CountDownLatch(1)
        searches.clear(); progress.clear()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/spoiler/client/unlock" -> MockResponse().setBody("""{"token":"spc_synthetic","expires_in":1800}""")
                        path == "/playback/progress" -> { progress.add(request); progressReply(request) }
                        Regex("/libraries/[0-9]+/search").matches(path) -> { searches.add(request); reply(request) }
                        else -> MockResponse().setResponseCode(404).setBody("{}")
                    }
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "synthetic-account", deviceId = "synthetic-device")
    }

    @After fun close() {
        release.countDown()
        SpoilerAccessRegistry.clearAll()
        server.shutdown()
    }

    private fun row(id: Int, view: Int = id + 100) = JSONObject().put("id", id)
        .put("title", "합성 %03d".format(id)).put("view_item_id", view)
    private fun rows(vararg rows: JSONObject) = MockResponse().setBody(JSONArray(rows.toList()).toString())
    private fun view(request: RecordedRequest) = request.requestUrl!!.pathSegments[1].toInt()
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("Synthetic request must reach the server", latch.await(5, TimeUnit.SECONDS))
    }
    private fun browseCache(): Map<*, *> = ShyniClient::class.java.getDeclaredField("browseCache")
        .apply { isAccessible = true }.get(client) as Map<*, *>

    @Test fun searchesKnownViewsOnceEscapesQueryAndPreservesViewIdentityWithoutBrowseCache() = runBlocking {
        reply = { request -> if (view(request) == 8) rows(row(23, 501), row(23, 501), row(23, 502),
            row(90).put("remote", true), row(91).put("federated_id", "remote-item")) else rows(row(23, 601)) }
        progressReply = { MockResponse().setBody("""{"items":[{"metadata_item_id":23,"completed":true}]}""") }
        val result = client.searchLibraries(listOf(movies, shows, movies, Library(10, "비디오 아님", "book")), "  A&B / 한글  ")
        assertEquals(0, result.failedLibraryCount)
        assertEquals(listOf(501, 502), result.items.filter { it.libraryId == 8 }.map { it.viewItemId })
        assertTrue(result.items.filter { it.libraryId == 8 }.all { it.type == "movie" && it.watched })
        assertEquals("show", result.items.single { it.libraryId == 9 }.type)
        assertEquals(601, result.items.single { it.libraryId == 9 }.viewItemId)
        assertEquals(2, searches.size)
        for (request in searches) {
            val url = request.requestUrl!!
            assertEquals("A&B / 한글", url.queryParameter("q"))
            assertEquals("200", url.queryParameter("limit"))
            assertEquals(if (view(request) == 8) "1" else "2", url.queryParameter("metadata_type"))
            assertNull(url.queryParameter("offset"))
        }
        assertEquals(1, progress.size)
        assertTrue(browseCache().isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test fun blankQueryDoesNotDownloadAnyCatalogOrCreateCacheEntries() = runBlocking {
        val result = client.searchLibraries(listOf(movies, shows), " \t\n ")
        assertEquals(LibrarySearchResult(emptyList()), result)
        assertTrue(browseCache().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun partialAndAllLibraryFailuresAreReportedWithoutInventingAnEmptySuccess() = runBlocking {
        reply = { request -> if (view(request) == 8) rows(row(80))
            else MockResponse().setResponseCode(503).setBody("""{"detail":"synthetic unavailable"}""") }
        val partial = client.searchLibraries(listOf(movies, shows), "합성")
        assertEquals(listOf(80), partial.items.map { it.id })
        assertEquals(1, partial.failedLibraryCount)
        reply = { MockResponse().setResponseCode(500).setBody("{}") }
        val failed = client.searchLibraries(listOf(movies, shows), "합성")
        assertTrue(failed.items.isEmpty())
        assertEquals(2, failed.failedLibraryCount)
        assertEquals(4, searches.size)
    }

    @Test fun malformedSuccessfulSearchResponseIsAFailedLibraryRatherThanNoMatches() = runBlocking {
        for (body in listOf("{}", "\"synthetic html fallback\"", "[42]")) {
            reply = { MockResponse().setBody(body) }
            val result = client.searchLibraries(listOf(movies), "합성")
            assertTrue(result.items.isEmpty())
            assertEquals(1, result.failedLibraryCount)
        }
        assertEquals(0, progress.size)
        assertEquals(3, server.requestCount)
    }

    @Test fun lockedPrivateViewsSuppressTheirPublicShadowWithoutAnyPrivateRequest() = runBlocking {
        reply = { request -> rows(row(view(request))) }
        val result = client.searchLibraries(listOf(movies, movies.copy(isSpoiler = true), shows), "합성")
        assertEquals(listOf(9), result.items.map { it.id })
        assertEquals(0, result.failedLibraryCount)
        assertEquals(listOf(9), searches.map(::view))
        assertEquals(1, server.requestCount)
    }

    @Test fun unlockedPrivateSearchUsesLiveHeadersAndNeverDowngradesResponsePrivacy() = runBlocking {
        client.unlockSpoiler("1234")
        reply = { request -> if (view(request) == 8) rows(row(23, 501).put("spoiler_enabled", false))
            else rows(row(24, 601).put("spoiler_enabled", true)) }
        val result = client.searchLibraries(listOf(movies, movies.copy(isSpoiler = true), shows), "합성")
        assertEquals(listOf(23, 24), result.items.map { it.id })
        assertTrue(result.items.all { it.isSpoiler })
        assertEquals(2, searches.size)
        assertEquals("spc_synthetic", searches.single { view(it) == 8 }.getHeader("X-Shyni-Spoiler"))
        assertEquals("synthetic-device", searches.single { view(it) == 8 }.getHeader("X-Device-Id"))
        assertNull(searches.single { view(it) == 9 }.getHeader("X-Shyni-Spoiler"))
        assertEquals(1, progress.size)
        assertEquals("spc_synthetic", progress.single().getHeader("X-Shyni-Spoiler"))
    }

    @Test fun positivePrivateRowWinsBeforeLockedRowsAreRemovedSoPublicDuplicateCannotLeak() = runBlocking {
        reply = { rows(row(23, 501).put("spoiler_enabled", false), row(23, 501).put("spoiler_enabled", true)) }
        val result = client.searchLibraries(listOf(movies), "합성")
        assertTrue(result.items.isEmpty())
        assertEquals(0, result.failedLibraryCount)
        assertTrue(progress.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun relockingDuringPendingPrivateResponseDropsItButKeepsPublicResults() = runBlocking {
        client.unlockSpoiler("1234")
        val privateStarted = CountDownLatch(1)
        reply = { request ->
            if (view(request) == 8) { privateStarted.countDown(); release.await(5, TimeUnit.SECONDS) }
            rows(row(view(request)))
        }
        val pending = async { client.searchLibraries(listOf(movies.copy(isSpoiler = true), shows), "합성") }
        try {
            await(privateStarted)
            client.clearSpoilerGrant()
            release.countDown()
            val result = withTimeout(5_000) { pending.await() }
            assertEquals(listOf(9), result.items.map { it.id })
            assertTrue(result.items.none { it.isSpoiler })
            assertFalse(client.spoilerUnlocked)
            assertEquals(0, progress.size)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun accountChangeRejectsAnOlderPendingResultBeforeProgressOrCachePublication() = runBlocking {
        val started = CountDownLatch(1)
        reply = { started.countDown(); release.await(5, TimeUnit.SECONDS); rows(row(23)) }
        val pending = async { runCatching { client.searchLibraries(listOf(movies), "합성") } }
        try {
            await(started)
            client.token = "other-synthetic-account"
            release.countDown()
            val result = withTimeout(5_000) { pending.await() }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals("Bearer synthetic-account", searches.single().getHeader("Authorization"))
            assertTrue(progress.isEmpty())
            assertTrue(browseCache().isEmpty())
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun authenticationFailurePropagatesAndCancelsTheOtherPendingSearch() = runBlocking {
        val otherStarted = CountDownLatch(1)
        reply = { request ->
            if (view(request) == 9) { otherStarted.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
            else { otherStarted.await(5, TimeUnit.SECONDS); MockResponse().setResponseCode(401).setBody("""{"detail":"synthetic expired"}""") }
        }
        val failure = runCatching { withTimeout(10_000) { client.searchLibraries(listOf(movies, shows), "합성") } }.exceptionOrNull()
        assertEquals(401, (failure as ApiException).status)
        assertEquals(2, searches.size)
        assertTrue(progress.isEmpty())
    }

    @Test fun cancellingARealPendingHttpSearchStopsWithoutAnotherRequest() = runBlocking {
        val started = CountDownLatch(1)
        reply = { started.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { client.searchLibraries(listOf(movies), "합성") }
        await(started)
        withTimeout(5_000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertEquals(1, searches.size)
        assertTrue(progress.isEmpty())
        assertTrue(browseCache().isEmpty())
    }

    @Test fun atMostThreeViewsAreSearchedConcurrentlyAndEachSearchHasAtMostTwoHundredResults() = runBlocking {
        val started = CountDownLatch(3)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        reply = { request ->
            val current = active.incrementAndGet()
            peak.updateAndGet { old -> maxOf(old, current) }
            started.countDown()
            try { release.await(5, TimeUnit.SECONDS); rows(row(view(request))) }
            finally { active.decrementAndGet() }
        }
        val libraries = (1..5).map { Library(it, "합성 TV $it", "tv") }
        val pending = async { client.searchLibraries(libraries, "합성") }
        try {
            await(started)
            assertEquals(3, searches.size)
            release.countDown()
            val result = withTimeout(5_000) { pending.await() }
            assertEquals(5, result.items.size)
            assertEquals(3, peak.get())
        } finally { release.countDown(); pending.cancelAndJoin() }
        reply = { MockResponse().setBody(JSONArray((1..250).map { row(it) }).toString()) }
        val capped = client.searchLibraries(listOf(movies), "더 큰 합성 결과")
        assertEquals(200, capped.items.size)
        assertEquals(2, progress.size)
        assertTrue(progress.all { it.requestUrl!!.queryParameter("item_ids")!!.split(',').size == 100 })
        assertTrue(browseCache().isEmpty())
    }
}
