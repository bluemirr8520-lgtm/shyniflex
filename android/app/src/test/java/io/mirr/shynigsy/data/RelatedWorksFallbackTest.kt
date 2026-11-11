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

class RelatedWorksFallbackTest {
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private var response: (String) -> MockResponse = { MockResponse().setResponseCode(404) }
    private val movie = MediaItem(11, 8, "Current", "movie", viewItemId = 101)
    private val libraries = listOf(Library(8, "Movies", "movie"))

    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests += path
                    return response(path)
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "synthetic-account", deviceId = "synthetic-device")
    }

    @After fun cleanup() { server.shutdown() }

    @Test fun fallsBackWhenFirstActorHasOnlyCurrentWork() = runBlocking {
        response = { path -> when (path) {
            "/people/tmdb/1" -> credits(11)
            "/people/tmdb/2" -> credits(11, 12)
            else -> MockResponse().setResponseCode(404)
        } }
        val result = client.relatedWorks(movie, listOf(CastMember("First", tmdbId = 1),
            CastMember("Duplicate", tmdbId = 1), CastMember("Second", tmdbId = 2),
            CastMember("Never queried", tmdbId = 3)), libraries)
        assertEquals(listOf(12), result.map { it.id })
        assertEquals(listOf("/people/tmdb/1", "/people/tmdb/2"), requests)
    }

    @Test fun lookupIsBoundedAndDoesNotAutomaticallyScanPpkey() = runBlocking {
        response = { credits() }
        val cast = (1..5).map { CastMember("Actor $it", tmdbId = it, ppkey = "local-$it") }
        assertTrue(client.relatedWorks(movie, cast, libraries).isEmpty())
        assertEquals(listOf("/people/tmdb/1", "/people/tmdb/2", "/people/tmdb/3"), requests)
    }

    @Test fun inaccessibleFirstActorCanFallBackButAuthorizationFailureStops() = runBlocking {
        response = { path -> if (path == "/people/tmdb/1") MockResponse().setResponseCode(404) else credits(12) }
        val cast = listOf(CastMember("First", tmdbId = 1), CastMember("Second", tmdbId = 2))
        assertEquals(listOf(12), client.relatedWorks(movie, cast, libraries).map { it.id })
        requests.clear()
        response = { MockResponse().setResponseCode(401) }
        val failure = runCatching { client.relatedWorks(movie, cast, libraries) }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.status == 401)
        assertEquals(listOf("/people/tmdb/1"), requests)
    }

    private fun credits(vararg ids: Int): MockResponse {
        val rows = ids.joinToString(",") {
            """{"metadata_type":1,"library_view_id":8,"representative_metadata_item_id":$it,"title":"Synthetic $it"}"""
        }
        return MockResponse().setBody("""{"local_credits":[$rows]}""")
    }
}
