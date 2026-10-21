package io.mirr.shynigsy.player

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

internal object SeekGestureMath {
    const val SCREEN_SPAN_MS = 120_000L

    fun position(startMs: Long, deltaX: Float, width: Int, durationMs: Long): Long {
        if (width <= 0 || durationMs <= 0) return startMs.coerceAtLeast(0)
        val span = min(SCREEN_SPAN_MS, durationMs)
        return (startMs + deltaX.toDouble() / width * span).roundToLong()
            .coerceIn(0, (durationMs - 1).coerceAtLeast(0))
    }

    fun horizontal(deltaX: Float, deltaY: Float, threshold: Float): Boolean =
        abs(deltaX) > threshold && abs(deltaX) > abs(deltaY) * 1.2f

    fun vertical(deltaX: Float, deltaY: Float, threshold: Float): Boolean =
        abs(deltaY) > threshold && abs(deltaY) > abs(deltaX) * 1.2f
}

/** One native seek in flight, one latest target waiting; no unbounded drag queue. */
internal class LatestSeekQueue(private val intervalMs: Long = 250) {
    data class Request(val positionMs: Long, val final: Boolean)

    var inFlight: Request? = null
        private set
    var pending: Request? = null
        private set
    var sentAtMs: Long = 0
        private set
    private var lastSentAtMs: Long? = null

    fun offer(positionMs: Long, final: Boolean = false) {
        val request = Request(positionMs.coerceAtLeast(0), final)
        val current = inFlight
        if (current?.positionMs == request.positionMs) {
            if (final) inFlight = current.copy(final = true)
            pending = null
        } else pending = request
    }

    fun poll(nowMs: Long): Request? {
        if (inFlight != null) return null
        val next = pending ?: return null
        val previous = lastSentAtMs
        if (!next.final && previous != null && nowMs - previous < intervalMs) return null
        pending = null
        inFlight = next
        sentAtMs = nowMs
        lastSentAtMs = nowMs
        return next
    }

    fun complete(): Request? = inFlight.also { inFlight = null }

    fun resumePreview() {
        inFlight = inFlight?.copy(final = false)
        pending = pending?.copy(final = false)
    }

    fun reset() {
        inFlight = null
        pending = null
        lastSentAtMs = null
        sentAtMs = 0
    }
}
