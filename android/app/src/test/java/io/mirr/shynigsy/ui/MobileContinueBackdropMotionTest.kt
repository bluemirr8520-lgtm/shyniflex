package io.mirr.shynigsy.ui

import org.junit.Assert.*
import org.junit.Test

class MobileContinueBackdropMotionTest {
    private val keys = listOf("first", "second", "third")

    @Test fun fractionIncludesTheSpaceBetweenPosters() {
        assertEquals(ContinueBackdropMotion("first", "second", 0f), continueBackdropMotion(keys, 0, 0, 100, 20))
        assertEquals(ContinueBackdropMotion("first", "second", .5f), continueBackdropMotion(keys, 0, 60, 100, 20))
        assertEquals(ContinueBackdropMotion("first", "second", 1f), continueBackdropMotion(keys, 0, 120, 100, 20))
    }

    @Test fun crossingAPosterBoundaryKeepsTheSameImageAtTheJoin() {
        val before = requireNotNull(continueBackdropMotion(keys, 0, 120, 100, 20))
        val after = requireNotNull(continueBackdropMotion(keys, 1, 0, 100, 20))
        assertEquals(before.toKey, after.fromKey)
        assertEquals(1f, before.fraction, 0f)
        assertEquals(0f, after.fraction, 0f)
        assertEquals("third", after.toKey)
    }

    @Test fun reverseMovementReducesTheSameTransitionFraction() {
        val forward = requireNotNull(continueBackdropMotion(keys, 1, 90, 100, 20))
        val reversed = requireNotNull(continueBackdropMotion(keys, 1, 30, 100, 20))
        assertEquals(forward.fromKey, reversed.fromKey)
        assertEquals(forward.toKey, reversed.toKey)
        assertEquals(.75f, forward.fraction, 0f)
        assertEquals(.25f, reversed.fraction, 0f)
    }

    @Test fun theFinalPosterAndASinglePosterNeverWrap() {
        assertEquals(ContinueBackdropMotion("third", null, 0f), continueBackdropMotion(keys, 2, 80, 100, 20))
        assertEquals(ContinueBackdropMotion("only", null, 0f), continueBackdropMotion(listOf("only"), 0, 80, 100, 20))
    }

    @Test fun staleIndexOrInvalidMeasurementsDoNotSelectAnotherItem() {
        assertNull(continueBackdropMotion(emptyList(), 0, 0, 100, 20))
        assertNull(continueBackdropMotion(keys, -1, 0, 100, 20))
        assertNull(continueBackdropMotion(keys, 3, 0, 100, 20))
        assertNull(continueBackdropMotion(keys, 0, -1, 100, 20))
        assertNull(continueBackdropMotion(keys, 0, 0, 0, 20))
        assertNull(continueBackdropMotion(keys, 0, 0, -1, 20))
        assertNull(continueBackdropMotion(keys, 0, 0, 100, -1))
    }

    @Test fun transientOffsetsAreClampedAndLargeBoundsCannotOverflow() {
        assertEquals(1f, requireNotNull(continueBackdropMotion(keys, 0, Int.MAX_VALUE, 100, 20)).fraction, 0f)
        assertEquals(.5f, requireNotNull(continueBackdropMotion(keys, 0, Int.MAX_VALUE,
            Int.MAX_VALUE, Int.MAX_VALUE)).fraction, 0f)
        assertEquals(.5f, requireNotNull(continueBackdropMotion(keys, 0, 50, 100, 0)).fraction, 0f)
    }
}
