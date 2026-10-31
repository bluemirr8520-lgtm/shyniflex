package io.mirr.shynigsy.player.mpv

import io.mirr.shynigsy.data.PlaybackMarker
import org.junit.Assert.*
import org.junit.Test

class MpvChapterCreditsTest {
    private fun chapters(last: Double = 2068.0) = listOf(
        MpvChapter(0.0, "Chapter 1"), MpvChapter(100.0, "Chapter 2"),
        MpvChapter(500.0, "Chapter 3"), MpvChapter(last, "Credits"),
    )

    @Test fun explicitFinalCreditsEndsAtNativeFullDuration() {
        assertEquals(PlaybackMarker("credits", 2_068_000, 2_424_096),
            chapterCreditsMarker(chapters(), 2424.096, 2_424_096, false))
    }

    @Test fun nextChapterBoundsCreditsAndFractionalStartsNeverShowEarly() {
        val list = listOf(MpvChapter(0.0, "Story"), MpvChapter(20.0001, "End credits"),
            MpvChapter(27.5, "Post credits"))
        assertEquals(PlaybackMarker("credits", 20_001, 27_500), chapterCreditsMarker(list, 40.0, 40_000, false))
    }

    @Test fun firstChapterMayStartAfterZeroWhenExplicitCreditsIsLater() {
        assertEquals(PlaybackMarker("credits", 2_068_000, 2_424_096),
            chapterCreditsMarker(chapters().drop(1), 2424.096, 2_424_096, false))
    }

    @Test fun requiresUniqueUnambiguousExplicitCreditsTitle() {
        assertNull(chapterCreditsMarker(chapters().map { it.copy(title = "Chapter") }, 2424.096, 0, false))
        assertNull(chapterCreditsMarker(chapters() + MpvChapter(2200.0, "End Credits"), 2424.096, 0, false))
        assertNull(chapterCreditsMarker(chapters().dropLast(1) + MpvChapter(2068.0, "Post Credits"), 2424.096, 0, false))
        assertNull(chapterCreditsMarker(listOf(MpvChapter(0.0, "Credits"), MpvChapter(10.0, "Story")), 40.0, 0, false))
        assertNull(chapterCreditsMarker(listOf(MpvChapter(0.0, "Story"), MpvChapter(10.0, "Credits"),
            MpvChapter(20.0, "Story")), 40.0, 0, false))
    }

    @Test fun failsClosedForConvertedUnknownOrContradictoryTimelines() {
        assertNull(chapterCreditsMarker(chapters(), 2424.096, 2_424_096, true))
        assertNull(chapterCreditsMarker(chapters(), null, 2_424_096, false))
        assertNull(chapterCreditsMarker(chapters(), Double.NaN, 0, false))
        assertNull(chapterCreditsMarker(chapters(), 2424.096, 2_430_000, false))
        assertNull(chapterCreditsMarker(listOf(MpvChapter(2068.0, "Credits")), 2424.096, 0, false))
    }

    @Test fun rejectsIncompleteUnorderedOrOutOfRangeChapterLists() {
        assertNull(chapterCreditsMarker(chapters(500.0), 2424.096, 0, false))
        assertNull(chapterCreditsMarker(chapters(2424.096), 2424.096, 0, false))
        assertNull(chapterCreditsMarker(chapters(Double.POSITIVE_INFINITY), 2424.096, 0, false))
        assertNull(chapterCreditsMarker(emptyList(), 2424.096, 0, false))
        assertNull(chapterCreditsMarker(List(65) { MpvChapter(it.toDouble(), "Credits") }, 100.0, 0, false))
    }
}
