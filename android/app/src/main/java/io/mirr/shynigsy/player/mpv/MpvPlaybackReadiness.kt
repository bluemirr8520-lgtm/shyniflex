package io.mirr.shynigsy.player.mpv

/** Native restart and cache readiness, independent of user pause or video output. */
internal class MpvPlaybackReadiness {
    var restartPending: Boolean = true
        private set

    var cachePaused: Boolean = false
        private set

    val ready: Boolean
        get() = !restartPending && !cachePaused

    fun reset() {
        restartPending = true
        cachePaused = false
    }

    fun seekStarted() {
        restartPending = true
    }

    fun fileLoaded(cachePaused: Boolean) {
        this.cachePaused = cachePaused
    }

    fun playbackRestarted() {
        restartPending = false
    }

    fun cachePausedChanged(value: Boolean) {
        cachePaused = value
    }
}
