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

class ActorFastLookupTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val actor = CastMember("Synthetic actor", ppkey = "actor-1")
    private val show = MediaItem(11, 9, "Current", "show", viewItemId = 101)
    private val tv = Library(9, "TV", "tv")
    private val movies = Library(8, "Movies", "movie")
    @Volatile private var provider = false

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when (request.requestUrl!!.encodedPath) {
                        "/people/tmdb/77" -> MockResponse().setBody(if (provider) """{"local_credits":[
                            {"metadata_type":1,"library_view_id":8,"metadata_item_id":31,"title":"Provider movie"},
                            {"metadata_type":2,"library_view_id":9,"metadata_item_id":11,"title":"Current"}]}
                        """ else """{"local_credits":[]}""")
                        "/libraries/9/shows" -> MockResponse().setBody(
                            """[{"id":11,"view_item_id":101,"title":"Current"},{"id":21,"view_item_id":201,"title":"Other"}]""")
                        "/libraries/8/movies" -> MockResponse().setBody((1..23_874).joinToString(",", "[", "]") {
                            """{"id":$it,"title":"Synthetic movie"}"""
                        })
                        "/libraries/8/movies/31" -> MockResponse().setBody(cast("actor-1"))
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

    @Test fun fastPpkeySearchNeverRequestsHugeMovieCatalogOrDetails() = runBlocking {
        val progress = mutableListOf<List<MediaItem>>()
        val result = client.actorWorks(actor, show, listOf(tv, movies), allowLocalFallback = true) { items, _, _ -> progress += items }

        assertEquals(setOf(11, 21), result.items.map { it.id }.toSet())
        assertTrue(result.tvLibrariesOnly)
        assertTrue(result.movieSearchAvailable)
        assertTrue(progress.any { it.map { work -> work.id }.toSet() == setOf(11, 21) })
        assertEquals(listOf("/libraries/9/shows"), requests.map { it.requestUrl!!.encodedPath })
        assertEquals("actor-1", requests.single().requestUrl!!.queryParameter("actor_ppkey"))
    }

    @Test fun knownProviderMoviesArePreservedAndNeverMislabeledAsTvOnly() = runBlocking {
        provider = true
        val result = client.actorWorks(actor.copy(tmdbId = 77), show, listOf(tv, movies),
            allowLocalFallback = true, scanMovieDetails = false)

        assertEquals(setOf(11, 31), result.items.map { it.id }.toSet())
        assertFalse(result.tvLibrariesOnly)
        assertTrue(result.movieSearchAvailable)
        assertEquals(listOf("/people/tmdb/77"), requests.map { it.requestUrl!!.encodedPath })
    }

    @Test fun movieParentRemainsAuthorizedWhenMovieSearchIsDeferred() = runBlocking {
        val parent = MediaItem(31, 8, "Movie", "movie")
        val result = client.actorWorks(actor, parent, listOf(movies))
        assertTrue(result.items.isEmpty())
        assertTrue(result.movieSearchAvailable)
        assertTrue(requests.isEmpty())
    }

    @Test fun lockedDuplicateWinsBeforeFastScopeIsNarrowed() = runBlocking {
        val duplicatePrivate = Library(8, "Locked TV", "tv", true)
        val result = client.actorWorks(actor, show, listOf(tv, movies, duplicatePrivate),
            allowLocalFallback = true, scanMovieDetails = false)
        assertEquals(setOf(11, 21), result.items.map { it.id }.toSet())
        assertFalse(result.movieSearchAvailable)
        assertFalse(requests.any { it.requestUrl!!.encodedPath.startsWith("/libraries/8/") })
    }

    @Test fun userRequestedMovieScanFetchesAndVerifiesMovies() = runBlocking {
        // Limit the explicit-scan fixture to one movie; no scan is automatic.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.requestUrl!!.encodedPath) {
                    "/libraries/9/shows" -> MockResponse().setBody("""[{"id":11,"title":"Current"}]""")
                    "/libraries/8/movies" -> MockResponse().setBody("""[{"id":31,"title":"Matched movie"}]""")
                    "/libraries/8/movies/31" -> MockResponse().setBody(cast("actor-1"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val result = client.actorWorks(actor, show, listOf(tv, movies), allowLocalFallback = true,
            scanMovieDetails = true, includeExtraMovieScan = true)
        assertEquals(setOf(11, 31), result.items.map { it.id }.toSet())
        assertFalse(result.movieSearchAvailable)
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/libraries/8/movies" })
        assertTrue(requests.any { it.requestUrl!!.encodedPath == "/libraries/8/movies/31" })
    }

    private fun cast(ppkey: String): String = """{"metadata_sections":[{"presentation":"person_row",
        "groups":[{"id":"cast","items":[{"title":"Synthetic actor",
        "action":{"type":"actor_ppkey","value":"$ppkey"}}]}]}]}"""
}
