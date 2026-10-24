package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackMarker
import java.util.Locale

private fun normalizedSkipMarker(marker: PlaybackMarker, durationMs: Long): PlaybackMarker? {
    val type = marker.type.trim().lowercase(Locale.ROOT)
    if (type != "intro" && type != "credits") return null
    if (marker.startMs < 0 || marker.endMs <= marker.startMs) return null
    val end = minOf(marker.endMs, durationMs)
    if (marker.startMs >= end) return null
    return if (type == marker.type && end == marker.endMs) marker else marker.copy(type = type, endMs = end)
}

/** Marker times use the full media timeline, including when transport starts at a conversion offset. */
internal fun currentSkipMarker(markers: List<PlaybackMarker>, positionMs: Long, durationMs: Long): PlaybackMarker? {
    if (durationMs <= 0 || positionMs < 0 || positionMs >= durationMs) return null
    var selected: PlaybackMarker? = null
    for (marker in markers) {
        val candidate = normalizedSkipMarker(marker, durationMs) ?: continue
        if (positionMs < candidate.startMs || positionMs >= candidate.endMs) continue
        val previous = selected
        if (previous == null || (candidate.type == "intro" && previous.type != "intro") ||
            (candidate.type == previous.type && (candidate.startMs < previous.startMs ||
                (candidate.startMs == previous.startMs && candidate.endMs < previous.endMs)))) selected = candidate
    }
    return selected
}

/** Wake at the next source-clock boundary, rounded up so a button never appears early. */
internal fun playbackMarkerRefreshDelayMs(markers: List<PlaybackMarker>, positionMs: Long, durationMs: Long,
    playbackSpeed: Float, isPlaying: Boolean): Long {
    if (!isPlaying || durationMs <= 0 || positionMs < 0 || positionMs >= durationMs ||
        !playbackSpeed.isFinite() || playbackSpeed <= 0f) return 500
    var nextBoundary: Long? = null
    for (marker in markers) {
        val candidate = normalizedSkipMarker(marker, durationMs) ?: continue
        val boundary = when {
            positionMs < candidate.startMs -> candidate.startMs
            positionMs < candidate.endMs -> candidate.endMs
            else -> continue
        }
        nextBoundary = nextBoundary?.let { minOf(it, boundary) } ?: boundary
    }
    val boundary = nextBoundary ?: return 500
    // Subtract as Long first: converting two huge nearby clocks to Double loses milliseconds.
    val remainingMs = boundary - positionMs
    // Find ceil(distance / speed), capped at 500. A Float times an integer <=500
    // is exact in Double; flooring that product avoids rounding a huge distance down.
    var lower = 1L
    var upper = 500L
    while (lower < upper) {
        val middle = (lower + upper) / 2
        if ((middle * playbackSpeed.toDouble()).toLong() >= remainingMs) upper = middle
        else lower = middle + 1
    }
    return lower
}
