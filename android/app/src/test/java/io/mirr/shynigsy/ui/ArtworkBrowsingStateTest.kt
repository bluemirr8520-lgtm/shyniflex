package io.mirr.shynigsy.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkBrowsingStateTest {
    @Test fun repeatedLifecycleCallbacksAreIdempotentAndUnknownStopsAreHarmless() {
        val state = ArtworkBrowsingState()
        val owner = Any()
        assertFalse(state.isBrowsing())
        state.stopped(owner)
        state.started(owner)
        state.started(owner)
        assertTrue(state.isBrowsing())
        state.stopped(owner)
        state.stopped(owner)
        assertFalse(state.isBrowsing())
    }

    @Test fun overlappingOwnersUseIdentityAndLastStopReturnsTheBackgroundBudget() {
        class EqualOwner {
            override fun equals(other: Any?) = other is EqualOwner
            override fun hashCode() = 1
        }
        val state = ArtworkBrowsingState()
        val first = EqualOwner()
        val replacement = EqualOwner()
        state.started(first)
        state.started(replacement)
        state.stopped(EqualOwner())
        state.stopped(first)
        assertTrue(state.isBrowsing())
        state.stopped(replacement)
        assertFalse(state.isBrowsing())
    }
}
