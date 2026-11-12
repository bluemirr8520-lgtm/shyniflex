package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.PlaybackSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackModeLabelTest {
    private val source = PlaybackSpec("영상", "https://example.invalid/stream/9", itemId = 1,
        libraryId = 2, partId = 9)

    @Test fun distinguishesOriginalFromRequestedVideoConversion() {
        assertEquals("원본", playbackModeLabel(source))
        assertEquals("변환 720p", playbackModeLabel(source.copy(conversionHeight = 720)))
        assertEquals("변환 1080p", playbackModeLabel(source.copy(conversionHeight = 1080)))
        assertEquals("변환\n원본", playbackModeButtonLabel(source))
        assertEquals("변환\n720p", playbackModeButtonLabel(source.copy(conversionHeight = 720)))
        assertEquals("변환\n1080p", playbackModeButtonLabel(source.copy(conversionHeight = 1080)))
    }

    @Test fun serverAudioOrRemuxConversionDoesNotAppearAsOriginal() {
        assertEquals("변환 자동", playbackModeLabel(source.copy(url = "https://example.invalid/transcode/9?mode=audio")))
        assertEquals("변환 자동", playbackModeLabel(source.copy(url = "https://example.invalid/transcode/9?mode=remux")))
        assertEquals("변환\n자동", playbackModeButtonLabel(source.copy(url = "https://example.invalid/transcode/9?mode=audio")))
    }

    @Test fun musicConversionRouteUsesConversionLabelWithoutChangingVideoTimeline() {
        val music = source.copy(url = "https://example.invalid/transcode/music/9?bitrate_kbps=320",
            decision = "audio_transcode")
        assertEquals(false, music.isServerConversion)
        assertEquals("변환 자동", playbackModeLabel(music))
        assertEquals("변환\n자동", playbackModeButtonLabel(music))
        assertEquals("원본", playbackModeLabel(source.copy(url = "https://example.invalid/transcode/music/10")))
    }
}
