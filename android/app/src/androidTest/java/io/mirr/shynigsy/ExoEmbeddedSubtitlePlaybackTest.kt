package io.mirr.shynigsy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.Media3GestureView
import io.mirr.shynigsy.player.mpv.MpvPlayer
import io.mirr.shynigsy.player.subtitles.PlexSubtitleOverlayView
import `is`.xyz.mpv.MPVLib
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Device-only playback against synthetic files and an isolated loopback account. */
@RunWith(AndroidJUnit4::class)
class ExoEmbeddedSubtitlePlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun subripReselectionUsesMpvAfterExternalAndOff() = verify("subrip", "Embedded SubRip cue")
    @Test fun subripPausedSeekUsesMpvAtTheTarget() = verify("subrip", "Embedded SubRip cue", seekOnly = true)
    @Test fun subripPlayingSeekUsesMpvAtTheTarget() = verify("subrip", "Embedded SubRip cue", seekOnly = true, seekWhilePlaying = true)
    @Test fun subripRemoteSeekNearEofRetainsPausedTarget() = verify("subrip", "Embedded SubRip cue", seekOnly = true, seekNearEnd = true)
    @Test fun subripResumeUsesMpvAndRetainsTheCue() = verify("subrip", "Embedded SubRip cue", resumeMs = 600)
    @Test fun webmWebvttRetainsMpvAndShowsTheSubtitle() = verify("webvtt", "Embedded WebVTT cue")
    @Test fun mismatchedNativeCodecKeepsMpv() = verify("subrip", "Embedded SubRip cue", reportedFormat = "webvtt")

    private fun verify(format: String, cue: String, reportedFormat: String = format, resumeMs: Long = 0,
        seekOnly: Boolean = false, seekWhilePlaying: Boolean = false, seekNearEnd: Boolean = false) {
        val context = instrumentation.targetContext
        assumeTrue("Only the isolated synthetic QA package may use these fixtures", context.packageName.endsWith(".spoilerqa"))
        assertFalse("Unlock the device before rendering", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val bytes = instrumentation.context.assets.open("media3/exo-embedded-$format.mkv").use { it.readBytes() }
        val settings = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val keys = listOf("playback_engine", "playback_speed", "resize_mode", "resize_scale", "subtitle_vertical", "picture_settings_v1")
        val old = keys.associateWith { settings.all[it] }
        val encrypted = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
        val oldLast = encrypted.getString("last_server", null)
        val store = SessionStore(context)
        Fixture(bytes, reportedFormat).use { fixture ->
            try {
                settings.edit().putString("playback_engine", "exo").putFloat("playback_speed", 1f)
                    .putInt("resize_mode", 0).putFloat("resize_scale", 1f).putBoolean("subtitle_vertical", true)
                    .remove("picture_settings_v1").commit()
                store.save(fixture.origin, "synthetic-exo-token", "fixture")
                val item = MediaItem(ITEM, VIEW, "Synthetic caption", "movie", durationMs = 20_000,
                    viewItemId = VIEW_ITEM, playbackPartId = PART)
                val spec = PlaybackSpec(item.title, "${fixture.origin}/stream/$PART?ticket=fixture",
                    itemId = ITEM, libraryId = VIEW, partId = PART, viewItemId = VIEW_ITEM, resumeMs = resumeMs,
                    durationMs = 20_000, sessionId = "synthetic-exo-session", progressToken = "synthetic-exo-context",
                    sourceItem = item, selectedSubtitleId = EMBEDDED, subtitlesPending = true)
                val intent = Intent(context, NativePlayerActivity::class.java)
                    .putExtra("server", fixture.origin).putExtra("spec", spec.toJson())
                ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                    scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                    if (reportedFormat != format || format == "webvtt" || resumeMs > 0) {
                        await(scenario, "A codec mismatch must retain MPV") { activity ->
                            val active = field<Player?>(activity, "player")
                            if (active is MpvPlayer) active.volume = 0f
                            val retained = active is MpvPlayer && active.playbackState == Player.STATE_READY &&
                                field<String?>(activity, "forceMpvSession") == "synthetic-exo-session:$PART" &&
                                !field<Boolean>(activity, "mpvProbePending") &&
                                field<PlexSubtitleOverlayView>(activity, "subtitleOverlay").subtitleText == cue
                            if (retained) active?.pause()
                            retained
                        }
                        return@use
                    }
                    await(scenario, "Exo must decode and display the selected embedded cue") { activity ->
                        val active = field<Player?>(activity, "player")
                        if (active is ExoPlayer) active.volume = 0f
                        val overlay = field<PlexSubtitleOverlayView>(activity, "subtitleOverlay")
                        // Pause well inside the cue rather than at its first decoded
                        // frame, so the MPV handoff is not a frame-boundary test.
                        val matched = active is ExoPlayer && active.currentPosition >= 600 && overlay.subtitleText == cue &&
                            active.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
                        if (matched) active?.pause()
                        matched
                    }
                    scenario.onActivity { activity ->
                        val active = field<Player>(activity, "player")
                        assertNull(active.playerError)
                        assertTrue(field<PlexSubtitleOverlayView>(activity, "subtitleOverlay").appearance.verticalWriting)
                    }
                    if (seekOnly) {
                        scenario.onActivity {
                            val active = field<Player>(it, "player")
                            if (seekWhilePlaying) active.play()
                            if (seekNearEnd) {
                                val gestures = field<Media3GestureView>(it, "gestures")
                                assertTrue("The remote seek gesture must start", gestures.beginRemoteSeek(true))
                                gestures.previewRemoteSeek((active.duration - 2).coerceAtLeast(0))
                                gestures.finishRemoteSeek()
                            } else active.seekTo(1_000)
                        }
                        await(scenario, "Seeking must retain the cue, target, and playback intent using MPV") {
                            val active = field<Player?>(it, "player")
                            active is MpvPlayer && active.playWhenReady == seekWhilePlaying &&
                                if (seekNearEnd) active.currentPosition >= 18_500
                                else active.playbackState == Player.STATE_READY && active.currentPosition in 500..2_500 &&
                                    field<PlexSubtitleOverlayView>(it, "subtitleOverlay").subtitleText == cue
                        }
                        return@use
                    }
                    selectMenu(scenario, "Synthetic external")
                    await(scenario, "The external subtitle selection must replace the embedded caption") {
                        field<Player>(it, "player") is ExoPlayer &&
                            field<PlexSubtitleOverlayView>(it, "subtitleOverlay").subtitleText == EXTERNAL_CUE
                    }
                    selectMenu(scenario, "자막 끄기")
                    await(scenario, "Subtitle off must clear the caption without switching engines") {
                        field<Player>(it, "player") is ExoPlayer &&
                            !field<Boolean>(it, "subtitlesEnabled") &&
                            field<PlexSubtitleOverlayView>(it, "subtitleOverlay").subtitleText.isEmpty()
                    }
                    selectMenu(scenario, "Synthetic embedded")
                    await(scenario, "Reselecting the embedded subtitle must restore its cue in MPV") {
                        field<Player>(it, "player") is MpvPlayer &&
                            field<PlexSubtitleOverlayView>(it, "subtitleOverlay").subtitleText == cue
                    }
                    fixture.assertHealthy()
                }
            } finally {
                store.clear(fixture.origin)
                encrypted.edit().apply { if (oldLast == null) remove("last_server") else putString("last_server", oldLast) }.commit()
                settings.edit().apply {
                    old.forEach { (key, value) -> when (value) {
                        null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
                        is Float -> putFloat(key, value); is String -> putString(key, value); is Long -> putLong(key, value)
                    } }
                }.commit()
            }
        }
    }

    private fun selectMenu(scenario: ActivityScenario<NativePlayerActivity>, partialLabel: String) {
        scenario.onActivity { activity ->
            field<PlayerView>(activity, "playerView").showController()
            NativePlayerActivity::class.java.getDeclaredMethod("showTracks", Int::class.javaPrimitiveType!!)
                .apply { isAccessible = true }.invoke(activity, C.TRACK_TYPE_TEXT)
        }
        val deadline = SystemClock.elapsedRealtime() + 4_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                val nodes = root.findAccessibilityNodeInfosByText(partialLabel)
                try {
                    val node = nodes.firstOrNull { it.text?.toString()?.contains(partialLabel) == true }
                    if (node != null && (node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        node.parent?.let { parent -> try { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { parent.recycle() } } == true)) return
                } finally { nodes.forEach(AccessibilityNodeInfo::recycle); root.recycle() }
            }
            Thread.sleep(40)
        }
        fail("Could not select synthetic subtitle menu row: $partialLabel")
    }

    private fun await(scenario: ActivityScenario<NativePlayerActivity>, message: String,
        predicate: (NativePlayerActivity) -> Boolean) {
        Log.i("ExoSubtitleQA", "stage=$message")
        val until = SystemClock.elapsedRealtime() + 15_000
        var diagnostics = "no Activity observation"
        while (SystemClock.elapsedRealtime() < until) {
            if (scenario.state == Lifecycle.State.DESTROYED)
                fail("$message; Activity destroyed before success; $diagnostics")
            var matched = false
            scenario.onActivity { activity ->
                matched = predicate(activity)
                val active = field<Player?>(activity, "player")
                val text = field<PlexSubtitleOverlayView>(activity, "subtitleOverlay").subtitleText
                val tracks = active?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT }
                    ?.joinToString { group -> (0 until group.length).joinToString { index ->
                        val format = group.getTrackFormat(index)
                        "${format.sampleMimeType}/${format.codecs}:supported=${group.isTrackSupported(index)}:selected=${group.isTrackSelected(index)}"
                    } }.orEmpty()
                // This fixture has no user media: retain only native type/codec/index,
                // never paths, signed URLs, titles or subtitle content from MPV.
                val native = if (active is MpvPlayer) runCatching {
                    val rows = JSONArray(MPVLib.getPropertyString("track-list") ?: "[]")
                    (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { row ->
                        "${row.optString("type")}:${row.optString("codec")}:${row.opt("ff-index")}"
                    } }.joinToString()
                }.getOrDefault("unavailable") else ""
                diagnostics = "engine=${active?.javaClass?.simpleName}, state=${active?.playbackState}, " +
                    "playing=${active?.isPlaying}, position=${active?.currentPosition}, error=${active?.playerError?.errorCodeName}, " +
                    "probe=${field<Boolean>(activity, "mpvProbePending")}, exoValidated=${field<Boolean>(activity, "exoValidated")}, " +
                    "text=$text, currentCues=${active?.currentCues?.cues?.map { it.text?.toString() }}, " +
                    "externalId=${field<Int?>(activity, "externalSubtitleId")}, enabled=${field<Boolean>(activity, "subtitlesEnabled")}, " +
                    "textTracks=$tracks, nativeTracks=$native"
            }
            if (matched) { Log.i("ExoSubtitleQA", "passed=$message; $diagnostics"); return }
            Thread.sleep(40)
        }
        fail("$message; $diagnostics")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: NativePlayerActivity, name: String): T =
        NativePlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private class Fixture(private val clip: ByteArray, private val format: String) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "exo-caption-http").apply { isDaemon = true } }
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val failure = AtomicReference<Throwable?>()
        val origin get() = "http://127.0.0.1:${listener.localPort}"
        private val thread = thread(name = "exo-caption-accept", isDaemon = true) {
            while (!listener.isClosed) try {
                val socket = listener.accept(); sockets += socket; workers.execute { respond(socket) }
            } catch (_: SocketTimeoutException) { } catch (error: SocketException) {
                if (!listener.isClosed) failure.compareAndSet(null, error)
            }
        }

        fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic caption fixture failed", it) } }

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
                when (Uri.parse(request[1]).path) {
                    "/stream/$PART" -> { media = true; video(connection, request[0], headers["range"]) }
                    "/subtitles/$PART" -> json(connection, JSONArray()
                        .put(JSONObject().put("id", EMBEDDED).put("source", "embedded").put("format", format)
                            .put("stream_index", 2).put("lang_code", "eng").put("label", "Synthetic embedded"))
                        .put(JSONObject().put("id", EXTERNAL).put("source", "external").put("format", "srt")
                            .put("lang_code", "eng").put("label", "Synthetic external")))
                    "/subtitles/raw/$EXTERNAL" -> reply(connection, "application/x-subrip",
                        "1\n00:00:00,000 --> 00:00:20,000\n$EXTERNAL_CUE\n".toByteArray())
                    "/playback/progress" -> json(connection, JSONObject().put("ok", true))
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
            val output = socket.getOutputStream()
            if (start !in clip.indices || end < start) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${clip.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush(); return
            }
            val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${clip.size}\r\n"
            output.write("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n${contentRange}Connection: close\r\n\r\n".toByteArray())
            if (method != "HEAD") output.write(clip, start, end - start + 1)
            output.flush()
        }

        private fun json(socket: Socket, data: Any) = reply(socket, "application/json", data.toString().toByteArray())
        private fun reply(socket: Socket, type: String, data: ByteArray, status: String = "200 OK") {
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(data); flush()
            }
        }
        private fun line(input: BufferedInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 8_192) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else error("Incomplete request")
                if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                bytes.write(value)
            }
            error("Oversized request")
        }
        override fun close() {
            listener.close(); synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            thread.join(1_000); workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val ITEM = 11; const val VIEW = 5; const val VIEW_ITEM = 17; const val PART = 77
        const val EMBEDDED = 7; const val EXTERNAL = 8; const val EXTERNAL_CUE = "External subtitle cue"
    }
}
