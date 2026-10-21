package io.mirr.shynigsy.player

internal data class SeekSkipFeedback(val text: String, val forward: Boolean, val description: String)

/** Describe the bounded target delta, rather than the requested ten-second step. */
internal fun seekSkipFeedback(startMs: Long, targetMs: Long, forwardAtBoundary: Boolean): SeekSkipFeedback {
    val start = startMs.coerceAtLeast(0)
    val target = targetMs.coerceAtLeast(0)
    val forward = if (target == start) forwardAtBoundary else target > start
    val distance = if (target >= start) target - start else start - target
    val seconds = when {
        distance == 0L -> "0"
        distance < 1_000 -> "0." + distance.toString().padStart(3, '0').trimEnd('0')
        else -> (distance / 1_000 + if (distance % 1_000 >= 500) 1 else 0).toString()
    }
    return SeekSkipFeedback("${seconds}초${if (forward) ">" else "<"}", forward,
        "${if (forward) "앞으로" else "뒤로"} ${seconds}초")
}
