package io.mirr.shynigsy.data

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlexPersonSearchTest {
    private lateinit var server: MockWebServer
    private lateinit var client: PlexClient
    private val libraries = listOf(Library(4, "Movies", "movie"), Library(9, "Shows", "tv"))

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = PlexClient(server.url("/plex").toString(), "synthetic-token", "synthetic-device")
    }

    @After fun close() = server.shutdown()

    private fun movie(id: Int, title: String = "Synthetic film $id", library: Int = 4) = JSONObject()
        .put("ratingKey", id).put("title", title).put("type", "movie").put("librarySectionID", library)
        .put("key", "/library/metadata/$id")

    private fun person(id: Int, name: String, kind: String = "actor", library: Int = 4) = JSONObject()
        .put("id", id).put("tag", name).put("type", "tag").put("librarySectionID", library)
        .put("key", "/library/sections/$library/all?$kind=$id")

    private fun hub(type: String, vararg rows: JSONObject) = JSONObject().put("type", type)
        .put("hubIdentifier", type).put(if (type in setOf("actor", "director")) "Directory" else "Metadata", JSONArray(rows.toList()))

    private fun response(key: String, vararg rows: JSONObject) {
        server.enqueue(MockResponse().setBody(JSONObject().put("MediaContainer", JSONObject()
            .put(key, JSONArray(rows.toList()))).toString()))
    }

    @Test fun localizedPersonHubLoadsScopedWorksAndDoesNotRequireTheNameInTheTitle() = runBlocking {
        response("Hub", hub("actor", person(19, "리오")))
        response("Metadata", movie(10), movie(11, library = 99), movie(12).put("type", "show"),
            movie(13).put("key", "https://outside.invalid/library/metadata/13"))
        assertEquals(listOf(10), client.search(" 리오 ", libraries).map { it.id })
        assertEquals("리오", server.takeRequest().requestUrl!!.queryParameter("query"))
        val works = server.takeRequest()
        assertEquals("/plex/library/sections/4/all", works.requestUrl!!.encodedPath)
        assertEquals("19", works.requestUrl!!.queryParameter("actor"))
        assertEquals("1", works.requestUrl!!.queryParameter("type"))
        assertEquals("40", works.requestUrl!!.queryParameter("X-Plex-Container-Size"))
        assertNull(works.requestUrl!!.queryParameter("title"))
        assertEquals("synthetic-token", works.getHeader("X-Plex-Token"))
    }

    @Test fun serverTranslatedOrFuzzyNamesRemainEvenBesideAnExactTitleMatch() = runBlocking {
        // The server resolves this name/alias. The app must not invent or require its own translation.
        response("Hub", hub("movie", movie(1, "리오"), movie(2, "Unrelated fuzzy title")),
            hub("actor", person(19, "Original server person name")))
        response("Metadata", movie(3, "Person's credited film"), movie(1, "리오"))
        assertEquals(listOf(1, 3), client.search("리오", libraries).map { it.id })
        assertEquals(2, server.requestCount)
    }

    @Test fun documentedPersonReasonsSurviveTitleRankingWithoutExtraRequests() = runBlocking {
        response("Hub", hub("movie", movie(1, "리오"), movie(2).put("reason", "actor").put("reasonID", 19),
            movie(3).put("reason", "director").put("reasonID", "20"), movie(4).put("reason", "genre"), movie(5)))
        assertEquals(listOf(1, 2, 3), client.search("리오", libraries).map { it.id })
        assertEquals(1, server.requestCount)
    }

    @Test fun internExactAndOriginalTitlesSuppressHundredsOfUnrelatedResults() = runBlocking {
        val unrelated = (1..214).map { movie(it, "Unrelated $it") }
        response("Hub", hub("movie", *(unrelated + listOf(movie(215, "인턴 2"), movie(216, "인턴"),
            movie(217, "The Intern").put("originalTitle", "인턴"))).toTypedArray()))
        assertEquals(listOf(216, 217, 215), client.search("인턴", libraries).map { it.id })
        assertEquals(1, server.requestCount)
    }

    @Test fun originalTitleAndServerFuzzyFallbackBothWorkWithoutPersonInference() = runBlocking {
        response("Hub", hub("movie", movie(1, "Unrelated"), movie(2, "인턴").put("originalTitle", "The Intern")))
        assertEquals(listOf(2), client.search("the intern", libraries).map { it.id })
        response("Hub", hub("movie", movie(3, "Server alias"), movie(4, "Another server alias")))
        assertEquals(listOf(3, 4), client.search("별칭", libraries).map { it.id })
        assertEquals(2, server.requestCount)
    }

    @Test fun directorsAndActorsUseSeparateIdsAndRepeatedPersonRowsOnlyLoadOnce() = runBlocking {
        val actor = person(19, "리오", library = 9)
        val actorReply = MockResponse().setBody("""{"MediaContainer":{"Metadata":[{"ratingKey":1,"title":"Show","type":"show","librarySectionID":9}]}}""")
        val directorReply = MockResponse().setBody("""{"MediaContainer":{"Metadata":[{"ratingKey":2,"title":"Movie","type":"movie","librarySectionID":4}]}}""")
        // These two expansions run concurrently; bind replies to identities rather than arrival order.
        val searchReply = MockResponse().setBody(JSONObject().put("MediaContainer", JSONObject().put("Hub", JSONArray()
            .put(hub("actor", actor, actor)).put(hub("director", person(19, "리오", kind = "director"))))).toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.requestUrl!!.encodedPath.endsWith("/hubs/search") -> searchReply
                request.requestUrl!!.queryParameter("actor") != null -> actorReply
                else -> directorReply
            }
        }
        assertEquals(listOf(1, 2), client.search("리오", libraries).map { it.id })
        server.takeRequest()
        val expansions = listOf(server.takeRequest(), server.takeRequest()).map { it.requestUrl!! }
        val actorRequest = expansions.single { it.queryParameter("actor") != null }
        assertEquals("19", actorRequest.queryParameter("actor"))
        assertEquals("2", actorRequest.queryParameter("type"))
        val directorRequest = expansions.single { it.queryParameter("director") != null }
        assertEquals("19", directorRequest.queryParameter("director"))
        assertNull(directorRequest.queryParameter("actor"))
    }

    @Test fun externalUnscopedAndConflictingPersonKeysCannotExpandTheSearch() = runBlocking {
        response("Hub", hub("actor",
            person(1, "리오").put("key", "https://outside.invalid/library/sections/4/all?actor=1"),
            person(2, "리오").put("key", "/:/scrobble?actor=2"),
            person(3, "리오", library = 99),
            person(4, "리오").put("key", "/library/sections/9/all?actor=4"),
            person(5, "리오").put("key", "/library/sections/4/all?actor=50"),
            person(6, "리오").put("key", "/library/sections/4/all?actor=6&actor=7"),
            person(7, "리오").put("key", "/library/sections/4/all?actor=7&director=8"),
            person(8, "리오").put("key", "/library/sections/4/all?actor=0")))
        assertTrue(client.search("리오", libraries).isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun trustedPersonKeyIsRebuiltWithoutUnrelatedParametersOrEmbeddedCredentials() = runBlocking {
        response("Hub", hub("actor", person(19, "리오").put("key",
            "/library/sections/4/all?actor=19&X-Plex-Token=untrusted&title=unrelated&unwatched=1")))
        response("Metadata", movie(1))
        assertEquals(listOf(1), client.search("리오", libraries).map { it.id })
        server.takeRequest()
        val works = server.takeRequest()
        val url = works.requestUrl!!
        assertNull(url.queryParameter("X-Plex-Token"))
        assertNull(url.queryParameter("title"))
        assertNull(url.queryParameter("unwatched"))
        assertEquals("synthetic-token", works.getHeader("X-Plex-Token"))
    }

    @Test fun hiddenTitleCannotSuppressAccessibleAliasResults() = runBlocking {
        response("Hub", hub("movie", movie(1, "리오", library = 99), movie(2, "Accessible alias")))
        assertEquals(listOf(2), client.search("리오", libraries).map { it.id })
        assertEquals(1, server.requestCount)
    }

    @Test fun expansionIsBoundedAndDoesNotEnumerateUnfilteredLibraries() = runBlocking {
        response("Hub", hub("actor", *(1..30).map { person(it, "리오 $it") }.toTypedArray()))
        repeat(6) { index -> response("Metadata", *(1..50).map { movie(index * 100 + it) }.toTypedArray()) }
        assertEquals(200, client.search("리오", libraries).size)
        assertEquals(7, server.requestCount)
        server.takeRequest()
        repeat(6) {
            val url = server.takeRequest().requestUrl!!
            assertNotNull(url.queryParameter("actor"))
            assertEquals("0", url.queryParameter("X-Plex-Container-Start"))
            assertEquals("40", url.queryParameter("X-Plex-Container-Size"))
        }
    }

    @Test fun unsupportedPersonFilterKeepsSearchHitsButAuthorizationFailurePropagates() = runBlocking {
        response("Hub", hub("movie", movie(1, "리오")), hub("actor", person(19, "리오")))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(listOf(1), client.search("리오", libraries).map { it.id })
        response("Hub", hub("actor", person(19, "리오")))
        server.enqueue(MockResponse().setResponseCode(403))
        val error = runCatching { client.search("리오", libraries) }.exceptionOrNull()
        assertEquals(403, (error as PlexRequestException).status)
        assertEquals(4, server.requestCount)
    }

    @Test fun cancellationDuringPersonExpansionStopsWithoutAnotherRequest() = runBlocking {
        response("Hub", hub("actor", *(1..6).map { person(it, "리오") }.toTypedArray()))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { client.search("리오", libraries) }
        // Await without blocking the coroutine that starts the request.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        }
        withTimeout(5_000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertEquals(3, server.requestCount)
    }

    @Test fun optionalExpansionFailureKeepsInitialResultsAndReportsIncompleteLibrary() = runBlocking {
        response("Hub", hub("movie", movie(1, "리오")), hub("actor", person(19, "리오")))
        server.enqueue(MockResponse().setResponseCode(500))
        val result = client.searchWithStatus("리오", libraries)
        assertEquals(listOf(1), result.items.map { it.id })
        assertEquals(1, result.failedLibraryCount)
    }

    @Test fun conflictingHubAndMediaSectionsDoNotSuppressTheAccessibleAlias() = runBlocking {
        response("Hub", hub("movie", movie(1, "리오", library = 9)).put("librarySectionID", 4),
            hub("movie", movie(2, "Server alias")))
        assertEquals(listOf(2), client.search("리오", libraries).map { it.id })
    }

    @Test fun shelfPersonGroupPreservesWatchSortAndPaginationAndCachesIdentityLookup() = runBlocking {
        response("Hub", hub("actor", person(19, "Original server alias"), person(20, "리오"), person(99, "리오", library = 9)),
            hub("director", person(21, "리오", kind = "director")))
        response("Metadata", movie(1))
        response("Metadata", movie(2))
        val library = libraries.first()
        val first = client.page(library, 60, 12, sort = LibrarySort.TITLE_DESC,
            watchFilter = LibraryWatchFilter.IN_PROGRESS, title = "리오", searchPeople = true)
        assertEquals(61, first.nextOffset)
        assertEquals("4", server.takeRequest().requestUrl!!.queryParameter("sectionId"))
        val url = server.takeRequest().requestUrl!!
        assertEquals("60", url.queryParameter("X-Plex-Container-Start"))
        assertEquals("12", url.queryParameter("X-Plex-Container-Size"))
        assertEquals("titleSort:desc", url.queryParameter("sort"))
        assertEquals("1", url.queryParameter("inProgress"))
        assertEquals("1", url.queryParameter("type"))
        assertEquals("리오", url.queryParameter("title"))
        assertEquals("19,20", url.queryParameter("actor"))
        assertEquals("21", url.queryParameter("director"))
        assertEquals(listOf("push", "title", "or", "actor", "or", "director", "pop"),
            (0..6).map(url::queryParameterName))
        client.page(library, 72, 12, title = "리오", searchPeople = true)
        assertEquals("72", server.takeRequest().requestUrl!!.queryParameter("X-Plex-Container-Start"))
        assertEquals(3, server.requestCount)
    }

    @Test fun shelfWithoutPersonHitsKeepsLiteralTitleQueryAndCachesTheMiss() = runBlocking {
        response("Hub", hub("movie", movie(1, "인턴")))
        response("Directory")
        response("Metadata", movie(1, "인턴"))
        response("Metadata")
        client.page(libraries.first(), title = "인턴", searchPeople = true)
        server.takeRequest()
        assertEquals("/plex/library/sections/4/actor", server.takeRequest().requestUrl!!.encodedPath)
        val url = server.takeRequest().requestUrl!!
        assertEquals("인턴", url.queryParameter("title"))
        assertNull(url.queryParameter("push"))
        assertNull(url.queryParameter("actor"))
        client.page(libraries.first(), 60, title = "인턴", searchPeople = true)
        assertEquals("/plex/library/sections/4/all", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(4, server.requestCount)
    }

    @Test fun seasonAndEpisodeShelfKeepTheirExistingTitleSearchWithoutPersonRequests() = runBlocking {
        response("Metadata")
        client.page(libraries.last(), contentType = LibraryContentType.EPISODES, title = "리오", searchPeople = true)
        assertEquals("/plex/library/sections/9/all", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(1, server.requestCount)
    }

    @Test fun identityCacheExpiresIsBoundedAndSeparatesLibraryScopes() {
        var now = 0L
        val cache = PlexPersonSearchCache { now }
        cache.put(libraries.first(), "리오", "synthetic-path")
        assertEquals(true to "synthetic-path", cache.get(libraries.first(), "리오"))
        assertEquals(false to null, cache.get(libraries.last(), "리오"))
        now = 300_000_000_000L
        assertEquals(false to null, cache.get(libraries.first(), "리오"))
        repeat(17) { cache.put(libraries.first(), "name$it", null) }
        assertEquals(false to null, cache.get(libraries.first(), "name0"))
        assertEquals(true to null, cache.get(libraries.first(), "name16"))
        assertEquals(false to null, PlexPersonSearchCache().get(libraries.first(), "name16"))
    }

    @Test fun shelfLookupFailurePreservesTitleResultsReportsPartialAndDoesNotCacheFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        response("Directory")
        response("Metadata", movie(1, "리오"))
        response("Hub", hub("actor", person(19, "리오")))
        response("Metadata", movie(2))
        val failed = client.page(libraries.first(), title = "리오", searchPeople = true)
        assertEquals(listOf(1), failed.items.map { it.id })
        assertTrue(failed.personSearchIncomplete)
        val retry = client.page(libraries.first(), title = "리오", searchPeople = true)
        assertEquals(listOf(2), retry.items.map { it.id })
        assertFalse(retry.personSearchIncomplete)
        assertEquals(5, server.requestCount)
    }
}
