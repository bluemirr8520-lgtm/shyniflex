package io.mirr.shynigsy.data

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

/** Synthetic fixtures preserve the observed PMS hidden=2 and actor-facet shapes. */
class PlexExcludedPersonSearchTest {
    private lateinit var server: MockWebServer
    private lateinit var client: PlexClient
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var sections = listOf(section(4, 0), section(18, 2))
    @Volatile private var reply: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    private fun section(id: Int, hidden: Any? = null) = JSONObject().put("key", id.toString())
        .put("type", "movie").put("title", "Synthetic library $id").apply { if (hidden != null) put("hidden", hidden) }
    private fun choice(id: Int, name: String = "합성 리오", library: Int = 18) = JSONObject()
        .put("key", id.toString()).put("title", name).put("fastKey", "/library/sections/$library/all?actor=$id")
    private fun movie(id: Int, library: Int = 18, title: String = "Credited movie $id") = JSONObject()
        .put("ratingKey", id).put("type", "movie").put("title", title).put("librarySectionID", library)
        .put("key", "/library/metadata/$id")
    private fun response(container: JSONObject) = MockResponse().setBody(JSONObject().put("MediaContainer", container).toString())
    private fun rows(kind: String, vararg values: JSONObject) = response(JSONObject().put("size", values.size)
        .put(kind, JSONArray(values.toList())))
    private fun hubs() = response(JSONObject().put("Hub", JSONArray().put(JSONObject().put("type", "movie")
        .put("Metadata", JSONArray().put(movie(1, 4, "리오"))))))
    private fun actorRequests() = requests.filter { it.requestUrl!!.encodedPath.endsWith("/actor") }

    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when (request.requestUrl!!.encodedPath) {
                        "/plex/library/sections" -> rows("Directory", *sections.toTypedArray())
                        "/plex/hubs/search" -> hubs()
                        else -> reply(request)
                    }
                }
            }
            start()
        }
        client = PlexClient(server.url("/plex").toString(), "synthetic-token", "synthetic-device")
    }
    @After fun close() = server.shutdown()

    @Test fun excludedAllowedSectionBatchesThirteenActorIdsAfterPublishingTitles() = runBlocking {
        sections = listOf(section(4, 0), section(18, 2), section(19, 1), section(20), section(21, 2.9))
        val libraries = client.libraries()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val initial = CopyOnWriteArrayList<List<MediaItem>>()
        try {
            reply = { request -> if (request.requestUrl!!.encodedPath.endsWith("/actor")) {
                started.countDown(); release.await(5, TimeUnit.SECONDS)
                rows("Directory", *(1..13).map { choice(1000 + it, "합성 리오 $it") }.toTypedArray())
            } else rows("Metadata", movie(500), movie(501, 99), movie(502).put("type", "show")) }
            val pending = async { client.searchWithStatus("리오", libraries) { initial += it } }
            withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
            assertEquals(listOf(1), initial.single().map { it.id })
            release.countDown()
            val result = withTimeout(5_000) { pending.await() }
            assertEquals(listOf(1, 500), result.items.map { it.id })
            assertEquals(0, result.failedLibraryCount)
            assertEquals(listOf("/plex/library/sections/18/actor"), actorRequests().map { it.requestUrl!!.encodedPath })
            assertNull(actorRequests().single().requestUrl!!.query)
            val works = requests.single { it.requestUrl!!.encodedPath.endsWith("/all") }.requestUrl!!
            assertEquals((1001..1013).joinToString(","), works.queryParameter("actor"))
            assertEquals("200", works.queryParameter("X-Plex-Container-Size"))
            assertEquals("1", works.queryParameter("type"))
        } finally { release.countDown() }
    }

    @Test fun excludedSectionsMustAlsoBeInTheCallersCurrentScope() = runBlocking {
        val libraries = client.libraries().filter { it.id == 4 } + Library(99, "Unreturned", "movie")
        assertEquals(listOf(1), client.search("리오", libraries).map { it.id })
        assertTrue(actorRequests().isEmpty())
    }

    @Test fun selectedLibraryUsesFacetMissFallbackAndPreservesNativeFiltersAndPaging() = runBlocking {
        val library = Library(4, "Synthetic", "movie")
        reply = { request -> if (request.requestUrl!!.encodedPath.endsWith("/actor"))
            rows("Directory", choice(10, library = 4), choice(11, library = 4)) else rows("Metadata", movie(50, 4)) }
        val first = client.page(library, offset = 60, limit = 12, title = "리오", searchPeople = true,
            sort = LibrarySort.TITLE_DESC, watchFilter = LibraryWatchFilter.IN_PROGRESS)
        assertFalse(first.personSearchIncomplete)
        val firstUrl = requests.first { it.requestUrl!!.encodedPath.endsWith("/all") }.requestUrl!!
        assertEquals("10,11", firstUrl.queryParameter("actor"))
        assertEquals("리오", firstUrl.queryParameter("title"))
        assertEquals("1", firstUrl.queryParameter("inProgress"))
        assertEquals("titleSort:desc", firstUrl.queryParameter("sort"))
        assertEquals("60", firstUrl.queryParameter("X-Plex-Container-Start"))
        assertEquals(listOf("push", "title", "or", "actor", "pop"), (0..4).map(firstUrl::queryParameterName))
        client.page(library, offset = 72, title = "리오", searchPeople = true)
        assertEquals(1, actorRequests().size)
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath.endsWith("/hubs/search") })
    }

    @Test fun selectedExcludedLibrarySkipsTheUnreliableGlobalHubLookup() = runBlocking {
        val library = client.libraries().single { it.id == 18 }
        reply = { request -> if (request.requestUrl!!.encodedPath.endsWith("/actor")) rows("Directory", choice(10))
            else rows("Metadata", movie(50)) }
        assertEquals(listOf(50), client.page(library, title = "리오", searchPeople = true).items.map { it.id })
        assertTrue(requests.none { it.requestUrl!!.encodedPath.endsWith("/hubs/search") })
    }

    @Test fun punctuationOnlyQueryNeverDownloadsActorFacets() = runBlocking {
        val libraries = client.libraries()
        reply = { rows("Metadata") }
        client.search("!!!", libraries)
        client.page(libraries.first(), title = "!!!", searchPeople = true)
        assertTrue(actorRequests().isEmpty())
    }

    @Test fun facetKeysAreStrictLocalIdsAndSameNamesDoNotMergeDifferentPeople() = runBlocking {
        val libraries = client.libraries()
        val bad = listOf<Any>("0", "-1", "1.5", "+1", "2147483648", "https://outside.invalid/1", JSONObject(), 7)
            .map { choice(30).put("key", it) }
        reply = { request -> if (request.requestUrl!!.encodedPath.endsWith("/actor")) rows("Directory",
            *(listOf(choice(10), choice(11), choice(12).put("title", JSONObject()),
                choice(13).put("librarySectionID", 18.5), choice(14).put("fastKey", "https://outside.invalid/all?actor=14")) + bad).toTypedArray())
            else rows("Metadata", movie(50)) }
        val result = client.searchWithStatus("리오", libraries)
        assertEquals(listOf(1, 50), result.items.map { it.id })
        assertEquals(1, result.failedLibraryCount)
        assertEquals("10,11", requests.single { it.requestUrl!!.encodedPath.endsWith("/all") }.requestUrl!!.queryParameter("actor"))
    }

    @Test fun completeFacetCacheIsReusedAcrossQueriesButNotAcrossClients() = runBlocking {
        val libraries = client.libraries()
        reply = { request -> if (request.requestUrl!!.encodedPath.endsWith("/actor"))
            rows("Directory", choice(10), choice(11, "합성 미오")) else rows("Metadata", movie(50)) }
        client.search("리오", libraries)
        client.search("미오", libraries)
        assertEquals(1, actorRequests().size)
        val other = PlexClient(server.url("/plex").toString(), "different-token", "different-device")
        other.search("리오", other.libraries())
        assertEquals(2, actorRequests().size)
    }

    @Test fun failuresPreserveTitleHitsAndDoNotCacheAnEmptyFacet() = runBlocking {
        val libraries = client.libraries()
        reply = { MockResponse().setResponseCode(500) }
        val failed = client.searchWithStatus("리오", libraries)
        assertEquals(listOf(1), failed.items.map { it.id })
        assertEquals(1, failed.failedLibraryCount)
        reply = { request -> if (request.requestUrl!!.encodedPath.endsWith("/actor")) rows("Directory", choice(10))
            else rows("Metadata", movie(50)) }
        assertEquals(listOf(1, 50), client.search("리오", libraries).map { it.id })
        assertEquals(2, actorRequests().size)
    }

    @Test fun malformedOrTruncatedFacetsAreNeverCachedAsCompleteMisses() = runBlocking {
        val invalid = listOf(JSONObject().put("Directory", "wrong").put("size", 0),
            JSONObject().put("Directory", JSONArray()).put("offset", 0.5),
            JSONObject().put("Directory", JSONArray()).put("size", "bad"),
            JSONObject().put("Directory", JSONArray()).put("totalSize", -1),
            JSONObject().put("Directory", JSONArray()).put("totalSize", 3))
        for (container in invalid) {
            reply = { response(container) }
            val result = client.actorMatches(Library(18, "Synthetic", "movie"), "리오")
            assertTrue(result.incomplete)
        }
        reply = { rows("Directory", choice(10)) }
        assertEquals(listOf(10), client.actorMatches(Library(18, "Synthetic", "movie"), "리오").ids)
        assertEquals(invalid.size + 1, actorRequests().size)
    }

    @Test fun actorFacetAuthorizationErrorsPropagate() = runBlocking {
        val libraries = client.libraries()
        for (status in listOf(401, 403)) {
            reply = { MockResponse().setResponseCode(status) }
            assertEquals(status, (runCatching { client.search("리오", libraries) }.exceptionOrNull() as PlexRequestException).status)
        }
    }

    @Test fun totalDeadlinePreservesFinishedWorksAndTitles() = runBlocking {
        sections = listOf(section(4, 0), section(18, 2), section(19, 2), section(20, 2))
        val libraries = client.libraries()
        reply = { request -> when (request.requestUrl!!.encodedPath) {
            "/plex/library/sections/18/actor" -> rows("Directory", choice(10))
            "/plex/library/sections/18/all" -> rows("Metadata", movie(50))
            else -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        } }
        val result = withTimeout(12_000) { client.searchWithStatus("리오", libraries) }
        assertEquals(listOf(1, 50), result.items.map { it.id })
        assertEquals(2, result.failedLibraryCount)
        assertEquals(3, actorRequests().size)
    }

    @Test fun cancellationStopsQueuedFacetsAndDoesNotPublishLateResults() = runBlocking {
        sections = (18..22).map { section(it, 2) }
        val libraries = client.libraries()
        val started = CountDownLatch(2)
        val initial = CopyOnWriteArrayList<List<MediaItem>>()
        reply = { started.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val pending = async { client.searchWithStatus("리오", libraries) { initial += it } }
        withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
        withTimeout(5_000) { pending.cancelAndJoin() }
        assertEquals(2, actorRequests().size)
        assertEquals(1, initial.size)
        assertTrue(requests.none { it.requestUrl!!.encodedPath.endsWith("/all") })
    }

    @Test fun sectionAndWorkCapsReportPartialResults() = runBlocking {
        sections = (18..26).map { section(it, 2) }
        val libraries = client.libraries()
        reply = { rows("Directory") }
        val result = client.searchWithStatus("리오", libraries)
        assertEquals(8, actorRequests().size)
        assertEquals(1, result.failedLibraryCount)
        assertTrue(requests.none { it.requestUrl!!.encodedPath.endsWith("/all") })
        val fresh = PlexClient(server.url("/plex").toString(), "another-token", "another-device")
        sections = listOf(section(18, 2))
        val scope = fresh.libraries()
        reply = { request -> if (request.requestUrl!!.encodedPath.endsWith("/actor")) rows("Directory", choice(10))
            else response(JSONObject().put("Metadata", JSONArray((1..200).map { movie(1000 + it) })).put("totalSize", 201)) }
        val capped = fresh.searchWithStatus("리오", scope)
        assertEquals(200, capped.items.size)
        assertEquals(1, capped.failedLibraryCount)
    }

    @Test fun facetCacheExpiresAndHasABoundedNumberOfLibraries() {
        var now = 0L
        val cache = PlexActorChoiceCache { now }
        val library = Library(18, "Synthetic", "movie")
        cache.put(library, listOf(PlexActorChoice(1, "리오")))
        assertNotNull(cache.get(library))
        now = 300_000_000_000L
        assertNull(cache.get(library))
        repeat(17) { cache.put(library.copy(id = it + 1), emptyList()) }
        assertNull(cache.get(library.copy(id = 1)))
        assertNotNull(cache.get(library.copy(id = 17)))
    }
}
