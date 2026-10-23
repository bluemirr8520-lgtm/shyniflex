package io.mirr.shynigsy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AacConversionContextTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private val metadata = PlaybackMetadataDispatcher()

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { dispatcher = metadata; start() }
        client = ShyniClient(server.url("/").toString(), "account", deviceId = "device")
    }
    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }
    private fun respond(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body))
    private fun request() = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Synthetic request did not arrive")
    private fun current(privateItem: Boolean = false, codec: String? = "eac3") = PlaybackSpec(
        "합성 작품", server.url("/stream/90?ticket=old-ticket").toString(), itemId = 23, libraryId = 8,
        partId = 90, viewItemId = 501, progressToken = "old-context", sessionId = "old-session",
        durationMs = 120_000, selectedAudioStreamIndex = 2, selectedAudioLanguage = "kor", selectedAudioCodec = codec,
        sourceItem = if (privateItem) MediaItem(23, 8, "합성 작품", "movie", viewItemId = 501, isSpoiler = true) else null,
        selectedSubtitleId = 4, markers = listOf(PlaybackMarker("intro", 1_000, 9_000)), navigationQuery = "합성 검색")
    private fun decision(context: String = "aac-context") = JSONObject("""{
        "decision":"video_transcode","selected":{"part_id":90,"audio_codec":"eac3"},
        "library_view_id":8,"view_item_id":501,"selected_audio_stream_index":2,"selected_audio_language":"ko",
        "playback_context_token":"$context","transcode":{"mode":"video","max_height":720},
        "playback_info":{"playback_method":"video_transcode","is_transcoding":true,
            "audio":{"source_codec":"eac3","output_codec":"aac"}}} """)
    private fun delivery(context: String = "aac-context") {
        respond("""{"ticket":"video-ticket"}""")
        respond("""{"ticket":"subtitle-ticket","library_view_id":8,"view_item_id":501}""")
        metadata.enqueueMetadata("/subtitles/90", MockResponse().setBody("""[
            {"id":4,"source":"embedded","format":"ass","lang_code":"ko","stream_index":5,"playable":true}]"""))
    }
    private suspend fun unlock() {
        respond("""{"token":"spc_initial","expires_in":1800}""")
        client.unlockSpoiler("1234"); request()
    }

    @Test fun knownEac3ConversionNegotiatesExactSourceAndKeepsOriginalSelectionAndTimeline() = runBlocking {
        respond(decision().toString()); delivery()
        val original = current()
        val converted = client.convertPlayback(original, 720, 42_000)
        val selection = JSONObject(request().body.readUtf8())
        assertEquals(90, selection.getInt("part_id")); assertFalse(selection.has("metadata_item_id"))
        assertEquals(8, selection.getInt("library_view_id")); assertEquals(501, selection.getInt("view_item_id"))
        assertEquals(720, selection.getInt("max_video_height"))
        val profile = selection.getJSONObject("client_profile")
        assertEquals(listOf("aac"), profile.getJSONArray("audio_codecs").let { (0 until it.length()).map(it::getString) })
        assertEquals("h264", profile.getJSONArray("video_codecs").getJSONObject(0).getString("codec"))
        assertFalse(profile.getJSONObject("hdr").getBoolean("dolby_vision"))
        assertEquals(selection.getString("client_session_id"), converted.sessionId)
        for (stage in listOf("/playback/transcode-ticket/90", "/stream-ticket/90", "/subtitles/90")) {
            val sent = request()
            assertEquals(stage, sent.requestUrl!!.encodedPath)
            assertEquals("aac-context", sent.requestUrl!!.queryParameter("playback_context_token"))
            assertFalse(sent.path!!.contains("old-context"))
        }
        assertEquals("aac-context", converted.progressToken); assertTrue(converted.aacConversionContext)
        assertEquals("eac3", converted.selectedAudioCodec)
        assertEquals(2, converted.selectedAudioStreamIndex); assertEquals("ko", converted.selectedAudioLanguage)
        assertEquals("2", converted.url.toHttpUrl().queryParameter("audio_stream_index"))
        assertEquals("720", converted.url.toHttpUrl().queryParameter("max_height"))
        assertEquals(42_000L, converted.resumeMs); assertEquals(42_000L, converted.streamStartPositionMs)
        assertEquals(original.durationMs, converted.durationMs); assertEquals(original.markers, converted.markers)
        assertEquals(4, converted.selectedSubtitleId); assertEquals(original.navigationQuery, converted.navigationQuery)
        assertEquals("subtitle-ticket", converted.subtitles.single().url.toHttpUrl().queryParameter("ticket"))
        assertEquals("external", converted.subtitles.single().source)
        assertEquals("old-context", original.progressToken); assertFalse(original.aacConversionContext)
        assertEquals(converted, PlaybackSpec.fromJson(converted.toJson()))
    }

    @Test fun aVerifiedContextSurvivesSeekAndActivityRoundtripWithoutAnotherDecision() = runBlocking {
        val converted = current().copy(url = server.url("/transcode/90?mode=video&max_height=720").toString(),
            conversionHeight = 720, progressToken = "aac-context", aacConversionContext = true)
        delivery()
        val refreshed = client.renewPlayback(PlaybackSpec.fromJson(converted.toJson()), 65_000)
        assertEquals("/playback/transcode-ticket/90", request().requestUrl!!.encodedPath)
        repeat(2) { request() }
        assertEquals(3, server.requestCount); assertEquals("aac-context", refreshed.progressToken)
        assertTrue(refreshed.aacConversionContext); assertEquals(65_000L, refreshed.streamStartPositionMs)
    }

    @Test fun returningToOriginalDoesNotNegotiateAnOutputProfile() = runBlocking {
        val converted = current().copy(conversionHeight = 720, progressToken = "aac-context", aacConversionContext = true)
        respond("""{"ticket":"original"}""")
        metadata.enqueueMetadata("/subtitles/90", MockResponse().setBody("[]"))
        val original = client.convertPlayback(converted, null, 50_000)
        assertEquals("/stream-ticket/90", request().requestUrl!!.encodedPath); request()
        assertEquals(2, server.requestCount); assertFalse(original.aacConversionContext)
        assertEquals("/stream/90", original.url.toHttpUrl().encodedPath); assertEquals(0L, original.streamStartPositionMs)
    }

    @Test fun unknownAndNonEac3CodecsRetainTheExistingConversionContract() = runBlocking {
        for (codec in listOf(null, "aac", "dts", "ac3")) {
            delivery()
            val converted = client.convertPlayback(current(codec = codec), 720, 2_000)
            assertEquals("/playback/transcode-ticket/90", request().requestUrl!!.encodedPath)
            request(); request()
            assertFalse(converted.aacConversionContext); assertEquals(codec, converted.selectedAudioCodec)
        }
    }

    @Test fun aliasesIdentifyOnlyEac3AndOlderIntentsStayUnnegotiated() {
        for (codec in listOf("EAC3", "ec-3", "e-ac-3", "ddp", "audio/eac3", "audio/eac3-joc")) assertTrue(isEac3Codec(codec))
        for (codec in listOf(null, "aac", "ac3", "audio/ac3", "dts")) assertFalse(isEac3Codec(codec))
        val older = JSONObject(current().toJson()).apply { remove("selectedAudioCodec"); remove("aacConversionContext") }
        val restored = PlaybackSpec.fromJson(older.toString())
        assertNull(restored.selectedAudioCodec); assertFalse(restored.aacConversionContext)
    }

    @Test fun aMissingOldOrAudioOnlyProofCannotStartTheVideoStream() = runBlocking {
        val failures: List<(JSONObject) -> Unit> = listOf(
            { it.remove("playback_info") },
            { it.put("decision", "audio_transcode") },
            { it.getJSONObject("transcode").put("mode", "audio") },
            { it.getJSONObject("playback_info").put("is_transcoding", "true") },
            { it.getJSONObject("playback_info").getJSONObject("audio").put("output_codec", "eac3") },
            { it.put("playback_context_token", "old-context") },
        )
        for ((index, change) in failures.withIndex()) {
            respond(decision().apply(change).toString())
            assertTrue(runCatching { client.convertPlayback(current(), 720, 0) }.exceptionOrNull() is ApiException)
            assertEquals("/playback/decision", request().path)
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun anotherPartViewOrViewItemIsRejectedBeforeAnyTicket() = runBlocking {
        for ((index, change) in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("selected").put("part_id", 91) },
            { it.put("library_view_id", 9) }, { it.put("view_item_id", 502) },
        ).withIndex()) {
            respond(decision().apply(change).toString())
            assertEquals(403, (runCatching { client.convertPlayback(current(), 720, 0) }.exceptionOrNull() as ApiException).status)
            request(); assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun anotherAudioTrackLanguageOrAnUnspecifiedIndexIsNeverSilentlyAccepted() = runBlocking {
        for ((index, change) in listOf<(JSONObject) -> Unit>(
            { it.put("selected_audio_stream_index", 3) }, { it.put("selected_audio_language", "en") },
            { it.remove("selected_audio_stream_index") },
            { it.put("selected_audio_stream_index", 2.5) },
        ).withIndex()) {
            respond(decision().apply(change).toString())
            assertTrue(runCatching { client.convertPlayback(current(), 720, 0) }.exceptionOrNull() is ApiException)
            request(); assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun unknownCurrentIndexUsesTheExactIndexAuthorizedByTheDecision() = runBlocking {
        respond(decision().toString()); delivery()
        val result = client.convertPlayback(current().copy(selectedAudioStreamIndex = null), 720, 0)
        assertEquals(2, result.selectedAudioStreamIndex)
        assertEquals("2", result.url.toHttpUrl().queryParameter("audio_stream_index"))
        repeat(4) { request() }
    }

    @Test fun changingAnAudioIndexDoesNotReuseThePreviouslyVerifiedContext() = runBlocking {
        respond(decision().toString())
        val verified = current().copy(progressToken = "aac-context", aacConversionContext = true, conversionHeight = 720)
        assertTrue(runCatching { client.renewPlayback(verified, 0, 3) }.exceptionOrNull() is ApiException)
        assertEquals("/playback/decision", request().path); assertEquals(1, server.requestCount)
    }

    @Test fun aDeniedDecisionPreservesTheServerFailureAndDoesNotFallBackToOriginal() = runBlocking {
        respond("""{"detail":"합성 변환 권한 없음"}""", 403)
        val failure = runCatching { client.convertPlayback(current(), 720, 0) }.exceptionOrNull() as ApiException
        assertEquals(403, failure.status); assertEquals("합성 변환 권한 없음", failure.message)
        assertEquals("/playback/decision", request().path); assertEquals(1, server.requestCount)
    }

    @Test fun cancelingPendingNegotiationDoesNotIssueAnyTicket() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.Default) { client.convertPlayback(current(), 720, 0) }
        assertEquals("/playback/decision", request().path)
        pending.cancelAndJoin(); assertTrue(pending.isCancelled); assertEquals(1, server.requestCount)
    }

    @Test fun aLateNegotiationCannotCrossAccountLockOrAReplacementGrant() = runBlocking {
        for (action in listOf("account", "lock", "new-grant")) {
            client.token = "account"; unlock()
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.encodedPath == "/spoiler/client/unlock")
                        return MockResponse().setBody("""{"token":"spc_replacement","expires_in":1800}""")
                    check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody(decision().toString())
                }
            }
            try {
                val pending = async(Dispatchers.Default) { runCatching { client.convertPlayback(current(true), 720, 0) }.exceptionOrNull() }
                val sent = request(); assertEquals("/playback/decision", sent.path)
                assertEquals("spc_initial", sent.getHeader("X-Shyni-Spoiler"))
                when (action) {
                    "account" -> client.token = "other-account"
                    "lock" -> client.clearSpoilerGrant()
                    else -> { client.unlockSpoiler("5678"); request() }
                }
                release.countDown()
                assertEquals(if (action == "account") 401 else 403, (pending.await() as ApiException).status)
                if (action == "new-grant") assertTrue(client.spoilerUnlocked)
            } finally {
                release.countDown(); server.dispatcher = metadata
            }
        }
    }

    @Test fun automaticPrivateReauthenticationRenegotiatesAacWithTheRotatedGrant() = runBlocking {
        unlock()
        val previous = current(true).copy(url = server.url("/transcode/90?mode=video&max_height=720").toString(),
            conversionHeight = 720, aacConversionContext = true)
        respond("""{"token":"spc_rotated","expires_in":1800}""")
        respond(decision("reauth-aac-context").toString()); delivery()
        val result = client.reauthenticateSpoilerPlayback(previous, 45_000)
        val sent = List(5) { request() }
        val selections = sent.filter { it.requestUrl!!.encodedPath == "/playback/decision" }
        assertEquals(1, selections.size)
        val selection = JSONObject(selections.single().body.readUtf8())
        assertTrue(selection.has("client_profile"))
        assertEquals(90, selection.getInt("part_id"))
        assertEquals(8, selection.getInt("library_view_id"))
        assertEquals(501, selection.getInt("view_item_id"))
        assertEquals(selection.getString("client_session_id"), result.sessionId)
        assertEquals(1, sent.count { it.requestUrl!!.encodedPath == "/stream-ticket/90" })
        assertEquals(1, sent.count { it.requestUrl!!.encodedPath == "/subtitles/90" })
        assertFalse(sent.any { it.requestUrl!!.encodedPath == "/playback/progress" })
        sent.drop(1).forEach { assertEquals("spc_rotated", it.getHeader("X-Shyni-Spoiler")) }
        sent.drop(2).forEach { assertEquals("reauth-aac-context", it.requestUrl!!.queryParameter("playback_context_token")) }
        assertEquals("reauth-aac-context", result.progressToken); assertTrue(result.aacConversionContext)
        assertEquals("eac3", result.selectedAudioCodec); assertEquals(2, result.selectedAudioStreamIndex)
        assertEquals(45_000L, result.streamStartPositionMs); assertEquals(720, result.conversionHeight)
        assertFalse(result.toJson().contains("old-context")); assertFalse(result.toJson().contains("spc_"))
    }
}
