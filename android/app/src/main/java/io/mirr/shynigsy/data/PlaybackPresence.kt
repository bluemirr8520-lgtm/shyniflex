package io.mirr.shynigsy.data

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Best-effort dashboard metadata; HTTP progress remains the source of truth. */
class PlaybackPresence internal constructor(
    private val factory: WebSocket.Factory,
    private val request: Request,
    private val spec: PlaybackSpec,
    private val deviceId: String,
    private val deviceName: String,
    private val platform: String,
    private val stillAuthorized: () -> Boolean,
    private val clockNanos: () -> Long = System::nanoTime,
    private val onForceStop: (String?) -> Unit = {},
) {
    private data class Snapshot(val positionMs: Long, val durationMs: Long, val state: String)
    private var socket: WebSocket? = null
    private var ready = false
    private var closed = false
    private var terminal = false
    private var snapshot: Snapshot? = null
    private var retryAt = clockNanos()
    private var failures = 0

    /** Never waits for a network connection or changes playback state. */
    @Synchronized fun update(positionMs: Long, durationMs: Long, state: String) {
        if (closed || terminal || state !in setOf("playing", "paused", "buffering")) return
        if (!stillAuthorized()) { close(false); return }
        snapshot = Snapshot(positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), state)
        val current = socket
        if (current != null) {
            if (ready && !current.send(message("progress", snapshot!!))) current.cancel()
            return
        }
        if (clockNanos() - retryAt < 0) return
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = opened(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = failed(webSocket, response?.code)
            override fun onMessage(webSocket: WebSocket, text: String) {
                val action = forceStop(webSocket, text) ?: return
                runCatching { onForceStop(action.takeIf { it.isNotEmpty() }) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { runCatching { webSocket.close(code, null) } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = failed(webSocket, code)
        }
        socket = try {
            factory.newWebSocket(request, listener)
        } catch (_: Exception) {
            failures = (failures + 1).coerceAtMost(5)
            retryAt = clockNanos() + TimeUnit.SECONDS.toNanos((1L shl failures).coerceAtMost(30L))
            null
        }
    }

    @Synchronized private fun forceStop(webSocket: WebSocket, text: String): String? {
        if (closed || terminal || !ready || socket !== webSocket || !stillAuthorized() || text.length > 4096) return null
        val body = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (body.optString("type") != "force_stop") return null
        terminal = true
        return body.optString("message", "").filterNot { it.code in 0..31 || it.code in 127..159 }.take(200)
    }

    @Synchronized private fun opened(webSocket: WebSocket) {
        if (closed || terminal || socket !== webSocket || !stillAuthorized()) {
            webSocket.close(1000, null)
            return
        }
        ready = true
        failures = 0
        snapshot?.let { if (!webSocket.send(message("progress", it))) webSocket.cancel() }
    }

    @Synchronized private fun failed(webSocket: WebSocket, status: Int?) {
        if (socket !== webSocket) return
        socket = null; ready = false
        if (closed) return
        // Authorization failures require a new account/grant, never a retry loop.
        if (status in setOf(401, 403, 4401, 4403)) { terminal = true; return }
        failures = (failures + 1).coerceAtMost(5)
        retryAt = clockNanos() + TimeUnit.SECONDS.toNanos((1L shl failures).coerceAtMost(30L))
    }

    /** Ordered stop precedes close; rotation uses false so the replacement remains active. */
    @Synchronized fun close(endSession: Boolean) {
        if (closed) return
        closed = true
        val current = socket
        socket = null
        if (current != null) {
            if (endSession && ready && stillAuthorized()) snapshot?.let { current.send(message("stop", it)) }
            current.close(1000, null)
        }
        ready = false; snapshot = null
    }

    private fun message(type: String, value: Snapshot): String = JSONObject().apply {
        put("type", type); put("part_id", spec.partId); put("metadata_item_id", spec.itemId)
        put("library_view_id", spec.libraryId); put("view_item_id", spec.viewItemId)
        put("playback_context_token", spec.progressToken); put("client_session_id", spec.sessionId)
        put("device_id", deviceId); put("device_name", deviceName); put("platform", platform)
        put("state", if (type == "stop") "stopped" else value.state)
        put("position_ms", value.positionMs); if (value.durationMs > 0) put("duration_ms", value.durationMs)
        put("decision", spec.decision); put("delivery_mode", "server_proxy")
    }.toString()
}
