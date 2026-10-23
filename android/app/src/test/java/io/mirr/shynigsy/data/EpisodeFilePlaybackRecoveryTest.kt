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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EpisodeFilePlaybackRecoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val metadata = PlaybackMetadataDispatcher()
    private val episode = MediaItem(24, 9, "번호 없는 회차", "episode", viewItemId = 601,
        durationMs = 1_800_000, resumeMs = 65_000, playbackPartId = 90)
    private val identityError = """{"detail":"에피소드 정체성을 확인할 수 없습니다"}"""

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { dispatcher = metadata; start() }
        client = ShyniClient(server.url("/").toString(), "fixture-account", deviceId = "fixture-device")
    }

    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }

    private fun respond(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    private fun request(): RecordedRequest = server.takeRequest(10, TimeUnit.SECONDS)
        ?: throw AssertionError("Expected fixture HTTP request")
    private fun respondMetadata(path: String, body: String, status: Int = 200) =
        metadata.enqueueMetadata(path, MockResponse().setResponseCode(status).setBody(body))

    private fun recoveryResponses(part: Int = 90) {
        respond(identityError, 404)
        respond("""{"ticket":"fixture-exact-ticket","library_view_id":9,"view_item_id":602}""")
        respondMetadata("/subtitles/$part", """[{"id":4,"format":"srt","source":"external","playable":true}]""")
        respondMetadata("/playback/progress", identityError, 404)
    }

    @Test fun authorizedBrowseRowPartSurvivesContinueAndIntentWithoutBecomingAManualVersion() = runBlocking {
        respond("""{"items":[{"id":999,"metadata_item_id":24,"part_id":90,"library_view_id":9,
            "view_item_id":601,"type":"episode","title":"회차","position_ms":65000}]}""")
        val item = client.continueWatching().single()
        assertEquals(24, item.id)
        assertEquals(90, item.playbackPartId)
        assertNull(item.navigationPartId)
        assertEquals(item, MediaItem.fromJson(item.toJson()))
        request()
        respond("""{"selected":{"part_id":91},"library_view_id":9,"view_item_id":601,"playback_context_token":"normal-context"}""")
        respond("""{"ticket":"normal-ticket"}""")
        respondMetadata("/subtitles/91", "[]")
        respondMetadata("/playback/progress", "{}")
        val played = client.playback(item)
        assertEquals(91, played.partId)
        assertNull(played.requestedPartId)
        val body = JSONObject(request().body.readUtf8())
        assertEquals(24, body.getInt("metadata_item_id"))
        assertFalse(body.has("part_id"))
        repeat(3) { request() }
    }

    @Test fun hintsRejectInvalidIdsAndContradictoryRowContext() {
        val base = """{"id":24,"view_item_id":601,"part_id":90}"""
        assertEquals(90, client.parseMedia(JSONObject(base), 9, "episode").playbackPartId)
        for (invalid in listOf<Any>(0, -1, 90.5, 4_294_967_386L, "invalid", JSONObject.NULL)) {
            assertNull(client.parseMedia(JSONObject(base).put("part_id", invalid), 9, "episode").playbackPartId)
        }
        for ((key, value) in listOf("library_view_id" to 8, "metadata_item_id" to 25,
            "view_item_id" to 0, "view_item_id" to 601.5)) {
            assertNull(client.parseMedia(JSONObject(base).put(key, value), 9, "episode").playbackPartId)
        }
    }

    @Test fun oldHintIsRetainedOnlyForTheExactMetadataTypeViewAndLibrary() {
        val old = episode
        assertEquals(90, client.parseMedia(JSONObject("""{"id":24}"""), 9, "episode", old).playbackPartId)
        assertNull(client.parseMedia(JSONObject("""{"id":25}"""), 9, "episode", old).playbackPartId)
        assertNull(client.parseMedia(JSONObject("""{"id":24,"type":"movie"}"""), 9, "episode", old).playbackPartId)
        assertNull(client.parseMedia(JSONObject("""{"id":24,"view_item_id":602}"""), 9, "episode", old).playbackPartId)
        assertNull(client.parseMedia(JSONObject("""{"id":24}"""), 8, "episode", old).playbackPartId)
    }

    @Test fun episodeEnrichmentCannotAttachAnotherPhysicalEpisodesPartToTheOriginalId() = runBlocking {
        respond("""{"id":5,"title":"시리즈"}""")
        respond("""[{"id":7,"season_number":0}]""")
        respond("""[{"id":999,"title":"다른 소스의 같은 회차","season_number":0,"episode_number":4,
            "view_item_id":777,"part_id":91}]""")
        respond("{}")
        val details = client.details(episode.copy(showId = 5, seasonNumber = 0, episodeNumber = 4))
        assertEquals(24, details.item.id)
        assertEquals(601, details.item.viewItemId)
        assertEquals(90, details.item.playbackPartId)
        assertEquals(4, server.requestCount)
    }

    @Test fun exactIdentityFailureRecoversOnceThroughTheBoundViewTicketAndKeepsSourceClock() = runBlocking {
        recoveryResponses()
        val result = client.playback(episode)
        assertEquals(90, result.partId)
        assertEquals(24, result.itemId)
        assertEquals(9, result.libraryId)
        assertEquals(602, result.viewItemId)
        assertEquals(65_000L, result.resumeMs)
        assertEquals(1_800_000L, result.durationMs)
        assertEquals("direct_play", result.decision)
        assertNull(result.progressToken)
        assertEquals(90, result.sourceItem!!.playbackPartId)
        assertTrue(result.url.endsWith("/stream/90?ticket=fixture-exact-ticket"))
        request()
        val ticket = request()
        assertEquals("/stream-ticket/90", ticket.requestUrl!!.encodedPath)
        assertEquals("9", ticket.requestUrl!!.queryParameter("library_view_id"))
        assertEquals("601", ticket.requestUrl!!.queryParameter("view_item_id"))
        assertNull(ticket.requestUrl!!.queryParameter("library_section_id"))
        assertNull(ticket.requestUrl!!.queryParameter("playback_context_token"))
        val enrichment = List(2) { request() }
        val subtitles = enrichment.single { it.requestUrl!!.encodedPath == "/subtitles/90" }
        assertEquals("602", subtitles.requestUrl!!.queryParameter("view_item_id"))
        assertEquals("9", subtitles.requestUrl!!.queryParameter("library_view_id"))
        assertNull(subtitles.requestUrl!!.queryParameter("playback_context_token"))
        assertEquals("/playback/progress", enrichment.single { it != subtitles }.requestUrl!!.encodedPath)
        val subtitleUrl = result.subtitles.single().url.toHttpUrl()
        assertEquals("fixture-exact-ticket", subtitleUrl.queryParameter("ticket"))
        assertEquals("602", subtitleUrl.queryParameter("view_item_id"))
        assertEquals(4, server.requestCount)
    }

    @Test fun explicitVersionAndVerifiedNavigationTakePriorityOverTheBrowseHint() = runBlocking {
        for ((manual, expected) in listOf(92 to 92, null to 91)) {
            recoveryResponses(expected)
            val result = client.playback(episode.copy(navigationPartId = 91), manual)
            assertEquals(expected, result.partId)
            assertEquals(expected, result.requestedPartId)
            val decision = JSONObject(request().body.readUtf8())
            assertEquals(expected, decision.getInt("part_id"))
            assertEquals("/stream-ticket/$expected", request().requestUrl!!.encodedPath)
            repeat(2) { request() }
        }
    }

    @Test fun decisionAccessDenialsAndMissingKnownPartNeverAttemptRecovery() = runBlocking {
        for (status in listOf(401, 403)) {
            respond(identityError, status)
            val caught = runCatching { client.playback(episode) }.exceptionOrNull() as ApiException
            assertEquals(status, caught.status)
            assertTrue(caught.message.orEmpty().contains("재생 방식 확인"))
            assertEquals("/playback/decision", request().requestUrl!!.encodedPath)
        }
        for (item in listOf(episode.copy(playbackPartId = null), episode.copy(type = "movie"))) {
            respond(identityError, 404)
            assertEquals(404, (runCatching { client.playback(item) }.exceptionOrNull() as ApiException).status)
            request()
        }
        assertEquals(4, server.requestCount)
        val missingContext = runCatching { client.playback(episode.copy(viewItemId = null)) }.exceptionOrNull() as ApiException
        assertTrue(missingContext.message.orEmpty().contains("재생 방식 확인"))
        assertEquals(4, server.requestCount)
    }

    @Test fun recoveryTicketStillEnforcesAuthenticationAndPolicyWithoutAnyFurtherRequest() = runBlocking {
        for (status in listOf(401, 403)) {
            respond(identityError, 404)
            respond("""{"detail":"이 파일은 재생할 수 없습니다"}""", status)
            val caught = runCatching { client.playback(episode) }.exceptionOrNull() as ApiException
            assertEquals(status, caught.status)
            assertTrue(caught.message.orEmpty().contains("파일 재생 권한 복구"))
            assertEquals(status, (caught.cause as ApiException).status)
            repeat(2) { request() }
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun recoveryRejectsMissingMismatchedOrMalformedBoundContextBeforeSubtitles() = runBlocking {
        val invalid = listOf(
            """{"ticket":"t","view_item_id":602}""",
            """{"ticket":"t","library_view_id":8,"view_item_id":602}""",
            """{"ticket":"t","library_view_id":9,"view_item_id":0}""",
            """{"ticket":"t","library_view_id":9,"view_item_id":602.5}""",
            """{"ticket":"t","library_view_id":9,"view_item_id":602,"part_id":91}""",
        )
        for (response in invalid) {
            respond(identityError, 404)
            respond(response)
            val caught = runCatching { client.playback(episode) }.exceptionOrNull() as ApiException
            assertEquals(403, caught.status)
            assertTrue(caught.message.orEmpty().contains("파일 재생 권한 복구"))
            repeat(2) { request() }
        }
        assertEquals(invalid.size * 2, server.requestCount)
    }

    @Test fun privateRecoveryRetainsTheGrantOnEveryRequestAndNeverSerializesIt() = runBlocking {
        respond("""{"token":"spc_fixture-grant","expires_in":1800}""")
        client.unlockSpoiler("1234")
        request()
        recoveryResponses()
        val result = client.playback(episode.copy(isSpoiler = true))
        assertTrue(result.sourceItem!!.isSpoiler)
        repeat(4) { assertEquals("spc_fixture-grant", request().getHeader("X-Shyni-Spoiler")) }
        assertFalse(result.toJson().contains("spc_fixture-grant"))
        client.clearSpoilerGrant()
        assertEquals(403, (runCatching { client.playbackHeaders(result) }.exceptionOrNull() as ApiException).status)
        assertEquals(5, server.requestCount)
    }

    @Test fun conversionAfterRecoveryStaysOnTheAuthorizedPartAndBoundView() = runBlocking {
        recoveryResponses()
        val source = client.playback(episode)
        repeat(4) { request() }
        respond("""{"ticket":"tc"}""")
        respond("""{"ticket":"sub","library_view_id":9,"view_item_id":602}""")
        respondMetadata("/subtitles/90", "[]")
        val converted = client.convertPlayback(source, 720, 70_000)
        assertEquals(90, converted.partId)
        assertEquals(602, converted.viewItemId)
        assertNull(converted.progressToken)
        assertEquals(720, converted.conversionHeight)
        assertEquals("70000", converted.url.toHttpUrl().queryParameter("start_ms"))
        repeat(3) {
            val sent = request()
            assertEquals("9", sent.requestUrl!!.queryParameter("library_view_id"))
            assertEquals("602", sent.requestUrl!!.queryParameter("view_item_id"))
            assertNull(sent.requestUrl!!.queryParameter("playback_context_token"))
        }
    }

    @Test fun cancellationDuringRecoveryTicketDoesNotReturnAStreamOrContinuePreparation() = runBlocking {
        withTimeout(30_000) {
            respond(identityError, 404)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = CompletableDeferred<Unit>()
            var returned = false
            val preparing = launch {
                try { client.playback(episode); returned = true }
                catch (cancel: CancellationException) { cancelled.complete(Unit); throw cancel }
            }
            try {
                val sent = withContext(Dispatchers.IO) { listOf(request(), request()) }
                assertEquals("/stream-ticket/90", sent.last().requestUrl!!.encodedPath)
                preparing.cancelAndJoin()
                cancelled.await()
                assertTrue(preparing.isCancelled)
                assertFalse(returned)
                assertEquals(2, server.requestCount)
            } finally { preparing.cancelAndJoin() }
        }
    }
}
