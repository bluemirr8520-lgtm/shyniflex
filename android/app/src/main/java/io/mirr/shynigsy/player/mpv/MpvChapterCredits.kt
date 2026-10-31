package io.mirr.shynigsy.player.mpv

import io.mirr.shynigsy.data.PlaybackMarker
import java.util.Locale
import kotlin.math.ceil

/** Chapter times are native source seconds; titles are never inferred from numbering. */
internal data class MpvChapter(val seconds: Double, val title: String?)

/**
 * Accept only one explicit credits chapter in a complete, ordered source timeline.
 * The last chapter ends at MPV's actual source duration, not a resume percentage.
 */
internal fun chapterCreditsMarker(chapters: List<MpvChapter>, nativeDurationSeconds: Double?,
    expectedSourceDurationMs: Long, serverConversion: Boolean): PlaybackMarker? {
    if (serverConversion || chapters.isEmpty() || chapters.size > 64) return null
    val duration = MpvSourceDuration.nativeMilliseconds(nativeDurationSeconds) ?: return null
    // An offset/partial timeline must not supply a full-file marker. Allow small
    // container rounding differences, but reject contradictory source durations.
    if (expectedSourceDurationMs > 0 &&
        kotlin.math.abs(expectedSourceDurationMs - duration) > 2_000L) return null
    val times = ArrayList<Long>(chapters.size)
    for (chapter in chapters) {
        val millis = chapter.seconds * 1_000.0
        if (!chapter.seconds.isFinite() || chapter.seconds < 0 || !millis.isFinite() ||
            millis > Long.MAX_VALUE / 1000.0) return null
        val time = ceil(millis).toLong()
        if (time >= duration || (times.isNotEmpty() && time <= times.last())) return null
        times.add(time)
    }
    // The first chapter may be opening credits; require an earlier chapter,
    // but do not assume the container's first chapter has to start at zero.
    val names = setOf("credits", "end credits", "ending credits", "엔딩 크레딧", "엔딩크레딧")
    val matches = chapters.indices.filter { index ->
        chapters[index].title?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ") in names
    }
    if (matches.size != 1) return null
    val index = matches.single()
    if (index == 0) return null
    val title = chapters[index].title?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ")
    // A generic "Credits" in the middle may be opening credits; only an
    // explicitly ending-labelled chapter may precede another chapter.
    if (title == "credits" && index != chapters.lastIndex) return null
    val start = times[index]
    val end = times.getOrNull(index + 1) ?: duration
    return if (end > start) PlaybackMarker("credits", start, end) else null
}
