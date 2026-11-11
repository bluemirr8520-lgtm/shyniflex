package io.mirr.shynigsy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
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

class UnifiedLibrarySearchTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var reply: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }
    private val movies = Library(8, "Synthetic movies", "movie")
    private val shows = Library(9, "Synthetic shows", "tv")

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.requestUrl!!.encodedPath == "/spoiler/client/unlock")
                        return MockResponse().setBody("""{"token":"spc_synthetic","expires_in":1800}""")
                    return reply(request)
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "synthetic-account", deviceId = "synthetic-device")
    }

    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }

    private fun row(id: Int, view: Int, type: Int, title: String) = JSONObject()
        .put("id", id).put("title", title).put("library_view_id", view).put("library_id", view)
        .put("metadata_type", type).put("view_item_id", id + 100)
    private fun rows(vararg rows: JSONObject) = MockResponse().setBody(JSONArray(rows.toList()).toString())

    @Test fun globalSearchUsesOneRequestAndOnlyAuthorizedMatchingViews() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/search" -> rows(row(1, 8, 1, "Nearly"), row(2, 9, 2, "Synthetic title"),
                row(3, 99, 1, "Synthetic hidden"), row(4, 8, 2, "Synthetic wrong type"),
                row(5, 8, 1, "Synthetic remote").put("remote", true))
            "/playback/progress" -> MockResponse().setBody("{}")
            else -> MockResponse().setResponseCode(404)
        } }
        val observed = mutableListOf<List<MediaItem>>()
        val result = client.searchLibrariesUnified(listOf(movies, shows), " Synthetic title ") { observed += it }
        assertEquals(listOf(2), result.items.map { it.id })
        assertEquals(listOf(2), observed.single().map { it.id })
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/search" })
        assertTrue(requests.none { it.requestUrl!!.encodedPath.startsWith("/libraries/") })
        val search = requests.first().requestUrl!!
        assertEquals("Synthetic title", search.queryParameter("q"))
        assertEquals("movie,tv", search.queryParameter("types"))
    }

    @Test fun unsupportedGlobalRouteFallsBackOnlyToScopedSearch() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/libraries/9/search" -> rows(JSONObject().put("id", 2).put("title", "Synthetic").put("view_item_id", 20))
            else -> MockResponse().setResponseCode(404)
        } }
        val result = client.searchLibrariesUnified(listOf(shows), "Synthetic")
        assertEquals(listOf(2), result.items.map { it.id })
        assertEquals(listOf("/search", "/libraries/9/search"), requests.map { it.requestUrl!!.encodedPath })
    }

    @Test fun authorizationFailureDoesNotFanOutAcrossLibraries() = runBlocking {
        reply = { MockResponse().setResponseCode(403).setBody("{}") }
        val error = runCatching { client.searchLibrariesUnified(listOf(movies, shows), "Synthetic") }.exceptionOrNull()
        assertTrue(error is ApiException && error.status == 403)
        assertEquals(listOf("/search"), requests.map { it.requestUrl!!.encodedPath })
    }

    @Test fun showsResultsBeforeSlowWatchHistoryCompletes() = runBlocking {
        val progressStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = CountDownLatch(1)
        try {
            reply = { request -> when (request.requestUrl!!.encodedPath) {
                "/search" -> rows(row(1, 8, 1, "Synthetic"))
                "/playback/progress" -> { progressStarted.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody("{}") }
                else -> MockResponse().setResponseCode(404)
            } }
            val pending = async { client.searchLibrariesUnified(listOf(movies), "Synthetic") { published.countDown() } }
            withContext(Dispatchers.IO) { assertTrue(progressStarted.await(5, TimeUnit.SECONDS)); assertTrue(published.await(1, TimeUnit.SECONDS)) }
            assertFalse(pending.isCompleted)
            release.countDown()
            assertEquals(listOf(1), withTimeout(5_000) { pending.await() }.items.map { it.id })
        } finally { release.countDown() }
    }

    @Test fun lockedPrivateViewNeverBecomesPublicDuplicate() = runBlocking {
        reply = { rows(row(1, 8, 1, "Synthetic"), row(2, 9, 2, "Synthetic")) }
        val result = client.searchLibrariesUnified(listOf(movies, movies.copy(isSpoiler = true), shows), "Synthetic")
        assertEquals(listOf(2), result.items.map { it.id })
        assertTrue(requests.none { it.getHeader("X-Shyni-Spoiler") != null })
    }

    @Test fun unauthorizedExactHitsCannotSuppressAnAccessibleServerAlias() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/search" -> rows(row(1, 99, 2, "Requested title"),
                row(2, 9, 2, "Requested title").put("spoiler_enabled", true),
                row(3, 9, 2, "Requested title").put("remote", true),
                row(4, 8, 2, "Requested title"), row(5, 9, 2, "Server alias"))
            else -> MockResponse().setResponseCode(404)
        } }
        val result = client.searchLibrariesUnified(listOf(movies, shows), "Requested title")
        assertEquals(listOf(5), result.items.map { it.id })
    }

    @Test fun viewSearchFiltersHiddenAndRemoteHitsBeforeRanking() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/libraries/9/search" -> rows(row(1, 9, 2, "Requested title").put("spoiler_enabled", true),
                row(2, 9, 2, "Requested title").put("remote", true),
                row(3, 8, 2, "Requested title"), row(4, 9, 2, "Server alias"))
            else -> MockResponse().setResponseCode(404)
        } }
        val result = client.items(shows, query = "Requested title")
        assertEquals(listOf(4), result.items.map { it.id })
    }

    @Test fun globalSearchHonorsTheServerTwoHundredResultLimit() = runBlocking {
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/search" -> MockResponse().setBody(JSONArray((1..250).map { row(it, 9, 2, "합성 검색 $it") }).toString())
            else -> MockResponse().setResponseCode(404)
        } }
        val result = client.searchLibrariesUnified(listOf(shows), "합성 검색")
        assertEquals(200, result.items.size)
        assertEquals("200", requests.single().requestUrl!!.queryParameter("limit"))
    }

    @Test fun staleAccountResponseNeverPublishesAnyResults() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var published = false
        try {
            reply = { request -> when (request.requestUrl!!.encodedPath) {
                "/search" -> { started.countDown(); release.await(5, TimeUnit.SECONDS); rows(row(1, 9, 2, "Synthetic")) }
                else -> MockResponse().setResponseCode(404)
            } }
            val pending = async { runCatching { client.searchLibrariesUnified(listOf(shows), "Synthetic") { published = true } } }
            withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
            client.token = "different-synthetic-account"
            release.countDown()
            assertTrue(withTimeout(5_000) { pending.await() }.exceptionOrNull() is CancellationException)
            assertFalse(published)
            assertTrue(requests.none { it.requestUrl!!.encodedPath == "/playback/progress" })
        } finally { release.countDown() }
    }

    @Test fun relockingWhileGlobalSearchIsPendingHidesPrivateRows() = runBlocking {
        client.unlockSpoiler("1234")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = mutableListOf<MediaItem>()
        try {
            reply = { request -> when (request.requestUrl!!.encodedPath) {
                "/search" -> { started.countDown(); release.await(5, TimeUnit.SECONDS)
                    rows(row(1, 8, 1, "Synthetic"), row(2, 9, 2, "Synthetic")) }
                else -> MockResponse().setResponseCode(404)
            } }
            val pending = async { client.searchLibrariesUnified(listOf(movies.copy(isSpoiler = true), shows), "Synthetic") { published += it } }
            withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
            client.clearSpoilerGrant()
            release.countDown()
            val result = withTimeout(5_000) { pending.await() }
            assertEquals(listOf(2), result.items.map { it.id })
            assertEquals(listOf(2), published.map { it.id })
        } finally { release.countDown() }
    }

    @Test fun cancellingGlobalSearchStopsWithoutScopedFallbackOrPublication() = runBlocking {
        val started = CountDownLatch(1)
        var published = false
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/search" -> { started.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
            else -> MockResponse().setResponseCode(404)
        } }
        val pending = async { client.searchLibrariesUnified(listOf(movies, shows), "Synthetic") { published = true } }
        withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
        withTimeout(5_000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertFalse(published)
        assertEquals(listOf("/search"), requests.map { it.requestUrl!!.encodedPath })
    }

    @Test fun relockingDuringPublicationDoesNotRequestPrivateWatchHistory() = runBlocking {
        client.unlockSpoiler("1234")
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/search" -> rows(row(1, 8, 1, "Synthetic"), row(2, 9, 2, "Synthetic"))
            else -> MockResponse().setResponseCode(404)
        } }
        val result = client.searchLibrariesUnified(listOf(movies.copy(isSpoiler = true), shows), "Synthetic") {
            client.clearSpoilerGrant()
        }
        assertEquals(listOf(2), result.items.map { it.id })
        assertTrue(requests.none { it.requestUrl!!.encodedPath == "/playback/progress" })
    }
}
