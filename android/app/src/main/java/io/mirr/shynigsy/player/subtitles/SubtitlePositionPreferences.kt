package io.mirr.shynigsy.player.subtitles

// Adapted from the user's Plex Play Universal 1.0.44 subtitle implementation.
// See PLEX_SUBTITLE_NOTICES.md for source provenance and scope.

import android.content.SharedPreferences

// Keep the two writing directions independent while preserving the active
// legacy coordinates used by gestures and both playback engines.
internal fun SharedPreferences.resolveSubtitlePositionChange(
    previousVertical: Boolean,
    nextVertical: Boolean,
    previousX: Int,
    previousY: Int,
    nextX: Int,
    nextY: Int,
): Pair<Int, Int> {
    fun prefix(vertical: Boolean) =
        if (vertical) "subtitle_vertical_writing" else "subtitle_horizontal_writing"

    val editor = edit()
    if (previousVertical != nextVertical) {
        editor.putInt("${prefix(previousVertical)}_x", previousX.coerceIn(-100, 100))
            .putInt("${prefix(previousVertical)}_y", previousY.coerceIn(-100, 100))
    }
    val target = when {
        previousVertical == nextVertical -> nextX to nextY
        nextVertical -> 0 to 0
        else -> getInt("subtitle_horizontal_writing_x", 0) to
            getInt("subtitle_horizontal_writing_y", 0)
    }
    val bounded = target.first.coerceIn(-100, 100) to target.second.coerceIn(-100, 100)
    editor.putInt("${prefix(nextVertical)}_x", bounded.first)
        .putInt("${prefix(nextVertical)}_y", bounded.second)
        .apply()
    return bounded
}
