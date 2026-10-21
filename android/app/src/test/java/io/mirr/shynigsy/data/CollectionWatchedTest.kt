package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CollectionWatchedTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private var episodes = "[]"
    private var failId: Int? = null
    private val writes = CopyOnWriteArrayList<Pair<Int, JSONObject>>()
    private val series = MediaItem(5, 9, "시리즈", "show", viewItemId = 601)

    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/libraries/9/shows/5/episodes" -> MockResponse().setBody(episodes)
                        path == "/playback/versions" -> {
                            val id = JSONObject(request.body.readUtf8()).getInt("metadata_item_id")
                            MockResponse().setBody("""{"options":[{"metadata_item_id":$id,"part_id":${id + 100}}]}""")
                        }
                        path.matches(Regex("/continue-watching/[0-9]+/watch-state")) -> {
                            val id = path.split('/')[2].toInt()
                            val data = JSONObject(request.body.readUtf8())
                            writes += id to data
                            if (id == failId) MockResponse().setResponseCode(403).setBody("""{"detail":"금지"}""")
                            else MockResponse().setBody("""{"ok":true,"watched":${data.getBoolean("watched")}}""")
                        }
                        else -> MockResponse().setResponseCode(404).setBody("{}")
                    }
                }
            }
            start()
        }
        client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
    }
    @After fun close() { server.shutdown() }
    private fun episode(id: Int, season: Int = 1, withView: Boolean = true) =
        """{"id":$id,"title":"회차$id","season_number":$season,"episode_number":$id,"position_ms":1234${if (withView) ",\"view_item_id\":601" else ""}}"""

    @Test fun collectionUsesAuthorizedPerEpisodeWritesAndReturnsOnlyFullySavedState() = runBlocking {
        episodes = listOf(episode(21), episode(22)).joinToString(prefix = "[", postfix = "]")
        val result = client.setCollectionWatched(series, true)
        assertEquals(listOf(21, 22), result.map { it.id })
        assertTrue(result.all { it.watched && it.resumeMs == 0L && it.showId == 5 })
        assertEquals(setOf(21, 22), writes.map { it.first }.toSet())
        writes.forEach { (id, data) ->
            assertEquals(id + 100, data.getInt("part_id"))
            assertEquals(9, data.getInt("library_view_id"))
            assertEquals(601, data.getInt("view_item_id"))
            assertTrue(data.getBoolean("watched"))
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun specialsSeasonZeroDoesNotMarkOtherSeasons() = runBlocking {
        episodes = listOf(episode(21, 0), episode(22, 1)).joinToString(prefix = "[", postfix = "]")
        val result = client.setCollectionWatched(MediaItem(77, 9, "스페셜", "season", showId = 5,
            seasonNumber = 0, viewItemId = 601), true)
        assertEquals(listOf(21), result.map { it.id })
        assertEquals(listOf(21), writes.map { it.first })
        assertEquals(3, server.requestCount)
    }

    @Test fun overFiveHundredEpisodesAreRejectedBeforeAnyStateWrite() = runBlocking {
        episodes = (1..501).joinToString(prefix = "[", postfix = "]") { episode(it) }
        val failure = runCatching { client.setCollectionWatched(series, true) }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertTrue(failure!!.message!!.contains("500"))
        assertTrue(writes.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun missingAuthorizedViewInOneEpisodeRejectsCollectionBeforeWrites() = runBlocking {
        episodes = listOf(episode(21), episode(22, withView = false)).joinToString(prefix = "[", postfix = "]")
        val failure = runCatching { client.setCollectionWatched(series.copy(viewItemId = null), true) }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertTrue(writes.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun partialFailureReportsSavedCountRatherThanPretendingWholeShowIsWatched() = runBlocking {
        episodes = listOf(episode(21), episode(22)).joinToString(prefix = "[", postfix = "]")
        failId = 22
        val failure = runCatching { client.setCollectionWatched(series, true) }.exceptionOrNull() as ApiException
        assertEquals(403, failure.status)
        assertTrue(failure.message!!.contains("2회차 중 1회차"))
        assertEquals(2, writes.size)
    }

    @Test fun markUnwatchedUsesSameCheckedPerEpisodeEndpoint() = runBlocking {
        episodes = "[${episode(21)}]"
        val result = client.setCollectionWatched(series, false)
        assertFalse(result.single().watched)
        assertEquals(0L, result.single().resumeMs)
        assertFalse(writes.single().second.getBoolean("watched"))
    }
}
