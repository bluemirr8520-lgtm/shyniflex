package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RelatedWorksLocalActorsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val requests = CopyOnWriteArrayList<String>()
    private var response: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }
    private val parent = MediaItem(1, 8, "Current", "show", viewItemId = 101)
    private val libraries = listOf(Library(8, "Series", "tv"), Library(9, "Other", "tv"),
        Library(10, "Movies", "movie"), Library(11, "Private", "tv", isSpoiler = true))

    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.path.orEmpty()
                    return response(request)
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "synthetic-account", deviceId = "synthetic-device")
    }

    @After fun cleanup() { server.shutdown() }

    @Test fun ppkeyFindsOtherShowsInAuthorizedTvViewsWithoutScanningMovies() = runBlocking {
        response = { req -> when (req.requestUrl!!.encodedPath) {
            "/libraries/8/shows" -> shows(1, 2)
            "/libraries/9/shows" -> shows(3)
            else -> MockResponse().setResponseCode(500)
        } }
        val works = client.relatedWorks(parent, listOf(CastMember("Actor", ppkey = "synthetic-person")), libraries)
        assertEquals(setOf(2, 3), works.map { it.id }.toSet())
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.contains("actor_ppkey=synthetic-person") })
        assertTrue(requests.none { it.contains("/movies") || it.contains("/libraries/11/") })
    }

    @Test fun unsupportedTmdbFallsBackToTheServerTvActorFilter() = runBlocking {
        response = { req -> when (req.requestUrl!!.encodedPath) {
            "/people/tmdb/22" -> MockResponse().setResponseCode(404)
            "/libraries/8/shows" -> shows(2)
            "/libraries/9/shows" -> shows()
            else -> MockResponse().setResponseCode(500)
        } }
        val works = client.relatedWorks(parent, listOf(CastMember("Actor", tmdbId = 22, ppkey = "local-22")), libraries)
        assertEquals(listOf(2), works.map { it.id })
        assertTrue(requests.any { it.startsWith("/people/tmdb/22?") })
        assertTrue(requests.none { it.contains("/movies") })
    }

    @Test fun successfulEmptyLocalFallbackResolvesItsUnsupportedProviderError() = runBlocking {
        response = { req -> when (req.requestUrl!!.encodedPath) {
            "/people/tmdb/22" -> MockResponse().setResponseCode(404)
            "/libraries/8/shows" -> shows()
            else -> MockResponse().setResponseCode(500)
        } }
        assertTrue(client.relatedWorks(parent, listOf(CastMember("Actor", tmdbId = 22, ppkey = "person")),
            listOf(libraries[0])).isEmpty())
    }

    @Test fun successfulFallbackDoesNotEraseAnotherActorsUnresolvedFailure() = runBlocking {
        response = { req -> when (req.requestUrl!!.encodedPath) {
            "/people/tmdb/21" -> MockResponse().setResponseCode(500)
            "/people/tmdb/22" -> MockResponse().setResponseCode(404)
            "/libraries/8/shows" -> shows()
            else -> MockResponse().setResponseCode(500)
        } }
        val failure = runCatching {
            client.relatedWorks(parent, listOf(CastMember("First", tmdbId = 21),
                CastMember("Second", tmdbId = 22, ppkey = "person")), listOf(libraries[0]))
        }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.status == 500)
    }

    @Test fun atMostThreeLocalActorsAreQueriedAndMovieParentsNeverTriggerLocalScans() = runBlocking {
        response = { shows() }
        val localActors = (1..5).map { CastMember("Actor $it", ppkey = "person-$it") }
        val onlyOneView = listOf(libraries[0])
        assertTrue(client.relatedWorks(parent, localActors, onlyOneView).isEmpty())
        assertEquals(3, requests.size)
        requests.clear()
        val movie = MediaItem(7, 10, "Movie", "movie")
        assertTrue(client.relatedWorks(movie, localActors, libraries).isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test fun incompleteLocalSearchReportsFailureRatherThanPretendingThereAreNoWorks() = runBlocking {
        response = { MockResponse().setResponseCode(500) }
        val failure = runCatching {
            client.relatedWorks(parent, listOf(CastMember("Actor", ppkey = "person")), listOf(libraries[0]))
        }.exceptionOrNull()
        assertTrue(failure is ApiException)
    }

    @Test fun providerAuthorizationFailureDoesNotFallback() = runBlocking {
        for (status in listOf(401, 403)) {
            requests.clear()
            response = { MockResponse().setResponseCode(status) }
            val failure = runCatching {
                client.relatedWorks(parent, listOf(CastMember("Actor", tmdbId = 22, ppkey = "person")), libraries)
            }.exceptionOrNull()
            assertTrue(failure is ApiException && failure.status == status)
            assertEquals(1, requests.size)
            assertTrue(requests.single().startsWith("/people/tmdb/22?"))
        }
    }

    @Test fun lockedPrivateParentIsNeverQueried() = runBlocking {
        val privateParent = parent.copy(libraryId = 11, isSpoiler = true)
        val failure = runCatching {
            client.relatedWorks(privateParent, listOf(CastMember("Actor", ppkey = "person")), libraries)
        }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.status == 403)
        assertTrue(requests.isEmpty())
    }

    @Test fun conflictingDuplicateViewIdsKeepThePrivateAccessRestriction() = runBlocking {
        response = { shows() }
        val mixed = listOf(libraries[0], libraries[1], Library(9, "Private duplicate", "movie", isSpoiler = true))
        assertTrue(client.relatedWorks(parent, listOf(CastMember("Actor", ppkey = "person")), mixed).isEmpty())
        assertEquals(1, requests.size)
        assertTrue(requests.single().startsWith("/libraries/8/shows?"))
        requests.clear()
        val privateParentDuplicate = listOf(libraries[0], Library(8, "Private duplicate", "movie", isSpoiler = true))
        val failure = runCatching {
            client.relatedWorks(parent, listOf(CastMember("Actor", ppkey = "person")), privateParentDuplicate)
        }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.status == 403)
        assertTrue(requests.isEmpty())
    }

    private fun shows(vararg ids: Int): MockResponse = MockResponse().setBody(
        ids.joinToString(prefix = "[", postfix = "]", separator = ",") {
            """{"id":$it,"title":"Synthetic $it"}"""
        })
}
