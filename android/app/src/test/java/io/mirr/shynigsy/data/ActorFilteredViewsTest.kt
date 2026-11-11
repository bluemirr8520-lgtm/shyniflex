package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The server's actor filter checks every grouped show member, not only its representative. */
class ActorFilteredViewsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var changeTokenOnCredits = false
    @Volatile private var providerCurrentOnly = false
    @Volatile private var emptyTv = false

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when (request.requestUrl!!.encodedPath) {
                        "/people/tmdb/77" -> {
                            if (changeTokenOnCredits) client.token = "different-synthetic-account"
                            val body = if (providerCurrentOnly) """{"local_credits":[
                                {"metadata_type":2,"library_view_id":9,"metadata_item_id":11,"view_item_id":101,"title":"Current TV"}
                            ]}""" else """{"local_credits":[
                                {"remote":true,"metadata_type":2,"library_view_id":9,"metadata_item_id":1,"title":"Remote"},
                                {"metadata_type":2,"library_view_id":999,"metadata_item_id":2,"title":"Other view"},
                                {"metadata_type":9,"library_view_id":9,"metadata_item_id":3,"title":"Wrong type"},
                                {"metadata_type":2,"library_view_id":9,"metadata_item_id":0,"title":"Invalid ID"},
                                {"metadata_type":2,"library_view_id":11,"metadata_item_id":4,"title":"Locked"}
                            ]}"""
                            MockResponse().setBody(body)
                        }
                        "/libraries/9/shows" -> MockResponse().setBody(
                            if (emptyTv) "[]" else """[{"id":11,"view_item_id":101,"title":"Current TV"}]""")
                        "/libraries/10/shows" -> MockResponse().setBody(
                            if (emptyTv) "[]" else """[{"id":21,"view_item_id":201,"title":"Matched grouped TV"}]""")
                        "/libraries/8/movies" -> MockResponse().setBody(
                            """[{"id":31,"title":"Matched movie"},{"id":32,"title":"Unrelated movie"}]""")
                        "/libraries/8/movies/31" -> MockResponse().setBody(cast("actor-1"))
                        "/libraries/8/movies/32" -> MockResponse().setBody(cast("other-actor"))
                        // The server filter found the actor in a non-representative member;
                        // the representative detail lacks the actor, by design.
                        "/libraries/10/shows/21" -> MockResponse().setBody(cast("other-actor"))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "synthetic-account", deviceId = "synthetic-device")
    }

    @After fun cleanup() {
        SpoilerAccessRegistry.clearAll()
        server.shutdown()
    }

    @Test fun filteredShowsAreTrustedAcrossViewsButMoviesStillNeedCastVerification() = runBlocking {
        val actor = CastMember("Synthetic actor", ppkey = "actor-1")
        val parent = MediaItem(11, 9, "Current TV", "show", viewItemId = 101)
        val libraries = listOf(Library(8, "Movies", "movie"), Library(9, "Current TV", "tv"),
            Library(10, "Other TV", "tv"), Library(11, "Locked TV", "tv", true))

        val result = client.actorWorks(actor, parent, libraries, allowLocalFallback = true)

        assertEquals(setOf(11, 21, 31), result.items.map { it.id }.toSet())
        val queries = requests.filter { it.requestUrl!!.encodedPath.endsWith("/shows") }
        assertEquals(setOf("/libraries/9/shows", "/libraries/10/shows"),
            queries.map { it.requestUrl!!.encodedPath }.toSet())
        assertTrue(queries.all { it.requestUrl!!.queryParameter("actor_ppkey") == "actor-1" })
        assertFalse(requests.any { it.requestUrl!!.encodedPath == "/libraries/10/shows/21" })
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/libraries/8/movies/31" })
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/libraries/8/movies/32" })
        assertFalse(requests.any { it.requestUrl!!.encodedPath.startsWith("/libraries/11/") })
    }

    @Test fun explicitSelectionFallsBackWhenNonemptyProviderRowsAreAllInvisible() = runBlocking {
        val actor = CastMember("Synthetic actor", tmdbId = 77, ppkey = "actor-1")
        val parent = MediaItem(11, 9, "Current TV", "show", viewItemId = 101)
        val libraries = listOf(Library(9, "Current TV", "tv"), Library(10, "Other TV", "tv"),
            Library(11, "Locked TV", "tv", true))

        val result = client.actorWorks(actor, parent, libraries, allowLocalFallback = true)

        assertEquals(setOf(11, 21), result.items.map { it.id }.toSet())
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/people/tmdb/77" })
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/libraries/9/shows" })
        assertFalse(requests.any { it.requestUrl!!.encodedPath.startsWith("/libraries/11/") })
    }

    @Test fun automaticLookupDoesNotFallBackWhenProviderRowsAreInvisible() = runBlocking {
        val actor = CastMember("Synthetic actor", tmdbId = 77, ppkey = "actor-1")
        val parent = MediaItem(11, 9, "Current TV", "show", viewItemId = 101)

        val result = client.actorWorks(actor, parent, listOf(Library(9, "Current TV", "tv")))

        assertTrue(result.items.isEmpty())
        assertEquals(listOf("/people/tmdb/77"), requests.map { it.requestUrl!!.encodedPath })
    }

    @Test fun currentShowOnlyFallsBackToTvViewsAndDeduplicatesTheCurrentShow() = runBlocking {
        providerCurrentOnly = true
        val actor = CastMember("Synthetic actor", tmdbId = 77, ppkey = "actor-1")
        val parent = MediaItem(11, 9, "Current TV", "show", viewItemId = 101)
        val libraries = listOf(Library(8, "Movies", "movie"), Library(9, "Current TV", "tv"),
            Library(10, "Other TV", "tv"), Library(11, "Locked TV", "tv", true))

        val result = client.actorWorks(actor, parent, libraries, allowLocalFallback = true)

        assertEquals(setOf(11, 21), result.items.map { it.id }.toSet())
        assertEquals(1, result.items.count { it.id == 11 })
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/libraries/10/shows" })
        assertFalse(requests.any { it.requestUrl!!.encodedPath.contains("/movies") })
        assertFalse(requests.any { it.requestUrl!!.encodedPath.startsWith("/libraries/11/") })
    }

    @Test fun currentShowIsRetainedWhenTvFallbackFindsNothing() = runBlocking {
        providerCurrentOnly = true
        emptyTv = true
        val actor = CastMember("Synthetic actor", tmdbId = 77, ppkey = "actor-1")
        val parent = MediaItem(11, 9, "Current TV", "show", viewItemId = 101)

        val result = client.actorWorks(actor, parent, listOf(Library(9, "Current TV", "tv")), allowLocalFallback = true)

        assertEquals(listOf(11), result.items.map { it.id })
    }

    @Test fun automaticLookupDoesNotFallbackWhenProviderHasOnlyCurrentShow() = runBlocking {
        providerCurrentOnly = true
        val actor = CastMember("Synthetic actor", tmdbId = 77, ppkey = "actor-1")
        val parent = MediaItem(11, 9, "Current TV", "show", viewItemId = 101)

        val result = client.actorWorks(actor, parent, listOf(Library(9, "Current TV", "tv")))

        assertEquals(listOf(11), result.items.map { it.id })
        assertEquals(listOf("/people/tmdb/77"), requests.map { it.requestUrl!!.encodedPath })
    }

    @Test fun changedLoginNeverStartsLocalFallback() = runBlocking {
        changeTokenOnCredits = true
        val actor = CastMember("Synthetic actor", tmdbId = 77, ppkey = "actor-1")
        val parent = MediaItem(11, 9, "Current TV", "show", viewItemId = 101)

        val error = runCatching {
            client.actorWorks(actor, parent, listOf(Library(9, "Current TV", "tv")), allowLocalFallback = true)
        }.exceptionOrNull()

        assertTrue(error is ApiException && error.status == 401)
        assertEquals(listOf("/people/tmdb/77"), requests.map { it.requestUrl!!.encodedPath })
    }

    private fun cast(ppkey: String): String = """{"metadata_sections":[{"presentation":"person_row",
        "groups":[{"id":"cast","items":[{"title":"Synthetic actor",
        "action":{"type":"actor_ppkey","value":"$ppkey"}}]}]}]}"""
}
