package io.mirr.shynigsy.data

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Manual PIN requests use only an isolated loopback account and never retry an unknown outcome. */
class SpoilerPinRequestTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShyniClient
    private lateinit var address: String

    @Before fun setup() {
        SpoilerAccessRegistry.clearAll()
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        address = server.url("/").newBuilder().host("127.0.0.1").build().toString()
        client = ShyniClient(address, "fixture-account", deviceId = "fixture-device")
    }

    @After fun close() { SpoilerAccessRegistry.clearAll(); server.shutdown() }

    private fun successfulGrant() = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"token":"spc_fixture-manual","expires_in":1800}""")

    @Test fun neverRespondingManualUnlockTimesOutWithoutGrantOrRetry() { runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val failure = runCatching { client.unlockSpoiler("1234", timeoutMs = 1_000L) }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals(408, (failure as ApiException).status)
        assertTrue(failure.message.orEmpty().contains("지연"))
        assertFalse(failure.message.orEmpty().contains("1234"))
        assertFalse(client.spoilerUnlocked)
        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)?.method)
        assertEquals("fixture-account", client.token)
    } }

    @Test fun queuedUnlockExpiresAndCannotBeSentWhenTheControlSlotReopens() { runBlocking {
        val submitted = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                if (call.request().url.encodedPath == "/spoiler/client/unlock") submitted.countDown()
            }
        }).build()
        client = ShyniClient(address, "fixture-account", http, deviceId = "fixture-device")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val occupying = async(Dispatchers.Default) { client.approveTvPairing("n".repeat(43)) }
        try {
            assertEquals("/auth/tv-login/tickets", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            val pending = async(Dispatchers.Default) {
                runCatching { client.unlockSpoiler("1234", timeoutMs = 1_000L) }.exceptionOrNull()
            }
            try {
                assertTrue(submitted.await(5, TimeUnit.SECONDS))
                val failure = pending.await()
                assertTrue(failure is ApiException)
                assertEquals(408, (failure as ApiException).status)
                assertEquals("The queued PIN must not reach the server", 1, server.requestCount)
                assertFalse(client.spoilerUnlocked)
                occupying.cancelAndJoin()
                server.enqueue(successfulGrant())
                assertTrue(client.unlockSpoiler("5678").unlocked)
                val recovery = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/spoiler/client/unlock", recovery.path)
                assertEquals("5678", JSONObject(recovery.body.readUtf8()).getString("pin"))
                assertEquals("The expired PIN must stay cancelled after capacity returns", 2, server.requestCount)
            } finally { pending.cancelAndJoin() }
        } finally {
            occupying.cancelAndJoin()
        }
    } }

    @Test fun explicitCancellationRemainsCancellationWithoutGrantOrRetry() { runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.Default) { client.unlockSpoiler("1234") }
        try {
            assertEquals("/spoiler/client/unlock", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertFalse(client.spoilerUnlocked)
            assertEquals(1, server.requestCount)
        } finally { pending.cancelAndJoin() }
    } }

    @Test fun anOuterTimeoutIsNotRelabelledAsAManualPinFailure() { runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val failure = runCatching {
            withTimeout(1_000L) { client.unlockSpoiler("1234") }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertFalse(client.spoilerUnlocked)
        assertEquals(1, server.requestCount)
    } }

    @Test fun rejectedOrFailedUnlockPostsOnlyOnceAndKeepsTheGrantLocked() { runBlocking {
        for ((index, status) in listOf(403, 401, 429, 503).withIndex()) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"detail":"synthetic rejection"}"""))
            val failure = runCatching { client.unlockSpoiler("1234") }.exceptionOrNull()
            assertTrue(failure is ApiException)
            assertEquals(status, (failure as ApiException).status)
            assertFalse(client.spoilerUnlocked)
            assertEquals(index + 1, server.requestCount)
            assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)?.method)
        }
    } }

    @Test fun oversizedUnlockResponseCannotInstallEvenAnOtherwiseValidGrant() { runBlocking {
        server.enqueue(MockResponse().setBody(JSONObject().put("token", "spc_fixture-manual")
            .put("expires_in", 1800).put("padding", "x".repeat(40_000)).toString()))
        val failure = runCatching { client.unlockSpoiler("1234") }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertFalse(client.spoilerUnlocked)
        assertEquals(1, server.requestCount)
    } }

    @Test fun successfulManualUnlockKeepsExistingAccountDeviceAndMemoryOnlyGrantFlow() { runBlocking {
        server.enqueue(successfulGrant())
        assertTrue(client.unlockSpoiler(" 1234 ").unlocked)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/spoiler/client/unlock", request.path)
        assertEquals("Bearer fixture-account", request.getHeader("Authorization"))
        assertEquals("fixture-device", request.getHeader("X-Device-Id"))
        assertNull(request.getHeader("X-Shyni-Spoiler"))
        assertEquals("1234", JSONObject(request.body.readUtf8()).getString("pin"))
        assertEquals("fixture-account", client.token)
        assertTrue(ShyniClient(address, "fixture-account", deviceId = "fixture-device").spoilerUnlocked)
        assertFalse(ShyniClient(address, "other-account", deviceId = "fixture-device").spoilerUnlocked)
        assertEquals("spc_fixture-manual", client.headersFor(address).getValue("X-Shyni-Spoiler"))
        assertEquals(1, server.requestCount)
    } }
}
