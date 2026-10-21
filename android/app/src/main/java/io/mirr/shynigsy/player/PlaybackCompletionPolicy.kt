package io.mirr.shynigsy.player

/** The committed full-video position, not elapsed wall time or a seek preview. */
internal fun reachedWatchedThreshold(positionMs: Long, durationMs: Long): Boolean {
    if (positionMs < 0 || durationMs <= 0) return false
    val threshold = durationMs / 100 * 95 + ((durationMs % 100 * 95 + 99) / 100)
    return positionMs >= threshold
}

/** Source-clock progress must advance through 95%; seek/restore positions are only baselines. */
internal class PlaybackCompletionGate(positionMs: Long, initiallyEligible: Boolean = false) {
    private var previousPositionMs = positionMs.coerceAtLeast(0)
    private var advancedSinceSeek = false
    var eligible: Boolean = initiallyEligible
        private set

    fun seek(positionMs: Long) {
        previousPositionMs = positionMs.coerceAtLeast(0)
        advancedSinceSeek = false
    }

    fun observe(positionMs: Long, durationMs: Long, playing: Boolean) {
        val position = positionMs.coerceAtLeast(0)
        if (playing && position > previousPositionMs) {
            advancedSinceSeek = true
            if (durationMs > 0 && !reachedWatchedThreshold(previousPositionMs, durationMs) &&
                reachedWatchedThreshold(position, durationMs)) eligible = true
        }
        previousPositionMs = position
    }

    /** An immediate seek-to-end may close the player, but is not a watched completion. */
    fun finish() {
        if (advancedSinceSeek) eligible = true
    }
}
