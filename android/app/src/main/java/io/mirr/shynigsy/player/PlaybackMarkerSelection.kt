package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackMarker
import java.util.Locale

/** Marker times use the full media timeline, including when transport starts at a conversion offset. */
internal fun currentSkipMarker(markers: List<PlaybackMarker>, positionMs: Long, durationMs: Long): PlaybackMarker? {
    if (durationMs <= 0 || positionMs < 0 || positionMs >= durationMs) return null
    var selected: PlaybackMarker? = null
    for (marker in markers) {
        val type = marker.type.trim().lowercase(Locale.ROOT)
        if (type != "intro" && type != "credits") continue
        if (marker.startMs < 0 || marker.endMs <= marker.startMs) continue
        val end = minOf(marker.endMs, durationMs)
        if (marker.startMs >= end || positionMs < marker.startMs || positionMs >= end) continue
        val candidate = if (type == marker.type && end == marker.endMs) marker else marker.copy(type = type, endMs = end)
        val previous = selected
        if (previous == null || (candidate.type == "intro" && previous.type != "intro") ||
            (candidate.type == previous.type && (candidate.startMs < previous.startMs ||
                (candidate.startMs == previous.startMs && candidate.endMs < previous.endMs)))) selected = candidate
    }
    return selected
}
