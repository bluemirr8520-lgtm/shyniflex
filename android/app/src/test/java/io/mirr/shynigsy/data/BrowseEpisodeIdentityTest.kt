package io.mirr.shynigsy.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Optional browse decoration may fail; the authoritative authorized catalog must not be discarded. */
class BrowseEpisodeIdentityTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val identityMessage = "에피소드의 정체성을 확인할 수 없습니다."
    private val show = MediaItem(5, 9, "합성 시리즈", "show", summary = "카드 소개", poster = "https://example.invalid/poster",
        viewItemId = 50, showTitle = "카드 시리즈 이름", backdrop = "https://example.invalid/art")
    private val season = MediaItem(7, 9, "합성 시즌", "season", seasonNumber = 0, showId = 5, viewItemId = 70)

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = ShyniClient(server.url("/").toString(), "synthetic-account", deviceId = "synthetic-device")
    }

    @After fun close() { server.shutdown() }

    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body))

    private fun identity(status: Int = 404, message: String = identityMessage) = respond(JSONObject().put("detail", message).toString(), status)

    private suspend fun apiFailure(status: Int, block: suspend () -> Unit): ApiException {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ApiException($status), received $failure", failure is ApiException)
        return (failure as ApiException).also { assertEquals(status, it.status) }
    }

    @Test fun failedOptionalShowHeaderPreservesTheOriginalCardAndAuthorizedSeasonIdentity() = runBlocking {
        identity()
        respond("""[{"id":7,"index_":0,"title":"합성 스페셜","view_item_id":70},
            {"id":8,"season_number":1,"title":"합성 시즌 1","view_item_id":80}]""")
        val details = client.details(show)
        assertEquals("An unavailable header must not invent a different show or View identity", show, details.item)
        assertEquals(listOf(7, 8), details.children.map { it.id })
        assertEquals(listOf(70, 80), details.children.map { it.viewItemId })
        assertEquals(listOf(0, 1), details.children.map { it.seasonNumber })
        assertTrue(details.children.all { it.libraryId == 9 && it.showId == 5 && it.type == "season" })
        assertEquals("/libraries/9/shows/5", server.takeRequest().path)
        assertEquals("/libraries/9/shows/5/seasons?platform=mobile", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test fun recognizedIdentityFailureStatusesAndReportedWordingsAllowOnlyTheOptionalHeaderFallback() = runBlocking {
        for (status in listOf(400, 404, 409, 422, 500)) {
            for (message in listOf(identityMessage, "에피소의 정체성을 확인할 수 없습니다", "에피소드 정체성을 확인할 수 없습니다")) {
                identity(status, message)
                respond("[]")
                val details = client.details(show)
                assertEquals(show, details.item)
                assertTrue(details.children.isEmpty())
                assertEquals("/libraries/9/shows/5", server.takeRequest().path)
                assertEquals("/libraries/9/shows/5/seasons?platform=mobile", server.takeRequest().path)
            }
        }
        assertEquals(30, server.requestCount)
    }

    @Test fun missingNumericParentShowIdDoesNotGuessFromASeasonTitle() = runBlocking {
        val missing = season.copy(showId = null, title = "제목으로만 식별한 합성 시즌", displaySubtitle = "시리즈 이름만 있는 항목")
        apiFailure(0) { client.details(missing) }
        assertEquals("No metadata, title, GUID or path guessing request may be sent", 0, server.requestCount)
    }

    @Test fun authenticationAndAuthorizationFailuresNeverUseTheHeaderFallback() = runBlocking {
        for (status in listOf(401, 403)) {
            identity(status)
            apiFailure(status) { client.details(show) }
            assertEquals("/libraries/9/shows/5", server.takeRequest().path)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun rateLimitingWithTheSameMessageIsStillARealFailure() = runBlocking {
        identity(429)
        apiFailure(429) { client.details(show) }
        assertEquals(1, server.requestCount)
    }

    @Test fun unrelatedNotFoundAndSimilarButDifferentMessagesStillPropagate() = runBlocking {
        for (message in listOf("요청한 합성 시리즈가 없습니다.", "에피소드 정체성을 찾을 수 없습니다.")) {
            respond(JSONObject().put("detail", message).toString(), 404)
            apiFailure(404) { client.details(show) }
            server.takeRequest()
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun aHeaderTransportTimeoutIsNotMistakenForTheIdentityFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // If an incorrect fallback is attempted, let it finish so this test fails instead of hanging.
        respond("[]")
        val shortCallClient = ShyniClient(server.url("/").toString(), "synthetic-account",
            httpClient = OkHttpClient.Builder().callTimeout(500, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build(),
            deviceId = "synthetic-device")
        val failure = apiFailure(0) { withTimeout(5_000) { shortCallClient.details(show) } }
        assertTrue("The transport cause remains available to the caller", failure.cause is IOException)
        assertTrue("A timeout must not request the season catalog", server.requestCount <= 1)
    }

    @Test fun forbiddenSeasonsAfterAnOptionalHeaderIdentityFailureStillPropagate() = runBlocking {
        identity()
        identity(403)
        apiFailure(403) { client.details(show) }
        assertEquals(2, server.requestCount)
        assertEquals("/libraries/9/shows/5", server.takeRequest().path)
        assertEquals("/libraries/9/shows/5/seasons?platform=mobile", server.takeRequest().path)
    }

    @Test fun theAuthoritativeSeasonListCannotBeSwallowedEvenWithTheSameIdentityMessage() = runBlocking {
        identity()
        identity()
        apiFailure(404) { client.details(show) }
        assertEquals(2, server.requestCount)
    }

    @Test fun optionalProgressIdentityFailureRetainsAuthorizedEpisodesAndTheirBrowseFields() = runBlocking {
        respond("""[{"id":21,"title":"합성 회차 A","view_item_id":121,"season_number":0,"episode_number":1,
            "position_ms":12000,"completed":false,"duration_ms":60000,"summary":"회차 A 소개"},
            {"id":22,"title":"합성 회차 B","view_item_id":122,"season_number":0,"episode_number":2,
            "position_ms":59000,"completed":true,"duration_ms":60000}]""")
        identity()
        val details = client.details(season)
        assertEquals(season, details.item)
        assertEquals(listOf(21, 22), details.children.map { it.id })
        assertEquals(listOf(121, 122), details.children.map { it.viewItemId })
        assertEquals(12000L, details.children.first().resumeMs)
        assertFalse(details.children.first().watched)
        assertEquals("회차 A 소개", details.children.first().summary)
        assertTrue(details.children.last().watched)
        assertEquals(0L, details.children.last().resumeMs)
        assertTrue(details.children.all { it.showId == 5 && it.libraryId == 9 && it.type == "episode" })
        assertEquals("/libraries/9/shows/5/seasons/7/episodes?limit=100&offset=0", server.takeRequest().path)
        assertEquals("/playback/progress?item_ids=21,22&library_view_id=9", server.takeRequest().path)
    }

    @Test fun successfulMissingProgressStillClearsBrowseResumeAndWatchedState() = runBlocking {
        respond("""[{"id":21,"title":"합성 회차 A","position_ms":12000,"completed":false,"duration_ms":60000},
            {"id":22,"title":"합성 회차 B","position_ms":59000,"completed":true,"duration_ms":60000}]""")
        respond("{}")
        val episodes = client.details(season).children
        assertEquals(2, episodes.size)
        assertTrue(episodes.all { it.resumeMs == 0L && !it.watched && it.durationMs == 60000L })
    }

    @Test fun firstSuccessfulProgressChunkSurvivesASecondChunkIdentityFailure() = runBlocking {
        val firstPage = JSONArray((1..100).map { id -> JSONObject().put("id", id).put("title", "합성 회차 $id")
            .put("view_item_id", 1000 + id).put("duration_ms", 60000).put("position_ms", 3000).put("completed", id == 3) })
        respond(firstPage.toString())
        respond("""[{"id":101,"title":"합성 마지막 회차","view_item_id":1101,"position_ms":3210,
            "completed":false,"duration_ms":70000,"summary":"마지막 회차 소개"}]""")
        respond("""{"items":[{"metadata_item_id":1,"position_ms":16000,"completed":false},
            {"metadata_item_id":2,"position_ms":59000,"completed":true}]}""")
        identity()
        val episodes = client.details(season).children
        assertEquals(101, episodes.size)
        assertEquals(16000L, episodes[0].resumeMs)
        assertTrue(episodes[1].watched)
        assertEquals(0L, episodes[1].resumeMs)
        assertFalse("A successful missing record is authoritative", episodes[2].watched)
        assertEquals(0L, episodes[2].resumeMs)
        assertEquals(101, episodes.last().id)
        assertEquals(1101, episodes.last().viewItemId)
        assertEquals(3210L, episodes.last().resumeMs)
        assertFalse(episodes.last().watched)
        assertEquals(70000L, episodes.last().durationMs)
        assertEquals("마지막 회차 소개", episodes.last().summary)
        assertEquals("0", server.takeRequest().requestUrl!!.queryParameter("offset"))
        assertEquals("100", server.takeRequest().requestUrl!!.queryParameter("offset"))
        assertEquals((1..100).joinToString(","), server.takeRequest().requestUrl!!.queryParameter("item_ids"))
        assertEquals("101", server.takeRequest().requestUrl!!.queryParameter("item_ids"))
    }

    @Test fun authoritativeEpisodeListIdentityFailureStillPropagates() = runBlocking {
        identity()
        apiFailure(404) { client.details(season) }
        assertEquals("/libraries/9/shows/5/seasons/7/episodes?limit=100&offset=0", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test fun unrelatedProgressNotFoundStillPropagates() = runBlocking {
        respond("""[{"id":21,"title":"합성 회차"}]""")
        respond("""{"detail":"일반 진행률 오류"}""", 404)
        apiFailure(404) { client.details(season) }
        assertEquals(2, server.requestCount)
    }
}
