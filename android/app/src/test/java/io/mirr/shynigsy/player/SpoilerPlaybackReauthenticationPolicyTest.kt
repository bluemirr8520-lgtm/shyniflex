package io.mirr.shynigsy.player

import org.junit.Assert.*
import org.junit.Test

class SpoilerPlaybackReauthenticationPolicyTest {
    @Test fun longGrantsReauthenticateFiveMinutesBeforeTheServerDeadline() {
        assertEquals(3_300_000L, SpoilerPlaybackReauthenticationPolicy.delayMs(3_600_000, 3_600_000))
        assertEquals(0L, SpoilerPlaybackReauthenticationPolicy.delayMs(300_000, 3_600_000))
    }

    @Test fun shortGrantsKeepOneThirdOfTheirLifetimeForReconnection() {
        assertEquals(60_000L, SpoilerPlaybackReauthenticationPolicy.delayMs(90_000, 90_000))
        assertEquals(2_000L, SpoilerPlaybackReauthenticationPolicy.delayMs(3_000, 3_000))
        assertEquals(0L, SpoilerPlaybackReauthenticationPolicy.delayMs(0, 3_000))
        assertEquals(0L, SpoilerPlaybackReauthenticationPolicy.delayMs(-1, 3_000))
    }

    @Test fun networkAndFinalProgressBudgetsFitInsideTheExistingDeadline() {
        assertEquals(30_000L, SpoilerPlaybackReauthenticationPolicy.timeoutMs(300_000))
        assertEquals(750L, SpoilerPlaybackReauthenticationPolicy.timeoutMs(1_000))
        assertEquals(0L, SpoilerPlaybackReauthenticationPolicy.timeoutMs(250))
        assertEquals(0L, SpoilerPlaybackReauthenticationPolicy.timeoutMs(-1))
        assertEquals(2_000L, SpoilerPlaybackReauthenticationPolicy.flushTimeoutMs(300_000))
        assertTrue(SpoilerPlaybackReauthenticationPolicy.flushTimeoutMs(1_000) < 750)
    }

    @Test fun rotationStopsOldAndNewProgressUntilTheNewContextIsReady() {
        val epochs = PlaybackAuthenticationEpoch()
        val original = epochs.captureProgress()!!
        val rotation = epochs.beginRotation()
        assertFalse(epochs.isCurrent(original))
        assertNull(epochs.captureProgress())
        assertTrue(epochs.completeRotation(rotation))
        assertEquals(rotation, epochs.captureProgress()!!)
    }

    @Test fun aCancelledOrReplacedRotationCannotReenableWritesForItsOldContext() {
        val epochs = PlaybackAuthenticationEpoch()
        val oldRotation = epochs.beginRotation()
        val replacement = epochs.beginRotation()
        assertFalse(epochs.completeRotation(oldRotation))
        assertNull(epochs.captureProgress())
        assertTrue(epochs.completeRotation(replacement))
        assertEquals(replacement, epochs.captureProgress()!!)
        assertFalse(epochs.isCurrent(oldRotation))
    }
}
