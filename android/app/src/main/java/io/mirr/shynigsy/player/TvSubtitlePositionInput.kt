package io.mirr.shynigsy.player

internal enum class TvSubtitleMove { UP, DOWN, LEFT, RIGHT, RESET }

internal data class TvSubtitlePosition(val x: Int, val y: Int)

/** A TV press makes a small bounded change without altering other subtitle appearance settings. */
internal fun moveTvSubtitlePosition(position: TvSubtitlePosition, move: TvSubtitleMove): TvSubtitlePosition {
    val x = position.x.coerceIn(-80, 80)
    val y = position.y.coerceIn(-80, 80)
    return when (move) {
        TvSubtitleMove.UP -> TvSubtitlePosition(x, (y - 1).coerceAtLeast(-80))
        TvSubtitleMove.DOWN -> TvSubtitlePosition(x, (y + 1).coerceAtMost(80))
        TvSubtitleMove.LEFT -> TvSubtitlePosition((x - 1).coerceAtLeast(-80), y)
        TvSubtitleMove.RIGHT -> TvSubtitlePosition((x + 1).coerceAtMost(80), y)
        TvSubtitleMove.RESET -> TvSubtitlePosition(0, 0)
    }
}

/**
 * Binding-independent input for a single shown TV position panel. The View owns key consumption.
 * No timers survive a release: held movement follows only matching Android repeat events.
 */
internal class TvSubtitlePositionInput(private val shownAtMs: Long) {
    private data class Press(val keyCode: Int, val downTimeMs: Long, val move: TvSubtitleMove,
        var lastMoveAtMs: Long)

    private var press: Press? = null
    private var latestStartedDownTimeMs = shownAtMs

    init { require(shownAtMs >= 0) }

    /** Null ignores inherited, stale, mismatched or over-frequent events without moving anything. */
    fun keyDown(keyCode: Int, downTimeMs: Long, eventTimeMs: Long, repeatCount: Int,
        move: TvSubtitleMove): TvSubtitleMove? {
        if (downTimeMs <= shownAtMs || eventTimeMs < downTimeMs || repeatCount < 0) return null
        if (repeatCount == 0) {
            // A duplicate DOWN must not become another nudge after focus-loss cancellation.
            if (downTimeMs <= latestStartedDownTimeMs) return null
            latestStartedDownTimeMs = downTimeMs
            press = Press(keyCode, downTimeMs, move, eventTimeMs)
            return move
        }
        val held = press ?: return null
        if (held.keyCode != keyCode || held.downTimeMs != downTimeMs || held.move != move ||
            move == TvSubtitleMove.RESET || eventTimeMs < held.lastMoveAtMs ||
            eventTimeMs - held.lastMoveAtMs < 80L) return null
        held.lastMoveAtMs = eventTimeMs
        return move
    }

    /** True exactly once for an owned release; the caller may persist its dirty preview now. */
    fun keyUp(keyCode: Int, downTimeMs: Long): Boolean {
        val held = press ?: return false
        if (held.keyCode != keyCode || held.downTimeMs != downTimeMs) return false
        press = null
        return true
    }

    /** Focus loss, dismissal and lifecycle interruption all require a new physical DOWN. */
    fun cancel(): Boolean {
        val wasPressed = press != null
        press = null
        return wasPressed
    }
}
