package io.mirr.shynigsy.player

import org.junit.Assert.*
import org.junit.Test

class LanguageStudyRepeatTest {
    @Test fun unsetRepeatNeverInterceptsPlaybackOrCompletion() {
        val repeat = LanguageStudyRepeat()
        assertNull(repeat.startMs)
        assertNull(repeat.range)
        assertFalse(repeat.sentence)
        assertEquals(StudyRepeatDecision.Idle, repeat.poll(100_000, true, 100))
        assertFalse(repeat.markEnd(2_000, 10_000))
    }

    @Test fun markingANewStartClearsThePreviousLoopAndClampsNegativePositions() {
        val repeat = sentenceRepeat()
        repeat.poll(2_000, false, 100)
        repeat.markStart(-10)
        assertEquals(0L, repeat.startMs)
        assertNull(repeat.range)
        assertFalse(repeat.sentence)
        assertEquals(StudyRepeatDecision.Idle, repeat.poll(2_000, true, 200))
    }

    @Test fun manualRangeNeedsAtLeastHalfASecond() {
        val repeat = LanguageStudyRepeat()
        repeat.markStart(1_000)
        assertFalse(repeat.markEnd(1_499, 10_000))
        assertEquals(1_000L, repeat.startMs)
        assertNull(repeat.range)
        assertTrue(repeat.markEnd(1_500, 10_000))
        assertEquals(StudyInterval(1_000, 1_500), repeat.range)
        assertFalse(repeat.sentence)
    }

    @Test fun endIsLimitedToKnownSourceDuration() {
        val repeat = LanguageStudyRepeat()
        repeat.markStart(1_000)
        assertTrue(repeat.markEnd(9_000, 2_000))
        assertEquals(StudyInterval(1_000, 2_000), repeat.range)
    }

    @Test fun durationShorterThanTheStartCannotProduceARepeat() {
        val repeat = LanguageStudyRepeat()
        repeat.markStart(2_000)
        assertFalse(repeat.markEnd(4_000, 1_500))
        assertNull(repeat.range)
        assertEquals(2_000L, repeat.startMs)
    }

    @Test fun unknownDurationDoesNotInventAnEndBoundary() {
        val repeat = LanguageStudyRepeat()
        repeat.markStart(1_000)
        assertTrue(repeat.markEnd(9_000, 0))
        assertEquals(StudyInterval(1_000, 9_000), repeat.range)
        assertTrue(repeat.markEnd(10_000, -1))
        assertEquals(StudyInterval(1_000, 10_000), repeat.range)
    }

    @Test fun anInvalidEndKeepsTheLastValidSelection() {
        val repeat = manualRepeat()
        assertFalse(repeat.markEnd(900, 10_000))
        assertEquals(StudyInterval(1_000, 2_000), repeat.range)
    }

    @Test fun sentenceRepeatUsesTheSourceBoundaryAndSetsItsKind() {
        val repeat = LanguageStudyRepeat()
        assertTrue(repeat.repeatSentence(StudyInterval(1_000, 3_000), 2_000))
        assertEquals(StudyInterval(1_000, 2_000), repeat.range)
        assertEquals(1_000L, repeat.startMs)
        assertTrue(repeat.sentence)
    }

    @Test fun invalidSentenceIntervalsDoNotReplaceAManualLoop() {
        val repeat = manualRepeat()
        assertFalse(repeat.repeatSentence(StudyInterval(-1, 2_000), 10_000))
        assertFalse(repeat.repeatSentence(StudyInterval(3_000, 2_000), 10_000))
        assertFalse(repeat.repeatSentence(StudyInterval(1_000, 1_499), 10_000))
        assertEquals(StudyInterval(1_000, 2_000), repeat.range)
        assertFalse(repeat.sentence)
    }

    @Test fun cancellingASentenceLeavesManualABRepeatsAlone() {
        val manual = manualRepeat()
        manual.cancelSentence()
        assertEquals(StudyInterval(1_000, 2_000), manual.range)
        val sentence = sentenceRepeat()
        sentence.cancelSentence()
        assertNull(sentence.range)
        assertNull(sentence.startMs)
        assertFalse(sentence.sentence)
    }

    @Test fun reachingBRequestsOneSeekAndWaitsWithoutRetryStorms() {
        val repeat = manualRepeat()
        assertEquals(StudyRepeatDecision.Idle, repeat.poll(1_999, false, 100))
        assertEquals(StudyRepeatDecision.Seek(1_000), repeat.poll(2_000, false, 200))
        for (now in 201L..300L) {
            assertEquals(StudyRepeatDecision.Waiting, repeat.poll(2_500, false, now))
        }
        assertEquals(StudyRepeatDecision.Waiting, repeat.poll(2_000, true, 301))
    }

    @Test fun enteringTheRangeAcknowledgesTheSeekAndAllowsTheNextLoop() {
        val repeat = manualRepeat()
        repeat.poll(2_000, false, 100)
        assertEquals(StudyRepeatDecision.Waiting, repeat.poll(999, false, 110))
        assertEquals(StudyRepeatDecision.Idle, repeat.poll(1_000, false, 120))
        assertEquals(StudyRepeatDecision.Idle, repeat.poll(1_999, false, 200))
        assertEquals(StudyRepeatDecision.Seek(1_000), repeat.poll(2_100, false, 300))
    }

    @Test fun aSeekThatNeverArrivesFailsOnceAfterTenSecondsAndClearsTheLoop() {
        val repeat = manualRepeat()
        repeat.poll(2_000, false, 5_000)
        assertEquals(StudyRepeatDecision.Waiting, repeat.poll(2_000, false, 14_999))
        assertEquals(StudyRepeatDecision.Failed, repeat.poll(2_000, false, 15_000))
        assertNull(repeat.range)
        assertNull(repeat.startMs)
        assertFalse(repeat.sentence)
        assertEquals(StudyRepeatDecision.Idle, repeat.poll(2_000, true, 16_000))
    }

    @Test fun eofRequestsARepeatEvenWhenTheLastClockSamplePrecedesB() {
        val repeat = manualRepeat()
        assertEquals(StudyRepeatDecision.Seek(1_000), repeat.poll(1_999, true, 100))
        assertEquals(StudyRepeatDecision.Waiting, repeat.poll(1_000, true, 200))
        assertEquals(StudyRepeatDecision.Idle, repeat.poll(1_000, false, 300))
    }

    @Test fun anEngineReplacementCanDropItsPendingSeekWithoutLosingTheRange() {
        val repeat = sentenceRepeat()
        repeat.poll(2_000, false, 100)
        repeat.resetPending()
        assertEquals(StudyInterval(1_000, 2_000), repeat.range)
        assertTrue(repeat.sentence)
        assertEquals(StudyRepeatDecision.Seek(1_000), repeat.poll(2_000, true, 200))
    }

    @Test fun clearingAfterASeekRemovesAllRepeatState() {
        val repeat = sentenceRepeat()
        repeat.poll(2_000, false, 100)
        repeat.clear()
        assertNull(repeat.startMs)
        assertNull(repeat.range)
        assertFalse(repeat.sentence)
        assertEquals(StudyRepeatDecision.Idle, repeat.poll(2_000, true, 10_100))
    }

    @Test fun longSourcePositionsDoNotOverflowRangeValidationOrTimeout() {
        val repeat = LanguageStudyRepeat()
        repeat.markStart(Long.MAX_VALUE - 500)
        assertTrue(repeat.markEnd(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(StudyRepeatDecision.Seek(Long.MAX_VALUE - 500),
            repeat.poll(Long.MAX_VALUE, true, Long.MAX_VALUE - 10_000))
        assertEquals(StudyRepeatDecision.Failed, repeat.poll(Long.MAX_VALUE, true, Long.MAX_VALUE))
    }

    private fun manualRepeat() = LanguageStudyRepeat().apply {
        markStart(1_000)
        check(markEnd(2_000, 10_000))
    }

    private fun sentenceRepeat() = LanguageStudyRepeat().apply {
        check(repeatSentence(StudyInterval(1_000, 2_000), 10_000))
    }
}
