package io.mirr.shynigsy.data

import okhttp3.Protocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PlaybackPresenceTest {
    private val spec = PlaybackSpec("episode", "https://example.test/video", itemId = 7, libraryId = 8,
        partId = 9, sessionId = "play-session", progressToken = "context", viewItemId = 10, decision = "direct_play")
    private val request = Request.Builder().url("https://example.test/ws/playback").build()

    private class FakeSocket(private val req: Request) : WebSocket {
        val messages = mutableListOf<String>()
        var wasClosed = false
        var wasCancelled = false
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages += text; return !wasClosed }
        override fun send(bytes: ByteString): Boolean = false
        override fun close(code: Int, reason: String?): Boolean { wasClosed = true; return true }
        override fun cancel() { wasCancelled = true }
    }
    private class FakeFactory : WebSocket.Factory {
        val sockets = mutableListOf<FakeSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeSocket(request).also { sockets += it; listeners += listener }
        fun open(index: Int = sockets.lastIndex) = listeners[index].onOpen(sockets[index],
            Response.Builder().request(sockets[index].request()).protocol(Protocol.HTTP_1_1).code(101).message("OK").build())
    }

    @Test fun sendsExactIdentityAndOrdersStopBeforeClose() {
        val factory = FakeFactory()
        val presence = PlaybackPresence(factory, request, spec, "device", "samsung SM-S918N", "mobile", { true })
        presence.update(1234, 5000, "playing")
        factory.open()
        val first = JSONObject(factory.sockets[0].messages.single())
        assertEquals("progress", first.getString("type"))
        assertEquals("samsung SM-S918N", first.getString("device_name"))
        assertEquals("device", first.getString("device_id"))
        assertEquals("play-session", first.getString("client_session_id"))
        assertEquals("context", first.getString("playback_context_token"))
        assertEquals(9, first.getInt("part_id")); assertEquals(8, first.getInt("library_view_id"))
        assertEquals(10, first.getInt("view_item_id")); assertEquals(1234L, first.getLong("position_ms"))
        presence.update(2345, 5000, "paused")
        presence.close(true)
        assertEquals(listOf("progress", "progress", "stop"), factory.sockets[0].messages.map { JSONObject(it).getString("type") })
        assertTrue(factory.sockets[0].wasClosed)
        presence.update(3000, 5000, "playing")
        assertEquals(1, factory.sockets.size)
    }

    @Test fun rotationClosesWithoutEndingSessionAndLateOpenCannotSend() {
        val factory = FakeFactory()
        val presence = PlaybackPresence(factory, request, spec, "device", "TV", "tv", { true })
        presence.update(1, 10, "playing")
        presence.close(false)
        factory.open()
        assertTrue(factory.sockets[0].messages.isEmpty())
        assertTrue(factory.sockets[0].wasClosed)
    }

    @Test fun retriesTransientFailureOnlyOnLaterUpdatesAndStopsOnAuthorizationFailure() {
        var now = 0L
        val factory = FakeFactory()
        val presence = PlaybackPresence(factory, request, spec, "device", "TV", "tv", { true }, { now })
        presence.update(1, 10, "playing")
        factory.listeners[0].onFailure(factory.sockets[0], java.io.IOException(), null)
        presence.update(2, 10, "playing")
        assertEquals(1, factory.sockets.size)
        now += 3_000_000_000L
        presence.update(3, 10, "playing")
        assertEquals(2, factory.sockets.size)
        factory.listeners[1].onClosed(factory.sockets[1], 4401, "")
        now += 100_000_000_000L
        presence.update(4, 10, "playing")
        assertEquals(2, factory.sockets.size)
    }

    @Test fun rejectsUnsafeTransportAndCookieOnlySessions() {
        assertNull(ShyniClient("http://example.test", "token", deviceId = "device")
            .createPlaybackPresence(spec, "phone", "mobile"))
        assertNull(ShyniClient("https://example.test", "cookie:session=test", deviceId = "device")
            .createPlaybackPresence(spec, "phone", "mobile"))
        assertNull(ShyniClient("https://example.test", "token", deviceId = "device")
            .createPlaybackPresence(spec.copy(sessionId = null), "phone", "mobile"))
        assertNotNull(ShyniClient("https://example.test", "token", deviceId = "device")
            .createPlaybackPresence(spec, "phone", "mobile"))
    }

    @Test fun serverStopIsDeliveredOnceAndNeverAfterCloseOrAuthorizationChange() {
        var authorized = true
        val actions = mutableListOf<String?>()
        val factory = FakeFactory()
        val presence = PlaybackPresence(factory, request, spec, "device", "phone", "mobile",
            { authorized }, onForceStop = { actions += it })
        presence.update(1, 10, "playing"); factory.open()
        factory.listeners[0].onMessage(factory.sockets[0], JSONObject().put("type", "force_stop").put("message", "Stop\nnow").toString())
        factory.listeners[0].onMessage(factory.sockets[0], """{"type":"force_stop","message":"again"}""")
        assertEquals(listOf("Stopnow"), actions)
        presence.close(false)
        authorized = false
        factory.listeners[0].onMessage(factory.sockets[0], """{"type":"force_stop"}""")
        assertEquals(1, actions.size)
    }

    @Test fun loopbackWebSocketUpgradesAndTransfersIdentityAndControl() {
        // The real factory enforces HTTPS. This test reuses its assembled headers but
        // redirects only the test request to a local cleartext MockWebServer.
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        MockWebServer().use { server ->
            val frames = LinkedBlockingQueue<String>()
            val stopped = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val messages = LinkedBlockingQueue<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.offer(text)
                    if (JSONObject(text).optString("type") == "progress") {
                        webSocket.send(JSONObject().put("type", "force_stop").put("message", "테스트 중지").toString())
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
            }))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val shyni = ShyniClient("https://example.test", "test-bearer", deviceId = "device")
            val template = requireNotNull(shyni.createPlaybackPresence(spec, "phone", "mobile"))
            val requestField = PlaybackPresence::class.java.getDeclaredField("request").apply { isAccessible = true }
            val assembled = requestField.get(template) as Request
            assertEquals("https", assembled.url.scheme)
            assertEquals("example.test", assembled.url.host)
            assertNull(assembled.url.query)
            val loopbackRequest = assembled.newBuilder().url(server.url("/ws/playback")).build()
            val presence = PlaybackPresence(client, loopbackRequest, spec, "device", "phone", "mobile",
                { true }, onForceStop = { messages.offer(it ?: ""); stopped.countDown() })
            try {
                presence.update(1234, 5000, "playing")
                val handshake = server.takeRequest(5, TimeUnit.SECONDS)
                assertNotNull(handshake)
                assertEquals("/ws/playback", handshake!!.path)
                assertEquals("Bearer test-bearer", handshake.getHeader("Authorization"))
                assertEquals("device", handshake.getHeader("X-Device-Id"))
                val progress = JSONObject(requireNotNull(frames.poll(5, TimeUnit.SECONDS)))
                assertEquals("progress", progress.getString("type"))
                assertEquals("phone", progress.getString("device_name"))
                assertEquals("play-session", progress.getString("client_session_id"))
                assertEquals("device", progress.getString("device_id"))
                assertEquals(9, progress.getInt("part_id"))
                assertTrue(stopped.await(5, TimeUnit.SECONDS))
                assertEquals("테스트 중지", messages.poll())
                presence.close(true)
                assertEquals("stop", JSONObject(requireNotNull(frames.poll(5, TimeUnit.SECONDS))).getString("type"))
                assertTrue(closed.await(5, TimeUnit.SECONDS))
            } finally {
                presence.close(false)
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}
