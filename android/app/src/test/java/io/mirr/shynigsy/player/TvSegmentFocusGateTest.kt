package io.mirr.shynigsy.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSegmentFocusGateTest {
    private val intro = TvSegmentMarkerKey(31, "intro", 10_000, 30_000)
    private val credits = TvSegmentMarkerKey(31, "credits", 300_000, 350_000)

    @Test fun repeatedTicksNeverReplaceTheFocusAfterTheFirstSuccessfulOffer() {
        val gate = TvSegmentFocusGate()
        var requests = 0
        assertTrue(gate.offer(intro, true, true) { requests++; true })
        repeat(20) { assertFalse(gate.offer(intro, true, true) { requests++; true }) }
        assertEquals(1, requests)
    }

    @Test fun anUnsuccessfulFocusAttemptRemainsPendingUntilItCanSucceed() {
        val gate = TvSegmentFocusGate()
        var requests = 0
        assertFalse(gate.offer(intro, true, true) { requests++; false })
        assertTrue(gate.offer(intro, true, true) { requests++; true })
        assertFalse(gate.offer(intro, true, true) { requests++; true })
        assertEquals(2, requests)
    }

    @Test fun aMarkerObservedWhileLoadingWaitsForEligibility() {
        val gate = TvSegmentFocusGate()
        var requests = 0
        repeat(5) { assertFalse(gate.offer(intro, false, true) { requests++; true }) }
        assertEquals(0, requests)
        assertTrue(gate.offer(intro, true, true) { requests++; true })
        assertEquals(1, requests)
    }

    @Test fun temporaryLoadingOrHiddenControlsDoNotRearmAnAlreadyHandledMarker() {
        val gate = TvSegmentFocusGate()
        var requests = 0
        assertTrue(gate.offer(intro, true, true) { requests++; true })
        assertFalse(gate.offer(intro, false, false) { requests++; true })
        assertFalse(gate.offer(intro, true, true) { requests++; true })
        assertEquals(1, requests)
    }

    @Test fun aDifferentIntervalOrPhysicalPartGetsItsOwnFocusOffer() {
        val gate = TvSegmentFocusGate()
        var requests = 0
        for (key in listOf(intro, credits, credits.copy(partId = 32), credits.copy(partId = 32, endMs = 360_000))) {
            assertTrue(gate.offer(key, true, true) { requests++; true })
            assertFalse(gate.offer(key, true, true) { requests++; true })
        }
        assertEquals(4, requests)
    }

    @Test fun genuinelyLeavingAndReenteringTheSameIntervalOffersFocusAgain() {
        val gate = TvSegmentFocusGate()
        var requests = 0
        assertTrue(gate.offer(intro, true, true) { requests++; true })
        assertFalse(gate.offer(null, true, true) { requests++; true })
        assertTrue(gate.offer(intro, true, true) { requests++; true })
        assertEquals(2, requests)
    }

    @Test fun heldConfirmAndDirectionKeysAllNeedReleaseBeforeAButtonCanBeFocused() {
        val gate = TvSegmentFocusGate()
        var requests = 0
        gate.keyDown(23) // DPAD_CENTER; integers keep this policy independent of Android.
        gate.keyDown(23) // Held repeats are one pressed key, not additional releases.
        gate.keyDown(22) // DPAD_RIGHT.
        assertFalse(gate.offer(intro, true, true) { requests++; true })
        gate.keyUp(999) // An unrelated release cannot clear the active press.
        gate.keyUp(23)
        assertFalse(gate.offer(intro, true, true) { requests++; true })
        gate.keyUp(22)
        assertTrue(gate.offer(intro, true, true) { requests++; true })
        assertEquals(1, requests)
    }

    @Test fun clearingLostKeyStateUnblocksAPendingOfferWithoutRearmingHandledFocus() {
        val gate = TvSegmentFocusGate()
        var requests = 0
        gate.keyDown(19)
        assertFalse(gate.offer(intro, true, true) { requests++; true })
        gate.clearHeldKeys()
        assertTrue(gate.offer(intro, true, true) { requests++; true })
        gate.keyDown(20)
        gate.clearHeldKeys()
        assertFalse(gate.offer(intro, true, true) { requests++; true })
        assertEquals(1, requests)
    }

    @Test fun dialogsAndOtherFocusOwnersCanDeferAnOtherwiseEligibleOffer() {
        val gate = TvSegmentFocusGate()
        var requests = 0
        repeat(5) { assertFalse(gate.offer(credits, true, false) { requests++; true }) }
        assertEquals(0, requests)
        assertTrue(gate.offer(credits, true, true) { requests++; true })
        assertFalse(gate.offer(credits, true, false) { requests++; true })
        assertFalse(gate.offer(credits, true, true) { requests++; true })
        assertEquals(1, requests)
    }

    @Test fun manualFocusOnAnObservedActionPreventsAnyLaterAutomaticReplacement() {
        val gate = TvSegmentFocusGate()
        var requests = 0
        gate.focused() // No marker is observed yet; it must not consume the first real interval.
        assertFalse(gate.offer(intro, true, false) { requests++; true })
        gate.focused()
        assertFalse(gate.offer(intro, true, true) { requests++; true })
        assertEquals(0, requests)
        assertTrue(gate.offer(credits, true, true) { requests++; true })
        assertEquals(1, requests)
    }
}
