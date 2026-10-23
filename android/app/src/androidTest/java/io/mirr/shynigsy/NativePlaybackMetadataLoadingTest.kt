package io.mirr.shynigsy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.view.WindowManager
import android.widget.ImageButton
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackMarker
import io.mirr.shynigsy.data.PlaybackNeighbors
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.player.NativePlayerActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Session/prefs are only touched after proving this is the isolated, loopback-only QA app. */
@RunWith(AndroidJUnit4::class)
class NativePlaybackMetadataLoadingTest {
    @Test fun initialBufferingDefersMetadataAndFirstReadyReusesItAcrossPausedSeeks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Only the synthetic QA package may run this fixture", context.packageName.endsWith(".spoilerqa"))
        assertFalse("Unlock the device before running the native player", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val settings = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val previousDevice = settings.getString("device_id", null)
        val previousSpeed = settings.getFloat("playback_speed", 1f)
        val sessions = SessionStore(context)
        val clip = instrumentation.context.assets.open("media3/gesture-av.mp4").use { it.readBytes() }
        MetadataFixture(clip).use { fixture ->
            settings.edit().putString("device_id", DEVICE).putFloat("playback_speed", 1f).commit()
            try {
                sessions.save(fixture.origin, SESSION, "합성 부가 정보 검사")
                val immediateMarker = PlaybackMarker("intro", 0, 500)
                val item = MediaItem(ITEM, VIEW, "합성 영상", "movie", durationMs = 6_000,
                    viewItemId = VIEW_ITEM, playbackPartId = PART)
                val spec = PlaybackSpec(item.title, "${fixture.origin}/stream/$PART?ticket=$TICKET",
                    itemId = ITEM, libraryId = VIEW, partId = PART, viewItemId = VIEW_ITEM,
                    durationMs = 6_000, sessionId = "metadata-fixture-session", progressToken = CONTEXT,
                    sourceItem = item, markers = listOf(immediateMarker))
                val intent = Intent(context, NativePlayerActivity::class.java)
                    .putExtra("server", fixture.origin).putExtra("spec", spec.toJson())
                ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                    assertTrue("MPV must reach the intentionally blocked stream", fixture.streamRequested.await(10, TimeUnit.SECONDS))
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val active = field<Player>(activity, "player")
                        active.volume = 0f
                        active.pause()
                        assertEquals(Player.STATE_BUFFERING, active.playbackState)
                        assertFalse(field<Boolean>(activity, "prepared"))
                        assertEquals("Existing markers remain immediately available", listOf(immediateMarker), field<PlaybackSpec>(activity, "spec").markers)
                    }
                    // Allow a real scheduling window; metadata may not compete with the blocked initial load.
                    Thread.sleep(500)
                    assertEquals(0, fixture.count("/libraries/$VIEW/media_info/$PART"))
                    assertEquals(0, fixture.count("/libraries/$VIEW/movies"))
                    fixture.releaseStream.countDown()
                    waitFor(scenario, "READY must load markers and verified same-folder navigation", 15_000) { activity ->
                        val active = field<Player>(activity, "player")
                        active.playbackState == Player.STATE_READY && field<Boolean>(activity, "prepared") &&
                            field<PlaybackSpec>(activity, "spec").markers == listOf(PlaybackMarker("intro", 1_000, 2_000)) &&
                            field<PlaybackNeighbors?>(activity, "playbackNeighbors")?.next?.navigationPartId == NEXT_PART
                    }
                    assertEquals("Markers and neighbors must share the completed part snapshot", 1, fixture.count("/libraries/$VIEW/media_info/$PART"))
                    assertEquals(1, fixture.count("/libraries/$VIEW/movies"))
                    assertEquals(1, fixture.count("/libraries/$VIEW/media_info/item/$NEXT_ITEM"))
                    val buffering = AtomicInteger()
                    scenario.onActivity { activity ->
                        val active = field<Player>(activity, "player")
                        assertFalse("Loading optional metadata must preserve the user's pause", active.playWhenReady)
                        assertTrue(activity.findViewById<ImageButton>(R.id.player_next).isEnabled)
                        active.addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_BUFFERING) buffering.incrementAndGet()
                            }
                        })
                        active.seekTo(2_500)
                    }
                    waitFor(scenario, "A paused seek must restart the same source", 10_000) { activity ->
                        val active = field<Player>(activity, "player")
                        active.playbackState == Player.STATE_READY && !active.playWhenReady && active.currentPosition in 2_000L..3_000L
                    }
                    assertTrue("The test must exercise a real BUFFERING -> READY cycle", buffering.get() > 0)
                    scenario.onActivity { field<Player>(it, "player").play() }
                    Thread.sleep(300)
                    scenario.onActivity { field<Player>(it, "player").pause() }
                    Thread.sleep(300)
                    assertEquals("Seeking/resuming READY must not reload the current part", 1, fixture.count("/libraries/$VIEW/media_info/$PART"))
                    assertEquals("Seeking/resuming READY must not reload its catalog", 1, fixture.count("/libraries/$VIEW/movies"))
                    assertEquals(1, fixture.count("/libraries/$VIEW/media_info/item/$NEXT_ITEM"))
                    fixture.assertHealthy()
                }
            } finally {
                fixture.releaseStream.countDown()
                sessions.clear(fixture.origin)
                settings.edit().apply {
                    if (previousDevice == null) remove("device_id") else putString("device_id", previousDevice)
                    putFloat("playback_speed", previousSpeed)
                }.commit()
            }
        }
    }

    private fun waitFor(scenario: ActivityScenario<NativePlayerActivity>, message: String, timeoutMs: Long,
        predicate: (NativePlayerActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var matched = false
            scenario.onActivity { matched = predicate(it) }
            if (matched) return
            Thread.sleep(50)
        }
        fail(message)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: NativePlayerActivity, name: String): T =
        NativePlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private data class Request(val path: String, val uri: Uri, val headers: Map<String, String>)

    /** Bounded fake API and Range stream; all requests terminate at this loopback socket. */
    private class MetadataFixture(private val clip: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        private val workers = Executors.newFixedThreadPool(6) { task -> Thread(task, "metadata-qa-http").apply { isDaemon = true } }
        private val records = Collections.synchronizedList(mutableListOf<Request>())
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val failure = AtomicReference<Throwable?>()
        val streamRequested = CountDownLatch(1)
        val releaseStream = CountDownLatch(1)
        val origin get() = "http://127.0.0.1:${server.localPort}"
        private val listener = thread(name = "metadata-qa-loopback", isDaemon = true) {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    sockets += socket
                    workers.execute { handle(socket) }
                } catch (_: SocketTimeoutException) { }
                catch (error: SocketException) { if (!server.isClosed) failure.compareAndSet(null, error) }
            }
        }

        fun count(path: String): Int = synchronized(records) { records.count { it.path == path } }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic metadata fixture failed", it) } }

        private fun handle(socket: Socket) {
            var streaming = false
            try {
                socket.use { connection ->
                    connection.soTimeout = 3_000
                    val input = BufferedInputStream(connection.getInputStream())
                    val first = line(input)?.split(' ') ?: return
                    check(first.size == 3)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val next = line(input) ?: error("Truncated fixture header")
                        if (next.isEmpty()) break
                        check(headers.size < 64)
                        val split = next.indexOf(':'); check(split > 0)
                        headers[next.substring(0, split).lowercase()] = next.substring(split + 1).trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..16_384)
                    repeat(length) { check(input.read() >= 0) }
                    val uri = Uri.parse(first[1])
                    val request = Request(uri.path.orEmpty(), uri, headers)
                    records += request
                    if (request.path == "/stream/$PART") {
                        streaming = true
                        check(uri.getQueryParameter("ticket") == TICKET)
                        streamRequested.countDown()
                        check(releaseStream.await(20, TimeUnit.SECONDS)) { "Synthetic stream was never released" }
                        video(connection, first[0], headers["range"])
                        return
                    }
                    check(headers["authorization"] == "Bearer $SESSION")
                    check(headers["x-device-id"] == DEVICE)
                    when (request.path) {
                        "/libraries/$VIEW/media_info/$PART" -> {
                            check(uri.getQueryParameter("playback_context_token") == CONTEXT)
                            json(connection, info(ITEM, PART, "current.mp4").put("markers", JSONArray()
                                .put(JSONObject().put("segment_type", "intro").put("start_ms", 1_000).put("end_ms", 2_000))))
                        }
                        "/libraries/$VIEW/movies" -> json(connection, JSONArray()
                            .put(JSONObject().put("id", ITEM).put("view_item_id", VIEW_ITEM).put("title", "현재"))
                            .put(JSONObject().put("id", NEXT_ITEM).put("view_item_id", NEXT_VIEW_ITEM).put("title", "다음")))
                        "/libraries/$VIEW/media_info/item/$NEXT_ITEM" -> {
                            check(uri.getQueryParameter("library_view_id") == VIEW.toString())
                            check(uri.getQueryParameter("view_item_id") == NEXT_VIEW_ITEM.toString())
                            json(connection, info(NEXT_ITEM, NEXT_PART, "next.mp4"))
                        }
                        "/playback/progress" -> json(connection, JSONObject().put("ok", true))
                        else -> error("Unexpected synthetic metadata endpoint: ${request.path}")
                    }
                }
            } catch (error: SocketException) {
                if (!streaming && !server.isClosed) failure.compareAndSet(null, error)
            } catch (error: Throwable) {
                if (!server.isClosed) failure.compareAndSet(null, error)
            } finally { sockets -= socket }
        }

        private fun info(item: Int, part: Int, file: String) = JSONObject().put("item", JSONObject().put("id", item))
            .put("parts", JSONArray().put(JSONObject().put("id", part).put("metadata_item_id", item)
                .put("file_path", "/synthetic/same-folder/$file")))

        private fun line(input: BufferedInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 8_192) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else error("Truncated fixture request")
                if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                bytes.write(value)
            }
            error("Oversized fixture request")
        }

        private fun json(socket: Socket, value: Any) {
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                write(bytes); flush()
            }
        }

        private fun video(socket: Socket, method: String, rangeHeader: String?) {
            val range = rangeHeader?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(clip.lastIndex) ?: clip.lastIndex
            val output = socket.getOutputStream()
            if (start >= clip.size) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${clip.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            } else {
                check(start >= 0 && end >= start)
                val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${clip.size}\r\n"
                val status = if (range == null) "200 OK" else "206 Partial Content"
                output.write("HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n${contentRange}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") output.write(clip, start, end - start + 1)
            }
            output.flush()
        }

        override fun close() {
            server.close(); releaseStream.countDown()
            synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            listener.join(1_000)
            workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val ITEM = 11
        const val NEXT_ITEM = 12
        const val VIEW = 5
        const val VIEW_ITEM = 17
        const val NEXT_VIEW_ITEM = 18
        const val PART = 77
        const val NEXT_PART = 78
        const val DEVICE = "synthetic-metadata-device"
        const val SESSION = "synthetic-metadata-session"
        const val TICKET = "synthetic-metadata-ticket"
        const val CONTEXT = "synthetic-metadata-context"
    }
}
