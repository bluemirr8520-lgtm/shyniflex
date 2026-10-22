package io.mirr.shynigsy.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryAutoLoadTest {
    private fun page(loaded: Int = 60, displayed: Int = loaded, total: Int = 180,
        last: Int = displayed + 1, measured: Int = displayed + 2, busy: Boolean = false, error: Boolean = false) =
        LibraryLoadSnapshot(loaded, displayed, total, busy, error, measured, last)

    @Test fun requestsAtTheSixItemBoundaryIncludingBothHeaders() {
        val gate = LibraryAutoLoadGate()
        assertFalse(gate.claim(page(last = 54)))
        assertTrue(gate.claim(page(last = 55)))
    }

    @Test fun sameOffsetIsNeverRequestedAgainForScrollingOrStateJitter() {
        val gate = LibraryAutoLoadGate()
        assertTrue(gate.claim(page()))
        assertFalse(gate.claim(page(last = 56)))
        assertFalse(gate.claim(page(last = 20)))
        assertFalse(gate.claim(page(last = 61)))
        assertFalse(gate.claim(page(busy = true)))
        assertFalse(gate.claim(page(busy = false)))
    }

    @Test fun failedAttemptIsNotRetriedWhenErrorIsClearedOrLayoutChanges() {
        val gate = LibraryAutoLoadGate()
        assertTrue(gate.claim(page()))
        assertFalse(gate.claim(page(busy = true)))
        assertFalse(gate.claim(page(error = true)))
        assertFalse(gate.claim(page(error = false, measured = 63)))
        assertFalse(gate.claim(page(last = 55)))
    }

    @Test fun changingDatasetAllowsTheSameOffsetOnceAgain() {
        val previousDataset = LibraryAutoLoadGate()
        assertTrue(previousDataset.claim(page()))
        assertFalse(previousDataset.claim(page()))
        // remember(datasetKey) constructs this fresh gate for an explicit refresh,
        // library/search/filter change, or account switch.
        val newDataset = LibraryAutoLoadGate()
        assertTrue(newDataset.claim(page()))
        assertFalse(newDataset.claim(page()))
    }

    @Test fun advancedPageWaitsForItsOwnMeasuredLayoutAndHasItsOwnOffset() {
        val gate = LibraryAutoLoadGate()
        assertTrue(gate.claim(page()))
        assertFalse(gate.claim(page(loaded = 120, measured = 62, last = 61)))
        assertFalse(gate.claim(page(loaded = 120, last = 114)))
        assertTrue(gate.claim(page(loaded = 120, last = 115)))
        assertFalse(gate.claim(page(loaded = 120, last = 121)))
        // An older count reappearing without a new dataset cannot repeat a request.
        assertFalse(gate.claim(page()))
    }

    @Test fun emptyAndTerminalPagesStopButACompletelyHiddenPageCanAdvance() {
        val gate = LibraryAutoLoadGate()
        assertFalse(gate.claim(page(loaded = 0, total = 100, measured = 2, last = 1)))
        assertFalse(gate.claim(page(loaded = 60, total = 60)))
        assertFalse(gate.claim(page(loaded = 60, total = 20)))
        assertFalse(gate.claim(page(loaded = 60, displayed = 0, measured = 2, last = -1)))
        assertTrue(gate.claim(page(loaded = 60, displayed = 0, measured = 2, last = 1)))
        assertFalse(gate.claim(page(loaded = 60, displayed = 0, measured = 2, last = 1)))
    }

    @Test fun BusyErrorAndUnmeasuredLayoutsDoNotConsumeAnUnrequestedOffset() {
        val gate = LibraryAutoLoadGate()
        assertFalse(gate.claim(page(busy = true)))
        assertFalse(gate.claim(page(error = true)))
        assertFalse(gate.claim(page(measured = 61)))
        assertFalse(gate.claim(page(last = -1)))
        assertTrue(gate.claim(page()))
    }
}
