package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class EpisodeSeriesLookupTest {
    private val episode = MediaItem(42, 8, "episode", "episode", viewItemId = 51)

    @Test fun missingShowUsesOnlyAUniqueMatchingView() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = ShyniClient(server.url("/").toString(), "account")
            server.enqueue(MockResponse().setBody("""[{"id":19,"view_item_id":51,"type":"show","title":"right"},{"id":20,"view_item_id":52,"type":"show","title":"other"}]"""))
            val found = client.episodeSeries(episode)
            assertEquals(19, found?.id)
            assertEquals(51, found?.viewItemId)
            assertEquals("/libraries/8/shows", server.takeRequest().requestUrl!!.encodedPath)
            server.enqueue(MockResponse().setBody("""[{"id":19,"view_item_id":51,"type":"show"},{"id":20,"view_item_id":51,"type":"show"}]"""))
            assertNull(client.episodeSeries(episode))
        } finally { server.shutdown() }
    }

    @Test fun excludesPrivateAndUnidentifiableRowsFromPublicLookup() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = ShyniClient(server.url("/").toString(), "account")
            server.enqueue(MockResponse().setBody("""[{"id":19,"view_item_id":51,"type":"show","spoiler_enabled":true},{"id":20,"type":"show"}]"""))
            assertNull(client.episodeSeries(episode))
            assertNull(client.episodeSeries(episode.copy(viewItemId = null)))
        } finally { server.shutdown() }
    }
}
