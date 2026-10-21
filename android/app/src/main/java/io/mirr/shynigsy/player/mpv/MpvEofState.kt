package io.mirr.shynigsy.player.mpv

/** An EOF signal belongs to a loaded playback, never to a pending seek/reload. */
internal class MpvEofState {
    var ended: Boolean = false
        private set
    var awaitingSeekRestart: Boolean = false
        private set
    private var nativeSeekObserved = false

    fun reset() {
        ended = false
        awaitingSeekRestart = false
        nativeSeekObserved = false
    }

    fun seekStarted() {
        ended = false
        awaitingSeekRestart = true
        nativeSeekObserved = false
    }

    fun playbackRestarted() { awaitingSeekRestart = false }

    fun nativeSeekingChanged(seeking: Boolean) {
        if (!awaitingSeekRestart) return
        if (seeking) nativeSeekObserved = true
        else if (nativeSeekObserved) awaitingSeekRestart = false
    }

    fun confirm(loaded: Boolean, stopRequested: Boolean, nativeEof: Boolean?, nativeSeeking: Boolean): Boolean {
        if (ended || !loaded || stopRequested || awaitingSeekRestart || nativeSeeking || nativeEof != true) return false
        ended = true
        return true
    }
}
