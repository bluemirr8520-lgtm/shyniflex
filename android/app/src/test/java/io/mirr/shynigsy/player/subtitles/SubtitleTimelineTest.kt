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

    @Test fun studyNavigationUsesActivePassagesAndDoesNotFillGaps() {
        val first = ManualSubtitleCue(1_000, 2_000, "첫 문장")
        val second = ManualSubtitleCue(3_000, 4_000, "다음 문장")
        val timeline = SubtitleTimeline(listOf(second, first))
        assertNull(timeline.studyCueAt(999, 0))
        assertEquals(first, timeline.studyCueAt(1_000, 0))
        assertNull(timeline.studyCueAt(2_000, 0))
        assertNull(timeline.studyCueAt(2_500, 0))
        assertEquals(second, timeline.studyCueAt(3_999, 0))
        assertNull(timeline.studyCueAt(4_000, 0))

        assertNull(timeline.previousStudyCue(1_500, 0))
        assertEquals(second, timeline.nextStudyCue(1_500, 0))
        assertEquals(first, timeline.previousStudyCue(2_500, 0))
        assertEquals(second, timeline.nextStudyCue(2_500, 0))
        assertEquals(first, timeline.previousStudyCue(3_000, 0))
        assertNull(timeline.nextStudyCue(3_000, 0))
        assertEquals(second, timeline.previousStudyCue(5_000, 0))
        assertNull(timeline.nextStudyCue(5_000, 0))
        assertNull(timeline.previousStudyCue(-1, 0))
        assertEquals(first, timeline.nextStudyCue(-1, 0))
    }

    @Test fun studyPassagesMergeTransitiveOverlapsButKeepUnicodeAndInputTextOrder() {
        val timeline = SubtitleTimeline(listOf(
            ManualSubtitleCue(2_800, 4_000, "끝 🌙"),
            ManualSubtitleCue(1_000, 2_000, "안녕, 세계"),
            ManualSubtitleCue(1_900, 3_000, "Hello — café"),
            ManualSubtitleCue(6_000, 7_000, "그다음"),
        ))
        val passage = ManualSubtitleCue(1_000, 4_000, "끝 🌙\n\n안녕, 세계\n\nHello — café")
        assertEquals(passage, timeline.studyCueAt(1_200, 0))
        assertEquals(passage, timeline.studyCueAt(3_900, 0))
        assertEquals(passage, timeline.previousStudyCue(6_500, 0))
        assertEquals(6_000L, timeline.nextStudyCue(2_500, 0)?.startMs)
        // Study merging must never extend the normal subtitle display lifetime.
        assertEquals("안녕, 세계", timeline.textAt(1_200))
        assertEquals("끝 🌙", timeline.textAt(3_900))
    }

    @Test fun duplicateStudyBoundsBecomeOnePassageWhileTouchingSentencesStaySeparate() {
        val timeline = SubtitleTimeline(listOf(
            ManualSubtitleCue(1_000, 2_000, "원문"),
            ManualSubtitleCue(1_000, 2_000, "번역"),
            ManualSubtitleCue(2_000, 3_000, "다음"),
        ))
        val first = ManualSubtitleCue(1_000, 2_000, "원문\n\n번역")
        val next = ManualSubtitleCue(2_000, 3_000, "다음")
        assertEquals(first, timeline.studyCueAt(1_500, 0))
        assertNull(timeline.previousStudyCue(1_500, 0))
        assertEquals(next, timeline.nextStudyCue(1_500, 0))
        assertEquals(next, timeline.studyCueAt(2_000, 0))
        assertEquals(first, timeline.previousStudyCue(2_000, 0))
        assertNull(timeline.nextStudyCue(2_000, 0))
    }

    @Test fun advancingSyncSubtractsOffsetClampsStartAndDropsAlreadyEndedPassages() {
        val timeline = SubtitleTimeline(listOf(
            ManualSubtitleCue(0, 500, "끝난 문장"),
            ManualSubtitleCue(700, 1_500, "화면 시작에 걸친 문장"),
            ManualSubtitleCue(2_000, 3_000, "뒤 문장"),
        ))
        val clipped = ManualSubtitleCue(0, 500, "화면 시작에 걸친 문장")
        val later = ManualSubtitleCue(1_000, 2_000, "뒤 문장")
        assertEquals(clipped, timeline.studyCueAt(0, 1_000))
        assertNull(timeline.previousStudyCue(0, 1_000))
        assertEquals(clipped, timeline.previousStudyCue(750, 1_000))
        assertEquals(later, timeline.nextStudyCue(750, 1_000))
        assertEquals(later, timeline.studyCueAt(1_200, 1_000))
        assertNull(timeline.studyCueAt(0, 3_000))
        assertNull(timeline.nextStudyCue(-1, 3_000))
    }

    @Test fun delayingSyncAddsToVideoTargetsAndChangingSyncRebuildsNavigation() {
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(1_000, 2_000, "대사")))
        assertNull(timeline.studyCueAt(1_200, -500))
        assertEquals(ManualSubtitleCue(1_500, 2_500, "대사"), timeline.nextStudyCue(1_200, -500))
        assertEquals(ManualSubtitleCue(1_500, 2_500, "대사"), timeline.studyCueAt(2_000, -500))
        assertEquals(ManualSubtitleCue(500, 1_500, "대사"), timeline.studyCueAt(1_200, 500))
        assertEquals(ManualSubtitleCue(1_000, 2_000, "대사"), timeline.studyCueAt(1_200, 0))
    }

    @Test fun studyNavigationIgnoresBlankInvalidAndWhollyNegativePassages() {
        val timeline = SubtitleTimeline(listOf(
            ManualSubtitleCue(0, 10, "  "), ManualSubtitleCue(20, 10, "뒤집힘"),
            ManualSubtitleCue(10, 10, "빈 시간"), ManualSubtitleCue(-20, -10, "음수"),
        ))
        for (position in listOf(-1L, 0L, 10L, Long.MAX_VALUE)) {
            assertNull(timeline.studyCueAt(position, 0))
            assertNull(timeline.previousStudyCue(position, 0))
            assertNull(timeline.nextStudyCue(position, 0))
        }
        val empty = SubtitleTimeline(emptyList())
        assertNull(empty.studyCueAt(0, 0))
        assertNull(empty.previousStudyCue(0, 0))
        assertNull(empty.nextStudyCue(0, 0))
    }

    @Test fun studyOffsetArithmeticCannotWrapExtremeTimesIntoValidSeekTargets() {
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(1_000, 2_000, "대사")))
        assertNull(timeline.studyCueAt(0, Long.MAX_VALUE))
        assertNull(timeline.nextStudyCue(0, Long.MAX_VALUE))
        assertNull(timeline.studyCueAt(Long.MAX_VALUE - 1, Long.MIN_VALUE))
        assertNull(timeline.nextStudyCue(0, Long.MIN_VALUE))

        val crossing = SubtitleTimeline(listOf(ManualSubtitleCue(Long.MIN_VALUE, Long.MAX_VALUE, "긴 문장")))
        assertEquals(ManualSubtitleCue(0, Long.MAX_VALUE - 1, "긴 문장"), crossing.studyCueAt(0, 1))
        assertEquals(ManualSubtitleCue(0, Long.MAX_VALUE, "긴 문장"), crossing.studyCueAt(0, -1))
    }

    @Test fun studyPassageUsesOnlyOverlappingCuesWithExclusiveRangeEnd() {
        val timeline = SubtitleTimeline(listOf(
            ManualSubtitleCue(3_000, 4_000, "after"),
            ManualSubtitleCue(0, 1_000, "before"),
            ManualSubtitleCue(1_000, 2_000, "first"),
            ManualSubtitleCue(1_500, 2_500, "overlap"),
            ManualSubtitleCue(2_500, 3_000, "last"),
        ))
        assertEquals(StudyPassage("first\n\noverlap\n\nlast", false), timeline.studyPassage(1_000, 3_000, 0))
        assertEquals(StudyPassage("overlap", false), timeline.studyPassage(2_000, 2_500, 0))
        assertEquals(StudyPassage("", false), timeline.studyPassage(4_000, 5_000, 0))
    }

    @Test fun studyPassageSubtractsSyncOffsetAndClipsBeforeVideoStart() {
        val timeline = SubtitleTimeline(listOf(
            ManualSubtitleCue(0, 500, "ended"),
            ManualSubtitleCue(700, 1_500, "clipped"),
            ManualSubtitleCue(2_000, 3_000, "later"),
        ))
        assertEquals(StudyPassage("clipped", false), timeline.studyPassage(0, 1_000, 1_000))
        assertEquals(StudyPassage("later", false), timeline.studyPassage(1_000, 2_000, 1_000))
        assertEquals(StudyPassage("ended", false), timeline.studyPassage(500, 1_000, -500))
        assertEquals(StudyPassage("", false), timeline.studyPassage(0, 500, -500))
    }

    @Test fun studyPassageDeduplicatesOnlyConsecutiveIdenticalTextInTimeOrder() {
        val timeline = SubtitleTimeline(listOf(
            ManualSubtitleCue(0, 100, "  first  "),
            ManualSubtitleCue(0, 100, "first"),
            ManualSubtitleCue(100, 200, "first"),
            ManualSubtitleCue(150, 300, "second"),
            ManualSubtitleCue(300, 400, "first"),
            ManualSubtitleCue(300, 400, "번역 🌙"),
        ))
        assertEquals(StudyPassage("first\n\nsecond\n\nfirst\n\n번역 🌙", false), timeline.studyPassage(0, 400, 0))
    }

    @Test fun studyPassageIgnoresInvalidCuesRangesAndEmptyTimelines() {
        val timeline = SubtitleTimeline(listOf(
            ManualSubtitleCue(0, 10, "  "), ManualSubtitleCue(10, 10, "empty"),
            ManualSubtitleCue(20, 10, "reverse"), ManualSubtitleCue(-20, -10, "negative"),
        ))
        assertEquals(StudyPassage("", false), timeline.studyPassage(0, 100, 0))
        assertEquals(StudyPassage("", false), timeline.studyPassage(100, 0, 0))
        assertEquals(StudyPassage("", false), timeline.studyPassage(10, 10, 0))
        assertEquals(StudyPassage("", false), timeline.studyPassage(-10, 10, 0))
        assertEquals(StudyPassage("", false), SubtitleTimeline(emptyList()).studyPassage(0, 100, 0))
    }

    @Test fun studyPassageLimitsCountSourceCuesAndReportTruncationOnlyWhenNeeded() {
        val timeline = SubtitleTimeline(listOf(
            ManualSubtitleCue(0, 100, "A"), ManualSubtitleCue(100, 200, "A"),
            ManualSubtitleCue(200, 300, "B"),
        ))
        assertEquals(StudyPassage("A", true), timeline.studyPassage(0, 300, 0, maxCues = 2))
        assertEquals(StudyPassage("A", false), timeline.studyPassage(0, 200, 0, maxCues = 2))
        assertEquals(StudyPassage("", true), timeline.studyPassage(0, 100, 0, maxCues = 0))
        assertEquals(StudyPassage("", true), timeline.studyPassage(0, 100, 0, maxChars = 0))
        assertEquals(StudyPassage("", false), timeline.studyPassage(400, 500, 0, maxCues = 0))
    }

    @Test fun studyPassageCharacterLimitIncludesSeparatorsAndPreservesUnicodePairs() {
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(0, 100, "A"), ManualSubtitleCue(100, 200, "🌙B")))
        assertEquals(StudyPassage("A\n\n🌙B", false), timeline.studyPassage(0, 200, 0, maxChars = 6))
        assertEquals(StudyPassage("A\n\n🌙", true), timeline.studyPassage(0, 200, 0, maxChars = 5))
        assertEquals(StudyPassage("A", true), timeline.studyPassage(0, 200, 0, maxChars = 4))
        assertEquals(StudyPassage("A", false), timeline.studyPassage(0, 100, 0, maxChars = 1))
        assertEquals(StudyPassage("", true), timeline.studyPassage(100, 200, 0, maxChars = 1))
    }

    @Test fun studyPassageOffsetArithmeticDoesNotWrapOrIncludeCollapsedIntervals() {
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(1_000, 2_000, "cue")))
        assertEquals(StudyPassage("", false), timeline.studyPassage(0, Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(StudyPassage("", false), timeline.studyPassage(0, Long.MAX_VALUE, Long.MAX_VALUE))
        val crossing = SubtitleTimeline(listOf(ManualSubtitleCue(Long.MIN_VALUE, Long.MAX_VALUE, "long")))
        assertEquals(StudyPassage("long", false), crossing.studyPassage(0, Long.MAX_VALUE, -1))
        assertEquals(StudyPassage("long", false), crossing.studyPassage(0, Long.MAX_VALUE, 1))
    }

    @Test fun studyPassageDoesNotInventWordsOrContractionsAtCharacterBoundary() {
        val prefix = "the ".repeat(999)
        val timeline = SubtitleTimeline(listOf(ManualSubtitleCue(0, 10, prefix + "player")))
        assertEquals(StudyPassage(prefix.trimEnd(), true), timeline.studyPassage(0, 10, 0))
        for (text in listOf("we don't", "we don’t")) {
            val contraction = SubtitleTimeline(listOf(ManualSubtitleCue(0, 10, text)))
            assertEquals(StudyPassage("we", true), contraction.studyPassage(0, 10, 0, maxChars = 6))
        }
        val longWord = SubtitleTimeline(listOf(ManualSubtitleCue(0, 10, "player")))
        assertEquals(StudyPassage("", true), longWord.studyPassage(0, 10, 0, maxChars = 4))
    }

    @Test fun studyPassageCanSkipManyExpiredCuesBeneathOneLongOverlapAndCapsHugeRanges() {
        val cues = listOf(ManualSubtitleCue(0, 100_000_000, "long")) +
            List(100_000) { ManualSubtitleCue(it * 1_000L, it * 1_000L + 900, "line$it") }
        val timeline = SubtitleTimeline(cues)
        assertEquals(StudyPassage("long\n\nline99999", false), timeline.studyPassage(99_999_000, 100_000_000, 0))
        val firstSixty = timeline.studyPassage(0, 100_000_000, 0, maxCues = Int.MAX_VALUE, maxChars = Int.MAX_VALUE)
        assertTrue(firstSixty.truncated)
        assertEquals(60, firstSixty.text.split("\n\n").size)
        assertTrue(firstSixty.text.endsWith("line58"))
        val longText = SubtitleTimeline(listOf(ManualSubtitleCue(0, 10, "가".repeat(5_000))))
        assertEquals(4_000, longText.studyPassage(0, 10, 0, maxChars = Int.MAX_VALUE).text.length)
        assertTrue(longText.studyPassage(0, 10, 0).truncated)
    }
}
