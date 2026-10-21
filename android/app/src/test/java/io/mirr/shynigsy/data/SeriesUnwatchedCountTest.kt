package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SeriesUnwatchedCountTest {
    private val show = MediaItem(5, 3, "시리즈", "show", viewItemId = 77)

    @Test fun countsAllPagesAndUniqueEpisodesWithAuthoritativeCompletion() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            fun reply(value: Any) { server.enqueue(MockResponse().setBody(value.toString())) }
            reply(JSONArray().put(JSONObject().put("id", 9)))
            reply(JSONArray((1..100).map { JSONObject().put("id", it) }))
            reply(JSONArray().put(JSONObject().put("id", 101)).put(JSONObject().put("id", 1)))
            reply(JSONObject().apply { for (id in 1..40) put(id.toString(), JSONObject().put("completed", true))
                put("41", JSONObject().put("position_ms", 99_000).put("percent", 99)) })
            reply(JSONObject().put("101", JSONObject().put("completed", true)))
            assertEquals(60, client.unwatchedEpisodeCount(show))
            assertEquals(60, client.unwatchedEpisodeCount(show))
            assertEquals("Cached counts must avoid extra lookups", 5, server.requestCount)
            val requests = List(5) { server.takeRequest() }
            assertEquals("100", requests[2].requestUrl!!.queryParameter("offset"))
            requests.takeLast(2).forEach { assertEquals("3", it.requestUrl!!.queryParameter("library_view_id")) }
        }
    }

    @Test fun failedHistoryAndMalformedCatalogRemainUnknown() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            server.enqueue(MockResponse().setBody("[{\"id\":9}]"))
            server.enqueue(MockResponse().setBody("[{\"id\":1}]"))
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"detail\":\"unavailable\"}"))
            assertNull(client.unwatchedEpisodeCount(show))
            server.enqueue(MockResponse().setBody("{\"unexpected\":true}"))
            assertNull(client.unwatchedEpisodeCount(show))
        }
    }

    @Test fun refreshInvalidatesCountsAndLockedPrivateItemsMakeNoRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture")
            assertNull(client.unwatchedEpisodeCount(show.copy(isSpoiler = true)))
            assertEquals(0, server.requestCount)
            repeat(2) { server.enqueue(MockResponse().setBody("[]")) }
            assertEquals(0, client.unwatchedEpisodeCount(show))
            client.invalidateSeriesCounts()
            assertEquals(0, client.unwatchedEpisodeCount(show))
            assertEquals(2, server.requestCount)
        }
    }
}
