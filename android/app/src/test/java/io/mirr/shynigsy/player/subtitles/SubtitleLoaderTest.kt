package io.mirr.shynigsy.player.subtitles

import io.mirr.shynigsy.data.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubtitleLoaderTest {
    private lateinit var server: MockWebServer

    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun close() { server.shutdown() }

    private val srt = "1\r\n00:00:01,200 --> 00:00:02,500\r\n안녕하세요\r\n\r\n"
    private val vtt = "WEBVTT\n\n00:03.000 --> 00:04.500\n대체 자막\n"
    private fun track(path: String = "/raw.srt", fallback: String? = null) = SubtitleTrack(
        id = 7,
        label = "한국어",
        url = server.url(path).toString(),
        mimeType = "application/x-subrip",
        fallbackUrl = fallback,
        fallbackMimeType = fallback?.let { "text/vtt" },
    )

    @Test fun resolvesCurrentAuthorizationWhenDownloadStarts() = runBlocking {
        var token = "previous-token"
        val requestedUrls = mutableListOf<String>()
        val loader = SubtitleLoader(headersFor = { url ->
            requestedUrls += url
            mapOf("Authorization" to "Bearer $token", "X-Device-Id" to "device-123")
        })
        val subtitle = track().copy(headers = mapOf(
            "Authorization" to "Bearer stored-token", "X-Subtitle-Variant" to "original",
        ))
        token = "refreshed-token"
        server.enqueue(MockResponse().setBody(srt))

        assertEquals(listOf(ManualSubtitleCue(1_200, 2_500, "안녕하세요")), loader.load(subtitle))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer refreshed-token", request.getHeader("Authorization"))
        assertEquals("device-123", request.getHeader("X-Device-Id"))
        assertEquals("original", request.getHeader("X-Subtitle-Variant"))
        assertEquals(listOf(subtitle.url), requestedUrls)
    }

    @Test fun parsesOriginalSrtWithoutRequiringServerVttConversion() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/x-subrip")
            .setBody(srt + "2\r\n00:00:03,000 --> 00:00:04,000\r\n둘째 줄\r\n"))

        val cues = SubtitleLoader().load(track())

        assertEquals(listOf(
            ManualSubtitleCue(1_200, 2_500, "안녕하세요"),
            ManualSubtitleCue(3_000, 4_000, "둘째 줄"),
        ), cues)
        assertEquals("/raw.srt", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        assertEquals(1, server.requestCount)
    }

    @Test fun usesVttFallbackWhenOriginalEndpointFails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/vtt").setBody(vtt))
        val requestedUrls = mutableListOf<String>()
        val loader = SubtitleLoader(headersFor = { url ->
            requestedUrls += url
            mapOf("Authorization" to "Bearer current-token")
        })
        val subtitle = track(fallback = server.url("/converted.vtt").toString())

        assertEquals(listOf(ManualSubtitleCue(3_000, 4_500, "대체 자막")), loader.load(subtitle))
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        val second = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/raw.srt", first.path)
        assertEquals("/converted.vtt", second.path)
        assertEquals("Bearer current-token", first.getHeader("Authorization"))
        assertEquals("Bearer current-token", second.getHeader("Authorization"))
        assertEquals(listOf(subtitle.url, subtitle.fallbackUrl), requestedUrls)
    }

    @Test fun usesFallbackWhenSuccessfulResponseIsLoginHtml() = runBlocking {
        server.enqueue(MockResponse().setBody("<html><body>로그인이 필요합니다.</body></html>"))
        server.enqueue(MockResponse().setBody(vtt))

        val cues = SubtitleLoader().load(track(fallback = server.url("/converted.vtt").toString()))

        assertEquals(listOf(ManualSubtitleCue(3_000, 4_500, "대체 자막")), cues)
        assertEquals(2, server.requestCount)
    }

    @Test fun rejectsEmptyAndMalformedSubtitleResponses() = runBlocking {
        for (body in listOf("", "WEBVTT\n\nno timing data\n", "<html>로그인</html>")) {
            server.enqueue(MockResponse().setBody(body))
            val failure = runCatching { SubtitleLoader().load(track()) }.exceptionOrNull()

            assertTrue("Invalid text must not be accepted as an empty subtitle track", failure is SubtitleLoadException)
            failure as SubtitleLoadException
            assertTrue(failure.empty)
            assertNull(failure.status)
        }
    }

    @Test fun preservesHttpFailureStatusForUsefulUserError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val failure = runCatching { SubtitleLoader().load(track()) }.exceptionOrNull() as SubtitleLoadException

        assertEquals(403, failure.status)
        assertFalse(failure.empty)
    }

    @Test fun doesNotForwardAuthenticationAcrossRedirects() = runBlocking {
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(MockResponse().setBody(srt))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/capture")))
            val loader = SubtitleLoader(headersFor = { mapOf("Authorization" to "Bearer private-token") })

            val failure = runCatching { loader.load(track()) }.exceptionOrNull() as SubtitleLoadException

            assertEquals(307, failure.status)
            assertEquals("Bearer private-token", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun cancellationStopsPendingRequestAndDoesNotTryFallback() = runBlocking {
        val callFailed = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) { callFailed.countDown() }
        }).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val loader = SubtitleLoader(http)

        withTimeout(10_000) {
            val pending = async(Dispatchers.Default) {
                loader.load(track(fallback = server.url("/converted.vtt").toString()))
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue("Cancelling subtitle selection must close its HTTP call", callFailed.await(5, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rejectsOversizedResponseBeforeReadingBody() = runBlocking {
        server.enqueue(MockResponse().setBody(srt).setHeader("Content-Length", 8L * 1024 * 1024 + 1))

        val failure = runCatching { SubtitleLoader().load(track()) }.exceptionOrNull() as SubtitleLoadException

        assertFalse(failure.empty)
        assertNull(failure.status)
        assertEquals(1, server.requestCount)
    }
}
