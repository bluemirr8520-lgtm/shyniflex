package io.mirr.shynigsy.player

import org.junit.Assert.*
import org.junit.Test

class TvSubtitlePositionInputTest {
    @Test fun nudgesUseOnePercentAndStayBoundedWhileResetReturnsToTheOrigin() {
        val position = TvSubtitlePosition(7, -9)
        assertEquals(TvSubtitlePosition(7, -10), moveTvSubtitlePosition(position, TvSubtitleMove.UP))
        assertEquals(TvSubtitlePosition(7, -8), moveTvSubtitlePosition(position, TvSubtitleMove.DOWN))
        assertEquals(TvSubtitlePosition(6, -9), moveTvSubtitlePosition(position, TvSubtitleMove.LEFT))
        assertEquals(TvSubtitlePosition(8, -9), moveTvSubtitlePosition(position, TvSubtitleMove.RIGHT))
        assertEquals(TvSubtitlePosition(0, 0), moveTvSubtitlePosition(position, TvSubtitleMove.RESET))
        for (move in TvSubtitleMove.entries) {
            for (edge in listOf(TvSubtitlePosition(-80, -80), TvSubtitlePosition(80, 80),
                TvSubtitlePosition(Int.MIN_VALUE, Int.MAX_VALUE))) {
                val moved = moveTvSubtitlePosition(edge, move)
                assertTrue(moved.x in -80..80)
                assertTrue(moved.y in -80..80)
            }
        }
        assertEquals(TvSubtitlePosition(-80, -80), moveTvSubtitlePosition(TvSubtitlePosition(-80, -80), TvSubtitleMove.UP))
        assertEquals(TvSubtitlePosition(80, 80), moveTvSubtitlePosition(TvSubtitlePosition(80, 80), TvSubtitleMove.RIGHT))
    }

    @Test fun thePressWhichOpenedThePanelCannotMoveOrCommitItsFirstControl() {
        val input = TvSubtitlePositionInput(shownAtMs = 1_000)
        assertNull(input.keyDown(23, 999, 1_010, 0, TvSubtitleMove.UP))
        assertNull(input.keyDown(23, 1_000, 1_010, 0, TvSubtitleMove.UP))
        assertNull(input.keyDown(23, 999, 1_100, 2, TvSubtitleMove.UP))
        assertFalse(input.keyUp(23, 999))
        assertFalse(input.cancel())
    }

    @Test fun aFreshPressMovesOnceAndOnlyItsMatchingReleaseCommitsOnce() {
        val input = TvSubtitlePositionInput(100)
        assertEquals(TvSubtitleMove.DOWN, input.keyDown(23, 110, 110, 0, TvSubtitleMove.DOWN))
        assertNull(input.keyDown(23, 110, 111, 0, TvSubtitleMove.DOWN))
        assertFalse(input.keyUp(66, 110))
        assertFalse(input.keyUp(23, 109))
        assertTrue(input.keyUp(23, 110))
        assertFalse(input.keyUp(23, 110))
        assertNull(input.keyDown(23, 110, 220, 1, TvSubtitleMove.DOWN))
    }

    @Test fun heldRepeatsRequireTheSameKeyGestureAndActionAndAtLeastEightyMilliseconds() {
        val input = TvSubtitlePositionInput(100)
        assertEquals(TvSubtitleMove.RIGHT, input.keyDown(23, 110, 110, 0, TvSubtitleMove.RIGHT))
        assertNull(input.keyDown(23, 110, 189, 1, TvSubtitleMove.RIGHT))
        assertNull(input.keyDown(66, 110, 190, 1, TvSubtitleMove.RIGHT))
        assertNull(input.keyDown(23, 111, 190, 1, TvSubtitleMove.RIGHT))
        assertNull(input.keyDown(23, 110, 190, 1, TvSubtitleMove.LEFT))
        assertEquals(TvSubtitleMove.RIGHT, input.keyDown(23, 110, 190, 2, TvSubtitleMove.RIGHT))
        assertNull(input.keyDown(23, 110, 269, 3, TvSubtitleMove.RIGHT))
        assertEquals(TvSubtitleMove.RIGHT, input.keyDown(23, 110, 270, 4, TvSubtitleMove.RIGHT))
        assertNull(input.keyDown(23, 110, 200, 5, TvSubtitleMove.RIGHT))
        assertTrue(input.keyUp(23, 110))
    }

    @Test fun anOrphanRepeatOrReleaseCannotBeginAnAdjustment() {
        val input = TvSubtitlePositionInput(100)
        assertNull(input.keyDown(23, 110, 200, 1, TvSubtitleMove.LEFT))
        assertFalse(input.keyUp(23, 110))
        assertEquals(TvSubtitleMove.LEFT, input.keyDown(23, 210, 210, 0, TvSubtitleMove.LEFT))
        assertTrue(input.keyUp(23, 210))
    }

    @Test fun resetHappensOnceEvenWhenItsConfirmKeyIsHeld() {
        val input = TvSubtitlePositionInput(100)
        assertEquals(TvSubtitleMove.RESET, input.keyDown(23, 110, 110, 0, TvSubtitleMove.RESET))
        assertNull(input.keyDown(23, 110, 200, 1, TvSubtitleMove.RESET))
        assertNull(input.keyDown(23, 110, 500, 2, TvSubtitleMove.RESET))
        assertTrue(input.keyUp(23, 110))
        assertEquals(TvSubtitleMove.RESET, input.keyDown(23, 600, 600, 0, TvSubtitleMove.RESET))
    }

    @Test fun focusLossCancellationRejectsOldEventsUntilANewPhysicalDown() {
        val input = TvSubtitlePositionInput(100)
        assertEquals(TvSubtitleMove.UP, input.keyDown(23, 110, 110, 0, TvSubtitleMove.UP))
        assertTrue(input.cancel())
        assertFalse(input.cancel())
        assertNull(input.keyDown(23, 110, 120, 0, TvSubtitleMove.UP))
        assertNull(input.keyDown(23, 110, 210, 1, TvSubtitleMove.UP))
        assertFalse(input.keyUp(23, 110))
        assertEquals(TvSubtitleMove.DOWN, input.keyDown(23, 220, 220, 0, TvSubtitleMove.DOWN))
    }

    @Test fun aNewKeyGestureCannotBeFinishedByThePreviousKeysRelease() {
        val input = TvSubtitlePositionInput(100)
        assertEquals(TvSubtitleMove.LEFT, input.keyDown(21, 110, 110, 0, TvSubtitleMove.LEFT))
        assertEquals(TvSubtitleMove.RIGHT, input.keyDown(22, 120, 120, 0, TvSubtitleMove.RIGHT))
        assertFalse(input.keyUp(21, 110))
        assertNull(input.keyDown(21, 110, 210, 1, TvSubtitleMove.LEFT))
        assertEquals(TvSubtitleMove.RIGHT, input.keyDown(22, 120, 200, 1, TvSubtitleMove.RIGHT))
        assertTrue(input.keyUp(22, 120))
    }

    @Test fun malformedEventTimesAndNegativeRepeatCountsCannotArmAnAdjustment() {
        val input = TvSubtitlePositionInput(100)
        assertNull(input.keyDown(23, 110, 109, 0, TvSubtitleMove.UP))
        assertNull(input.keyDown(23, 110, 110, -1, TvSubtitleMove.UP))
        assertFalse(input.keyUp(23, 110))
        assertEquals(TvSubtitleMove.UP, input.keyDown(23, 120, 120, 0, TvSubtitleMove.UP))
    }
}
