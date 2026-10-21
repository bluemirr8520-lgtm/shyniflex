package io.mirr.shynigsy.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackCompletionEligibilityTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val spec = PlaybackSpec("작품", "https://server/stream/90", itemId = 23,
        libraryId = 8, partId = 90, viewItemId = 501, sessionId = "fixture-session",
        sourceItem = MediaItem(23, 8, "작품", "movie", viewItemId = 501))

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { start() }
        client = ShyniClient(server.url("/").toString(), "fixture-account", deviceId = "fixture-device")
    }
    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }

    @Test fun defaultAndExplicitNullPreserveTheExistingWireContract() {
        val old = client.progressBody(spec, 97_000, 100_000, "stopped", 2_000)
        val explicitNull = client.progressBody(spec, 97_000, 100_000, "stopped", 2_000, null)
        assertFalse(old.has("completion_eligible"))
        assertFalse(explicitNull.has("completion_eligible"))
        assertEquals(old.toString(), explicitNull.toString())
    }

    @Test fun ineligibleSeekStillSavesFullPositionAndActualWatchedTime() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        client.saveProgress(spec, 97_000, 100_000, "stopped", 2_000, completionEligible = false)
        val sent = server.takeRequest(10, TimeUnit.SECONDS) ?: throw AssertionError("Missing progress request")
        assertEquals("/playback/progress", sent.requestUrl!!.encodedPath)
        assertEquals("Bearer fixture-account", sent.getHeader("Authorization"))
        val body = JSONObject(sent.body.readUtf8())
        assertFalse(body.getBoolean("completion_eligible"))
        assertEquals(97_000L, body.getLong("position_ms"))
        assertEquals(2_000L, body.getLong("watched_ms"))
        assertEquals("fixture-session", body.getString("client_session_id"))
        assertEquals(90, body.getInt("part_id"))
        assertEquals(8, body.getInt("library_view_id"))
        assertEquals(501, body.getInt("view_item_id"))
    }

    @Test fun directSeekToEndIsStillIneligibleEvenWithCompletionCandidateState() {
        val body = client.progressBody(spec, 100_000, 100_000, "completed", 2_000, false)
        assertEquals("completed_candidate", body.getString("state"))
        assertFalse(body.getBoolean("completion_eligible"))
        assertEquals(100_000L, body.getLong("position_ms"))
        assertTrue(client.progressBody(spec, 100_000, 100_000, "completed", 80_000, true)
            .getBoolean("completion_eligible"))
    }

    @Test fun eligibilityFlagDoesNotWeakenPrivateGrantChecks() = runBlocking {
        val privatePlayback = spec.copy(sourceItem = spec.sourceItem!!.copy(isSpoiler = true))
        assertEquals(403, (runCatching {
            client.saveProgress(privatePlayback, 97_000, 100_000, "stopped", 2_000, false)
        }.exceptionOrNull() as ApiException).status)
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"token":"spc_fixture-grant","expires_in":300}"""))
        client.unlockSpoiler("1234")
        server.takeRequest(10, TimeUnit.SECONDS)
        server.enqueue(MockResponse().setBody("{}"))
        client.saveProgress(privatePlayback, 97_000, 100_000, "stopped", 2_000, false)
        val sent = server.takeRequest(10, TimeUnit.SECONDS) ?: throw AssertionError("Missing private progress request")
        assertEquals("spc_fixture-grant", sent.getHeader("X-Shyni-Spoiler"))
        assertEquals("fixture-device", sent.getHeader("X-Device-Id"))
        assertFalse(JSONObject(sent.body.readUtf8()).getBoolean("completion_eligible"))
    }
}
