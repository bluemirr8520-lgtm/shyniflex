package io.mirr.shynigsy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.PlaybackEnginePreference
import io.mirr.shynigsy.player.mpv.MpvPlayer
import io.mirr.shynigsy.player.subtitles.LocalSubtitleStore
import io.mirr.shynigsy.player.subtitles.LocalSubtitleWrites
import io.mirr.shynigsy.player.subtitles.PlexSubtitleOverlayView
import io.mirr.shynigsy.player.subtitles.localSubtitleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real engines and subtitle Canvas, using only synthetic media and an isolated loopback account. */
@RunWith(AndroidJUnit4::class)
class NativeLocalSubtitlePlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun mpvLocalSubtitleSurvivesSeekAndSwitchToExo() = verify(PlaybackEnginePreference.MPV)
    @Test fun exoLocalSubtitleSurvivesSeekAndSwitchToMpv() = verify(PlaybackEnginePreference.EXO)
    @Test fun storedSubtitleIsNotAppliedToAnotherPart() = verify(PlaybackEnginePreference.MPV, anotherPart = true)

    private fun verify(initialEngine: PlaybackEnginePreference, anotherPart: Boolean = false) {
        val context = instrumentation.targetContext
        assumeTrue("Only the isolated QA app may use the synthetic account", context.packageName.endsWith(".spoilerqa"))
        assertFalse("Unlock the device before checking subtitles", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val clip = instrumentation.context.assets.open("media3/gesture-av.mp4").use { it.readBytes() }
        val settings = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val keys = listOf("playback_engine", "playback_speed", "resize_mode", "resize_scale", "subtitle_vertical", "picture_settings_v1")
        val old = keys.associateWith { settings.all[it] }
        val sessions = SessionStore(context)
        val encrypted = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
        val oldLast = encrypted.getString("last_server", null)
        val localStore = LocalSubtitleStore(context)
        Fixture(clip).use { fixture ->
            fun spec(part: Int): PlaybackSpec {
                val item = MediaItem(11, 5, "Synthetic local caption", "movie", durationMs = 6_000,
                    viewItemId = 17, playbackPartId = part)
                return PlaybackSpec(item.title, "${fixture.origin}/stream/$part?ticket=synthetic",
                    itemId = item.id, libraryId = item.libraryId, partId = part, viewItemId = item.viewItemId,
                    durationMs = 6_000, sourceItem = item, subtitlesPending = true,
                    sessionId = "synthetic-local-session", progressToken = "synthetic-local-context")
            }
            val original = spec(77)
            val owner = localSubtitleOwner(fixture.origin, TOKEN, original)!!
            val target = if (anotherPart) spec(78) else original
            val targetOwner = localSubtitleOwner(fixture.origin, TOKEN, target)!!
            try {
                settings.edit().putString("playback_engine", initialEngine.key).putFloat("playback_speed", 1f)
                    .putInt("resize_mode", 0).putFloat("resize_scale", 1f).putBoolean("subtitle_vertical", true)
                    .remove("picture_settings_v1").commit()
                sessions.save(fixture.origin, TOKEN, "fixture")
                val imported = runBlocking { withContext(Dispatchers.IO) {
                    LocalSubtitleWrites.awaitPending()
                    localStore.importBytes(owner, "synthetic-local.srt", SUBTITLE.toByteArray())
                } }
                val intent = Intent(context, NativePlayerActivity::class.java)
                    .putExtra("server", fixture.origin).putExtra("spec", target.toJson())
                ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                    scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                    if (anotherPart) {
                        assertNotEquals(owner, targetOwner)
                        await(scenario, "The other part must play without the stored local subtitle") { activity ->
                            val player = field<Player?>(activity, "player")
                            player?.volume = 0f
                            val ready = player != null && player.playbackState == Player.STATE_READY && player.currentPosition >= 500 &&
                                !field<Boolean>(activity, "mpvProbePending")
                            if (ready) player?.pause()
                            ready
                        }
                        scenario.onActivity { activity ->
                            assertEquals(targetOwner, field<String?>(activity, "localSubtitleOwnerKey"))
                            assertNull(field<String?>(activity, "localSubtitleActiveId"))
                            assertNull(field<Any?>(activity, "localSubtitleRecord"))
                            assertFalse(field<PlexSubtitleOverlayView>(activity, "subtitleOverlay").subtitleText.startsWith("Local fixture"))
                        }
                    } else {
                        await(scenario, "The stored local subtitle must render in the requested engine") { activity ->
                            val player = field<Player?>(activity, "player")
                            player?.volume = 0f
                            val text = field<PlexSubtitleOverlayView>(activity, "subtitleOverlay").subtitleText
                            val ready = matches(player, initialEngine) && player != null && player.playbackState == Player.STATE_READY &&
                                player.currentPosition >= 500 && text in setOf(CUE_A, CUE_B) &&
                                field<String?>(activity, "localSubtitleActiveId") == imported.id &&
                                !field<Boolean>(activity, "mpvProbePending")
                            if (ready) player?.pause()
                            ready
                        }
                        scenario.onActivity { activity ->
                            assertCanvasDraws(activity)
                            field<Player>(activity, "player").seekTo(4_000)
                        }
                        await(scenario, "Seeking must update the local subtitle using the same engine") { activity ->
                            val player = field<Player?>(activity, "player")
                            matches(player, initialEngine) && player != null && player.currentPosition in 3_500L..4_500L &&
                                field<PlexSubtitleOverlayView>(activity, "subtitleOverlay").subtitleText == CUE_B
                        }
                        val next = if (initialEngine == PlaybackEnginePreference.MPV) PlaybackEnginePreference.EXO else PlaybackEnginePreference.MPV
                        scenario.onActivity { activity ->
                            NativePlayerActivity::class.java.getDeclaredMethod("applyPlaybackEnginePreference", PlaybackEnginePreference::class.java)
                                .apply { isAccessible = true }.invoke(activity, next)
                        }
                        await(scenario, "Changing engines must keep the local cue and paused position") { activity ->
                            val player = field<Player?>(activity, "player")
                            player?.volume = 0f
                            matches(player, next) && player != null && player.playbackState == Player.STATE_READY &&
                                !player.playWhenReady && player.currentPosition in 3_000L..5_000L &&
                                field<PlexSubtitleOverlayView>(activity, "subtitleOverlay").subtitleText == CUE_B &&
                                field<String?>(activity, "localSubtitleActiveId") == imported.id &&
                                !field<Boolean>(activity, "mpvProbePending")
                        }
                        scenario.onActivity { assertCanvasDraws(it) }
                        if (initialEngine == PlaybackEnginePreference.MPV) verifySubtitleMenuAndWebEditor(scenario)
                    }
                    fixture.assertHealthy()
                }
            } finally {
                runBlocking { withContext(Dispatchers.IO) { LocalSubtitleWrites.awaitPending(); localStore.remove(owner) } }
                sessions.clear(fixture.origin)
                encrypted.edit().apply { if (oldLast == null) remove("last_server") else putString("last_server", oldLast) }.commit()
                settings.edit().apply { old.forEach { (key, value) -> when (value) {
                    null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
                    is Float -> putFloat(key, value); is String -> putString(key, value); is Long -> putLong(key, value)
                } } }.commit()
            }
        }
    }

    private fun matches(player: Player?, engine: PlaybackEnginePreference) =
        if (engine == PlaybackEnginePreference.MPV) player is MpvPlayer else player is ExoPlayer

    private fun verifySubtitleMenuAndWebEditor(scenario: ActivityScenario<NativePlayerActivity>) {
        scenario.onActivity { activity ->
            field<PlayerView>(activity, "playerView").showController()
            NativePlayerActivity::class.java.getDeclaredMethod("showTracks", Int::class.javaPrimitiveType!!)
                .apply { isAccessible = true }.invoke(activity, C.TRACK_TYPE_TEXT)
            val dialog = field<AlertDialog>(activity, "subtitleDialog")
            val list = dialog.listView
            val labels = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
            assertTrue(labels.contains("자막 파일 불러오기"))
            assertTrue(labels.contains("웹에서 자막 검색"))
            assertEquals(BuildConfig.OPENSUBTITLES_API_KEY.isNotBlank(), labels.contains("온라인 자막 검색"))
            val index = labels.indexOf("웹에서 자막 검색")
            list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index))
        }
        await(scenario, "The web search entry must open an editable local query") { activity ->
            field<AlertDialog?>(activity, "subtitleDialog")?.window?.decorView
                ?.findViewWithTag<EditText>("subtitle-search-query") != null
        }
        scenario.onActivity { activity ->
            val dialog = field<AlertDialog>(activity, "subtitleDialog")
            val editor = dialog.window!!.decorView.findViewWithTag<EditText>("subtitle-search-query")
            assertEquals("Synthetic local caption 한국어 자막", editor.text.toString())
            editor.setText("Edited synthetic caption")
            assertEquals("Edited synthetic caption", editor.text.toString())
            assertTrue(dialog.window!!.decorView.findViewWithTag<View>("subtitle-search-close").performClick())
            assertFalse("Cancel must close without opening a browser", dialog.isShowing)
        }
    }

    private fun assertCanvasDraws(activity: NativePlayerActivity) {
        val overlay = field<PlexSubtitleOverlayView>(activity, "subtitleOverlay")
        assertEquals(View.VISIBLE, overlay.visibility)
        assertTrue(overlay.appearance.verticalWriting)
        assertTrue(overlay.width > 0 && overlay.height > 0)
        val bitmap = Bitmap.createBitmap(overlay.width, overlay.height, Bitmap.Config.ARGB_8888)
        try {
            overlay.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("The actual subtitle Canvas must draw visible pixels", pixels.count { it ushr 24 != 0 } > 20)
        } finally { bitmap.recycle() }
    }

    private fun await(scenario: ActivityScenario<NativePlayerActivity>, description: String, predicate: (NativePlayerActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var diagnostics = "No player"
        while (SystemClock.elapsedRealtime() < deadline) {
            if (scenario.state == Lifecycle.State.DESTROYED) fail("$description; Activity finished; $diagnostics")
            var matched = false
            scenario.onActivity { activity ->
                matched = predicate(activity)
                val player = field<Player?>(activity, "player")
                diagnostics = "engine=${player?.javaClass?.simpleName}, state=${player?.playbackState}, position=${player?.currentPosition}, " +
                    "playing=${player?.playWhenReady}, error=${player?.playerError?.errorCodeName}, local=${field<String?>(activity, "localSubtitleActiveId") != null}, " +
                    "cue=${field<PlexSubtitleOverlayView>(activity, "subtitleOverlay").subtitleText}"
            }
            if (matched) return
            Thread.sleep(40)
        }
        fail("$description; $diagnostics")
    }

    @Suppress("UNCHECKED_CAST") private fun <T> field(activity: NativePlayerActivity, name: String): T =
        NativePlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    internal class Fixture(private val clip: ByteArray, private val subtitleBody: ByteArray? = null) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "local-caption-http").apply { isDaemon = true } }
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val failure = AtomicReference<Throwable?>()
        val subtitleRequests = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var subtitleRows = JSONArray().toString()
        val origin get() = "http://127.0.0.1:${listener.localPort}"
        private val accept = thread(name = "local-caption-accept", isDaemon = true) {
            while (!listener.isClosed) try {
                val socket = listener.accept(); sockets += socket; workers.execute { respond(socket) }
            } catch (_: SocketTimeoutException) { } catch (error: SocketException) {
                if (!listener.isClosed) failure.compareAndSet(null, error)
            }
        }

        fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic subtitle server failed", it) } }
        private fun respond(socket: Socket) {
            var media = false
            try { socket.use { connection ->
                connection.soTimeout = 3_000
                val input = BufferedInputStream(connection.getInputStream())
                val request = line(input)?.split(' ') ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val row = line(input) ?: error("Incomplete request")
                    if (row.isEmpty()) break
                    val split = row.indexOf(':'); check(split > 0 && headers.size < 64)
                    headers[row.substring(0, split).lowercase()] = row.substring(split + 1).trim()
                }
                val length = headers["content-length"]?.toInt() ?: 0
                check(length in 0..65_536); repeat(length) { check(input.read() >= 0) }
                val path = Uri.parse(request[1]).path.orEmpty()
                when {
                    path.startsWith("/stream/") -> { media = true; video(connection, request[0], headers["range"]) }
                    path in setOf("/external-subtitle.srt", "/subtitles/raw/9", "/subtitles/track/9.vtt") && subtitleBody != null -> {
                        subtitleRequests.incrementAndGet()
                        reply(connection, "application/x-subrip", subtitleBody)
                    }
                    path.startsWith("/subtitles/") -> reply(connection, "application/json", subtitleRows.toByteArray())
                    path == "/playback/progress" -> reply(connection, "application/json", "{\"ok\":true}".toByteArray())
                    else -> reply(connection, "application/json", "{}".toByteArray(), "404 Not Found")
                }
            } } catch (error: SocketException) {
                if (!media && !listener.isClosed) failure.compareAndSet(null, error)
            } catch (error: Throwable) { if (!listener.isClosed) failure.compareAndSet(null, error) }
            finally { sockets -= socket }
        }

        private fun video(socket: Socket, method: String, rangeHeader: String?) {
            val range = rangeHeader?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) ?: error("Bad range") }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(clip.lastIndex) ?: clip.lastIndex
            if (start !in clip.indices || end < start) {
                reply(socket, "video/mp4", byteArrayOf(), "416 Range Not Satisfiable"); return
            }
            val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${clip.size}\r\n"
            socket.getOutputStream().apply {
                write(("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Type: video/mp4\r\n" +
                    "Accept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n${contentRange}Connection: close\r\n\r\n").toByteArray())
                if (method != "HEAD") write(clip, start, end - start + 1)
                flush()
            }
        }

        private fun reply(socket: Socket, type: String, bytes: ByteArray, status: String = "200 OK") {
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        }
        private fun line(input: BufferedInputStream): String? {
            val output = ByteArrayOutputStream()
            while (output.size() < 8_192) {
                val value = input.read()
                if (value < 0) return if (output.size() == 0) null else error("Incomplete line")
                if (value == 10) return output.toString("US-ASCII").trimEnd('\r')
                output.write(value)
            }
            error("Oversized line")
        }
        override fun close() {
            listener.close(); synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            accept.join(1_000); workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val TOKEN = "synthetic-local-subtitle-token"
        const val CUE_A = "Local fixture first cue"
        const val CUE_B = "Local fixture after seek"
        val SUBTITLE = "1\n00:00:00,000 --> 00:00:03,000\n$CUE_A\n\n2\n00:00:03,000 --> 00:00:06,000\n$CUE_B\n"
    }
}
