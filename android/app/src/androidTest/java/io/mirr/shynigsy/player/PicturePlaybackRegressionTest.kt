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
import androidx.media3.common.C
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
import kotlin.math.sqrt

/** Decodes synthetic video through the real player Activity; never opens an account or remote server. */
@RunWith(AndroidJUnit4::class)
class PicturePlaybackRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun firstBrightPresetEnablesVisibleExoEffectsAndPreservesPausedPlayback() = withFixture { fixture, settings ->
        launch(fixture).use { scenario ->
            awaitVisibleAndPause(scenario, useExo = true)
            assertRenderedColor(scenario)
            val originalFrame = awaitFrame(scenario, "The original Exo frame must be visible") { it.coloredSamples > 15 }
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
                assertTrue(dialog.window!!.decorView.findViewWithTag<View>("picture-lg-lg_bright_room").performClick())
                assertTrue(dialog.window!!.decorView.findViewWithTag<View>("picture-close").performClick())
            }
            waitFor(scenario, "The first preset must prepare a corrected Exo renderer with visible video") { activity ->
                val active = field<Player?>(activity, "player")
                active is ExoPlayer && active !== oldPlayer && active.playbackState == Player.STATE_READY &&
                    field<Boolean>(activity, "playbackFirstFrameReported") && field<Boolean>(activity, "exoValidated")
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
                assertEquals(BRIGHT, field<PictureSettings>(activity, "pictureSettings"))
            }
            awaitFrame(scenario, "Brightness must change the paused video pixels, not only the saved setting") {
                it.coloredSamples > 15 && it.meanLuma > originalFrame.meanLuma + 3
            }
            scenario.onActivity { field<Player>(it, "player").play() }
            waitFor(scenario, "Playback must continue from the preserved position after applying the preset") {
                val active = field<Player>(it, "player")
                active is ExoPlayer && active.isPlaying && active.currentPosition >= oldPosition + 150
            }
            scenario.onActivity { field<Player>(it, "player").pause() }
        }
    }

    @Test fun savedCorrectionStartsInVisibleExoAndSurvivesRecreation() = withFixture(BRIGHT) { fixture, settings ->
        launch(fixture).use { scenario ->
            assertTrue("The requested renderer must request the fixture", fixture.streamRequested.await(10, TimeUnit.SECONDS))
            awaitVisibleAndPause(scenario, useExo = true)
            var position = 0L
            scenario.onActivity { activity ->
                position = field<Player>(activity, "player").currentPosition
                assertEquals("exo", settings.getString("playback_engine", null))
                assertEquals(BRIGHT, field<PictureSettings>(activity, "pictureSettings"))
            }
            assertRenderedColor(scenario)
            scenario.recreate()
            awaitVisibleAndPause(scenario, useExo = true, pauseWhenReady = false)
            scenario.onActivity { activity ->
                val active = field<Player>(activity, "player")
                assertFalse(active.playWhenReady)
                assertTrue(abs(active.currentPosition - position) < 800)
                assertEquals(SPEED, active.playbackParameters.speed, .001f)
                assertEquals(BRIGHT, field<PictureSettings>(activity, "pictureSettings"))
                assertEquals(BRIGHT, PicturePresetStore(settings).readCurrent())
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
        settings.edit().putString("playback_engine", "mpv").commit()
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
            selectEngine(scenario, PlaybackEnginePreference.EXO)
            awaitVisibleAndPause(scenario, useExo = true, pauseWhenReady = false)
            scenario.onActivity { activity ->
                val active = field<Player>(activity, "player")
                assertNotSame("Engine selection must replace the playing renderer immediately", previous, active)
                assertFalse("Changing engines must preserve pause", active.playWhenReady)
                assertTrue("Switching to Exo must retain the source position", abs(active.currentPosition - pausedPosition) < 800)
                assertEquals(SPEED, active.playbackParameters.speed, 0.001f)
                assertEquals("exo", settings.getString("playback_engine", null))
                assertEquals(PlaybackEnginePreference.EXO, field<PlaybackEnginePreference>(activity, "enginePreferenceForContent"))
                assertEquals(BRIGHT, field<PictureSettings>(activity, "pictureSettings"))
                assertEquals(BRIGHT, PicturePresetStore(settings).readCurrent())
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
                assertEquals(BRIGHT, field<PictureSettings>(activity, "pictureSettings"))
                assertEquals(BRIGHT, PicturePresetStore(settings).readCurrent())
                assertPictureProperties(BRIGHT)
            }
            assertRenderedColor(scenario)
        }
    }

    @Test fun pausedExoUpdatesBrightnessContrastSaturationAndNeutralWithoutReplacingItsRenderer() = withFixture(BRIGHT) { fixture, settings ->
        val store = PicturePresetStore(settings)
        val neutral = store.save("원본 픽셀 검사", PictureSettings())
        val brightness = store.save("밝기 픽셀 검사", PictureSettings(brightness = 30))
        val contrast = store.save("대비 픽셀 검사", PictureSettings(contrast = -35))
        val saturation = store.save("채도 픽셀 검사", PictureSettings(saturation = -100))
        launch(fixture).use { scenario ->
            awaitVisibleAndPause(scenario, useExo = true)
            lateinit var initial: Player
            var position = 0L
            scenario.onActivity { activity ->
                initial = field(activity, "player")
                position = initial.currentPosition
            }
            val corrected = awaitFrame(scenario, "Saved correction must produce visible video") { it.coloredSamples > 15 }
            selectPicturePreset(scenario, neutral)
            val original = awaitFrame(scenario, "Restoring original must redraw the paused video") {
                it.coloredSamples > 15 && it.meanLuma < corrected.meanLuma - 3
            }
            assertTrue("The synthetic fixture must contain colors for saturation comparison", original.meanChroma > 20)
            assertTrue("The synthetic fixture must contain contrast for comparison", original.lumaDeviation > 20)

            fun assertPreserved(expected: PictureSettings) = scenario.onActivity { activity ->
                val active = field<Player>(activity, "player")
                assertSame("Changing an existing effect must keep the same Exo renderer", initial, active)
                assertTrue(active is ExoPlayer)
                assertFalse("A paused frame must redraw without resuming playback", active.playWhenReady)
                assertTrue("Live image correction must not seek the video", abs(active.currentPosition - position) < 150)
                assertEquals(SPEED, active.playbackParameters.speed, .001f)
                assertEquals(expected, field<PictureSettings>(activity, "pictureSettings"))
                assertEquals(expected, store.readCurrent())
                assertNull(active.playerError)
            }
            fun restoreOriginal() {
                selectPicturePreset(scenario, neutral)
                awaitFrame(scenario, "Original must remove the correction from the same paused frame") {
                    it.coloredSamples > 15 && it.meanDifference(original) < 6
                }
                assertPreserved(PictureSettings())
            }
            assertPreserved(PictureSettings())

            selectPicturePreset(scenario, brightness)
            awaitFrame(scenario, "Brightness must visibly increase the paused frame luminance") {
                it.coloredSamples > 15 && it.meanLuma > original.meanLuma + 8
            }
            assertPreserved(brightness.settings)
            restoreOriginal()

            selectPicturePreset(scenario, contrast)
            awaitFrame(scenario, "Lower contrast must reduce the paused frame luminance spread") {
                it.coloredSamples > 15 && it.lumaDeviation < original.lumaDeviation * .85
            }
            assertPreserved(contrast.settings)
            restoreOriginal()

            selectPicturePreset(scenario, saturation)
            awaitFrame(scenario, "Zero saturation must make the paused video grayscale") {
                it.coloredSamples > 15 && it.meanChroma < 6
            }
            assertPreserved(saturation.settings)
            restoreOriginal()
        }
    }

    private fun selectPicturePreset(scenario: ActivityScenario<NativePlayerActivity>, preset: PicturePreset) {
        scenario.onActivity { activity ->
            NativePlayerActivity::class.java.getDeclaredMethod("showPictureSettings")
                .apply { isAccessible = true }.invoke(activity)
            val dialog = field<PictureSettingsDialog>(activity, "pictureDialog").dialog
            fun click(tag: String) {
                val view = requireNotNull(dialog.window!!.decorView.findViewWithTag<View>(tag)) { "Missing picture action: $tag" }
                assertTrue("Picture action must be enabled: $tag", view.performClick())
            }
            click("picture-presets")
            click("picture-custom-${preset.id}")
            click("picture-apply")
            click("picture-close")
        }
    }

    private fun selectEngine(scenario: ActivityScenario<NativePlayerActivity>, preference: PlaybackEnginePreference) {
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
            if (preference == PlaybackEnginePreference.EXO) assertFalse("Selecting Exo must retain image correction",
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
                (!useExo || field<Boolean>(activity, "exoValidated")) && hasVideoDimensions(active)
            if (ready && pauseWhenReady) active!!.pause()
            ready
        }
    }

    private fun hasVideoDimensions(active: Player): Boolean {
        if (active.videoSize.width > 0 && active.videoSize.height > 0) return true
        if (active !is ExoPlayer) return false
        // Media3 1.10.1's effect video sink omits onVideoSizeChanged. The decoded format is
        // the dimensions fallback, while first-frame/validation and Surface PixelCopy remain required.
        if (active.videoFormat?.let { it.width > 0 && it.height > 0 } == true) return true
        return active.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { index ->
                group.isTrackSelected(index) && group.getTrackFormat(index).let { it.width > 0 && it.height > 0 }
            }
        }
    }

    /** Surface pixels exclude controls, text, and the Activity background, so audio-only playback cannot pass. */
    private fun assertRenderedColor(scenario: ActivityScenario<NativePlayerActivity>) {
        awaitFrame(scenario, "Video is black despite a reported first frame") { it.coloredSamples > 15 }
    }

    private fun awaitFrame(scenario: ActivityScenario<NativePlayerActivity>, message: String,
        predicate: (FrameSamples) -> Boolean): FrameSamples {
        waitFor(scenario, "Exo must preserve the synthetic video's 16:9 aspect ratio") { activity ->
            if (field<Player?>(activity, "player") !is ExoPlayer) true else {
                val content = field<PlayerView>(activity, "playerView")
                    .findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
                content != null && content.width > 0 && content.height > 0 &&
                    abs(content.width.toFloat() / content.height - 16f / 9f) < .025f
            }
        }
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        var status = -1
        var lastFrame: FrameSamples? = null
        var diagnostics = "not sampled"
        try {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            do {
                val copied = CountDownLatch(1)
                val result = AtomicInteger(-1)
                scenario.onActivity { activity ->
                    diagnostics = playerDiagnostics(activity)
                    val surface = field<PlayerView>(activity, "playerView").videoSurfaceView as SurfaceView
                    assertTrue("The playback surface must remain valid", surface.holder.surface.isValid)
                    PixelCopy.request(surface, bitmap, { value -> result.set(value); copied.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue("Video surface capture did not finish", copied.await(3, TimeUnit.SECONDS))
                status = result.get()
                if (status == PixelCopy.SUCCESS) {
                    lastFrame = FrameSamples.from(bitmap)
                    if (predicate(lastFrame)) return lastFrame
                }
                Thread.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertEquals("PixelCopy must capture the decoded video surface", PixelCopy.SUCCESS, status)
            throw AssertionError("$message; frame=$lastFrame; player=$diagnostics")
        } finally { bitmap.recycle() }
    }

    private class FrameSamples(private val pixels: IntArray) {
        private val luma = pixels.map { Color.red(it) * .2126 + Color.green(it) * .7152 + Color.blue(it) * .0722 }
        val meanLuma = luma.average()
        val lumaDeviation = sqrt(luma.sumOf { (it - meanLuma) * (it - meanLuma) } / luma.size)
        val meanChroma = pixels.map { maxOf(Color.red(it), Color.green(it), Color.blue(it)) -
            minOf(Color.red(it), Color.green(it), Color.blue(it)) }.average()
        val coloredSamples = pixels.count { Color.red(it) > 24 || Color.green(it) > 24 || Color.blue(it) > 24 }

        fun meanDifference(other: FrameSamples): Double {
            require(pixels.size == other.pixels.size)
            return pixels.indices.sumOf { index ->
                val left = pixels[index]; val right = other.pixels[index]
                abs(Color.red(left) - Color.red(right)) + abs(Color.green(left) - Color.green(right)) +
                    abs(Color.blue(left) - Color.blue(right))
            }.toDouble() / (pixels.size * 3)
        }

        override fun toString() = "luma=$meanLuma, spread=$lumaDeviation, chroma=$meanChroma, visible=$coloredSamples"

        companion object {
            fun from(bitmap: Bitmap): FrameSamples {
                val samples = ArrayList<Int>()
                // Avoid the surface edges; identical sample positions make neutral restoration measurable.
                for (y in bitmap.height / 10 until bitmap.height * 9 / 10 step 6) {
                    for (x in bitmap.width / 10 until bitmap.width * 9 / 10 step 8) samples += bitmap.getPixel(x, y)
                }
                return FrameSamples(samples.toIntArray())
            }
        }
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
            "picture_settings_v1", "picture_presets_v1", "picture_selected_preset_v1")
        val previous = keys.associateWith { settings.all[it] }
        val encrypted = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
        val previousServer = encrypted.getString("last_server", null)
        val sessions = SessionStore(context)
        VideoFixture(instrumentation.context.assets.open("media3/gesture-av.mp4").use { it.readBytes() }).use { fixture ->
            try {
                settings.edit().putString("playback_engine", "exo").putFloat("playback_speed", SPEED)
                    .putInt("resize_mode", 0).putFloat("resize_scale", 1f).putBoolean("subtitle_vertical", false)
                    .putString("picture_settings_v1", encodePictureSettings(initial)).remove("picture_presets_v1")
                    .remove("picture_selected_preset_v1").commit()
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
        var diagnostics = "not sampled"
        while (SystemClock.elapsedRealtime() < deadline) {
            var matched = false
            try {
                scenario.onActivity { activity ->
                    diagnostics = playerDiagnostics(activity)
                    field<Player?>(activity, "player")?.playerError?.let {
                        throw AssertionError("$message; player=$diagnostics")
                    }
                    matched = predicate(activity)
                }
            } catch (error: RuntimeException) {
                throw AssertionError("$message; Activity unavailable or callback failed; last player=$diagnostics", error)
            }
            if (matched) return
            Thread.sleep(25)
        }
        fail("$message; player=$diagnostics")
    }

    /** Dimensions and state only: never include source URLs, account information or renderer exception text. */
    private fun playerDiagnostics(activity: NativePlayerActivity): String {
        val active = field<Player?>(activity, "player") ?: return "engine=none"
        val format = (active as? ExoPlayer)?.videoFormat
        val content = field<PlayerView>(activity, "playerView")
            .findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
        val selected = active.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
            (0 until group.length).filter(group::isTrackSelected).map { index ->
                group.getTrackFormat(index).let { "${it.width}x${it.height}" }
            }
        }
        return "engine=${active.javaClass.simpleName}, state=${active.playbackState}, " +
            "size=${active.videoSize.width}x${active.videoSize.height}, " +
            "format=${format?.width}x${format?.height}, selected=${selected.take(4)}, " +
            "content=${content?.width}x${content?.height}, " +
            "prepared=${field<Boolean>(activity, "prepared")}, validated=${field<Boolean>(activity, "exoValidated")}, " +
            "frame=${field<Boolean>(activity, "playbackFirstFrameReported")}, ready=${active.playWhenReady}, " +
            "playing=${active.isPlaying}, position=${active.currentPosition}, error=${active.playerError?.errorCode}"
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
        val BRIGHT = PictureSettings(8, 2, 2)
    }
}
