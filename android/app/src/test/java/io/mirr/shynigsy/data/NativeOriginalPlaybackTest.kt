package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class NativeOriginalPlaybackTest {
    private val item = MediaItem(24, 9, "Synthetic original", "movie", viewItemId = 601,
        resumeMs = 65_000, durationMs = 200_000)

    @Test fun wmvRemuxAndAudioCompatibilityDecisionsUseAnAuthorizedOriginal() {
        for (mode in listOf("remux", "audio")) verify(mode, "wmv", "vc1", "wmapro", native = true)
    }

    @Test fun aviAndTransportStreamsAvoidBrowserOnlyRemuxing() {
        verify("remux", "avi", "mpeg4", "mp3", native = true)
        verify("remux", "mpegts", "h264", "aac", native = true)
    }

    @Test fun explicitVideoConversionAndUnknownOrModernSourcesArePreserved() {
        verify("video", "wmv", "vc1", "wmapro", native = false)
        verify("audio", "mp4", "hevc", "eac3", native = false)
        verify("remux", null, null, null, native = false)
    }

    @Test fun originalTicketAccessDenialDoesNotFallBackToAnotherPartOrTransport() = runBlocking {
        MockWebServer().use { server ->
            val metadata = PlaybackMetadataDispatcher()
            metadata.enqueueMetadata("/playback/progress", MockResponse().setBody("[]"))
            server.dispatcher = metadata
            server.enqueue(MockResponse().setBody(decision("remux", "wmv", "vc1", "wmapro")))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"denied"}"""))
            server.start()
            val client = ShyniClient(server.url("/").toString(), "fixture-account")
            val error = runCatching { client.playback(item, deferSubtitles = true) }.exceptionOrNull()
            assertTrue(error is ApiException)
            assertEquals(403, (error as ApiException).status)
            val paths = (0 until server.requestCount).mapNotNull { server.takeRequest(1, TimeUnit.SECONDS)?.requestUrl?.encodedPath }
            assertTrue(paths.contains("/stream-ticket/90"))
            assertFalse(paths.any { it.startsWith("/playback/transcode-ticket") })
        }
    }

    private fun verify(mode: String, container: String?, video: String?, audio: String?, native: Boolean) = runBlocking {
        MockWebServer().use { server ->
            val metadata = PlaybackMetadataDispatcher()
            metadata.enqueueMetadata("/playback/progress", MockResponse().setBody("[]"))
            server.dispatcher = metadata
            server.enqueue(MockResponse().setBody(decision(mode, container, video, audio)))
            server.enqueue(MockResponse().setBody("""{"ticket":"fixture-media-ticket"}"""))
            server.start()
            val result = ShyniClient(server.url("/").toString(), "fixture-account").playback(item, deferSubtitles = true)
            assertEquals(90, result.partId)
            assertEquals(9, result.libraryId)
            assertEquals(601, result.viewItemId)
            assertEquals(65_000L, result.resumeMs)
            assertEquals(container, result.sourceContainer)
            assertEquals(video, result.sourceVideoCodec)
            assertEquals(7, result.selectedAudioStreamIndex)
            assertEquals(if (native) 2 else 0, result.selectedAudioStreamOrdinal)
            assertEquals(!native, result.isServerConversion)
            assertEquals(if (native) "direct_play" else "${mode}_transcode", result.decision)
            val paths = (0 until server.requestCount).mapNotNull { server.takeRequest(1, TimeUnit.SECONDS)?.requestUrl?.encodedPath }
            assertTrue(paths.contains(if (native) "/stream-ticket/90" else "/playback/transcode-ticket/90"))
            if (native) assertFalse(paths.any { it.startsWith("/playback/transcode-ticket") })
        }
    }

    private fun decision(mode: String, container: String?, video: String?, audio: String?) = JSONObject().apply {
        put("selected", JSONObject().put("part_id", 90).put("container", container).put("video_codec", video).put("audio_codec", audio))
        put("library_view_id", 9); put("view_item_id", 601)
        put("playback_context_token", "fixture-context"); put("decision", "${mode}_transcode")
        put("selected_audio_stream_index", 7); put("selected_audio_stream_ordinal", 2)
        put("transcode", JSONObject().put("mode", mode).apply { if (mode == "video") put("max_height", 720) })
    }.toString()
}
