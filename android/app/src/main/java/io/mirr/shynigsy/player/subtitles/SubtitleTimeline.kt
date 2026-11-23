package io.mirr.shynigsy.player.subtitles

// Adapted from the user's Plex Play Universal 1.0.44 subtitle implementation.
// See PLEX_SUBTITLE_NOTICES.md for source provenance and scope.

/** Built once per subtitle selection; use from one playback coroutine. */
internal class SubtitleTimeline(cues: List<ManualSubtitleCue>) {
    private data class Entry(val order: Int, val cue: ManualSubtitleCue)

    private val entries = cues.mapIndexedNotNull { index, cue ->
        if (cue.startMs < cue.endMs && cue.text.isNotBlank()) Entry(index, cue) else null
    }.sortedWith(compareBy({ it.cue.startMs }, { it.order }))
    private val starts = LongArray(entries.size) { entries[it].cue.startMs }
    private val maxEnds = LongArray(entries.size).also { ends ->
        var maximum = Long.MIN_VALUE
        entries.forEachIndexed { index, entry ->
            maximum = maxOf(maximum, entry.cue.endMs)
            ends[index] = maximum
        }
    }
    private val boundaries = entries.flatMap { listOf(it.cue.startMs, it.cue.endMs) }
        .distinct().sorted().toLongArray()
    private var cachedBucket = -1
    private var cachedText = ""
    // Overlapping ASS/SAMI events form one repeatable passage. Touching cues
    // remain separate sentences, and the normal renderer still uses each event.
    private val studyCues = mergeStudyCues()
    private var studyOffset: Long? = null
    private var shiftedStudyCues = emptyList<ManualSubtitleCue>()
    private var studyStarts = LongArray(0)

    fun textAt(positionMs: Long): String {
        val bucket = boundaries.upperBound(positionMs)
        if (bucket == cachedBucket) return cachedText
        val end = starts.upperBound(positionMs)
        val start = maxEnds.upperBound(positionMs)
        // Prefix end maxima retain cues that span several overlapping cues or a seek.
        val active = ArrayList<Entry>()
        for (index in start until end) {
            entries[index].takeIf { positionMs < it.cue.endMs }?.let(active::add)
        }
        // Preserve file order, including ASS/SAMI overlapping lines.
        active.sortBy { it.order }
        cachedText = active.joinToString("\n\n") { it.cue.text }
        cachedBucket = bucket
        return cachedText
    }

    /** Returns only the currently active passage, in video time, with an exclusive end. */
    fun studyCueAt(positionMs: Long, offsetMs: Long): ManualSubtitleCue? {
        prepareStudyOffset(offsetMs)
        val cue = shiftedStudyCues.getOrNull(studyStarts.upperBound(positionMs) - 1)
        return cue?.takeIf { positionMs < it.endMs }
    }

    /** Within a passage, skips that passage; in a gap, returns the last earlier one. */
    fun previousStudyCue(positionMs: Long, offsetMs: Long): ManualSubtitleCue? {
        prepareStudyOffset(offsetMs)
        val index = studyStarts.upperBound(positionMs) - 1
        val candidate = shiftedStudyCues.getOrNull(index) ?: return null
        return if (positionMs < candidate.endMs) shiftedStudyCues.getOrNull(index - 1) else candidate
    }

    /** Returns the first passage that starts after the current video position. */
    fun nextStudyCue(positionMs: Long, offsetMs: Long): ManualSubtitleCue? {
        prepareStudyOffset(offsetMs)
        return shiftedStudyCues.getOrNull(studyStarts.upperBound(positionMs))
    }

    private fun mergeStudyCues(): List<ManualSubtitleCue> {
        val result = ArrayList<ManualSubtitleCue>()
        val passage = ArrayList<Entry>()
        var start = 0L
        var end = 0L
        fun finishPassage() {
            if (passage.isEmpty()) return
            result += ManualSubtitleCue(start, end,
                passage.sortedBy { it.order }.joinToString("\n\n") { it.cue.text })
            passage.clear()
        }
        for (entry in entries) {
            if (passage.isNotEmpty() && entry.cue.startMs >= end) finishPassage()
            if (passage.isEmpty()) {
                start = entry.cue.startMs
                end = entry.cue.endMs
            } else end = maxOf(end, entry.cue.endMs)
            passage += entry
        }
        finishPassage()
        return result
    }

    private fun prepareStudyOffset(offsetMs: Long) {
        if (studyOffset == offsetMs) return
        // Display uses videoPosition + offset, so seek targets subtract offset.
        // Cache the conversion: normal polling never allocates or scans the list.
        shiftedStudyCues = studyCues.mapNotNull { cue ->
            val start = subtractStudyOffset(cue.startMs, offsetMs).coerceAtLeast(0)
            val end = subtractStudyOffset(cue.endMs, offsetMs)
            if (end <= start) null else ManualSubtitleCue(start, end, cue.text)
        }
        studyStarts = LongArray(shiftedStudyCues.size) { shiftedStudyCues[it].startMs }
        studyOffset = offsetMs
    }
}

private fun subtractStudyOffset(value: Long, offset: Long): Long = when {
    offset > 0 && value < Long.MIN_VALUE + offset -> Long.MIN_VALUE
    offset < 0 && value > Long.MAX_VALUE + offset -> Long.MAX_VALUE
    else -> value - offset
}

/** Index of the first value strictly greater than target. */
private fun LongArray.upperBound(target: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = low + (high - low) / 2
        if (this[mid] <= target) low = mid + 1 else high = mid
    }
    return low
}
