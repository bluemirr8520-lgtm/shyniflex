package io.mirr.shynigsy.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekSkipFeedbackTest {
    @Test fun tenSecondSkipsUseTheRequestedArrowWithoutASignOrClock() {
        assertEquals("10초<", seekSkipFeedback(30_000, 20_000, true).text)
        assertEquals("10초>", seekSkipFeedback(30_000, 40_000, false).text)
    }

    @Test fun swipesShowTheirCumulativeDeltaFromTheGestureOrigin() {
        assertEquals("24초>", seekSkipFeedback(10_000, 34_000, false).text)
        assertEquals("8초<", seekSkipFeedback(10_000, 2_000, true).text)
    }

    @Test fun clampedTargetsShowTheActualAvailableDistance() {
        assertEquals("3초<", seekSkipFeedback(3_000, 0, false).text)
        assertEquals("2초>", seekSkipFeedback(58_000, 59_999, true).text)
    }

    @Test fun wholeSecondsRoundAndSubSecondDeltasRemainVisible() {
        assertEquals("2초>", seekSkipFeedback(0, 1_500, true).text)
        assertEquals("1초>", seekSkipFeedback(0, 1_499, true).text)
        assertEquals("0.5초>", seekSkipFeedback(0, 500, true).text)
        assertEquals("0.025초<", seekSkipFeedback(500, 475, false).text)
    }

    @Test fun zeroMovementUsesTheBoundaryDirectionHint() {
        val backward = seekSkipFeedback(0, 0, false)
        assertEquals("0초<", backward.text)
        assertFalse(backward.forward)
        val forward = seekSkipFeedback(60_000, 60_000, true)
        assertEquals("0초>", forward.text)
        assertTrue(forward.forward)
    }

    @Test fun accessibilityDescriptionStatesDirectionAndDistance() {
        assertEquals("앞으로 10초", seekSkipFeedback(0, 10_000, false).description)
        assertEquals("뒤로 10초", seekSkipFeedback(10_000, 0, true).description)
    }
}
