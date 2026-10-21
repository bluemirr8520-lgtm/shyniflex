package io.mirr.shynigsy.player.mpv

import org.junit.Assert.*
import org.junit.Test

class MpvEofStateTest {
    @Test fun confirmedNativeEofFinishesEvenWhenTheOriginalNotificationWasMissed() {
        val state = MpvEofState()
        assertTrue(state.confirm(loaded = true, stopRequested = false, nativeEof = true, nativeSeeking = false))
        assertTrue(state.ended)
        assertFalse(state.confirm(true, false, true, false))
    }

    @Test fun loadingStopAndNativeSeekCannotFinishPlayback() {
        assertFalse(MpvEofState().confirm(false, false, true, false))
        assertFalse(MpvEofState().confirm(true, true, true, false))
        assertFalse(MpvEofState().confirm(true, false, true, true))
    }

    @Test fun staleEofIsBlockedUntilARequestedSeekActuallyRestarts() {
        val state = MpvEofState()
        state.seekStarted()
        assertFalse(state.confirm(true, false, true, false))
        assertFalse(state.ended)
        state.playbackRestarted()
        assertFalse(state.confirm(true, false, false, false))
        assertTrue(state.confirm(true, false, true, false))
    }

    @Test fun seekingToTheEndIsRecognizedAfterTheRestart() {
        val state = MpvEofState()
        state.seekStarted()
        state.playbackRestarted()
        assertTrue(state.confirm(true, false, true, false))
    }

    @Test fun observedSeekThenNativeCompletionCanRecoverAMissedRestartEvent() {
        val state = MpvEofState()
        state.seekStarted()
        state.nativeSeekingChanged(false)
        assertFalse(state.confirm(true, false, true, false))
        state.nativeSeekingChanged(true)
        assertFalse(state.confirm(true, false, true, true))
        state.nativeSeekingChanged(false)
        assertTrue(state.confirm(true, false, true, false))
    }

    @Test fun replayAndReplacementClearThePreviousEndLatch() {
        val state = MpvEofState()
        assertTrue(state.confirm(true, false, true, false))
        state.reset()
        assertFalse(state.ended)
        assertFalse(state.confirm(false, false, true, false))
        assertFalse(state.confirm(true, false, false, false))
    }

    @Test fun missingNativeEofNeverInfersCompletionFromAPositionOrConversionOffset() {
        val state = MpvEofState()
        assertFalse(state.confirm(true, false, null, false))
        assertFalse(state.confirm(true, false, false, false))
        assertFalse(state.ended)
    }
}
