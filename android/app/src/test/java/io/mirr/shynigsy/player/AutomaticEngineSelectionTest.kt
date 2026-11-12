package io.mirr.shynigsy.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackCodecInfo
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SubtitleTrack
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
        assertTrue(isPotentialExoCandidate(spec, info, emptyList(), true))
        assertTrue(isSafeExoProbe(info, probe, supported))
    }

    @Test fun indexedHeadersCanPrepareExoWithoutPretendingADecodedProfileWasObserved() {
        val header = MpvExoTrackEvidence(null, null, 1920, 1080, 24.0, 2)
        assertTrue(isSafeExoHeaderProbe(info, header, emptyList()))
        assertTrue(isPotentialExoCandidate(spec, info.copy(container = "mkv"), emptyList(), true))
        assertFalse(isPotentialExoCandidate(spec, info.copy(container = "mpegts"), emptyList(), true))
        assertFalse(isSafeExoHeaderProbe(info, header.copy(width = null), emptyList()))
        assertFalse(isSafeExoHeaderProbe(info, header.copy(width = 1280), emptyList()))
        assertFalse(isSafeExoHeaderProbe(info, header.copy(frameRate = Double.NaN), emptyList()))
        assertFalse(isSafeExoHeaderProbe(info, header.copy(audioChannels = 6), emptyList()))
        assertFalse(isSafeExoHeaderProbe(info, header.copy(videoProfile = "High 10"), emptyList()))
        assertFalse(isSafeExoHeaderProbe(info, header.copy(audioProfile = "HE-AAC"), emptyList()))
        val subtitle = SubtitleTrack(9, "한국어", "https://example.invalid/sub", source = "embedded", format = "mov_text", streamIndex = 4)
        assertFalse(isSafeExoHeaderProbe(info, header, listOf(subtitle)))
        assertTrue(isSafeExoHeaderProbe(info, header.copy(subtitleStreamIndex = 4), listOf(subtitle)))
        assertFalse(isSafeExoHeaderProbe(info, header.copy(subtitleStreamIndex = 5), listOf(subtitle)))
        assertFalse(isSafeExoHeaderProbe(info, header.copy(subtitleStreamIndex = 4), emptyList()))
        // The previous decoded path still requires independent decoded color/profile evidence.
        assertFalse(isSafeExoProbe(info, probe.copy(tracks = header), supported))
    }

    @Test fun headerHandoffCannotStartUntilExoConfirmsAvcEightBitAndAacLcProfiles() {
        val video = ExtractedTrack(C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_H264, true, true,
            codecs = "avc1.640028", width = 1920, height = 1080, frameRate = 24.0)
        val audio = ExtractedTrack(C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_AAC, true, true, "mp4a.40.2", 2)
        for (codec in listOf("avc1.42e01e", "avc1.4d0028", "avc1.640028", "avc3.640028", "avc1.66.30"))
            assertTrue(headerExtractedTracksSafe(listOf(video.copy(codecs = codec), audio)))
        for (codec in listOf(null, "avc1", "avc1.6e0033", "avc1.7a0033", "avc1.f40033", "avc1.100.", "hvc1.1.6.L120"))
            assertFalse(headerExtractedTracksSafe(listOf(video.copy(codecs = codec), audio)))
        assertFalse(headerExtractedTracksSafe(listOf(video.copy(width = null), audio)))
        assertFalse(headerExtractedTracksSafe(listOf(video.copy(height = 4320), audio)))
        assertFalse(headerExtractedTracksSafe(listOf(video.copy(frameRate = Double.NaN), audio)))
        assertFalse(headerExtractedTracksSafe(listOf(video.copy(frameRate = 120.0), audio)))
        assertFalse(headerExtractedTracksSafe(listOf(video.copy(supported = false), audio)))
        assertFalse(headerExtractedTracksSafe(listOf(video, audio.copy(codecs = "mp4a.40.5"))))
        assertFalse(headerExtractedTracksSafe(listOf(video, audio.copy(channels = 6))))
        assertFalse(headerExtractedTracksSafe(listOf(video, audio, audio)))
        for (transfer in listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG))
            assertFalse(headerExtractedTracksSafe(listOf(video.copy(colorTransfer = transfer), audio)))
        assertFalse(headerExtractedTracksSafe(listOf(video.copy(hdrStaticInfoPresent = true), audio)))
        assertFalse(headerExtractedTracksSafe(listOf(video.copy(lumaBitDepth = 10), audio)))
        assertFalse(headerExtractedTracksSafe(listOf(video.copy(chromaBitDepth = 10), audio)))
        val text = ExtractedTrack(C.TRACK_TYPE_TEXT, MimeTypes.APPLICATION_TX3G, true, false)
        assertTrue(headerExtractedTracksSafe(listOf(video, audio, text), embeddedExpected = true))
        assertFalse(headerExtractedTracksSafe(listOf(video, audio), embeddedExpected = true))
        assertFalse(headerExtractedTracksSafe(listOf(video, audio, text), embeddedExpected = false))
    }

    @Test fun unknownUnsupportedOrPrivateMediaStayOnMpv() {
        assertTrue(isPotentialExoCandidate(spec, info.copy(hdrFormat = null, videoBitDepth = null), emptyList(), true))
        assertFalse(isSafeExoProbe(info.copy(hdrFormat = null), probe.copy(gamma = null), supported))
        assertFalse(isSafeExoProbe(info, probe.copy(pixelFormat = "p010"), supported))
        assertFalse(isPotentialExoCandidate(spec, info.copy(videoBitDepth = 10), emptyList(), true))
        assertFalse(isPotentialExoCandidate(spec, info.copy(subtitleFormats = listOf("ass")), emptyList(), true))
        assertFalse(isPotentialExoCandidate(spec, info.copy(partId = 99), emptyList(), true))
        assertFalse(isPotentialExoCandidate(spec.copy(sourceItem = source.copy(isSpoiler = true)), info, emptyList(), true))
        assertFalse(isPotentialExoCandidate(spec, info, null, true))
        assertFalse(isPotentialExoCandidate(spec, info, emptyList(), false))
        assertFalse(isSafeExoProbe(info, probe, { _, _, _, _ -> false }))
    }

    @Test fun externalTextSubtitlesCanUseTheSameCanvasWithExo() {
        val srt = SubtitleTrack(7, "한국어", "https://example.invalid/sub", source = "external", language = "ko", format = "srt")
        val ass = SubtitleTrack(8, "ASS", "https://example.invalid/sub2", source = "external", format = "ass")
        assertTrue(isPotentialExoCandidate(spec.copy(selectedSubtitleId = 7), info.copy(subtitleFormats = listOf("subrip", "ass")), listOf(srt, ass), true))
        assertFalse(isPotentialExoCandidate(spec.copy(selectedSubtitleId = 8), info, listOf(srt), true))
        assertFalse(isPotentialExoCandidate(spec, info, listOf(srt.copy(source = "embedded")), true))
        assertFalse(isPotentialExoCandidate(spec, info, listOf(srt.copy(format = "pgs")), true))
        assertFalse(isPotentialExoCandidate(spec, info, listOf(srt.copy(source = null)), true))
        assertFalse(isPotentialExoCandidate(spec, info.copy(subtitleFormats = listOf("srt", "srt")), listOf(srt), true))
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

    @Test fun embeddedMovTextRequiresMatchingNativeIndexAndSingleSupportedExoTrack() {
        val text = SubtitleTrack(9, "한국어", "https://example.invalid/sub", source = "embedded", format = "mov_text", streamIndex = 4)
        val verified = probe.copy(tracks = probe.tracks.copy(subtitleStreamIndex = 4))
        assertTrue(isPotentialExoCandidate(spec.copy(selectedSubtitleId = 9), info.copy(subtitleFormats = listOf("tx3g")), listOf(text), true))
        assertTrue(isSafeExoProbe(info, verified, supported, listOf(text)))
        assertFalse(isSafeExoProbe(info, probe, supported, listOf(text)))
        assertFalse(isSafeExoProbe(info, verified, supported, emptyList()))
        assertFalse(isSafeExoProbe(info, verified, supported, listOf(text.copy(streamIndex = 5))))
        assertFalse(isPotentialExoCandidate(spec, info, listOf(text, text.copy(id = 10)), true))
        val video = ExtractedTrack(C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_H264, true, true)
        val audio = ExtractedTrack(C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_AAC, true, true, "mp4a.40.2", 2)
        val tx3g = ExtractedTrack(C.TRACK_TYPE_TEXT, MimeTypes.APPLICATION_TX3G, true, false)
        assertTrue(extractedTracksSafe(listOf(video, audio, tx3g), true))
        assertTrue(extractedTracksSafe(listOf(video, audio, tx3g.copy(mime = MimeTypes.APPLICATION_MEDIA3_CUES, codecs = MimeTypes.APPLICATION_TX3G)), true))
        assertFalse(extractedTracksSafe(listOf(video, audio, tx3g.copy(mime = MimeTypes.APPLICATION_MEDIA3_CUES, codecs = null)), true))
        assertFalse(extractedTracksSafe(listOf(video, audio), true))
        assertFalse(extractedTracksSafe(listOf(video, audio, tx3g.copy(supported = false)), true))
        assertFalse(extractedTracksSafe(listOf(video, audio, tx3g.copy(mime = MimeTypes.TEXT_SSA)), true))
        assertFalse(extractedTracksSafe(listOf(video, audio, tx3g, tx3g), true))
        assertFalse(extractedTracksSafe(listOf(video, audio, tx3g), false))
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
