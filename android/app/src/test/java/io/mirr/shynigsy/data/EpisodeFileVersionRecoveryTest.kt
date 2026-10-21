package io.mirr.shynigsy.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EpisodeFileVersionRecoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val episode = MediaItem(24, 9, "번호 없는 회차", "episode", viewItemId = 601, playbackPartId = 90)
    private val identityError = """{"detail":"에피소드 정체성을 확인할 수 없습니다"}"""

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { start() }
        client = ShyniClient(server.url("/").toString(), "fixture-account", deviceId = "fixture-device")
    }

    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }

    private fun respond(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    private fun request(): RecordedRequest = server.takeRequest(10, TimeUnit.SECONDS)
        ?: throw AssertionError("Expected fixture HTTP request")

    @Test fun identityFailureReturnsOnlyKnownFileMetadataWithoutAuthorizingPlayback() = runBlocking {
        respond(identityError, 404)
        val result = client.versions(episode).single()
        assertEquals(MediaVersion(90, "원본 파일 90", metadataItemId = 24), result)
        val sent = request()
        assertEquals("/playback/versions", sent.requestUrl!!.encodedPath)
        val body = JSONObject(sent.body.readUtf8())
        assertEquals(24, body.getInt("metadata_item_id"))
        assertEquals(9, body.getInt("library_view_id"))
        assertEquals(601, body.getInt("view_item_id"))
        assertFalse(body.has("part_id"))
        assertEquals(1, server.requestCount)
    }

    @Test fun successfulVersionResponseAlwaysWinsOverBrowseHintIncludingEmptyOptions() = runBlocking {
        respond("""{"options":[{"part_id":91,"metadata_item_id":25,"resolution":"1080p"}]}""")
        assertEquals(91, client.versions(episode).single().partId)
        request()
        respond("""{"options":[]}""")
        assertTrue(client.versions(episode).isEmpty())
        request()
        assertEquals(2, server.requestCount)
    }

    @Test fun accessDenialsAndUnrelatedErrorsNeverReturnKnownVersionOrWriteWatchState() = runBlocking {
        for ((body, status) in listOf(identityError to 401, identityError to 403,
            identityError to 429, """{"detail":"파일을 찾을 수 없습니다"}""" to 404)) {
            for (write in listOf(false, true)) {
                respond(body, status)
                val caught = runCatching {
                    if (write) client.setWatched(episode, true) else client.versions(episode)
                }.exceptionOrNull() as ApiException
                assertEquals(status, caught.status)
                assertEquals("/playback/versions", request().requestUrl!!.encodedPath)
            }
        }
        assertEquals(8, server.requestCount)
    }

    @Test fun recoveryRequiresExactPositiveEpisodeFileAndViewContext() = runBlocking {
        val invalid = listOf(episode.copy(type = "movie"), episode.copy(id = 0),
            episode.copy(libraryId = 0), episode.copy(viewItemId = 0),
            episode.copy(playbackPartId = null), episode.copy(playbackPartId = -1),
            episode.copy(navigationPartId = 0))
        for (item in invalid) {
            respond(identityError, 404)
            assertEquals(404, (runCatching { client.versions(item) }.exceptionOrNull() as ApiException).status)
            request()
        }
        assertTrue(runCatching { client.versions(episode.copy(viewItemId = null)) }.exceptionOrNull() is ApiException)
        assertEquals(invalid.size, server.requestCount)
        respond(identityError, 404)
        assertEquals(404, (runCatching { client.setWatched(episode.copy(playbackPartId = null), true) }
            .exceptionOrNull() as ApiException).status)
        request()
        assertEquals(invalid.size + 1, server.requestCount)
    }

    @Test fun verifiedNavigationPinTakesPriorityWithoutInventingMetadataOwnership() = runBlocking {
        respond(identityError, 404)
        assertEquals(91, client.versions(episode.copy(navigationPartId = 91)).single().partId)
        request()
        respond(identityError, 404)
        respond("""{"ok":true,"watched":true}""")
        client.setWatched(episode.copy(navigationPartId = 91), true)
        request()
        val changed = request()
        assertEquals("/continue-watching/24/watch-state", changed.requestUrl!!.encodedPath)
        assertEquals(91, JSONObject(changed.body.readUtf8()).getInt("part_id"))
    }

    @Test fun manualWatchedAndUnwatchedStillUseAuthorizedExactPartPut() = runBlocking {
        for (watched in listOf(true, false)) {
            respond(identityError, 404)
            respond("""{"ok":true,"watched":$watched}""")
            client.setWatched(episode, watched)
            request()
            val changed = request()
            assertEquals("PUT", changed.method)
            assertEquals("/continue-watching/24/watch-state", changed.requestUrl!!.encodedPath)
            assertEquals("Bearer fixture-account", changed.getHeader("Authorization"))
            val body = JSONObject(changed.body.readUtf8())
            assertEquals(90, body.getInt("part_id"))
            assertEquals(9, body.getInt("library_view_id"))
            assertEquals(601, body.getInt("view_item_id"))
            assertEquals(watched, body.getBoolean("watched"))
            assertFalse(body.has("playback_context_token"))
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun serverWriteDenialsAndUnconfirmedStateStillFail() = runBlocking {
        for (status in listOf(401, 403)) {
            respond(identityError, 404)
            respond("""{"detail":"접근할 수 없습니다"}""", status)
            assertEquals(status, (runCatching { client.setWatched(episode, true) }.exceptionOrNull() as ApiException).status)
            repeat(2) { request() }
        }
        respond(identityError, 404)
        respond("""{"ok":true,"watched":false}""")
        assertTrue(runCatching { client.setWatched(episode, true) }.exceptionOrNull() is ApiException)
        repeat(2) { request() }
        assertEquals(6, server.requestCount)
    }

    @Test fun privateGrantIsRequiredAndPreservedForVersionAndWatchRequests() = runBlocking {
        val privateEpisode = episode.copy(isSpoiler = true)
        assertEquals(403, (runCatching { client.versions(privateEpisode) }.exceptionOrNull() as ApiException).status)
        assertEquals(0, server.requestCount)
        respond("""{"token":"spc_fixture-grant","expires_in":1800}""")
        client.unlockSpoiler("1234")
        request()
        respond(identityError, 404)
        client.versions(privateEpisode)
        assertEquals("spc_fixture-grant", request().getHeader("X-Shyni-Spoiler"))
        respond(identityError, 404)
        respond("""{"ok":true,"watched":true}""")
        client.setWatched(privateEpisode, true)
        repeat(2) { assertEquals("spc_fixture-grant", request().getHeader("X-Shyni-Spoiler")) }
        client.clearSpoilerGrant()
        assertEquals(403, (runCatching { client.setWatched(privateEpisode, false) }.exceptionOrNull() as ApiException).status)
        assertEquals(4, server.requestCount)
    }

    @Test fun cancelledVersionLookupNeverFallsBackOrWritesWatchState() = runBlocking {
        withTimeout(30_000) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = CompletableDeferred<Unit>()
            var returned = false
            val operation = launch {
                try { client.setWatched(episode, true); returned = true }
                catch (cancel: CancellationException) { cancelled.complete(Unit); throw cancel }
            }
            try {
                assertEquals("/playback/versions", withContext(Dispatchers.IO) { request() }.requestUrl!!.encodedPath)
                operation.cancelAndJoin()
                cancelled.await()
                assertTrue(operation.isCancelled)
                assertFalse(returned)
                assertEquals(1, server.requestCount)
            } finally { operation.cancelAndJoin() }
        }
    }
}
