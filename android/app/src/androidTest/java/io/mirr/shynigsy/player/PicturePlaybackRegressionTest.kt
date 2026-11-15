package io.mirr.shynigsy.player

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.ListView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.player.mpv.MpvPlayer
import `is`.xyz.mpv.MPVLib
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
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
import kotlin.math.abs

/** Decodes synthetic video through the real player Activity; never opens an account or remote server. */
@RunWith(AndroidJUnit4::class)
class PicturePlaybackRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun brightPresetReplacesVisibleExoWithVisibleMpvAndPreservesPausedPlayback() = withFixture { fixture, settings ->
        launch(fixture).use { scenario ->
            awaitVisibleAndPause(scenario, useExo = true)
            assertRenderedColor(scenario)
            var oldPosition = 0L
            lateinit var oldPlayer: Player
            scenario.onActivity { activity ->
                oldPlayer = field(activity, "player")
                oldPosition = oldPlayer.currentPosition
                assertFalse(oldPlayer.playWhenReady)
                assertEquals(SPEED, oldPlayer.playbackParameters.speed, 0.001f)
                NativePlayerActivity::class.java.getDeclaredMethod("showPictureSettings")
                    .apply { isAccessible = true }.invoke(activity)
                val dialog = field<PictureSettingsDialog>(activity, "pictureDialog").dialog
                assertTrue(dialog.window!!.decorView.findViewWithTag<View>("picture-presets").performClick())
                assertTrue(dialog.window!!.decorView.findViewWithTag<View>("picture-builtin-builtin_bright").performClick())
                assertTrue(dialog.window!!.decorView.findViewWithTag<View>("picture-close").performClick())
            }
            waitFor(scenario, "The selected preset must replace Exo with a ready, visible MPV frame") { activity ->
                val active = field<Player?>(activity, "player")
                active is MpvPlayer && active.playbackState == Player.STATE_READY &&
                    field<Boolean>(activity, "playbackFirstFrameReported")
            }
            scenario.onActivity { activity ->
                val active = field<Player>(activity, "player")
                assertNotSame(oldPlayer, active)
                assertFalse("Changing the picture must not resume a paused film", active.playWhenReady)
                assertTrue("The source position must survive engine replacement", abs(active.currentPosition - oldPosition) < 800)
                assertEquals(SPEED, active.playbackParameters.speed, 0.001f)
                assertEquals("exo", settings.getString("playback_engine", null))
                assertEquals(BRIGHT, PicturePresetStore(settings).readCurrent())
                assertEquals(PART, field<PlaybackSpec>(activity, "spec").partId)
                assertPictureProperties(BRIGHT)
            }
            assertRenderedColor(scenario)
            scenario.onActivity { field<Player>(it, "player").play() }
            waitFor(scenario, "Playback must continue from the preserved position after applying the preset") {
                val active = field<Player>(it, "player")
                active is MpvPlayer && active.isPlaying && active.currentPosition >= oldPosition + 150
            }
            scenario.onActivity { field<Player>(it, "player").pause() }
        }
    }

    @Test fun savedCorrectionStartsInMpvAndNeutralNextPlaybackReturnsToVisibleExo() = withFixture(BRIGHT) { fixture, settings ->
        launch(fixture).use { scenario ->
            assertTrue("The native engine must request the fixture", fixture.streamRequested.await(10, TimeUnit.SECONDS))
            var initial: Player? = null
            scenario.onActivity { activity ->
                initial = field(activity, "player")
                assertTrue("A saved non-neutral preset must select MPV directly", initial is MpvPlayer)
                assertFalse("Corrected video must not start an Exo inspection handoff", field(activity, "mpvProbePending"))
                assertFalse(field(activity, "manualExoProbePending"))
            }
            awaitVisibleAndPause(scenario, useExo = false)
            scenario.onActivity { activity ->
                assertSame("The initial native renderer must remain the active engine", initial, field<Player>(activity, "player"))
                assertEquals("exo", settings.getString("playback_engine", null))
                assertPictureProperties(BRIGHT)
            }
            assertRenderedColor(scenario)
        }
        // A newly opened neutral source must use the unchanged saved engine preference.
        PicturePresetStore(settings).writeCurrent(PictureSettings())
        launch(fixture).use { scenario ->
            awaitVisibleAndPause(scenario, useExo = true)
            scenario.onActivity { activity ->
                assertEquals(PictureSettings(), field<PictureSettings>(activity, "pictureSettings"))
                assertEquals("exo", settings.getString("playback_engine", null))
                assertNull(field<Player>(activity, "player").playerError)
            }
            assertRenderedColor(scenario)
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun engineMenuImmediatelySwitchesBothWaysAndKeepsPositionPauseSpeedAndSavedPresets() = withFixture(BRIGHT) { fixture, settings ->
        val preset = PicturePresetStore(settings).save("엔진 전환 검사", BRIGHT)
        launch(fixture).use { scenario ->
            awaitVisibleAndPause(scenario, useExo = false)
            var pausedPosition = 0L
            var previous: Player? = null
            scenario.onActivity { activity ->
                previous = field(activity, "player")
                pausedPosition = previous!!.currentPosition
                assertEquals(SPEED, previous!!.playbackParameters.speed, 0.001f)
                assertPictureProperties(BRIGHT)
            }
            selectEngine(scenario, PlaybackEnginePreference.EXO, clearsCorrection = true)
            awaitVisibleAndPause(scenario, useExo = true, pauseWhenReady = false)
            scenario.onActivity { activity ->
                val active = field<Player>(activity, "player")
                assertNotSame("Engine selection must replace the playing renderer immediately", previous, active)
                assertFalse("Changing engines must preserve pause", active.playWhenReady)
                assertTrue("Switching to Exo must retain the source position", abs(active.currentPosition - pausedPosition) < 800)
                assertEquals(SPEED, active.playbackParameters.speed, 0.001f)
                assertEquals("exo", settings.getString("playback_engine", null))
                assertEquals(PlaybackEnginePreference.EXO, field<PlaybackEnginePreference>(activity, "enginePreferenceForContent"))
                assertEquals(PictureSettings(), field<PictureSettings>(activity, "pictureSettings"))
                assertEquals(PictureSettings(), PicturePresetStore(settings).readCurrent())
                assertEquals("An explicit Exo selection must retain named custom presets", listOf(preset), PicturePresetStore(settings).list())
                pausedPosition = active.currentPosition
                previous = active
            }
            assertRenderedColor(scenario)
            selectEngine(scenario, PlaybackEnginePreference.MPV)
            awaitVisibleAndPause(scenario, useExo = false, pauseWhenReady = false)
            scenario.onActivity { activity ->
                val active = field<Player>(activity, "player")
                assertNotSame("Returning to MPV must apply inside the same playback Activity", previous, active)
                assertFalse(active.playWhenReady)
                assertTrue("Switching back to MPV must retain the source position", abs(active.currentPosition - pausedPosition) < 800)
                assertEquals(SPEED, active.playbackParameters.speed, 0.001f)
                assertEquals("mpv", settings.getString("playback_engine", null))
                assertEquals(PlaybackEnginePreference.MPV, field<PlaybackEnginePreference>(activity, "enginePreferenceForContent"))
                assertEquals(listOf(preset), PicturePresetStore(settings).list())
                assertPictureProperties(PictureSettings())
            }
            assertRenderedColor(scenario)
        }
    }

    private fun selectEngine(scenario: ActivityScenario<NativePlayerActivity>, preference: PlaybackEnginePreference,
        clearsCorrection: Boolean = false) {
        scenario.onActivity { activity ->
            NativePlayerActivity::class.java.getDeclaredMethod("showPlaybackEnginePreference")
                .apply { isAccessible = true }.invoke(activity)
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            val root = requireNotNull(WindowInspector.getGlobalWindowViews().firstOrNull {
                it.tag == "playback-engine-choice" && it.isShown
            }) { "The production engine selection dialog must be visible" }
            fun findList(view: View): ListView? {
                if (view is ListView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) {
                    findList(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            val list = requireNotNull(findList(root))
            val index = PlaybackEnginePreference.entries.indexOf(preference)
            if (clearsCorrection) assertTrue("The Exo choice must explain the correction reset",
                list.adapter.getItem(index).toString().contains("보정 해제"))
            assertTrue(list.performItemClick(requireNotNull(list.getChildAt(index - list.firstVisiblePosition)), index, list.adapter.getItemId(index)))
        }
    }

    private fun launch(fixture: VideoFixture): ActivityScenario<NativePlayerActivity> {
        val item = MediaItem(ITEM, LIBRARY, "합성 영상 보정 검사", "movie", durationMs = 6_000,
            viewItemId = VIEW_ITEM, playbackPartId = PART)
        val spec = PlaybackSpec(item.title, "${fixture.origin}/stream/$PART?ticket=picture-synthetic-ticket",
            itemId = ITEM, libraryId = LIBRARY, partId = PART, viewItemId = VIEW_ITEM,
            resumeMs = 1_000, durationMs = 6_000, sessionId = "picture-synthetic-session",
            progressToken = "picture-synthetic-context", sourceItem = item, subtitlesPending = true)
        return ActivityScenario.launch<NativePlayerActivity>(Intent(instrumentation.targetContext, NativePlayerActivity::class.java)
            .putExtra("server", fixture.origin).putExtra("spec", spec.toJson())).also { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    private fun awaitVisibleAndPause(scenario: ActivityScenario<NativePlayerActivity>, useExo: Boolean,
        pauseWhenReady: Boolean = true) {
        waitFor(scenario, "The ${if (useExo) "Exo" else "MPV"} renderer must decode an actual video frame") { activity ->
            val active = field<Player?>(activity, "player")
            if (active != null) active.volume = 0f
            val correctEngine = if (useExo) active is ExoPlayer else active is MpvPlayer
            val ready = correctEngine && active != null && active.playbackState == Player.STATE_READY &&
                field<Boolean>(activity, "playbackFirstFrameReported") &&
                (!useExo || field<Boolean>(activity, "exoValidated")) && active.videoSize.width > 0
            if (ready && pauseWhenReady) active!!.pause()
            ready
        }
    }

    /** Surface pixels exclude controls, text, and the Activity background, so audio-only playback cannot pass. */
    private fun assertRenderedColor(scenario: ActivityScenario<NativePlayerActivity>) {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        var status = -1
        var colors = 0
        try {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            do {
                val copied = CountDownLatch(1)
                val result = AtomicInteger(-1)
                scenario.onActivity { activity ->
                    val surface = field<PlayerView>(activity, "playerView").videoSurfaceView as SurfaceView
                    assertTrue("The playback surface must remain valid", surface.holder.surface.isValid)
                    PixelCopy.request(surface, bitmap, { value -> result.set(value); copied.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue("Video surface capture did not finish", copied.await(3, TimeUnit.SECONDS))
                status = result.get()
                colors = 0
                if (status == PixelCopy.SUCCESS) {
                    for (y in 0 until bitmap.height step 12) for (x in 0 until bitmap.width step 16) {
                        val color = bitmap.getPixel(x, y)
                        if (Color.red(color) > 24 || Color.green(color) > 24 || Color.blue(color) > 24) colors++
                    }
                    if (colors > 15) return
                }
                Thread.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertEquals("PixelCopy must capture the decoded video surface", PixelCopy.SUCCESS, status)
            assertTrue("Video is black despite a reported first frame", colors > 15)
        } finally { bitmap.recycle() }
    }

    private fun assertPictureProperties(settings: PictureSettings) {
        fun property(name: String) = MPVLib.getPropertyDouble(name)
            ?: MPVLib.getPropertyString(name)?.toDoubleOrNull() ?: error("Missing native picture property $name")
        assertEquals(settings.brightness.toDouble(), property("brightness"), 0.01)
        assertEquals(settings.contrast.toDouble(), property("contrast"), 0.01)
        assertEquals(settings.saturation.toDouble(), property("saturation"), 0.01)
    }

    private fun withFixture(initial: PictureSettings = PictureSettings(), action: (VideoFixture, SharedPreferences) -> Unit) {
        val context = instrumentation.targetContext
        assertEquals("Synthetic playback must never run in the real account application", "io.mirr.shyniflex.spoilerqa", context.packageName)
        assertFalse("Unlock the device before checking video output", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val settings = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val keys = setOf("playback_engine", "playback_speed", "resize_mode", "resize_scale", "subtitle_vertical",
            "picture_settings_v1", "picture_presets_v1")
        val previous = keys.associateWith { settings.all[it] }
        val encrypted = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
        val previousServer = encrypted.getString("last_server", null)
        val sessions = SessionStore(context)
        VideoFixture(instrumentation.context.assets.open("media3/gesture-av.mp4").use { it.readBytes() }).use { fixture ->
            try {
                settings.edit().putString("playback_engine", "exo").putFloat("playback_speed", SPEED)
                    .putInt("resize_mode", 0).putFloat("resize_scale", 1f).putBoolean("subtitle_vertical", false)
                    .putString("picture_settings_v1", encodePictureSettings(initial)).remove("picture_presets_v1").commit()
                sessions.save(fixture.origin, "picture-synthetic-token", "합성 보정 검사")
                action(fixture, settings)
                fixture.assertHealthy()
            } finally {
                sessions.clear(fixture.origin)
                encrypted.edit().apply {
                    if (previousServer == null) remove("last_server") else putString("last_server", previousServer)
                }.commit()
                settings.edit().apply {
                    for ((key, value) in previous) when (value) {
                        null -> remove(key)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }.commit()
            }
        }
    }

    private fun waitFor(scenario: ActivityScenario<NativePlayerActivity>, message: String, predicate: (NativePlayerActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var matched = false
            scenario.onActivity { activity ->
                field<Player?>(activity, "player")?.playerError?.let { throw AssertionError(message, it) }
                matched = predicate(activity)
            }
            if (matched) return
            Thread.sleep(25)
        }
        fail(message)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: NativePlayerActivity, name: String): T =
        NativePlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private class VideoFixture(private val clip: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        private val workers = Executors.newFixedThreadPool(6) { task -> Thread(task, "picture-qa-http").apply { isDaemon = true } }
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val failure = AtomicReference<Throwable?>()
        val streamRequested = CountDownLatch(1)
        val origin get() = "http://127.0.0.1:${server.localPort}"
        private val listener = thread(name = "picture-qa-loopback", isDaemon = true) {
            while (!server.isClosed) {
                try {
                    val socket = server.accept(); sockets += socket
                    workers.execute { respond(socket) }
                } catch (_: SocketTimeoutException) { }
                catch (error: SocketException) { if (!server.isClosed) failure.compareAndSet(null, error) }
            }
        }

        fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic picture server failed", it) } }

        private fun respond(socket: Socket) {
            var video = false
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
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..65_536)
                    repeat(length) { check(input.read() >= 0) }
                    when (Uri.parse(request[1]).path) {
                        "/stream/$PART" -> { video = true; streamRequested.countDown(); stream(connection, request[0], headers["range"]) }
                        "/subtitles/$PART" -> json(connection, JSONArray())
                        "/libraries/$LIBRARY/media_info/$PART" -> json(connection, JSONObject()
                            .put("item", JSONObject().put("id", ITEM)).put("markers", JSONArray())
                            .put("parts", JSONArray().put(JSONObject().put("id", PART).put("metadata_item_id", ITEM)
                                .put("file_path", "/synthetic/picture/fixture.mp4").put("container", "mp4")
                                .put("video_codec", "h264").put("video_bit_depth", 8).put("hdr_format", "sdr")
                                .put("width", 160).put("height", 90).put("fps", 24)
                                .put("audio_codec", "aac").put("audio_channels", 2).put("subtitles", JSONArray()))))
                        "/libraries/$LIBRARY/movies" -> json(connection, JSONArray())
                        "/playback/progress" -> json(connection, JSONObject().put("ok", true))
                        else -> bytes(connection, "application/json", "{}".toByteArray(), "404 Not Found")
                    }
                }
            } catch (error: SocketException) {
                if (!video && !server.isClosed) failure.compareAndSet(null, error)
            } catch (error: Throwable) { if (!server.isClosed) failure.compareAndSet(null, error) }
            finally { sockets -= socket }
        }

        private fun stream(socket: Socket, method: String, rangeHeader: String?) {
            val range = rangeHeader?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) ?: error("Invalid synthetic range") }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(clip.lastIndex) ?: clip.lastIndex
            val output = socket.getOutputStream()
            if (start !in clip.indices || end < start) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${clip.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush()
                return
            }
            val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${clip.size}\r\n"
            output.write("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n${contentRange}Connection: close\r\n\r\n".toByteArray())
            if (method != "HEAD") output.write(clip, start, end - start + 1)
            output.flush()
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
            server.close()
            synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            listener.join(1_000); workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val ITEM = 11
        const val LIBRARY = 5
        const val VIEW_ITEM = 17
        const val PART = 77
        const val SPEED = 1.25f
        val BRIGHT = PictureSettings(12, -5, 0)
    }
}
