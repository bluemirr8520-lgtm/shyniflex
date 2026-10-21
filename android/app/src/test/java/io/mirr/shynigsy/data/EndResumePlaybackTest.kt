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

class EndResumePlaybackTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = ShyniClient(server.url("/").toString(), "fixture-account", deviceId = "fixture-device")
    }

    @After fun close() { server.shutdown() }

    private suspend fun prepare(positionMs: Long, durationMs: Long): PlaybackSpec {
        server.enqueue(MockResponse().setBody("""{"selected":{"part_id":90,"duration_ms":$durationMs},
            "library_view_id":9,"view_item_id":601,"playback_context_token":"fixture-context"}"""))
        server.enqueue(MockResponse().setBody("""{"ticket":"fixture-ticket"}"""))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("""{"24":{"position_ms":$positionMs,"duration_ms":$durationMs,"completed":false}}"""))
        val result = client.playback(MediaItem(24, 9, "끝 위치 회귀 검사", "episode", viewItemId = 601,
            resumeMs = 1_000, durationMs = durationMs))
        val requests = List(4) {
            server.takeRequest(10, TimeUnit.SECONDS) ?: throw AssertionError("Missing preparation request")
        }
        assertEquals(listOf("/playback/decision", "/stream-ticket/90", "/subtitles/90", "/playback/progress"),
            requests.map { it.requestUrl!!.encodedPath })
        assertEquals(listOf("POST", "POST", "GET", "GET"), requests.map { it.method })
        assertEquals("24", requests.last().requestUrl!!.queryParameter("item_ids"))
        assertEquals("9", requests.last().requestUrl!!.queryParameter("library_view_id"))
        assertEquals(24, JSONObject(requests.first().body.readUtf8()).getInt("metadata_item_id"))
        assertEquals(90, result.partId)
        assertEquals(601, result.viewItemId)
        assertFalse(result.sourceItem!!.watched)
        return result
    }

    @Test fun exactOrPastEndResumeRestartsOnlyTheOpenedPlaybackWithoutChangingHistory() = runBlocking {
        for (position in listOf(100_000L, 100_001L)) {
            val result = prepare(position, 100_000)
            assertEquals(0L, result.resumeMs)
            assertEquals(0L, result.sourceItem!!.resumeMs)
            assertEquals(100_000L, result.durationMs)
        }
        assertEquals("Preparing playback must not write/reset saved progress", 8, server.requestCount)
    }

    @Test fun justBeforeEndAndUnknownDurationKeepTheirReportedResumePositions() = runBlocking {
        for ((position, duration) in listOf(99_999L to 100_000L, 120_000L to 0L)) {
            val result = prepare(position, duration)
            assertEquals(position, result.resumeMs)
            assertEquals(position, result.sourceItem!!.resumeMs)
            assertEquals(duration, result.durationMs)
        }
        assertEquals(8, server.requestCount)
    }
}
