package io.mirr.shynigsy.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackCodecInfo
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.player.mpv.MpvExoProbe
import io.mirr.shynigsy.player.mpv.MpvExoTrackEvidence
import org.junit.Assert.*
import org.junit.Test

class AutomaticEngineSelectionTest {
    private val source = MediaItem(1, 2, "test", "movie", viewItemId = 3)
    private val spec = PlaybackSpec("test", "https://example.invalid/stream/4?ticket=fixture", itemId = 1,
        libraryId = 2, partId = 4, sessionId = "session", viewItemId = 3, sourceItem = source,
        selectedAudioCodec = "aac", subtitlesPending = true)
    private val info = PlaybackCodecInfo(4, "mp4", "h264", "High", 8, "SDR", 1920, 1080, 24.0,
        "aac", "LC", 2, "aac", emptyList())
    private val probe = MpvExoProbe(MpvExoTrackEvidence("High", "LC", 1920, 1080, 24.0, 2),
        "yuv420p", null, "bt.709", "bt.1886", 1920, 1080, 24.0)
    private val supported: (Int, Int, Double, String) -> Boolean = { _, _, _, _ -> true }

    @Test fun positivelyVerifiedCommonFileCanSelectExo() {
        assertTrue(isPotentialExoCandidate(spec, info, true, true))
        assertTrue(isSafeExoProbe(info, probe, supported))
    }

    @Test fun unknownUnsupportedOrPrivateMediaStayOnMpv() {
        assertTrue(isPotentialExoCandidate(spec, info.copy(hdrFormat = null, videoBitDepth = null), true, true))
        assertFalse(isSafeExoProbe(info.copy(hdrFormat = null), probe.copy(gamma = null), supported))
        assertFalse(isSafeExoProbe(info, probe.copy(pixelFormat = "p010"), supported))
        assertFalse(isPotentialExoCandidate(spec, info.copy(videoBitDepth = 10), true, true))
        assertFalse(isPotentialExoCandidate(spec, info.copy(subtitleFormats = listOf("ass")), true, true))
        assertFalse(isPotentialExoCandidate(spec, info.copy(partId = 99), true, true))
        assertFalse(isPotentialExoCandidate(spec.copy(sourceItem = source.copy(isSpoiler = true)), info, true, true))
        assertFalse(isPotentialExoCandidate(spec, info, false, true))
        assertFalse(isPotentialExoCandidate(spec, info, true, false))
        assertFalse(isSafeExoProbe(info, probe) { _, _, _, _ -> false })
    }

    @Test fun extractedTracksMustBeSupportedAndMustNotHideSubtitles() {
        val video = ExtractedTrack(C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_H264, true, true)
        val audio = ExtractedTrack(C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_AAC, true, true, "mp4a.40.2", 2)
        assertTrue(extractedTracksSafe(listOf(video, audio)))
        assertFalse(extractedTracksSafe(listOf(video, audio, ExtractedTrack(C.TRACK_TYPE_TEXT, MimeTypes.TEXT_SSA, false, false))))
        assertFalse(extractedTracksSafe(listOf(video, audio, audio)))
        assertFalse(extractedTracksSafe(listOf(video, audio.copy(supported = false))))
        assertFalse(extractedTracksSafe(listOf(video, audio.copy(codecs = null))))
        assertFalse(extractedTracksSafe(listOf(video, audio.copy(codecs = "mp4a.40.5"))))
    }

    @Test fun networkAndPermissionErrorsDoNotSwitchEngine() {
        assertTrue(shouldFallbackFromExo(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertTrue(shouldFallbackFromExo(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertFalse(shouldFallbackFromExo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(shouldFallbackFromExo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }

    @Test fun failureBeforeFirstFrameDoesNotDiscardResumePosition() {
        assertEquals(65000, exoFallbackPosition(65000, 0, false, false))
        assertEquals(65000, exoFallbackPosition(65000, 0, true, false))
        assertEquals(70000, exoFallbackPosition(65000, 70000, true, true))
    }
}
