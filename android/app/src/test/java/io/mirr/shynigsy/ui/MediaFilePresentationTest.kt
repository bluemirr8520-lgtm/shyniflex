package io.mirr.shynigsy.ui

import io.mirr.shynigsy.data.MediaFileAudio
import io.mirr.shynigsy.data.MediaFileInfo
import io.mirr.shynigsy.data.MediaFileSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MediaFilePresentationTest {
    @Test fun onlyTheFileNameIsShownWithoutDirectoriesOrTemporaryUrlParameters() {
        val file = MediaFileInfo(9, fileName = "https://example.test/private/library/movie.mkv?ticket=secret#fragment")
        assertEquals("movie.mkv", mediaFileHeading(file, 0))
        assertEquals("episode.mkv", mediaFileHeading(file.copy(fileName = "C:\\private\\episode.mkv"), 0))
        assertEquals("파일 3", mediaFileHeading(file.copy(fileName = null), 2))
    }

    @Test fun codecAndOriginalVideoCharacteristicsArePreserved() {
        val file = MediaFileInfo(9, videoCodec = "hvc1", videoProfile = "Main 10", videoBitDepth = 10,
            resolution = "3840x2160", frameRate = 23.976, hdrFormat = "HDR10")
        assertEquals("HEVC (H.265) · Main 10 · 10비트 · HDR10", mediaFileVideoLabel(file))
        assertEquals("3840 × 2160 · 23.976 fps", mediaFileResolutionLabel(file))
        assertEquals(UnknownFileInformation, mediaFileVideoLabel(MediaFileInfo(10)))
        assertEquals(UnknownFileInformation, mediaFileResolutionLabel(MediaFileInfo(10, frameRate = Double.NaN)))
    }

    @Test fun sizeAndBitrateFormattingIsLocaleIndependentAndUsesExplicitBinaryUnits() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val file = MediaFileInfo(9, container = "mkv", fileSizeBytes = 1_073_741_824, bitrateBps = 8_500_000)
            assertEquals("MKV · 1.00 GiB · 8.50 Mbps", mediaFileFormatLabel(file))
            assertEquals(UnknownFileInformation, mediaFileFormatLabel(MediaFileInfo(10, fileSizeBytes = -1, bitrateBps = 0)))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun trackLabelsRetainLanguageSourceAndAccessibilityFlagsWithoutInferringChannelLayout() {
        assertEquals("한국어 · E-AC-3 · 6채널 · 기본",
            mediaFileAudioLabel(MediaFileAudio(codec = "eac3", language = "kor", channels = 6, isDefault = true)))
        assertEquals("한국어 · SRT · 외부 · 강제 · 청각장애인용",
            mediaFileSubtitleLabel(MediaFileSubtitle(language = "ko-KR", format = "subrip", source = "external", forced = true, sdh = true)))
        val missingTracks = mediaFileAudioFallback(MediaFileInfo(9, audioCodec = "aac", audioChannels = 2))
        assertTrue(missingTracks.contains("트랙 정보 미확인"))
        assertFalse(missingTracks.contains("없음"))
        assertEquals(UnknownFileInformation, mediaFileSubtitleLabel(MediaFileSubtitle(language = "und")))
    }
}
