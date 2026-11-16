package io.mirr.shynigsy

import android.app.Activity
import android.content.pm.ActivityInfo
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface

internal data class PlaybackReturnOrientationState(
    val originalRequest: Int,
    val fixedRequest: Int,
    val displayRotation: Int,
    val playbackPending: Boolean,
)

/** Only the browsing Activity is pinned; the separate player keeps its landscape sensor policy. */
internal class PlaybackReturnOrientation(private val activity: Activity) {
    private var state: PlaybackReturnOrientationState? = null
    private var resumed = false
    private var destroyed = false
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())
    private val poseGate = PlaybackReturnPoseGate()
    private val reverseDefaultRotation by lazy {
        runCatching {
            val resource = activity.resources.getIdentifier("config_reverseDefaultRotation", "bool", "android")
            resource != 0 && activity.resources.getBoolean(resource)
        }.getOrDefault(false)
    }
    private val sensor by lazy {
        object : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                val current = state ?: return
                if (!resumed || current.playbackPending || destroyed) return
                handler.removeCallbacks(releaseAfterStablePose)
                val delay = poseGate.observe(current.displayRotation, orientation, SystemClock.elapsedRealtime()) ?: return
                handler.postDelayed(releaseAfterStablePose, delay)
            }
        }
    }
    private val releaseAfterStablePose = Runnable {
        val current = state
        if (resumed && !destroyed && current != null && !current.playbackPending &&
            poseGate.isStable(SystemClock.elapsedRealtime())) release()
    }

    fun restore(savedInstanceState: Bundle?) {
        val saved = savedInstanceState?.getBundle(STATE_KEY) ?: return
        if (saved.getInt("version") != 1 || activity.isTelevision()) return
        val original = saved.getInt("original", Int.MIN_VALUE)
        val fixed = saved.getInt("fixed", Int.MIN_VALUE)
        val rotation = saved.getInt("rotation", Int.MIN_VALUE)
        if (original !in ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED..ActivityInfo.SCREEN_ORIENTATION_LOCKED ||
            !isFixedPlaybackReturnOrientation(fixed) || rotation !in Surface.ROTATION_0..Surface.ROTATION_270) return
        if (!canHoldDirection()) {
            activity.requestedOrientation = original
            return
        }
        state = PlaybackReturnOrientationState(original, fixed, rotation, saved.getBoolean("pending"))
        activity.requestedOrientation = fixed
    }

    fun save(outState: Bundle) {
        outState.remove(STATE_KEY)
        val current = state ?: return
        // Ordinary background saves outlive onStop's release. Only an actual
        // configuration handoff should carry a returned browsing lock forward.
        if (!current.playbackPending && !activity.isChangingConfigurations) return
        outState.putBundle(STATE_KEY, Bundle().apply {
            putInt("version", 1)
            putInt("original", current.originalRequest)
            putInt("fixed", current.fixedRequest)
            putInt("rotation", current.displayRotation)
            putBoolean("pending", current.playbackPending)
        })
    }

    fun beforePlayback() {
        if (destroyed || !canHoldDirection()) return
        stopListening()
        // Starting another film before the physical device catches up must not lose
        // the original automatic-rotation policy behind our temporary fixed request.
        val previous = state
        if (previous != null) {
            state = previous.copy(playbackPending = true)
            activity.requestedOrientation = previous.fixedRequest
            return
        }
        val rotation = displayRotation() ?: return
        val fixed = fixedPlaybackReturnOrientation(activity.resources.configuration.orientation, rotation,
            reverseDefaultRotation) ?: return
        state = PlaybackReturnOrientationState(activity.requestedOrientation, fixed, rotation, playbackPending = true)
        // Set this before launching. A result callback alone is too late to prevent
        // the parent first appearing at the player's current sensor direction.
        activity.requestedOrientation = fixed
    }

    fun playbackReturned() {
        val current = state ?: return
        state = current.copy(playbackPending = false)
        activity.requestedOrientation = current.fixedRequest
        startListening()
    }

    fun onResume() {
        resumed = true
        startListening()
    }

    fun onPause() {
        resumed = false
        stopListening()
    }

    fun onStop(changingConfigurations: Boolean) {
        // The stop caused by launching the player must retain the parent's direction.
        // Leaving browsing after return ends this temporary lock for the next app visit.
        if (!changingConfigurations && state?.playbackPending == false) release()
    }

    fun onDestroy() {
        destroyed = true
        stopListening()
        state = null
    }

    fun launchFailed() { release() }

    private fun canHoldDirection(): Boolean = !activity.isTelevision() && !activity.isInMultiWindowMode &&
        !activity.isInPictureInPictureMode && sensor.canDetectOrientation()

    private fun startListening() {
        if (destroyed || !resumed || state?.playbackPending != false || listening) return
        if (!canHoldDirection()) { release(); return }
        poseGate.reset()
        listening = true
        sensor.enable()
    }

    private fun stopListening() {
        if (listening) sensor.disable()
        listening = false
        handler.removeCallbacks(releaseAfterStablePose)
        poseGate.reset()
    }

    private fun release() {
        val original = state?.originalRequest ?: return
        stopListening()
        state = null
        if (!destroyed) activity.requestedOrientation = original
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int? = if (Build.VERSION.SDK_INT >= 30) activity.display?.rotation
        else activity.windowManager.defaultDisplay.rotation

    private companion object { const val STATE_KEY = "playback_return_orientation" }
}
