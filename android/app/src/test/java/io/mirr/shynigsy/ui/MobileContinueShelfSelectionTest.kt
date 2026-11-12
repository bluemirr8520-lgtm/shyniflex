package io.mirr.shynigsy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileContinueShelfSelectionTest {
    @Test fun leadingReadablePosterWinsOverClippedRemnant() {
        assertEquals("second", continueShelfAnchorKey(listOf(
            ContinueShelfCard("first", -80, 100), ContinueShelfCard("second", 32, 100),
            ContinueShelfCard("third", 144, 100)), 0, 230))
        assertEquals("first", continueShelfAnchorKey(listOf(
            ContinueShelfCard("first", -49, 100), ContinueShelfCard("second", 63, 100)), 0, 230))
    }

    @Test fun narrowViewportStillChoosesTheMostVisiblePoster() {
        assertEquals("second", continueShelfAnchorKey(listOf(
            ContinueShelfCard("first", -180, 200), ContinueShelfCard("second", 32, 200)), 0, 90))
    }

    @Test fun EmptyOrOffscreenCardsCannotSupplyAStaleSelection() {
        assertNull(continueShelfAnchorKey(emptyList(), 0, 200))
        assertNull(continueShelfAnchorKey(listOf(ContinueShelfCard("hidden", 200, 100)), 0, 200))
        assertNull(continueShelfAnchorKey(listOf(ContinueShelfCard("empty", 0, 0)), 0, 200))
        assertNull(continueShelfAnchorKey(listOf(ContinueShelfCard("old", 0, 100)), 0, 0))
    }
}
