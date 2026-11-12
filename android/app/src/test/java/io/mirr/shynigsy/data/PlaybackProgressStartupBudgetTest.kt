package io.mirr.shynigsy.data

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** A hung optional history response must cancel its socket, not hold a valid media ticket. */
class PlaybackProgressStartupBudgetTest {
    private lateinit var server: MockWebServer
    private val item = MediaItem(24, 9, "합성 재생 준비", "movie", durationMs = 180_000,
        resumeMs = 65_000, viewItemId = 601)
    private val progressPath = "/playback/progress"

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun client(http: OkHttpClient = OkHttpClient()) = ShyniClient(
        server.url("/").newBuilder().host("127.0.0.1").build().toString(),
        "fixture-account", http, "fixture-device")

    private fun dispatch(progress: () -> MockResponse,
        ticket: () -> MockResponse = { json("""{"ticket":"authorized-ticket"}""") }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/playback/decision" -> json("""{"selected":{"part_id":90,"duration_ms":180000},
                    "library_view_id":9,"view_item_id":601,"playback_context_token":"fixture-context"}""")
                "/stream-ticket/90" -> ticket()
                progressPath -> progress()
                else -> json("{}", 404)
            }
        }
    }

    @Test fun unavailableProgressCancelsTheHttpCallAndKeepsTheCardsResumePoint() = runBlocking {
        withTimeout(10_000) {
            val arrived = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    if (call.request().url.encodedPath == progressPath && call.isCanceled()) cancelled.countDown()
                }
            }).build()
            dispatch(progress = {
                arrived.countDown()
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            })
            val started = System.nanoTime()
            val result = client(http).playback(item, deferSubtitles = true)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("The real progress endpoint must have been reached", arrived.await(1, TimeUnit.SECONDS))
            assertTrue("Optional history must return well before the normal 45-second read timeout", elapsedMs < 6_000)
            assertTrue("The timed-out HTTP request must be cancelled, not left running", cancelled.await(2, TimeUnit.SECONDS))
            assertEquals(65_000L, result.resumeMs)
            assertEquals(65_000L, result.sourceItem!!.resumeMs)
            assertFalse(result.sourceItem!!.watched)
            assertEquals(180_000L, result.durationMs)
            assertTrue(result.url.endsWith("/stream/90?ticket=authorized-ticket"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun fastAuthoritativeProgressStillOverridesTheCardBeforePlaybackStarts() = runBlocking {
        dispatch(progress = { json("""{"24":{"position_ms":95000,"duration_ms":180000,"completed":false}}""") })
        val result = client().playback(item, deferSubtitles = true)
        assertEquals(95_000L, result.resumeMs)
        assertEquals(95_000L, result.sourceItem!!.resumeMs)
        dispatch(progress = { json("""{"24":{"position_ms":179000,"completed":true}}""") })
        assertEquals("A freshly completed item must start at the beginning", 0L,
            client().playback(item, deferSubtitles = true).resumeMs)
    }

    @Test fun temporaryServerAndReadFailuresKeepACompletedCardCompleted() = runBlocking {
        for (response in listOf(
            json("""{"detail":"synthetic busy server"}""", 503),
            json("""{"detail":"synthetic rate limit"}""", 429),
            json("x".repeat(64 * 1024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )) {
            dispatch(progress = { response })
            val result = client().playback(item.copy(watched = true), deferSubtitles = true)
            assertEquals(0L, result.resumeMs)
            assertTrue(result.sourceItem!!.watched)
            assertEquals(0L, result.sourceItem!!.resumeMs)
            assertTrue(result.url.endsWith("/stream/90?ticket=authorized-ticket"))
        }
    }

    @Test fun receivedAuthorizationDenialsCannotBeHiddenByASlowErrorBody() = runBlocking {
        withTimeout(10_000) {
            for (status in listOf(401, 403)) {
                dispatch(progress = { json("""{"detail":"delayed authorization denial"}""", status)
                    .setBodyDelay(3, TimeUnit.SECONDS) })
                val failure = runCatching { client().playback(item, deferSubtitles = true) }.exceptionOrNull()
                assertTrue("An authorization response must not become a valid playback result", failure is ApiException)
                assertEquals(status, (failure as ApiException).status)
                assertTrue(failure.message.orEmpty().contains("이어보기 위치 조회"))
            }
        }
    }

    @Test fun changedAccountDuringAnOptionalTimeoutStillRejectsPlayback() = runBlocking {
        withTimeout(10_000) {
            val arrived = CountDownLatch(1)
            dispatch(progress = { arrived.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) })
            val active = client()
            val pending = async(Dispatchers.Default) { runCatching { active.playback(item, deferSubtitles = true) } }
            try {
                assertTrue(arrived.await(5, TimeUnit.SECONDS))
                active.token = "other-account"
                val failure = pending.await().exceptionOrNull()
                assertTrue(failure is ApiException)
                assertEquals(401, (failure as ApiException).status)
            } finally { pending.cancelAndJoin() }
        }
    }

    @Test fun optionalProgressFailureDoesNotBypassTheMandatoryTicket() = runBlocking {
        withTimeout(10_000) {
            val ticketArrived = CountDownLatch(1)
            val progressArrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            dispatch(progress = {
                progressArrived.countDown(); json("{}", 503)
            }, ticket = {
                ticketArrived.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) json("{}", 500)
                else json("""{"detail":"synthetic ticket denial"}""", 403)
            })
            val pending = async(Dispatchers.Default) { runCatching { client().playback(item, deferSubtitles = true) } }
            try {
                assertTrue(ticketArrived.await(5, TimeUnit.SECONDS))
                assertTrue(progressArrived.await(5, TimeUnit.SECONDS))
                assertFalse("The original ticket must remain mandatory", pending.isCompleted)
                release.countDown()
                val failure = pending.await().exceptionOrNull()
                assertTrue(failure is ApiException)
                assertEquals(403, (failure as ApiException).status)
                assertTrue(failure.message.orEmpty().contains("재생 티켓 발급"))
            } finally { release.countDown(); pending.cancelAndJoin() }
        }
    }
}
