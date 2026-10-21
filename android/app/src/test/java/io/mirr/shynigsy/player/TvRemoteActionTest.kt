package io.mirr.shynigsy.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvRemoteActionTest {
    @Test fun hiddenControlsAllowSeekingAndOpeningWithoutAccidentalPlayback() {
        assertEquals(TvRemoteAction.SEEK_BACK, tvRemoteAction(KeyEvent.KEYCODE_DPAD_LEFT, false))
        assertEquals(TvRemoteAction.SEEK_FORWARD, tvRemoteAction(KeyEvent.KEYCODE_DPAD_RIGHT, false))
        for (key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            assertEquals(TvRemoteAction.SHOW_CONTROLS, tvRemoteAction(key, false))
        }
    }

    @Test fun visibleControlsKeepDirectionalAndConfirmKeysForFocusedViews() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            assertNull(tvRemoteAction(key, true))
        }
    }

    @Test fun dedicatedMediaAndMenuKeysWorkWithEitherControllerState() {
        for (visible in listOf(false, true)) {
            assertEquals(TvRemoteAction.PLAY, tvRemoteAction(KeyEvent.KEYCODE_MEDIA_PLAY, visible))
            assertEquals(TvRemoteAction.PAUSE, tvRemoteAction(KeyEvent.KEYCODE_MEDIA_PAUSE, visible))
            assertEquals(TvRemoteAction.TOGGLE_PLAY, tvRemoteAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, visible))
            assertEquals(TvRemoteAction.SEEK_BACK, tvRemoteAction(KeyEvent.KEYCODE_MEDIA_REWIND, visible))
            assertEquals(TvRemoteAction.SEEK_FORWARD, tvRemoteAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, visible))
            assertEquals(TvRemoteAction.SETTINGS, tvRemoteAction(KeyEvent.KEYCODE_MENU, visible))
        }
    }

    @Test fun backVolumeAndNeighborKeysRetainExistingHandling() {
        for (visible in listOf(false, true)) {
            for (key in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
                assertNull(tvRemoteAction(key, visible))
            }
        }
    }
}
