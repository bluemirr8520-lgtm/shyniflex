package io.mirr.shynigsy.player

/** Video color adjustments use MPV's native controls without activating Exo's effect surface. */
internal fun pictureRequiresMpv(settings: PictureSettings): Boolean = !settings.normalized().isNeutral

/** Derive the engine for this playback without replacing the user's saved engine preference. */
internal fun pictureEnginePreference(
    preference: PlaybackEnginePreference,
    settings: PictureSettings,
): PlaybackEnginePreference = if (pictureRequiresMpv(settings)) PlaybackEnginePreference.MPV else preference
