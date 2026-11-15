package io.mirr.shynigsy.player

import org.junit.Assert.*
import org.junit.Test

class PictureEnginePolicyTest {
    private fun start(preference: PlaybackEnginePreference, settings: PictureSettings,
        forced: Boolean = false, converted: Boolean = false, privateMedia: Boolean = false) =
        engineStart(pictureEnginePreference(preference, settings), forced, converted, privateMedia,
            softwareOnly = false, nativeTextRestore = false, sessionMissing = false)

    @Test fun originalVideoRetainsAutoAndExplicitEngineRoutes() {
        assertFalse(pictureRequiresMpv(PictureSettings()))
        assertEquals(EngineStart.AUTO, start(PlaybackEnginePreference.AUTO, PictureSettings()))
        assertEquals(EngineStart.MPV, start(PlaybackEnginePreference.MPV, PictureSettings()))
        assertEquals(EngineStart.MANUAL_EXO, start(PlaybackEnginePreference.EXO, PictureSettings()))
    }

    @Test fun EveryAdjustedAxisAndPresetUsesMpvWithoutStartingAnExoProbe() {
        val adjusted = listOf(PictureSettings(brightness = 1), PictureSettings(brightness = -1),
            PictureSettings(contrast = 1), PictureSettings(contrast = -1),
            PictureSettings(saturation = 1), PictureSettings(saturation = -1)) +
            builtInPicturePresets().filterNot { it.settings.isNeutral }.map { it.settings }
        for (settings in adjusted) for (preference in PlaybackEnginePreference.entries) {
            assertTrue(pictureRequiresMpv(settings))
            assertEquals(EngineStart.MPV, start(preference, settings))
        }
    }

    @Test fun ReturningToOriginalDoesNotRememberATemporaryPictureEngineOverride() {
        val savedChoice = PlaybackEnginePreference.EXO
        assertEquals(EngineStart.MPV, start(savedChoice, PictureSettings(10, 15, 20)))
        assertEquals(EngineStart.MANUAL_EXO, start(savedChoice, PictureSettings()))
        assertEquals(EngineStart.MPV,
            start(PlaybackEnginePreference.AUTO, PictureSettings(Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)))
        assertEquals(EngineStart.AUTO, start(PlaybackEnginePreference.AUTO, PictureSettings()))
    }

    @Test fun NeutralSettingsKeepExistingSourceAndFailureSafetyRestrictions() {
        val original = PictureSettings()
        assertEquals(EngineStart.SAFE_MPV, start(PlaybackEnginePreference.EXO, original, converted = true))
        assertEquals(EngineStart.SAFE_MPV, start(PlaybackEnginePreference.EXO, original, privateMedia = true))
        assertEquals(EngineStart.MPV, start(PlaybackEnginePreference.EXO, original, forced = true))
        assertEquals(EngineStart.MPV, start(PlaybackEnginePreference.AUTO, original, converted = true))
    }
}
