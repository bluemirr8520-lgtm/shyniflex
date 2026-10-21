package io.mirr.shynigsy.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
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

class BrowseEpisodeIdentityAccessTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val season = MediaItem(7, 9, "시즌 0", "season", showId = 5, seasonNumber = 0, viewItemId = 601)
    private val identityError = """{"detail":"에피소드의 정체성을 확인할 수 없습니다."}"""

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { start() }
        client = ShyniClient(server.url("/").toString(), "fixture-account", deviceId = "fixture-device")
    }

    @After fun close() {
        SpoilerAccessRegistry.clearAll()
        server.shutdown()
    }

    private fun respond(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun episodeRows(ids: IntRange): String = JSONArray(ids.map { id ->
        JSONObject().put("id", id).put("title", "회차 $id").put("view_item_id", 601)
            .put("season_number", 0).put("episode_number", id).put("completed", false)
            .put("position_ms", 65_000L + id).put("duration_ms", 1_800_000)
    }).toString()

    private fun request(): RecordedRequest = server.takeRequest(10, TimeUnit.SECONDS)
        ?: throw AssertionError("Expected fixture HTTP request")

    @Test fun seasonProgressAuthenticationAndAuthorizationFailuresNeverBecomeCatalogSuccess() = runBlocking {
        for (status in listOf(401, 403)) {
            respond(episodeRows(24..25))
            // The same text must not override an authentication or access denial.
            respond(identityError, status)
            val caught = runCatching { client.details(season) }.exceptionOrNull()
            assertTrue("HTTP $status must propagate", caught is ApiException)
            assertEquals(status, (caught as ApiException).status)
            assertEquals("/libraries/9/shows/5/seasons/7/episodes", request().requestUrl!!.encodedPath)
            val progress = request()
            assertEquals("/playback/progress", progress.requestUrl!!.encodedPath)
            assertEquals("24,25", progress.requestUrl!!.queryParameter("item_ids"))
        }
        assertEquals("Access failures must not cause fallback requests", 4, server.requestCount)
    }

    @Test fun privateSeasonIdentityFailureKeepsItsCatalogAndNeverDropsTheGrant() = runBlocking {
        respond("""{"token":"spc_fixture-private-grant","expires_in":1800}""")
        assertTrue(client.unlockSpoiler("1234").unlocked)
        val unlock = request()
        assertEquals("/spoiler/client/unlock", unlock.requestUrl!!.encodedPath)
        assertNull(unlock.getHeader("X-Shyni-Spoiler"))

        respond(episodeRows(24..25))
        respond(identityError, 404)
        val details = client.details(season.copy(isSpoiler = true))
        assertEquals(listOf(24, 25), details.children.map { it.id })
        assertTrue(details.children.all { it.isSpoiler && it.libraryId == 9 && it.viewItemId == 601 && it.showId == 5 })
        assertEquals(65_024L, details.children.first().resumeMs)
        assertEquals(0, details.children.first().seasonNumber)

        val catalog = request()
        val progress = request()
        assertEquals("/libraries/9/shows/5/seasons/7/episodes", catalog.requestUrl!!.encodedPath)
        assertEquals("/playback/progress", progress.requestUrl!!.encodedPath)
        assertEquals("9", progress.requestUrl!!.queryParameter("library_view_id"))
        for (sent in listOf(catalog, progress)) {
            assertEquals("spc_fixture-private-grant", sent.getHeader("X-Shyni-Spoiler"))
            assertEquals("Bearer fixture-account", sent.getHeader("Authorization"))
            assertEquals("fixture-device", sent.getHeader("X-Device-Id"))
        }
        assertTrue(client.spoilerUnlocked)
        assertFalse(details.children.any { it.toJson().contains("spc_fixture-private-grant") })
        assertEquals("No ungranted/public retry is permitted", 3, server.requestCount)
    }

    @Test fun cancellingPendingSeasonProgressCancelsHttpAndDoesNotPublishTheCatalog() = runBlocking {
        withTimeout(30_000) {
            val progressCallCancelled = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    if (call.request().url.encodedPath == "/playback/progress") progressCallCancelled.complete(Unit)
                }
            }).build()
            val cancellable = ShyniClient(server.url("/").toString(), "fixture-account",
                httpClient = http, deviceId = "fixture-device")
            respond(episodeRows(24..25))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            var catalogReturned = false
            val loading = launch {
                try {
                    cancellable.details(season)
                    catalogReturned = true
                } catch (cancel: CancellationException) {
                    cancellationObserved.complete(Unit)
                    throw cancel
                }
            }
            try {
                // Let the coroutine run while the fixture waits for real HTTP delivery.
                val observed = withContext(Dispatchers.IO) { listOf(request(), request()) }
                assertEquals(listOf("/libraries/9/shows/5/seasons/7/episodes", "/playback/progress"),
                    observed.map { it.requestUrl!!.encodedPath })
                loading.cancelAndJoin()
                cancellationObserved.await()
                progressCallCancelled.await()
                assertTrue(loading.isCancelled)
                assertFalse("Cancellation must not be swallowed as optional-progress fallback", catalogReturned)
                assertEquals(2, server.requestCount)
            } finally {
                loading.cancelAndJoin()
            }
        }
    }

    @Test fun failedFirstProgressChunkDoesNotSuppressTheSuccessfulSecondChunk() = runBlocking {
        respond(episodeRows(1..100))
        respond(episodeRows(101..101))
        respond(identityError, 404)
        respond("""{"items":[{"metadata_item_id":101,"completed":false,"position_ms":123456,"duration_ms":1800000}]}""")
        val details = client.details(season)
        assertEquals(101, details.children.size)
        assertEquals(65_001L, details.children.first().resumeMs)
        assertEquals(65_100L, details.children[99].resumeMs)
        assertEquals(123_456L, details.children.last().resumeMs)
        assertFalse(details.children.last().watched)

        assertEquals("0", request().requestUrl!!.queryParameter("offset"))
        assertEquals("100", request().requestUrl!!.queryParameter("offset"))
        val firstChunk = request()
        val lastChunk = request()
        assertEquals((1..100).joinToString(","), firstChunk.requestUrl!!.queryParameter("item_ids"))
        assertEquals("101", lastChunk.requestUrl!!.queryParameter("item_ids"))
        assertEquals("9", lastChunk.requestUrl!!.queryParameter("library_view_id"))
        assertEquals(4, server.requestCount)
    }
}
