package io.mirr.shynigsy

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test

class PlaybackReturnOrientationPolicyTest {
    @Test fun naturallyPortraitDisplaysRetainAllFourDirections() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_PORTRAIT, Surface.ROTATION_0))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_LANDSCAPE, Surface.ROTATION_90))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_PORTRAIT, Surface.ROTATION_180))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_LANDSCAPE, Surface.ROTATION_270))
    }

    @Test fun naturallyLandscapeDisplaysDoNotAssumePortraitAtZeroRotation() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_LANDSCAPE, Surface.ROTATION_0))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_PORTRAIT, Surface.ROTATION_90))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_LANDSCAPE, Surface.ROTATION_180))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_PORTRAIT, Surface.ROTATION_270))
    }

    @Test fun naturallyPortraitPlatformOverlayReversesOnlyLandscapeDirections() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_PORTRAIT, Surface.ROTATION_0, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_LANDSCAPE, Surface.ROTATION_90, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_PORTRAIT, Surface.ROTATION_180, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_LANDSCAPE, Surface.ROTATION_270, true))
    }

    @Test fun naturallyLandscapePlatformOverlayReversesOnlyPortraitDirections() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_LANDSCAPE, Surface.ROTATION_0, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_PORTRAIT, Surface.ROTATION_90, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_LANDSCAPE, Surface.ROTATION_180, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            fixedPlaybackReturnOrientation(Configuration.ORIENTATION_PORTRAIT, Surface.ROTATION_270, true))
    }

    @Test fun invalidConfigurationAndSavedFixedValuesNeverBecomeALock() {
        assertNull(fixedPlaybackReturnOrientation(Configuration.ORIENTATION_UNDEFINED, Surface.ROTATION_0))
        assertNull(fixedPlaybackReturnOrientation(Configuration.ORIENTATION_PORTRAIT, -1))
        assertNull(fixedPlaybackReturnOrientation(Configuration.ORIENTATION_LANDSCAPE, 4))
        assertFalse(isFixedPlaybackReturnOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))
        assertFalse(isFixedPlaybackReturnOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))
        assertFalse(isFixedPlaybackReturnOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED))
    }

    @Test fun physicalPoseOpposesTheCompensatingDisplayRotation() {
        assertTrue(playbackReturnPoseMatches(Surface.ROTATION_0, 0))
        assertTrue(playbackReturnPoseMatches(Surface.ROTATION_90, 270))
        assertTrue(playbackReturnPoseMatches(Surface.ROTATION_180, 180))
        assertTrue(playbackReturnPoseMatches(Surface.ROTATION_270, 90))
        assertFalse(playbackReturnPoseMatches(Surface.ROTATION_90, 90))
        assertFalse(playbackReturnPoseMatches(Surface.ROTATION_270, 270))
    }

    @Test fun nearbyAnglesWrapAroundZeroWithoutAcceptingDiagonalOrFlatPoses() {
        assertTrue(playbackReturnPoseMatches(Surface.ROTATION_0, 340))
        assertTrue(playbackReturnPoseMatches(Surface.ROTATION_0, 20))
        assertFalse(playbackReturnPoseMatches(Surface.ROTATION_0, 339))
        assertFalse(playbackReturnPoseMatches(Surface.ROTATION_0, 21))
        assertFalse(playbackReturnPoseMatches(Surface.ROTATION_0, -1))
        assertFalse(playbackReturnPoseMatches(Surface.ROTATION_0, 360))
        assertFalse(playbackReturnPoseMatches(-1, 0))
        assertFalse(playbackReturnPoseMatches(4, 0))
    }

    @Test fun returnedPoseMustRemainAlignedBeforeRestoringAutomaticRotation() {
        val gate = PlaybackReturnPoseGate()
        assertFalse(gate.isStable(1_000))
        assertEquals(450L, gate.observe(Surface.ROTATION_0, 2, 1_000))
        assertFalse(gate.isStable(1_449))
        assertTrue(gate.isStable(1_450))
        assertEquals(0L, gate.observe(Surface.ROTATION_0, 358, 1_450))
    }

    @Test fun wrongOrUnknownPoseCancelsThePendingRelease() {
        val gate = PlaybackReturnPoseGate()
        gate.observe(Surface.ROTATION_90, 270, 1_000)
        assertNull(gate.observe(Surface.ROTATION_90, 0, 1_300))
        assertFalse(gate.isStable(2_000))
        assertEquals(450L, gate.observe(Surface.ROTATION_90, 270, 2_000))
        assertNull(gate.observe(Surface.ROTATION_90, -1, 2_100))
        assertFalse(gate.isStable(3_000))
    }

    @Test fun pauseOrRecreationRequiresAFreshMatchingPose() {
        val gate = PlaybackReturnPoseGate()
        gate.observe(Surface.ROTATION_0, 0, 1_000)
        gate.reset()
        assertFalse(gate.isStable(2_000))
        assertEquals(450L, gate.observe(Surface.ROTATION_0, 0, 2_000))
        assertFalse(gate.isStable(2_449))
    }

    @Test fun backwardsClockCannotReleaseFromAnOlderObservation() {
        val gate = PlaybackReturnPoseGate()
        gate.observe(Surface.ROTATION_0, 0, 2_000)
        assertFalse(gate.isStable(1_500))
        assertEquals(450L, gate.observe(Surface.ROTATION_0, 0, 1_500))
        assertTrue(gate.isStable(1_950))
    }
}
