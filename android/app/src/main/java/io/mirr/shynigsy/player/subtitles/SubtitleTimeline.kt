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
