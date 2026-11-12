package io.mirr.shynigsy.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test

class PlaybackEnginePreferenceTest {
    private fun start(preference: PlaybackEnginePreference, forced: Boolean = false,
        converted: Boolean = false, spoiler: Boolean = false, software: Boolean = false,
        textRestore: Boolean = false, scaled: Boolean = false, noSession: Boolean = false) =
        engineStart(preference, forced, converted, spoiler, software, textRestore, scaled, noSession)

    @Test fun choiceDefaultsAndUnsafeSourcesPreserveMpv() {
        assertEquals(PlaybackEnginePreference.AUTO, PlaybackEnginePreference.fromKey(null))
        assertEquals(PlaybackEnginePreference.AUTO, PlaybackEnginePreference.fromKey("invalid"))
        assertEquals(PlaybackEnginePreference.EXO, PlaybackEnginePreference.fromKey("exo"))
        assertEquals(EngineStart.AUTO, start(PlaybackEnginePreference.AUTO))
        assertEquals(EngineStart.MPV, start(PlaybackEnginePreference.MPV))
        assertEquals(EngineStart.MANUAL_EXO, start(PlaybackEnginePreference.EXO))
        assertEquals(EngineStart.MPV, start(PlaybackEnginePreference.EXO, forced = true))
        assertEquals(EngineStart.SAFE_MPV, start(PlaybackEnginePreference.EXO, converted = true))
        assertEquals(EngineStart.SAFE_MPV, start(PlaybackEnginePreference.EXO, spoiler = true))
        assertEquals(EngineStart.SAFE_MPV, start(PlaybackEnginePreference.EXO, software = true))
        assertEquals(EngineStart.SAFE_MPV, start(PlaybackEnginePreference.EXO, textRestore = true))
        assertEquals(EngineStart.SAFE_MPV, start(PlaybackEnginePreference.EXO, scaled = true))
        assertEquals(EngineStart.SAFE_MPV, start(PlaybackEnginePreference.EXO, noSession = true))
    }

    @Test fun manualExoAcceptsSupportedAdditionalCodecsWithoutDroppingEmbeddedSubtitles() {
        val video = ExtractedTrack(C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_H265, true, true)
        val audio = ExtractedTrack(C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_E_AC3, true, true)
        val text = ExtractedTrack(C.TRACK_TYPE_TEXT, MimeTypes.APPLICATION_TX3G, true, false)
        assertTrue(manuallyExtractedTracksSafe(listOf(video, audio), false, true))
        assertTrue(manuallyExtractedTracksSafe(listOf(video), false, false))
        assertTrue(manuallyExtractedTracksSafe(listOf(video, audio, text), true, true))
        assertFalse(manuallyExtractedTracksSafe(listOf(video), false, true))
        assertFalse(manuallyExtractedTracksSafe(listOf(video, audio), true, true))
        assertFalse(manuallyExtractedTracksSafe(listOf(video, audio, text.copy(supported = false)), true, true))
        assertFalse(manuallyExtractedTracksSafe(listOf(video, audio, text.copy(mime = MimeTypes.TEXT_SSA)), true, true))
        assertFalse(manuallyExtractedTracksSafe(listOf(video.copy(supported = false), audio), false, true))
        assertFalse(manuallyExtractedTracksSafe(listOf(video, audio.copy(supported = false)), false, true))
        assertFalse(manuallyExtractedTracksSafe(listOf(video, audio, text), false, true))
    }
}
