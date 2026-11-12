package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the public wrapper with synthetic Views, sessions and provider replies. */
class ActorWorksCacheIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var providerRows = listOf(row(9, 1), row(10, 2), row(11, 3))
    private val actor = CastMember("Synthetic actor", tmdbId = 77)
    private val parent = MediaItem(1, 9, "Synthetic parent", "show", viewItemId = 1001)
    private val normal = listOf(Library(9, "TV", "tv"), Library(10, "Other TV", "tv"))
    private val all = normal + Library(11, "Private", "tv", isSpoiler = true)

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests += path
                    return when (path) {
                        "/people/tmdb/77" -> MockResponse().setBody(JSONObject().put("local_credits", JSONArray(providerRows)).toString())
                        "/libraries/9/shows" -> MockResponse().setBody("""[{"id":1,"view_item_id":1001,"title":"Current"},{"id":2,"view_item_id":1002,"title":"Other"}]""")
                        "/playback/progress" -> MockResponse().setBody("{}")
                        "/spoiler/client/unlock" -> MockResponse().setBody("""{"token":"spc_synthetic-private","expires_in":1800}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "synthetic-account", deviceId = "synthetic-device")
    }

    @After fun cleanup() { SpoilerAccessRegistry.clearAll(); server.shutdown() }

    @Test fun sameActorFromAnotherShowReusesTheCompleteAuthorizedCatalogue() = runBlocking {
        val first = client.actorWorks(actor, parent, normal)
        val second = client.actorWorks(actor, parent.copy(id = 99, viewItemId = 1099), normal.reversed())
        assertEquals(first, second)
        assertEquals(setOf(1, 2), second.items.map { it.id }.toSet())
        assertEquals(1, providerRequests())
    }

    @Test fun changedAccountCannotReuseThePreviousAccountsCatalogue() = runBlocking {
        client.actorWorks(actor, parent, normal)
        providerRows = listOf(row(9, 8))
        client.token = "another-synthetic-account"
        assertEquals(listOf(8), client.actorWorks(actor, parent, normal).items.map { it.id })
        assertEquals(2, providerRequests())
    }

    @Test fun removedViewDoesNotRemainVisibleThroughThePreviousScopeKey() = runBlocking {
        assertEquals(2, client.actorWorks(actor, parent, normal).items.size)
        assertEquals(listOf(1), client.actorWorks(actor, parent, listOf(normal.first())).items.map { it.id })
        assertEquals(2, providerRequests())
    }

    @Test fun relockingPrivateAccessDropsCachedPrivateRowsBeforeReturning() = runBlocking {
        client.unlockSpoiler("1234")
        assertEquals(setOf(1, 2, 3), client.actorWorks(actor, parent, all).items.map { it.id }.toSet())
        client.clearSpoilerGrant()
        assertEquals(setOf(1, 2), client.actorWorks(actor, parent, all).items.map { it.id }.toSet())
        assertEquals(2, providerRequests())
    }

    @Test fun conflictingViewTypesAreDeniedAndPrivateDuplicatesRemainPrivate() = runBlocking {
        val conflict = listOf(Library(9, "TV", "tv"), Library(9, "Movie", "movie"))
        for (scopes in listOf(conflict, conflict.reversed(),
            listOf(Library(9, "TV", "tv"), Library(9, "Private TV", "tv", true)))) {
            val failure = runCatching { client.actorWorks(actor, parent, scopes) }.exceptionOrNull()
            assertTrue(failure is ApiException && failure.status == 403)
        }
        assertEquals(0, providerRequests())
    }

    @Test fun parentDependentLegacyMovieScanDoesNotReuseAnotherShowsFallbackDecision() = runBlocking {
        providerRows = listOf(row(9, 1))
        val selected = actor.copy(ppkey = "synthetic-person")
        val scope = listOf(normal.first())
        val otherParent = parent.copy(id = 2, viewItemId = 1002)
        assertEquals(listOf(1), client.actorWorks(selected, otherParent, scope,
            allowLocalFallback = true, scanMovieDetails = true).items.map { it.id })
        assertEquals(setOf(1, 2), client.actorWorks(selected, parent, scope,
            allowLocalFallback = true, scanMovieDetails = true).items.map { it.id }.toSet())
        assertEquals(2, providerRequests())
        assertEquals(1, requests.count { it == "/libraries/9/shows" })
    }

    @Test fun successfulPlaybackProgressInvalidatesACompletedActorCatalogue() = runBlocking {
        client.actorWorks(actor, parent, normal)
        providerRows = listOf(row(9, 4))
        val spec = PlaybackSpec("Synthetic", server.url("/stream/4").toString(),
            itemId = 1, libraryId = 9, partId = 4, viewItemId = 1001, sessionId = "synthetic-session")
        client.saveProgress(spec, 1_000, 100_000, "paused")
        assertEquals(listOf(4), client.actorWorks(actor, parent, normal).items.map { it.id })
        assertEquals(2, providerRequests())
    }

    private fun providerRequests() = requests.count { it == "/people/tmdb/77" }
    private fun row(view: Int, id: Int) = JSONObject().put("metadata_type", 2).put("library_view_id", view)
        .put("metadata_item_id", id).put("view_item_id", 1000 + id).put("title", "Synthetic work $id")
}
