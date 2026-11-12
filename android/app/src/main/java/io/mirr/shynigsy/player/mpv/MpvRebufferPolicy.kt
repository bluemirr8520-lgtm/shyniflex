package io.mirr.shynigsy.player.mpv

/**
 * Gives intermittent network delivery time to recover instead of repeatedly
 * resuming with only one second buffered. It never delays the first frame or
 * changes the demuxer's byte budget. MPV can resume before this target when the
 * bounded cache fills or the source reaches EOF.
 */
internal class MpvRebufferPolicy(cacheSeconds: Int, forwardCacheMiB: Int) {
    private val maximumWaitSeconds = minOf(cacheSeconds.coerceAtLeast(1), when {
        forwardCacheMiB < 16 -> 2
        forwardCacheMiB < 48 -> 4
        else -> 6
    })
    var waitSeconds: Int = 1
        private set
    private var previousCachePause = false
    private var stableSinceMs: Long? = null

    /**
     * eligible: a network original after its first frame, with playback requested,
     * and without a seek, user pause, focus loss, error or EOF.
     * progressing: that same source is currently ready and playing.
     * Returns an option change only; duplicate notifications cannot add a stall.
     */
    fun observe(nowMs: Long, cachePaused: Boolean, eligible: Boolean, progressing: Boolean): Int? {
        val before = waitSeconds
        val newlyStarved = cachePaused && !previousCachePause
        previousCachePause = cachePaused
        if (newlyStarved && eligible) {
            waitSeconds = (waitSeconds * 2).coerceAtMost(maximumWaitSeconds)
        }
        if (!eligible || cachePaused || !progressing) {
            stableSinceMs = null
        } else if (waitSeconds > 1) {
            val since = stableSinceMs
            if (since == null || nowMs < since) stableSinceMs = nowMs
            else if (nowMs - since >= STABLE_PLAYBACK_MS) {
                waitSeconds = 1
                stableSinceMs = null
            }
        }
        return waitSeconds.takeIf { it != before }
    }

    /** Every source load and explicit seek starts with the normal one-second target. */
    fun reset(): Int? {
        val changed = waitSeconds != 1
        waitSeconds = 1
        previousCachePause = false
        stableSinceMs = null
        return if (changed) 1 else null
    }

    private companion object {
        const val STABLE_PLAYBACK_MS = 60_000L
    }
}
