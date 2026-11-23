package io.mirr.shynigsy.player.subtitles

// Adapted from the user's Plex Play Universal 1.0.44 subtitle implementation.
// See PLEX_SUBTITLE_NOTICES.md for source provenance and scope.

internal data class StudyPassage(val text: String, val truncated: Boolean)

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
    // A range maximum tree skips ended cues even beneath a very long overlapping event.
    // Passage queries visit only matching leaves and their ancestors, never an entire movie.
    private val passageTreeBase = generateSequence(1) { it * 2 }.first { it >= entries.size }
    private val passageMaxEnds = LongArray(passageTreeBase * 2) { Long.MIN_VALUE }.also { tree ->
        entries.forEachIndexed { index, entry -> tree[passageTreeBase + index] = entry.cue.endMs }
        for (index in passageTreeBase - 1 downTo 1) tree[index] = maxOf(tree[index * 2], tree[index * 2 + 1])
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

    /**
     * Collects cues overlapping [startMs, endMs) in source-video time, applying the display offset.
     * The limits count source cues (including repeated captions) and UTF-16 characters. Hard caps
     * keep even an accidentally movie-long range bounded; adjacent duplicate text is emitted once.
     */
    fun studyPassage(
        startMs: Long,
        endMs: Long,
        offsetMs: Long,
        maxCues: Int = 60,
        maxChars: Int = 4_000,
    ): StudyPassage {
        if (startMs < 0 || endMs <= startMs || entries.isEmpty()) return StudyPassage("", false)
        val cueLimit = maxCues.coerceIn(0, 60)
        val charLimit = maxChars.coerceIn(0, 4_000)
        val selected = ArrayList<Entry>(cueLimit + 1)
        fun collect(node: Int, low: Int, high: Int) {
            if (selected.size > cueLimit || low >= entries.size ||
                subtractStudyOffset(passageMaxEnds[node], offsetMs) <= startMs ||
                subtractStudyOffset(entries[low].cue.startMs, offsetMs).coerceAtLeast(0) >= endMs) return
            if (high - low == 1) {
                selected += entries[low]
                return
            }
            val middle = low + (high - low) / 2
            collect(node * 2, low, middle)
            collect(node * 2 + 1, middle, high)
        }
        collect(1, 0, passageTreeBase)
        val result = StringBuilder(minOf(charLimit, 256))
        var truncated = selected.size > cueLimit
        var previousText: String? = null
        for (entry in selected.take(cueLimit)) {
            val text = entry.cue.text.trim()
            if (text == previousText) continue
            previousText = text
            val separatorLength = if (result.isEmpty()) 0 else 2
            val available = charLimit - result.length - separatorLength
            if (available <= 0) {
                truncated = true
                break
            }
            var count = minOf(available, text.length)
            // Do not cut a supplementary Unicode character in half at the character budget.
            if (count < text.length && count > 0 && text[count - 1].isHighSurrogate() && text[count].isLowSurrogate()) count--
            // A partial English word could otherwise become a different dictionary entry
            // (for example "player" cut to "play"). Exclude the complete unfinished token.
            if (count < text.length && count > 0 && text[count - 1].isStudyWordChar() && text[count].isStudyWordChar()) {
                while (count > 0 && text[count - 1].isStudyWordChar()) count--
                while (count > 0 && text[count - 1].isWhitespace()) count--
            }
            if (count > 0) {
                if (separatorLength > 0) result.append("\n\n")
                result.append(text, 0, count)
            }
            if (count < text.length) {
                truncated = true
                break
            }
        }
        return StudyPassage(result.toString(), truncated)
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

private fun Char.isStudyWordChar() = this in 'a'..'z' || this in 'A'..'Z' || this == '\'' || this == '’'

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
