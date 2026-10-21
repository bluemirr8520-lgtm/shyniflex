package io.mirr.shynigsy.player

import android.view.KeyEvent

internal enum class TvRemoteAction { PLAY, PAUSE, TOGGLE_PLAY, SEEK_BACK, SEEK_FORWARD, SETTINGS, SHOW_CONTROLS }

/** Null leaves the key to the focused View, especially when controls are visible. */
internal fun tvRemoteAction(keyCode: Int, controlsVisible: Boolean): TvRemoteAction? = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY -> TvRemoteAction.PLAY
    KeyEvent.KEYCODE_MEDIA_PAUSE -> TvRemoteAction.PAUSE
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> TvRemoteAction.TOGGLE_PLAY
    KeyEvent.KEYCODE_MEDIA_REWIND -> TvRemoteAction.SEEK_BACK
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> TvRemoteAction.SEEK_FORWARD
    KeyEvent.KEYCODE_MENU -> TvRemoteAction.SETTINGS
    else -> if (controlsVisible) null else when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> TvRemoteAction.SEEK_BACK
        KeyEvent.KEYCODE_DPAD_RIGHT -> TvRemoteAction.SEEK_FORWARD
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> TvRemoteAction.SHOW_CONTROLS
        else -> null
    }
}
