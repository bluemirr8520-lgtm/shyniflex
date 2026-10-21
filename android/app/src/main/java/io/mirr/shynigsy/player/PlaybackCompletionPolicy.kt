package io.mirr.shynigsy.player

/** The committed full-video position, not elapsed wall time or a seek preview. */
internal fun reachedWatchedThreshold(positionMs: Long, durationMs: Long): Boolean {
    if (positionMs < 0 || durationMs <= 0) return false
    val threshold = durationMs / 100 * 95 + ((durationMs % 100 * 95 + 99) / 100)
    return positionMs >= threshold
}
