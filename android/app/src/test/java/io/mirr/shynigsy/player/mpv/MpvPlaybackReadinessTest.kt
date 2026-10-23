package io.mirr.shynigsy.player.mpv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvPlaybackReadinessTest {
    @Test fun fileLoadedAndAnEmptyCachePauseDoNotFinishInitialRestart() {
        val state = MpvPlaybackReadiness()
        state.fileLoaded(cachePaused = false)
        state.cachePausedChanged(false)
        assertTrue(state.restartPending)
        assertFalse(state.ready)

        // Paused starts and audio-only files have no advancing video clock to
        // wait for. The native restart is sufficient for either kind of load.
        state.playbackRestarted()
        assertTrue(state.ready)
    }

    @Test fun everySeekRequiresAnotherRestartDespiteRepeatedCacheNotifications() {
        val state = MpvPlaybackReadiness()
        state.fileLoaded(cachePaused = false)
        state.playbackRestarted()
        assertTrue(state.ready)

        repeat(2) {
            state.seekStarted()
            state.seekStarted()
            state.cachePausedChanged(false)
            state.fileLoaded(cachePaused = false)
            assertFalse(state.ready)
            state.playbackRestarted()
            assertTrue(state.ready)
        }
    }

    @Test fun restartingWhileCachePausedWaitsUntilTheCacheRecovers() {
        val state = MpvPlaybackReadiness()
        state.fileLoaded(cachePaused = true)
        state.playbackRestarted()
        assertFalse(state.restartPending)
        assertFalse(state.ready)

        state.cachePausedChanged(false)
        assertTrue(state.ready)
        state.cachePausedChanged(true)
        assertFalse(state.ready)
        state.cachePausedChanged(false)
        assertTrue(state.ready)
    }

    @Test fun cacheRecoveryDuringASeekCannotClearTheRestartRequirement() {
        val state = MpvPlaybackReadiness()
        state.playbackRestarted()
        state.cachePausedChanged(true)
        state.seekStarted()
        assertTrue(state.cachePaused)

        state.cachePausedChanged(false)
        assertFalse(state.ready)
        state.playbackRestarted()
        assertTrue(state.ready)
    }

    @Test fun replacementLoadDropsOldCachePauseAndRearmsInitialRestart() {
        val state = MpvPlaybackReadiness()
        state.playbackRestarted()
        state.cachePausedChanged(true)
        state.reset()
        assertFalse(state.cachePaused)
        assertTrue(state.restartPending)
        assertFalse(state.ready)

        state.fileLoaded(cachePaused = false)
        assertFalse(state.ready)
        state.playbackRestarted()
        assertTrue(state.ready)
    }
}
