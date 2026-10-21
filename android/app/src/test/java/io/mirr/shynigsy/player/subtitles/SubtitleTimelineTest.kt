package io.mirr.shynigsy.player.subtitles

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SubtitleTimelineTest {
    @Test fun emptyTimelineStaysEmpty() {
        val timeline = SubtitleTimeline(emptyList())
        for (position in listOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)) assertEquals("", timeline.textAt(position))
    }

    @Test fun startIsInclusiveAndEndIsExclusive() {
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(1_000, 2_000, "첫 줄")))
        assertEquals("", timeline.textAt(999))
        assertEquals("첫 줄", timeline.textAt(1_000))
        assertEquals("첫 줄", timeline.textAt(1_999))
        assertEquals("", timeline.textAt(2_000))
    }

    @Test fun gapsDoNotKeepPreviousSubtitle() {
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(0, 10, "A"), ManualSubtitleCue(20, 30, "B")))
        assertEquals("A", timeline.textAt(9))
        assertEquals("", timeline.textAt(15))
        assertEquals("B", timeline.textAt(20))
        assertEquals("", timeline.textAt(30))
    }

    @Test fun backwardSeeksInvalidateCachedInterval() {
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(0, 10, "A"), ManualSubtitleCue(10, 20, "B")))
        assertEquals("B", timeline.textAt(15))
        assertEquals("A", timeline.textAt(5))
        assertEquals("B", timeline.textAt(10))
    }

    @Test fun overlappingLinesPreserveInputOrderNotTimeOrder() {
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(5, 15, "first"), ManualSubtitleCue(0, 20, "second")))
        assertEquals("first\n\nsecond", timeline.textAt(8))
        assertEquals("second", timeline.textAt(16))
    }

    @Test fun longOverlappingCueSurvivesShortCueEnd() {
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(0, 100, "long"), ManualSubtitleCue(10, 20, "short")))
        assertEquals("long\n\nshort", timeline.textAt(15))
        assertEquals("long", timeline.textAt(70))
    }

    @Test fun blankAndInvalidIntervalsAreIgnoredButDuplicateLinesRemain() {
        val timeline = SubtitleTimeline(listOf(
            ManualSubtitleCue(0, 10, "  "), ManualSubtitleCue(10, 10, "invalid"),
            ManualSubtitleCue(20, 10, "reverse"), ManualSubtitleCue(0, 10, "A"), ManualSubtitleCue(0, 10, "A"),
        ))
        assertEquals("A\n\nA", timeline.textAt(5))
        assertEquals("", timeline.textAt(10))
    }

    @Test fun sameBoundaryIntervalReusesTextObject() {
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(0, 100, "A"), ManualSubtitleCue(0, 100, "B")))
        assertSame(timeline.textAt(1), timeline.textAt(99))
    }

    @Test fun extremeTimestampsDoNotOverflow() {
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(Long.MIN_VALUE, Long.MAX_VALUE, "A")))
        assertEquals("A", timeline.textAt(Long.MIN_VALUE))
        assertEquals("A", timeline.textAt(Long.MAX_VALUE - 1))
        assertEquals("", timeline.textAt(Long.MAX_VALUE))
    }

    @Test fun matchesLegacyLookupAcrossRandomOverlapsAndSeeks() {
        val random = Random(3526)
        val cues = List(800) { index ->
            val start = random.nextLong(-500, 100_000)
            ManualSubtitleCue(start, start + random.nextLong(-10, 12_000), if (index % 19 == 0) " " else "줄$index")
        }.shuffled(random)
        val timeline = SubtitleTimeline(cues)
        repeat(2_000) {
            val position = random.nextLong(-1_000, 115_000)
            assertEquals(cues.textAt(position), timeline.textAt(position))
        }
    }

    @Test fun longMovieSupportsTenThousandCuesAndRepeatedPolling() {
        val cues = List(10_000) { ManualSubtitleCue(it * 1_000L, it * 1_000L + 900, "대사$it") }
        val timeline = SubtitleTimeline(cues)
        for (index in 9_999 downTo 0 step 17) {
            repeat(8) { tick -> assertEquals("대사$index", timeline.textAt(index * 1_000L + tick * 100)) }
            assertEquals("", timeline.textAt(index * 1_000L + 999))
        }
    }
}
