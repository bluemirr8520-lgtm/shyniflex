package io.mirr.shynigsy.player

import org.junit.Assert.*
import org.junit.Test

class EnginePreflightPolicyTest {
    @Test fun userInputSettlesCurrentPlayerBeforeTheActionIsDispatched() {
        assertEquals(EngineInteraction.START_MPV, engineInteraction(false, true, false, false))
        assertEquals(EngineInteraction.ACCEPT_MPV, engineInteraction(true, false, true, false))
        assertEquals(EngineInteraction.FALLBACK_FROM_EXO, engineInteraction(true, false, false, true))
        assertEquals(EngineInteraction.NONE, engineInteraction(true, false, false, false))
    }

    @Test fun lateProbeResultsCannotReplaceANewOrBackgroundedPlayer() {
        fun commit(pending: Boolean = true, samePlayer: Boolean = true, sameGeneration: Boolean = true,
            sameSource: Boolean = true, sameAccount: Boolean = true, visible: Boolean = true,
            closing: Boolean = false) = mayCommitMpvProbe(pending, samePlayer, sameGeneration, sameSource, sameAccount, visible, closing)
        assertTrue(commit())
        assertFalse(commit(pending = false)) // Timeout or user interaction selected MPV.
        assertFalse(commit(samePlayer = false))
        assertFalse(commit(sameGeneration = false))
        assertFalse(commit(sameSource = false))
        assertFalse(commit(sameAccount = false))
        assertFalse(commit(visible = false))
        assertFalse(commit(closing = true))
    }

    @Test fun probeOrUnvalidatedExoCannotMarkPlaybackComplete() {
        assertFalse(playbackEngineSettled(mpvProbePending = true, exoPreparing = false))
        assertFalse(playbackEngineSettled(mpvProbePending = false, exoPreparing = true))
        assertTrue(playbackEngineSettled(mpvProbePending = false, exoPreparing = false))
    }
}
