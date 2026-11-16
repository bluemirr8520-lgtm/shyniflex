package io.mirr.shynigsy

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import kotlin.math.abs
import kotlin.math.min

/** Match DisplayRotation.configure, including the platform's reverse-default-rotation overlay. */
internal fun fixedPlaybackReturnOrientation(configurationOrientation: Int, displayRotation: Int,
    reverseDefaultRotation: Boolean = false): Int? {
    if (displayRotation !in Surface.ROTATION_0..Surface.ROTATION_270) return null
    if (configurationOrientation != Configuration.ORIENTATION_PORTRAIT &&
        configurationOrientation != Configuration.ORIENTATION_LANDSCAPE) return null
    val quarterTurn = displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270
    val naturallyLandscape = (configurationOrientation == Configuration.ORIENTATION_LANDSCAPE) != quarterTurn
    if (naturallyLandscape) {
        val portraitRotation = if (reverseDefaultRotation) Surface.ROTATION_90 else Surface.ROTATION_270
        return when (displayRotation) {
            Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            portraitRotation -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        }
    } else {
        val landscapeRotation = if (reverseDefaultRotation) Surface.ROTATION_270 else Surface.ROTATION_90
        return when (displayRotation) {
            Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            landscapeRotation -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
    }
}

internal fun isFixedPlaybackReturnOrientation(value: Int): Boolean = value in setOf(
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)

/** Display rotation compensates the physical device angle, so its direction is reversed. */
internal fun playbackReturnPoseMatches(displayRotation: Int, physicalDegrees: Int): Boolean {
    if (displayRotation !in Surface.ROTATION_0..Surface.ROTATION_270 || physicalDegrees !in 0..359) return false
    val target = (360 - displayRotation * 90) % 360
    val distance = abs(target - physicalDegrees)
    return min(distance, 360 - distance) <= 20
}

/** A timer may finish the stable interval even if a stationary sensor emits no new angle. */
internal class PlaybackReturnPoseGate(private val stableMs: Long = 450) {
    private var matchingSince: Long? = null

    fun observe(displayRotation: Int, physicalDegrees: Int, nowMs: Long): Long? {
        if (!playbackReturnPoseMatches(displayRotation, physicalDegrees)) { reset(); return null }
        val since = matchingSince?.takeIf { nowMs >= it } ?: nowMs.also { matchingSince = it }
        return (stableMs - (nowMs - since)).coerceAtLeast(0)
    }

    fun isStable(nowMs: Long): Boolean = matchingSince?.let { nowMs >= it && nowMs - it >= stableMs } == true
    fun reset() { matchingSince = null }
}
