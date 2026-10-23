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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackPreparationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val metadata = PlaybackMetadataDispatcher()
    private val item = MediaItem(24, 9, "테스트 회차", "episode", durationMs = 1_800_000,
        seasonNumber = 0, episodeNumber = 4, viewItemId = 601, resumeMs = 65_000)
    private val identityMessage = "에피소드 정체성을 확인할 수 없습니다"
    private val decision = """{"selected":{"part_id":90},"library_view_id":9,"view_item_id":602,
        "playback_context_token":"fixture-context","decision":"direct_play"}"""

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { dispatcher = metadata; start() }
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

    private fun respondError(status: Int, message: String = identityMessage) =
        respond(JSONObject().put("detail", message).toString(), status)

    private fun respondMetadata(path: String, body: String, status: Int = 200) =
        metadata.enqueueMetadata(path, MockResponse().setResponseCode(status).setBody(body))

    private fun progressError(status: Int, message: String = identityMessage) =
        respondMetadata("/playback/progress", JSONObject().put("detail", message).toString(), status)

    private fun authorizedBeforeProgress() {
        respond(decision)
        respond("""{"ticket":"fixture-media-ticket"}""")
        respondMetadata("/subtitles/90", "[]")
    }

    private fun request(): RecordedRequest = server.takeRequest(10, TimeUnit.SECONDS)
        ?: throw AssertionError("Expected fixture HTTP request")

    private fun failure(error: Throwable?, stage: String, status: Int, originalMessage: String) {
        assertTrue(error is ApiException)
        val api = error as ApiException
        assertEquals(status, api.status)
        assertTrue(api.message.orEmpty().contains(stage))
        assertTrue(api.message.orEmpty().contains(originalMessage))
        val cause = api.cause
        assertTrue("The stage must retain the original API failure", cause is ApiException)
        assertEquals(status, (cause as ApiException).status)
        assertEquals(originalMessage, cause.message)
        assertFalse(api.message.orEmpty().contains("fixture-context"))
        assertFalse(api.message.orEmpty().contains("fixture-media-ticket"))
        assertFalse(api.message.orEmpty().contains(server.url("/").toString()))
    }

    @Test fun authorizedPlaybackSurvivesUnknownProgressIdentityUsingTheOriginalCardState() = runBlocking {
        authorizedBeforeProgress()
        progressError(404)
        val result = client.playback(item)
        assertEquals(24, result.itemId)
        assertEquals(90, result.partId)
        assertEquals(9, result.libraryId)
        assertEquals(602, result.viewItemId)
        assertEquals("fixture-context", result.progressToken)
        assertEquals(65_000L, result.resumeMs)
        assertEquals(1_800_000L, result.durationMs)
        assertEquals(65_000L, result.sourceItem!!.resumeMs)
        assertFalse(result.sourceItem!!.watched)
        assertNull(result.requestedPartId)
        assertTrue(result.url.endsWith("/stream/90?ticket=fixture-media-ticket"))
        assertEquals(listOf("/playback/decision", "/stream-ticket/90"),
            List(2) { request().requestUrl!!.encodedPath })
        assertEquals(setOf("/subtitles/90", "/playback/progress"),
            List(2) { request().requestUrl!!.encodedPath }.toSet())
    }

    @Test fun aWatchedCardStillStartsFromTheBeginningWhenProgressIdentityIsUnknown() = runBlocking {
        authorizedBeforeProgress()
        progressError(409)
        val result = client.playback(item.copy(watched = true))
        assertEquals(0L, result.resumeMs)
        assertEquals(0L, result.sourceItem!!.resumeMs)
        assertTrue(result.sourceItem!!.watched)
        assertEquals(4, server.requestCount)
    }

    @Test fun decisionIdentityFailureCannotBypassPolicyOrObtainATicket() = runBlocking {
        respondError(404)
        val caught = runCatching { client.playback(item) }.exceptionOrNull()
        failure(caught, "재생 방식 확인", 404, identityMessage)
        assertEquals("/playback/decision", request().requestUrl!!.encodedPath)
        assertEquals(1, server.requestCount)
    }

    @Test fun ticketAccessDenialKeepsItsStatusAndStopsPreparation() = runBlocking {
        respond(decision)
        respondError(403, "재생 권한이 없습니다.")
        val caught = runCatching { client.playback(item) }.exceptionOrNull()
        failure(caught, "재생 티켓 발급", 403, "재생 권한이 없습니다.")
        assertEquals(2, server.requestCount)
    }

    @Test fun subtitleIdentityFailureIsReportedInsteadOfSilentlyDroppingSubtitles() = runBlocking {
        respond(decision)
        respond("""{"ticket":"fixture-media-ticket"}""")
        respondMetadata("/subtitles/90", JSONObject().put("detail", identityMessage).toString(), 404)
        respondMetadata("/playback/progress", "{}")
        val caught = runCatching { client.playback(item) }.exceptionOrNull()
        failure(caught, "자막 정보 조회", 404, identityMessage)
        assertTrue(server.requestCount in 3..4)
    }

    @Test fun progressAccessDenialsAndUnrelatedFailuresRemainFatalWithTheirOriginalCause() = runBlocking {
        val errors = listOf(401 to identityMessage, 403 to identityMessage,
            404 to "시청 기록 경로를 찾을 수 없습니다.", 503 to "서버가 일시적으로 응답하지 않습니다.")
        for ((status, message) in errors) {
            respond(decision)
            respond("""{"ticket":"fixture-media-ticket"}""")
            metadata.enqueueTogether("/subtitles/90", MockResponse().setBody("[]"),
                "/playback/progress", MockResponse().setResponseCode(status)
                    .setBody(JSONObject().put("detail", message).toString()))
            val caught = runCatching { client.playback(item) }.exceptionOrNull()
            failure(caught, "이어보기 위치 조회", status, message)
            repeat(4) { request() }
        }
        assertEquals(16, server.requestCount)
    }

    @Test fun conversionAndSubtitleTicketsStaySeparateWhenProgressIdentityFails() = runBlocking {
        respond("""{"selected":{"part_id":90},"library_view_id":9,"view_item_id":602,
            "playback_context_token":"fixture-context","decision":"video_transcode",
            "selected_audio_stream_index":2,"transcode":{"mode":"video","max_height":720,"audio_stream_index":2}}""")
        respond("""{"ticket":"fixture-transcode-ticket"}""")
        respond("""{"ticket":"fixture-original-subtitle-ticket"}""")
        respondMetadata("/subtitles/90", """[{"id":4,"source":"embedded","format":"ass","lang_code":"ko","playable":true,"stream_index":3}]""")
        progressError(422)
        val result = client.playback(item)
        assertTrue(result.isServerConversion)
        assertEquals(720, result.conversionHeight)
        assertEquals("video_transcode", result.decision)
        assertEquals(2, result.selectedAudioStreamIndex)
        assertEquals(0, result.selectedAudioStreamOrdinal)
        assertEquals(65_000L, result.resumeMs)
        assertEquals("fixture-transcode-ticket", result.url.toHttpUrl().queryParameter("ticket"))
        val subtitle = result.subtitles.single()
        assertEquals("external", subtitle.source)
        assertEquals("fixture-original-subtitle-ticket", subtitle.url.toHttpUrl().queryParameter("ticket"))
        assertEquals("fixture-context", subtitle.url.toHttpUrl().queryParameter("playback_context_token"))
        assertEquals(listOf("/playback/decision", "/playback/transcode-ticket/90"),
            List(2) { request().requestUrl!!.encodedPath })
        val enrichment = List(3) { request().requestUrl!!.encodedPath }
        assertEquals(setOf("/stream-ticket/90", "/subtitles/90", "/playback/progress"), enrichment.toSet())
        assertTrue(enrichment.indexOf("/stream-ticket/90") < enrichment.indexOf("/subtitles/90"))
    }

    @Test fun privatePreparationNeverDowngradesItsGrantForOptionalProgressFallback() = runBlocking {
        respond("""{"token":"spc_fixture-grant","expires_in":1800}""")
        client.unlockSpoiler("1234")
        request()
        authorizedBeforeProgress()
        progressError(404)
        val result = client.playback(item.copy(isSpoiler = true))
        assertTrue(result.sourceItem!!.isSpoiler)
        assertEquals(65_000L, result.resumeMs)
        repeat(4) {
            val sent = request()
            assertEquals("spc_fixture-grant", sent.getHeader("X-Shyni-Spoiler"))
            assertEquals("Bearer fixture-account", sent.getHeader("Authorization"))
        }
        assertTrue(result.headers.isEmpty())
        assertFalse(result.toJson().contains("spc_fixture-grant"))
        assertEquals(5, server.requestCount)
    }

    @Test fun cancellationWhileProgressIsPendingIsNotWrappedOrTurnedIntoAPlaybackResult() = runBlocking {
        withTimeout(30_000) {
            val httpCancelled = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    if (call.request().url.encodedPath == "/playback/progress") httpCancelled.complete(Unit)
                }
            }).build()
            val cancellable = ShyniClient(server.url("/").toString(), "fixture-account",
                httpClient = http, deviceId = "fixture-device")
            authorizedBeforeProgress()
            metadata.enqueueMetadata("/playback/progress", MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            var resultReturned = false
            val preparing = launch {
                try {
                    cancellable.playback(item)
                    resultReturned = true
                } catch (cancel: CancellationException) {
                    cancellationObserved.complete(Unit)
                    throw cancel
                }
            }
            try {
                val observed = withContext(Dispatchers.IO) { List(4) { request() } }
                assertEquals(setOf("/subtitles/90", "/playback/progress"),
                    observed.drop(2).map { it.requestUrl!!.encodedPath }.toSet())
                preparing.cancelAndJoin()
                cancellationObserved.await()
                httpCancelled.await()
                assertTrue(preparing.isCancelled)
                assertFalse(resultReturned)
            } finally {
                preparing.cancelAndJoin()
            }
        }
    }
}
