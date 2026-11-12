package io.mirr.shynigsy.player

/** A user choice is persisted, while the active engine is determined per playback. */
internal enum class PlaybackEnginePreference(val key: String, val label: String) {
    AUTO("auto", "자동"), MPV("mpv", "MPV"), EXO("exo", "ExoPlayer");

    companion object {
        fun fromKey(value: String?): PlaybackEnginePreference = entries.firstOrNull { it.key == value } ?: AUTO
    }
}

internal enum class EngineStart { AUTO, MPV, MANUAL_EXO, SAFE_MPV }

/** Explicit Exo still respects sources that require MPV's timebase or privacy handling. */
internal fun engineStart(preference: PlaybackEnginePreference, forceMpv: Boolean,
    serverConversion: Boolean, privateMedia: Boolean, softwareOnly: Boolean,
    nativeTextRestore: Boolean, nonDefaultLayout: Boolean, sessionMissing: Boolean): EngineStart {
    if (forceMpv) return EngineStart.MPV
    if (preference == PlaybackEnginePreference.MPV) return EngineStart.MPV
    val requiresMpv = serverConversion || privateMedia || softwareOnly || nativeTextRestore ||
        nonDefaultLayout || sessionMissing
    if (requiresMpv) return if (preference == PlaybackEnginePreference.EXO) EngineStart.SAFE_MPV else EngineStart.MPV
    return if (preference == PlaybackEnginePreference.EXO) EngineStart.MANUAL_EXO else EngineStart.AUTO
}
