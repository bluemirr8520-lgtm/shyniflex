package io.mirr.shynigsy.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class PlexResolutionChangeTest {
    @Test fun requestedHeightUsesSelectedMediaVersionAndKeepsPlaybackIdentity() = runBlocking {
        MockWebServer().use { pms ->
            val decisions = CopyOnWriteArrayList<RecordedRequest>()
            val rejectConversion = AtomicBoolean(false)
            pms.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.requestUrl!!.encodedPath) {
                        "/plex/library/metadata/7" -> MockResponse().setBody(metadata)
                        "/plex/video/:/transcode/universal/decision" -> {
                            decisions.add(request)
                            if (rejectConversion.get()) MockResponse().setBody(
                                """{"MediaContainer":{"generalDecisionCode":4000,"transcodeDecisionCode":4000,"generalDecisionText":"Conversion disabled"}}""")
                            else MockResponse().setBody(
                                """{"MediaContainer":{"generalDecisionCode":1001,"transcodeDecisionCode":1001}}""")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            pms.start()
            val server = pms.url("/plex").toString()
            val token = "fixture-resolution-token"
            val client = PlexPlayerServer(server, token, "fixture-client")
            val original = PlexClient(server, token, "fixture-client").playback(
                MediaItem(7, 3, "Fixture", "movie"), selectedPartId = 82)
            var current = original
            val conversions = mutableSetOf<String>()
            for ((height, width) in listOf(1080 to 1920, 720 to 1280, 480 to 854)) {
                current = client.convertPlayback(current, height, 90_500, 2, if (current.isServerConversion) null else 3)
                val start = client.playbackUrl(current).toHttpUrl()
                val decisionRequest = decisions.last()
                assertEquals("GET", decisionRequest.method)
                assertEquals(token, decisionRequest.getHeader("X-Plex-Token"))
                assertEquals("/plex/video/:/transcode/universal/start.mkv", start.encodedPath)
                val expected = mapOf("path" to "/library/metadata/7", "mediaIndex" to "1", "partIndex" to "0",
                    "directPlay" to "0", "directStream" to "0", "videoResolution" to "${width}x$height",
                    "audioStreamID" to "902", "subtitleStreamID" to "903")
                for (url in listOf(start, decisionRequest.requestUrl!!)) {
                    expected.forEach { (key, value) -> assertEquals(key, value, url.queryParameter(key)) }
                    assertTrue("Positive video bitrate", url.queryParameter("maxVideoBitrate")!!.toInt() > 0)
                }
                assertEquals(90.5, start.queryParameter("offset")!!.toDouble(), 0.001)
                assertTrue(conversions.add(requireNotNull(start.queryParameter("session"))))
                assertEquals(start.queryParameter("session"), decisionRequest.requestUrl!!.queryParameter("session"))
                assertEquals(token, start.queryParameter("X-Plex-Token"))
                assertFalse(current.toJson().contains(token))
                assertTrue(current.isServerConversion)
                assertEquals(height, current.conversionHeight)
                assertEquals(original.sessionId, current.sessionId)
                assertEquals(82, current.partId)
                assertEquals(90_500L, current.resumeMs)
            }

            rejectConversion.set(true)
            assertTrue("PMS refusal must not replace the current stream",
                runCatching { client.convertPlayback(current, 720, 91_000, 2) }.isFailure)
            val restored = client.convertPlayback(current, null, 95_000, 2)
            assertFalse(restored.isServerConversion)
            assertEquals(original.url, restored.url)
            assertEquals(original.sessionId, restored.sessionId)
            assertEquals(95_000L, restored.resumeMs)
            assertEquals(2, restored.selectedAudioStreamIndex)
        }
    }

    private val metadata = """{"MediaContainer":{"Metadata":[{
        "ratingKey":"7","librarySectionID":"3","type":"movie","title":"Fixture","duration":180000,
        "Media":[{"container":"mp4","Part":[{"id":81,"key":"/library/parts/81/first.mp4"}]},
          {"container":"mkv","Part":[{"id":82,"key":"/library/parts/82/second.mkv","Stream":[
            {"streamType":2,"index":1,"id":901,"codec":"aac","selected":1},
            {"streamType":2,"index":2,"id":902,"codec":"aac"},
            {"streamType":3,"index":3,"id":903,"codec":"srt"}
          ]}]}]
    }]}}"""
}
