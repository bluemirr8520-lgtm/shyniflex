package io.mirr.shynigsy

import android.app.Activity
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.player.NativePlayerActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** A real sensor-landscape player returns to an isolated list host; no real account or media is used. */
@RunWith(AndroidJUnit4::class)
class PlaybackReturnOrientationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun completedVideoRestoresThePortraitListBeforeItsFirstVisibleFrame() =
        verifyNaturalCompletion(Surface.ROTATION_0, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

    @Test fun completedVideoRestoresTheLandscapeListBeforeItsFirstVisibleFrame() =
        verifyNaturalCompletion(Surface.ROTATION_90, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

    @Test fun completedVideoRestoresTheReverseLandscapeListBeforeItsFirstVisibleFrame() =
        verifyNaturalCompletion(Surface.ROTATION_270, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)

    @Test fun aRecreatedListRetainsItsPendingPrePlaybackOrientation() = withFixture(Surface.ROTATION_0) { _, scenario ->
        scenario.onActivity { host ->
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, host.requestedOrientation)
            host.beginPendingPlayback()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, host.requestedOrientation)
        }
        scenario.recreate()
        scenario.onActivity { host ->
            assertEquals("Recreation must retain the original list direction during playback",
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, host.requestedOrientation)
            assertEquals(Configuration.ORIENTATION_PORTRAIT, host.resources.configuration.orientation)
            host.restoreFromSimulatedReturn()
        }
        val frame = awaitReturnFrame()
        assertEquals(Configuration.ORIENTATION_PORTRAIT, frame.configuration)
        assertEquals(Surface.ROTATION_0, frame.displayRotation)
    }

    @Test fun aRecreatedReturnedListRetainsItsOriginalPolicyAndHeldDirection() = withFixture(Surface.ROTATION_270) { _, scenario ->
        var previous: PlaybackReturnOrientationHostActivity? = null
        val replacement = AtomicReference<PlaybackReturnOrientationHostActivity?>()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (stage == Stage.RESUMED && activity is PlaybackReturnOrientationHostActivity &&
                previous != null && activity !== previous) replacement.set(activity)
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.addLifecycleCallback(callback)
        try {
            scenario.onActivity { host ->
                previous = host
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, host.requestedOrientation)
                host.beginPendingPlayback()
                host.restoreFromSimulatedReturn()
                val held = requireNotNull(orientationState(host))
                assertFalse(held.playbackPending)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, held.originalRequest)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, held.fixedRequest)
                // One main-thread action prevents physical-pose release from running between
                // the return and recreation request. Reverse landscape also differs from the
                // usual upright/flat phone pose used for these device tests.
                host.recreate()
            }
            waitUntil("The returned list must recreate and resume", 8_000) { replacement.get() != null }
            scenario.onActivity { host ->
                assertSame(replacement.get(), host)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, host.requestedOrientation)
                val restored = requireNotNull(orientationState(host)) { "A returned hold must survive recreation" }
                assertFalse(restored.playbackPending)
                assertEquals("The temporary fixed request must not replace the saved original policy",
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, restored.originalRequest)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, restored.fixedRequest)
                assertEquals(Surface.ROTATION_270, restored.displayRotation)
            }
        } finally { monitor.removeLifecycleCallback(callback) }
    }

    private fun orientationState(host: PlaybackReturnOrientationHostActivity): PlaybackReturnOrientationState? {
        val helper = PlaybackReturnOrientationHostActivity::class.java.getDeclaredMethod("getReturnOrientation")
            .apply { isAccessible = true }.invoke(host)
        return PlaybackReturnOrientation::class.java.getDeclaredField("state").apply { isAccessible = true }
            .get(helper) as? PlaybackReturnOrientationState
    }

    private fun verifyNaturalCompletion(rotation: Int, expectedRequest: Int) = withFixture(rotation) { intent, scenario ->
        val playerActivity = AtomicReference<NativePlayerActivity?>()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is NativePlayerActivity) {
                if (stage == Stage.RESUMED) playerActivity.set(activity)
                if (stage == Stage.DESTROYED) playerActivity.compareAndSet(activity, null)
            }
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.addLifecycleCallback(callback)
        try {
            scenario.onActivity { host ->
                assertEquals("The normal list must start with its normal automatic policy",
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, host.requestedOrientation)
                host.launchPlayback(intent)
            }
            assertEquals("The parent must request its saved direction before starting the player",
                expectedRequest, PlaybackReturnOrientationProbe.beforeLaunch.get()!!.requested)
            var actualVideoObserved = false
            waitUntil("The real player must render the synthetic video in landscape", 12_000) {
                instrumentation.runOnMainSync {
                    val activity = playerActivity.get()
                    if (activity != null && !activity.isDestroyed && !activity.isFinishing) {
                        val active = NativePlayerActivity::class.java.getDeclaredField("player")
                            .apply { isAccessible = true }.get(activity) as? Player
                        active?.volume = 0f
                        val firstFrame = NativePlayerActivity::class.java.getDeclaredField("playbackFirstFrameReported")
                            .apply { isAccessible = true }.getBoolean(activity)
                        actualVideoObserved = active?.playbackState == Player.STATE_READY && firstFrame &&
                            active.videoSize.width > 0 && activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    }
                }
                actualVideoObserved
            }
            // The device's later rotation preference must not replace the saved list direction.
            // Sensor re-arming is separately unit-tested: this assertion captures the first visible
            // return frame, before the controller's stable physical-pose interval can expire.
            assertTrue(instrumentation.uiAutomation.setRotation(if (rotation == Surface.ROTATION_0)
                UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0))
            val frame = awaitReturnFrame()
            assertEquals("Only normal EOF may complete this fixture", Activity.RESULT_OK,
                PlaybackReturnOrientationProbe.resultCode.get())
            assertEquals("The first visible list frame must use its pre-playback direction", rotation, frame.displayRotation)
            assertEquals(if (rotation == Surface.ROTATION_0) Configuration.ORIENTATION_PORTRAIT
                else Configuration.ORIENTATION_LANDSCAPE, frame.configuration)
            waitUntil("The list host must remain resumed after playback", 5_000) { scenario.state == Lifecycle.State.RESUMED }
        } finally {
            monitor.removeLifecycleCallback(callback)
            instrumentation.runOnMainSync { playerActivity.get()?.let { if (!it.isFinishing && !it.isDestroyed) it.finish() } }
        }
    }

    private fun awaitReturnFrame(): PlaybackReturnOrientationFrame {
        waitUntil("The restored list must draw after playback returns", 20_000) {
            PlaybackReturnOrientationProbe.firstVisibleReturn.get() != null
        }
        return requireNotNull(PlaybackReturnOrientationProbe.firstVisibleReturn.get())
    }

    private fun withFixture(rotation: Int, block: (Intent, ActivityScenario<PlaybackReturnOrientationHostActivity>) -> Unit) {
        val context = instrumentation.targetContext
        assertEquals("Only the isolated QA app may run orientation fixtures", "io.mirr.shyniflex.spoilerqa", context.packageName)
        assertFalse("Unlock the phone before validating visible orientation", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        assumeTrue("Physical orientation restoration needs an accelerometer",
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null)
        val resolver = context.contentResolver
        val originalAutoRotation = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        val originalUserRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0)
        val settings = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val keys = setOf("playback_speed", "playback_engine", "picture_settings_v1", "continuous_playback")
        val oldSettings = keys.associateWith { settings.all[it] }
        val records = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
        val originalRecord = records.getString(SERVER, null)
        val originalLastServer = records.getString("last_server", null)
        val sessions = SessionStore(context)
        val video = File(context.cacheDir, "orientation-return-synthetic.mp4")
        try {
            assertTrue(instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0))
            ActivityScenario.launch(PlaybackReturnOrientationHostActivity::class.java).use { natural ->
                waitUntil("The test device must settle in natural orientation", 5_000) {
                    var settled = false
                    natural.onActivity { settled = displayRotation(it) == Surface.ROTATION_0 }
                    settled
                }
                natural.onActivity { assumeTrue("These physical rotation cases target a natural-portrait phone",
                    it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) }
            }
            assertTrue(instrumentation.uiAutomation.setRotation(rotation))
            instrumentation.context.assets.open("media3/gesture-av.mp4").use { input -> video.outputStream().use { output -> input.copyTo(output) } }
            settings.edit().putFloat("playback_speed", 1f).putString("playback_engine", "mpv")
                .remove("picture_settings_v1").putBoolean("continuous_playback", false).commit()
            sessions.save(SERVER, "synthetic-orientation-only", "화면 방향 검사")
            val spec = PlaybackSpec(title = "화면 방향 합성 검사", url = Uri.fromFile(video).toString(),
                itemId = 1, libraryId = 1, partId = 1, viewItemId = 1, durationMs = 6_000)
            val playerIntent = Intent(context, NativePlayerActivity::class.java).putExtra("server", SERVER).putExtra("spec", spec.toJson())
            PlaybackReturnOrientationProbe.reset()
            ActivityScenario.launch(PlaybackReturnOrientationHostActivity::class.java).use { scenario ->
                waitUntil("The list must settle into its chosen direction before playback", 5_000) {
                    var settled = false
                    scenario.onActivity { settled = displayRotation(it) == rotation && it.window.decorView.hasWindowFocus() }
                    settled
                }
                block(playerIntent, scenario)
            }
        } finally {
            sessions.clear(SERVER)
            records.edit().apply {
                if (originalRecord == null) remove(SERVER) else putString(SERVER, originalRecord)
                if (originalLastServer == null) remove("last_server") else putString("last_server", originalLastServer)
            }.commit()
            restore(settings, oldSettings)
            video.delete()
            // Freeze restores the saved USER_ROTATION too; unfreezing alone would lose a user's
            // rotation lock. Restore both settings through the same supported automation API.
            assertTrue(instrumentation.uiAutomation.setRotation(originalUserRotation))
            if (originalAutoRotation != 0) assertTrue(instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE))
            assertEquals(originalAutoRotation, Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1))
            assertEquals(originalUserRotation, Settings.System.getInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0))
            PlaybackReturnOrientationProbe.reset()
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(activity: Activity) = activity.windowManager.defaultDisplay.rotation

    private fun restore(preferences: SharedPreferences, values: Map<String, Any?>) {
        preferences.edit().apply {
            for ((key, value) in values) when (value) {
                null -> remove(key)
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
            }
        }.commit()
    }

    private fun waitUntil(message: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail(message)
    }

    private companion object { const val SERVER = "https://orientation-return-test.invalid" }
}
