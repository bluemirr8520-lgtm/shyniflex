package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexPlayerServerTest {
    private val spec = PlaybackSpec("Movie", "https://media.test/pms/library/parts/81/file.mkv",
        itemId = 7, libraryId = 3, partId = 81, sessionId = "watch")

    @Test fun onlyTheInMemoryPlaybackUrlGetsTheToken() {
        val client = PlexPlayerServer("https://media.test/pms", "sample-token", "test-player")
        assertEquals("sample-token", client.playbackUrl(spec).toHttpUrl().queryParameter("X-Plex-Token"))
        assertTrue(client.playbackHeaders(spec).isEmpty())
        assertFalse(spec.toJson().contains("sample-token"))
        assertThrows(ApiException::class.java) { client.playbackUrl(spec.copy(url = "https://media.test/other")) }
        assertThrows(ApiException::class.java) { client.playbackUrl(spec.copy(url = "https://other.test/pms/library")) }
    }

    @Test fun reportsPlexStopAndScrobblesOncePerPlayback() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(200)) }
            val client = PlexPlayerServer(server.url("/").toString(), "sample-token", "test-player")
            client.saveProgress(spec, 8000, 8000, "completed", 8000, true)
            val update = server.takeRequest()
            assertEquals("/:/timeline", update.requestUrl!!.encodedPath)
            assertEquals("stopped", update.requestUrl!!.queryParameter("state"))
            assertEquals("8000", update.requestUrl!!.queryParameter("time"))
            assertEquals("7", update.requestUrl!!.queryParameter("ratingKey"))
            client.markPlaybackWatched(spec)
            client.markPlaybackWatched(spec)
            assertEquals(2, server.requestCount)
            assertEquals("/:/scrobble", server.takeRequest().requestUrl!!.encodedPath)
            client.markPlaybackWatched(spec.copy(sessionId = "another-watch"))
            assertEquals(3, server.requestCount)
        }
    }
}
