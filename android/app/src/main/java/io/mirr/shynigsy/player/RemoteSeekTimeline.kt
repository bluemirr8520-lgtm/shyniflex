package io.mirr.shynigsy.player

/** Elapsed time, rather than a remote's repeat rate, determines continuous seeking. */
internal class RemoteSeekTimeline(private val startMs: Long, private val durationMs: Long,
    private val forward: Boolean, private val startedAtMs: Long) {
    fun targetAt(nowMs: Long): Long {
        val seconds = ((nowMs - startedAtMs - HOLD_DELAY_MS).coerceAtLeast(0) / 1000.0).coerceAtMost(86_400.0)
        val ramp = seconds.coerceAtMost(3.0)
        val distance = 10_000L + (20_000 * ramp + 10_000 * ramp * ramp + 80_000 * (seconds - ramp)).toLong()
        return (startMs + if (forward) distance else -distance).coerceIn(0, (durationMs - 1).coerceAtLeast(0))
    }

    companion object { const val HOLD_DELAY_MS = 300L }
}
