package io.mirr.shynigsy.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackCompletionGateTest {
    @Test fun normalPlaybackMustCrossTheThresholdBeforeCompletionIsEligible() {
        val gate = PlaybackCompletionGate(0)
        gate.observe(94_999, 100_000, playing = true)
        assertFalse(gate.eligible)
        gate.observe(95_000, 100_000, playing = true)
        assertTrue(gate.eligible)
    }

    @Test fun seekPastServerNinetyPercentAndClientNinetyFivePercentIsNotCompletion() {
        for (target in listOf(92_000L, 95_000L, 97_000L)) {
            val gate = PlaybackCompletionGate(1_000)
            gate.seek(target)
            assertFalse(gate.eligible)
            gate.observe(target, 100_000, playing = false)
            assertFalse(gate.eligible)
        }
    }

    @Test fun seekAboveThresholdDoesNotRearmMerelyBecausePlaybackContinuesThere() {
        val gate = PlaybackCompletionGate(1_000)
        gate.seek(97_000)
        gate.observe(98_000, 100_000, playing = true)
        assertFalse(gate.eligible)
        gate.finish()
        assertTrue("Actual playback after the last seek permits natural EOF completion", gate.eligible)
    }

    @Test fun directSeekToEndCanCloseWithoutMarkingWatched() {
        val gate = PlaybackCompletionGate(1_000)
        gate.observe(2_000, 100_000, playing = true)
        gate.seek(100_000)
        gate.observe(100_000, 100_000, playing = false)
        gate.finish()
        assertFalse(gate.eligible)
    }

    @Test fun restoredNearEndPositionDoesNotPretendToCrossThreshold() {
        val gate = PlaybackCompletionGate(97_000)
        gate.observe(97_000, 100_000, playing = true)
        assertFalse(gate.eligible)
        gate.finish()
        assertFalse("No advancement after restore is not a natural watched ending", gate.eligible)
    }

    @Test fun seekingBackBelowThresholdAllowsANewNormalPlaybackCrossing() {
        val gate = PlaybackCompletionGate(97_000)
        gate.seek(94_000)
        gate.observe(94_500, 100_000, playing = true)
        assertFalse(gate.eligible)
        gate.observe(95_000, 100_000, playing = true)
        assertTrue(gate.eligible)
    }

    @Test fun pausedPositionChangesCannotCountAsPlaybackAdvancement() {
        val gate = PlaybackCompletionGate(94_000)
        gate.observe(95_000, 100_000, playing = false)
        gate.finish()
        assertFalse(gate.eligible)
    }

    @Test fun aLaterSeekClearsAdvancementEvidenceFromBeforeThatSeek() {
        val gate = PlaybackCompletionGate(1_000)
        gate.observe(2_000, 100_000, playing = true)
        gate.seek(97_000)
        gate.finish()
        assertFalse(gate.eligible)
    }

    @Test fun validCompletionRemainsStickyAcrossSeekAndActivityRestore() {
        val gate = PlaybackCompletionGate(94_000)
        gate.observe(95_000, 100_000, playing = true)
        gate.seek(10_000)
        assertTrue(gate.eligible)
        val restored = PlaybackCompletionGate(10_000, initiallyEligible = gate.eligible)
        assertTrue(restored.eligible)
    }

    @Test fun conversionUsesFullSourceClockAndUnknownDurationWaitsForNaturalEof() {
        val converted = PlaybackCompletionGate(9_400_000)
        converted.observe(9_500_000, 10_000_000, playing = true)
        assertTrue(converted.eligible)
        val unknown = PlaybackCompletionGate(0)
        unknown.observe(500, 0, playing = true)
        assertFalse(unknown.eligible)
        unknown.finish()
        assertTrue(unknown.eligible)
    }
}
