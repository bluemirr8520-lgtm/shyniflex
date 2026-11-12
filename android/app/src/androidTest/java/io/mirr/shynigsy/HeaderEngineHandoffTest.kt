package io.mirr.shynigsy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.player.NativePlayerActivity
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
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Exercises the real Activity handoff. Every request stays on a synthetic loopback server. */
@RunWith(AndroidJUnit4::class)
class HeaderEngineHandoffTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun manualExoStartsFromHeadersBeforeMpvCanRenderTheResumeFrame() = handoff("exo")

    @Test fun automaticExoStartsFromHeadersBeforeMpvCanRenderTheResumeFrame() = handoff("auto")

    @Test fun savedZoomKeepsExoAndResizeControlsNeverReplaceThePlayingEngine() = handoff("exo", verifyResize = true)

    private fun handoff(preference: String, verifyResize: Boolean = false) {
        val context = instrumentation.targetContext
        requireSyntheticEnvironment(context)
        val settings = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val keys = setOf("playback_engine", "playback_speed", "resize_mode", "resize_scale", "subtitle_vertical")
        val oldSettings = keys.associateWith { settings.all[it] }
        val encrypted = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
        val oldLastServer = encrypted.getString("last_server", null)
        val sessions = SessionStore(context)
        val clip = instrumentation.context.assets.open("media3/gesture-av.mp4").use { it.readBytes() }
        HeaderFixture(clip).use { fixture ->
            try {
                settings.edit().putString("playback_engine", preference).putFloat("playback_speed", 1f)
                    .putInt("resize_mode", 0).putFloat("resize_scale", if (verifyResize) 1.1f else 1f)
                    .putBoolean("subtitle_vertical", false).commit()
                sessions.save(fixture.origin, SESSION, "합성 엔진 검사")
                val item = MediaItem(ITEM, VIEW, "합성 엔진 영상", "movie", durationMs = 6_000,
                    viewItemId = VIEW_ITEM, playbackPartId = PART)
                val spec = PlaybackSpec(item.title, "${fixture.origin}/stream/$PART?ticket=$TICKET",
                    itemId = ITEM, libraryId = VIEW, partId = PART, viewItemId = VIEW_ITEM,
                    resumeMs = RESUME_MS, durationMs = 6_000, sessionId = "synthetic-header-session",
                    progressToken = "synthetic-header-context", sourceItem = item, subtitlesPending = true)
                val intent = Intent(context, NativePlayerActivity::class.java)
                    .putExtra("server", fixture.origin).putExtra("spec", spec.toJson())
                val mpvFirstFrame = AtomicBoolean()
                val exoFirstFrame = AtomicBoolean()
                val headerTracksSeen = AtomicBoolean()
                ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                    assertTrue("The native probe must request the synthetic original", fixture.streamRequested.await(10, TimeUnit.SECONDS))
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val native = field<Player>(activity, "player") as MpvPlayer
                        native.volume = 0f
                        native.addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { mpvFirstFrame.set(true) }
                            override fun onTracksChanged(tracks: Tracks) {
                                if (native.manualExoEvidence(emptyList(), null, null) != null)
                                    headerTracksSeen.set(true)
                            }
                        })
                    }
                    fixture.releaseHeaders.countDown()
                    waitFor(scenario, "The real Activity must choose Exo while the resume-frame bytes remain withheld", 7_000) {
                        field<Player?>(it, "player") is ExoPlayer
                    }
                    assertEquals("No resumed video data is released before the engine handoff", 1L, fixture.releaseTail.count)
                    assertFalse("MPV must not render the resumed first frame as an Exo prerequisite", mpvFirstFrame.get())
                    assertTrue("MPV must independently enumerate the audio/video headers", headerTracksSeen.get())
                    scenario.onActivity { activity ->
                        val exo = field<Player>(activity, "player") as ExoPlayer
                        exo.volume = 0f
                        assertEquals("The requested original resume point must survive replacement", RESUME_MS, exo.currentPosition)
                        assertEquals("The source identity must survive replacement", PART, field<PlaybackSpec>(activity, "spec").partId)
                        exo.addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { exoFirstFrame.set(true) }
                        })
                    }
                    fixture.releaseTail.countDown()
                    waitFor(scenario, "Exo must decode the same source, advance from its resume point and retain external Korean subtitles", 12_000) { activity ->
                        val active = field<Player?>(activity, "player")
                        active is ExoPlayer && exoFirstFrame.get() && active.isPlaying &&
                            active.currentPosition >= RESUME_MS + 300 && active.currentPosition < 5_500 &&
                            active.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } &&
                            active.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } &&
                            field<PlexSubtitleOverlayView>(activity, "subtitleOverlay").subtitleText == SUBTITLE
                    }
                    scenario.onActivity { activity ->
                        val active = field<Player>(activity, "player")
                        assertNull(active.playerError)
                        active.pause()
                        assertTrue(field<PlaybackSpec>(activity, "spec").subtitles.any { it.id == SUBTITLE_ID })
                    }
                    if (verifyResize) verifyExoResizeAndFallback(scenario)
                    fixture.assertHealthy()
                }
            } finally {
                fixture.releaseHeaders.countDown(); fixture.releaseTail.countDown()
                sessions.clear(fixture.origin)
                encrypted.edit().apply {
                    if (oldLastServer == null) remove("last_server") else putString("last_server", oldLastServer)
                }.commit()
                restore(settings, oldSettings)
            }
        }
    }

    private fun verifyExoResizeAndFallback(scenario: ActivityScenario<NativePlayerActivity>) {
        lateinit var original: ExoPlayer
        var sourcePosition = 0L
        scenario.onActivity {
            original = field<Player>(it, "player") as ExoPlayer
            sourcePosition = original.currentPosition
            assertEquals(1.1f, field<Float>(it, "resizeScale"), 0f)
        }
        val choices = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to 1.1f,
            AspectRatioFrameLayout.RESIZE_MODE_FIT to 1.25f,
            AspectRatioFrameLayout.RESIZE_MODE_FIT to 1.5f,
            AspectRatioFrameLayout.RESIZE_MODE_FIT to 1f,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to 1f,
            AspectRatioFrameLayout.RESIZE_MODE_FILL to 1f,
            AspectRatioFrameLayout.RESIZE_MODE_FIT to 1.1f)
        choices.forEachIndexed { index, (mode, scale) ->
            if (index > 0) scenario.onActivity {
                assertTrue(it.findViewById<ImageButton>(R.id.player_resize).performClick())
            }
            waitFor(scenario, "The video surface must complete its fit/crop/stretch layout", 2_000) { activity ->
                val playerView = field<PlayerView>(activity, "playerView")
                val surface = playerView.videoSurfaceView!!
                when (mode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> surface.width <= playerView.width + 2 && surface.height <= playerView.height + 2
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> surface.width >= playerView.width - 2 && surface.height >= playerView.height - 2
                    else -> surface.width == playerView.width && surface.height == playerView.height
                }
            }
            scenario.onActivity { activity ->
                val playerView = field<PlayerView>(activity, "playerView")
                val surface = playerView.videoSurfaceView!!
                val content = playerView.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame)
                assertSame("Screen-size choices must keep the same Exo decoder and stream", original, field<Player>(activity, "player"))
                assertFalse("Resizing a paused source must not resume playback", original.playWhenReady)
                assertTrue("Resizing must not seek or reopen the source", kotlin.math.abs(original.currentPosition - sourcePosition) < 150)
                assertEquals(mode, playerView.resizeMode)
                assertEquals(scale, surface.scaleX, 0f); assertEquals(scale, surface.scaleY, 0f)
                assertEquals(1f, playerView.scaleX, 0f); assertEquals(1f, playerView.scaleY, 0f)
                assertEquals(1f, content.scaleX, 0f); assertEquals(1f, content.scaleY, 0f)
                val subtitle = field<PlexSubtitleOverlayView>(activity, "subtitleOverlay")
                assertEquals(1f, subtitle.scaleX, 0f); assertEquals(1f, subtitle.scaleY, 0f)
                assertEquals(SUBTITLE, subtitle.subtitleText)
                val controls = playerView.findViewById<ImageButton>(R.id.player_resize)
                assertEquals(1f, controls.scaleX, 0f); assertEquals(1f, controls.scaleY, 0f)
                assertTrue("The final viewport must crop only the overflowing video", playerView.clipChildren)
                assertFalse("Zoom may use the video's former letterbox space", content.clipChildren)
                assertTrue(surface.width > 0 && surface.height > 0)
                if (mode != AspectRatioFrameLayout.RESIZE_MODE_FILL) {
                    assertEquals("Fit and crop must preserve the original video aspect ratio",
                        160.0 / 90, surface.width.toDouble() / surface.height, 0.025)
                } else {
                    assertEquals(playerView.width, surface.width)
                    assertEquals(playerView.height, surface.height)
                }
                val settings = activity.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
                assertEquals(mode, settings.getInt("resize_mode", -1))
                assertEquals(scale, settings.getFloat("resize_scale", -1f), 0f)
            }
        }
        scenario.onActivity { activity ->
            original.play()
            assertTrue(activity.findViewById<ImageButton>(R.id.player_resize).performClick())
            assertSame("Live resizing must retain the same Exo engine", original, field<Player>(activity, "player"))
            assertTrue(original.playWhenReady)
            assertEquals(1.25f, field<PlayerView>(activity, "playerView").videoSurfaceView!!.scaleX, 0f)
        }
        waitFor(scenario, "Playback must continue advancing during live resizing", 2_000) {
            field<Player>(it, "player") === original && original.isPlaying && original.currentPosition >= sourcePosition + 150
        }
        scenario.onActivity { activity ->
            original.pause()
            sourcePosition = original.currentPosition
            // Return the preference to 110% before exercising the fallback.
            repeat(5) { assertTrue(activity.findViewById<ImageButton>(R.id.player_resize).performClick()) }
            assertEquals(1.1f, field<Float>(activity, "resizeScale"), 0f)
        }
        scenario.onActivity { activity ->
            NativePlayerActivity::class.java.getDeclaredMethod("fallbackFromExo", Player::class.java)
                .apply { isAccessible = true }.invoke(activity, original)
        }
        waitFor(scenario, "A real engine fallback must restore MPV without double-applying Exo zoom", 10_000) {
            val active = field<Player?>(it, "player")
            active is MpvPlayer && active.playbackState == Player.STATE_READY
        }
        scenario.onActivity { activity ->
            val active = field<Player>(activity, "player")
            val playerView = field<PlayerView>(activity, "playerView")
            assertFalse(active.playWhenReady)
            assertTrue(kotlin.math.abs(active.currentPosition - sourcePosition) < 500)
            assertEquals(1f, playerView.videoSurfaceView!!.scaleX, 0f)
            assertEquals(1f, playerView.videoSurfaceView!!.scaleY, 0f)
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, playerView.resizeMode)
            val zoom = MPVLib.getPropertyDouble("video-zoom")
                ?: MPVLib.getPropertyString("video-zoom")?.toDoubleOrNull()
            assertNotNull(zoom)
            assertEquals("MPV must apply the saved zoom exactly once", kotlin.math.ln(1.1) / kotlin.math.ln(2.0), zoom!!, 0.001)
        }
    }

    @Test fun hiddenNativeSubtitleRejectsHeaderHandoffAndContinuesToProduceKoreanCues() {
        val context = instrumentation.targetContext
        requireSyntheticEnvironment(context)
        val clip = File(context.cacheDir, "header-hidden-subtitle.mkv")
        instrumentation.context.assets.open("media3/native-subtitle.mkv").use { input ->
            clip.outputStream().use(input::copyTo)
        }
        var player: MpvPlayer? = null
        var view: PlayerView? = null
        val headersChecked = AtomicBoolean()
        val rejected = AtomicBoolean()
        val caption = AtomicReference("")
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val video = PlayerView(activity).apply { useController = false }
                        activity.setContentView(video)
                        val native = MpvPlayer(activity).apply {
                            volume = 0f; setAudioAttributes(AudioAttributes.DEFAULT, false)
                            onNativeHeadersReady = {
                                rejected.set(manualExoEvidence(emptyList(), null, null) == null && exoHeaderEvidence() == null)
                                headersChecked.set(true)
                            }
                            onNativeSubtitleText = { caption.set(it) }
                            trackSelectionParameters = trackSelectionParameters.buildUpon()
                                .setPreferredTextLanguages("ko", "kor").setSelectUndeterminedTextLanguage(true).build()
                        }
                        player = native; view = video; video.player = native
                        native.setMediaItem(PlayerItem.fromUri(Uri.fromFile(clip))); native.prepare(); native.play()
                    }
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (SystemClock.elapsedRealtime() < deadline &&
                        !(headersChecked.get() && caption.get() == "한글 내장 자막 확인")) Thread.sleep(40)
                    assertTrue("Native headers must be observed", headersChecked.get())
                    assertTrue("A hidden non-mov_text subtitle must prevent the Exo handoff", rejected.get())
                    assertEquals("The retained MPV engine must still decode the embedded cue", "한글 내장 자막 확인", caption.get())
                    main { assertNull(player!!.playerError) }
                } finally { main { view?.player = null; player?.release() } }
            }
        } finally { clip.delete() }
    }

    private fun requireSyntheticEnvironment(context: Context) {
        assumeTrue("Only the isolated synthetic QA package may use these fixtures", context.packageName.endsWith(".spoilerqa"))
        assertFalse("Unlock the device before exercising a real rendering surface", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
    }

    private fun waitFor(scenario: ActivityScenario<NativePlayerActivity>, message: String, timeoutMs: Long,
        predicate: (NativePlayerActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var matched = false
            scenario.onActivity { matched = predicate(it) }
            if (matched) return
            Thread.sleep(25)
        }
        fail(message)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: NativePlayerActivity, name: String): T =
        NativePlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, Any?>) {
        preferences.edit().apply {
            for ((key, value) in values) when (value) {
                null -> remove(key)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
            }
        }.commit()
    }

    /** Fast-start index and a short prefix are available; all later ranges share the same gate. */
    private class HeaderFixture(private val clip: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        private val workers = Executors.newFixedThreadPool(6) { task -> Thread(task, "header-qa-http").apply { isDaemon = true } }
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val failure = AtomicReference<Throwable?>()
        val streamRequested = CountDownLatch(1)
        val releaseHeaders = CountDownLatch(1)
        val releaseTail = CountDownLatch(1)
        val origin get() = "http://127.0.0.1:${server.localPort}"
        private val prefixBytes: Int = run {
            val input = ByteBuffer.wrap(clip).order(ByteOrder.BIG_ENDIAN)
            var offset = 0
            var payload = -1
            while (offset + 8 <= clip.size) {
                val size = input.getInt(offset)
                check(size >= 8 && size <= clip.size - offset)
                if (String(clip, offset + 4, 4, Charsets.US_ASCII) == "mdat") { payload = offset + 8; break }
                offset += size
            }
            check(payload >= 0)
            payload + (clip.size - payload) / 10
        }
        private val listener = thread(name = "header-qa-loopback", isDaemon = true) {
            while (!server.isClosed) {
                try {
                    val socket = server.accept(); sockets += socket
                    workers.execute { respond(socket) }
                } catch (_: SocketTimeoutException) { }
                catch (error: SocketException) { if (!server.isClosed) failure.compareAndSet(null, error) }
            }
        }

        fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic header fixture failed", it) } }

        private fun respond(socket: Socket) {
            var streaming = false
            try {
                socket.use { connection ->
                    connection.soTimeout = 3_000
                    val input = BufferedInputStream(connection.getInputStream())
                    val request = line(input)?.split(' ') ?: return
                    check(request.size == 3)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val row = line(input) ?: error("Incomplete synthetic headers")
                        if (row.isEmpty()) break
                        check(headers.size < 64)
                        val split = row.indexOf(':'); check(split > 0)
                        headers[row.substring(0, split).lowercase()] = row.substring(split + 1).trim()
                    }
                    val bodyLength = headers["content-length"]?.toInt() ?: 0
                    check(bodyLength in 0..65_536)
                    repeat(bodyLength) { check(input.read() >= 0) }
                    when (Uri.parse(request[1]).path) {
                        "/stream/$PART" -> {
                            streaming = true; streamRequested.countDown()
                            check(releaseHeaders.await(20, TimeUnit.SECONDS))
                            video(connection, request[0], headers["range"])
                        }
                        "/subtitles/$PART" -> json(connection, JSONArray().put(JSONObject()
                            .put("id", SUBTITLE_ID).put("source", "external").put("format", "srt")
                            .put("lang_code", "kor").put("label", "합성 한국어")))
                        "/subtitles/raw/$SUBTITLE_ID" -> bytes(connection, "application/x-subrip",
                            "1\n00:00:00,000 --> 00:00:06,000\n$SUBTITLE\n".toByteArray())
                        "/libraries/$VIEW/media_info/$PART" -> json(connection, JSONObject()
                            .put("item", JSONObject().put("id", ITEM)).put("markers", JSONArray())
                            .put("parts", JSONArray().put(JSONObject().put("id", PART).put("metadata_item_id", ITEM)
                                .put("file_path", "/synthetic/header/fixture.mp4").put("container", "mp4")
                                .put("video_codec", "h264").put("video_bit_depth", 8).put("hdr_format", "sdr")
                                .put("width", 160).put("height", 90).put("fps", 24)
                                .put("audio_codec", "aac").put("audio_channels", 2).put("subtitles", JSONArray()))))
                        "/libraries/$VIEW/movies" -> json(connection, JSONArray())
                        "/playback/progress" -> json(connection, JSONObject().put("ok", true))
                        else -> bytes(connection, "application/json", "{}".toByteArray(), "404 Not Found")
                    }
                }
            } catch (error: SocketException) {
                if (!streaming && !server.isClosed) failure.compareAndSet(null, error)
            } catch (error: Throwable) { if (!server.isClosed) failure.compareAndSet(null, error) }
            finally { sockets -= socket }
        }

        private fun video(socket: Socket, method: String, rangeHeader: String?) {
            val range = rangeHeader?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) ?: error("Unsupported synthetic range") }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(clip.lastIndex) ?: clip.lastIndex
            val output = socket.getOutputStream()
            if (start !in clip.indices || end < start) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${clip.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush()
                return
            }
            val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${clip.size}\r\n"
            output.write("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n${contentRange}Connection: close\r\n\r\n".toByteArray())
            output.flush()
            if (method == "HEAD") return
            var position = start
            while (position <= end && !server.isClosed) {
                if (position >= prefixBytes) check(releaseTail.await(20, TimeUnit.SECONDS))
                val count = minOf(8_192, end - position + 1, if (position < prefixBytes) prefixBytes - position else Int.MAX_VALUE)
                output.write(clip, position, count); output.flush(); position += count
            }
        }

        private fun json(socket: Socket, data: Any) = bytes(socket, "application/json", data.toString().toByteArray())
        private fun bytes(socket: Socket, type: String, data: ByteArray, status: String = "200 OK") {
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(data); flush()
            }
        }
        private fun line(input: BufferedInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 8_192) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else error("Incomplete synthetic request")
                if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                bytes.write(value)
            }
            error("Oversized synthetic request")
        }
        override fun close() {
            server.close(); releaseHeaders.countDown(); releaseTail.countDown()
            synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            listener.join(1_000); workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val ITEM = 11
        const val VIEW = 5
        const val VIEW_ITEM = 17
        const val PART = 77
        const val SUBTITLE_ID = 7
        const val RESUME_MS = 1_500L
        const val SESSION = "synthetic-header-token"
        const val TICKET = "synthetic-header-ticket"
        const val SUBTITLE = "엔진 전환 후 한국어 자막 확인"
    }
}
