package io.mirr.shynigsy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {
    @Test fun intentRoundtripPreservesScopedPlaybackIdentityAndSubtitles() {
        val source = MediaItem(10, 3, "회차 제목", "episode", viewItemId = 501, showId = 2,
            showTitle = "  작품 제목  ")
        val spec = PlaybackSpec("작품", "https://server/stream/8?ticket=temporary", emptyMap(),
            10, 3, 8, 23000, 3600000,
            listOf(SubtitleTrack(6, "한국어", "https://server/subtitles/track/6.vtt?ticket=temporary")),
            "session", "context", 501, "direct_play", selectedAudioStreamIndex = 0,
            selectedAudioStreamOrdinal = 0, selectedAudioLanguage = "ko", requestedPartId = 8,
            conversionHeight = 720, streamStartPositionMs = 23000, sourceItem = source)
        val restored = PlaybackSpec.fromJson(spec.toJson())
        assertEquals(spec, restored)
        assertEquals("작품 제목", restored.controllerTitle)
        assertEquals("회차 제목", restored.sourceItem!!.title)
        assertEquals("작품", restored.copy(sourceItem = source.copy(showTitle = "  ")).controllerTitle)
        assertEquals("영화 제목", restored.copy(title = "영화 제목",
            sourceItem = source.copy(type = "movie", title = "영화 제목", showTitle = "다른 시리즈")).controllerTitle)
    }

    @Test fun subtitleOriginalAndConversionFallbackSurviveIntentRoundtrip() {
        val spec = PlaybackSpec("작품", "https://server/stream/8?ticket=temporary", itemId = 10, libraryId = 3, partId = 8,
            subtitles = listOf(SubtitleTrack(6, "한국어", "https://server/subtitles/raw/6?ticket=temporary",
                "application/x-subrip", fallbackUrl = "https://server/subtitles/track/6.vtt?ticket=temporary",
                fallbackMimeType = "text/vtt")))
        assertEquals(spec, PlaybackSpec.fromJson(spec.toJson()))
    }

    @Test fun subtitleSourceLanguageAndGlobalIndexSurviveWithServerSelectedId() {
        val spec = PlaybackSpec("작품", "https://server/stream/8?ticket=temporary", itemId = 10, libraryId = 3, partId = 8,
            subtitles = listOf(SubtitleTrack(6, "한국어", "https://server/subtitles/track/6.vtt?ticket=temporary",
                source = "embedded", language = "ko", streamIndex = 0, format = "ass",
                forced = false, sdh = true, title = "Korean Full")), selectedSubtitleId = 6)
        val restored = PlaybackSpec.fromJson(spec.toJson())
        assertEquals(spec, restored)
        assertEquals(0, restored.subtitles.single().streamIndex)
        assertEquals(6, restored.selectedSubtitleId)
    }

    @Test fun subtitleExplicitFalseFlagsSurviveIntentRoundtrip() {
        val spec = PlaybackSpec("작품", "https://server/stream/8", itemId = 10, libraryId = 3, partId = 8,
            subtitles = listOf(SubtitleTrack(6, "한국어", "https://server/subtitles/track/6.vtt",
                forced = false, sdh = false)))
        val restored = PlaybackSpec.fromJson(spec.toJson())
        assertEquals(spec, restored)
        assertEquals(false, restored.subtitles.single().forced)
        assertEquals(false, restored.subtitles.single().sdh)
    }

    @Test fun olderSubtitleIntentLeavesSourceAndSelectionUnknownRatherThanGuessing() {
        val spec = PlaybackSpec.fromJson("""{"title":"작품","url":"https://server/stream/8","itemId":10,"libraryId":3,"partId":8,
            "subtitles":[{"id":6,"label":"한국어","url":"https://server/subtitles/track/6.vtt"}]}""")
        val subtitle = spec.subtitles.single()
        assertNull(subtitle.source)
        assertNull(subtitle.language)
        assertNull(subtitle.streamIndex)
        assertNull(subtitle.format)
        assertNull(subtitle.forced)
        assertNull(subtitle.sdh)
        assertNull(subtitle.title)
        assertNull(spec.selectedSubtitleId)
        assertNull(spec.requestedPartId)
        assertNull(spec.conversionHeight)
        assertEquals(0L, spec.streamStartPositionMs)
        assertNull(spec.sourceItem)
        assertEquals("", spec.navigationQuery)
        assertEquals("작품", spec.controllerTitle)
    }

    @Test fun mediaItemRoundtripPreservesSpecialSeasonZeroAndResume() {
        val item = MediaItem(10, 3, "스페셜", "season", seasonNumber = 0, showId = 2, resumeMs = 1234, watched = true)
        assertEquals(item, MediaItem.fromJson(item.toJson()))
    }

    @Test fun olderMediaIntentDefaultsToUnwatched() {
        val item = MediaItem.fromJson("""{"id":10,"libraryId":3,"title":"작품","type":"movie"}""")
        assertEquals(false, item.watched)
        assertNull(item.playbackPartId)
    }

    @Test fun continueEpisodeDisplayMetadataSurvivesRoundtrip() {
        val item = MediaItem(24, 9, "Episode title", "episode", viewItemId = 601,
            showTitle = "Series", displaySubtitle = "시즌 2 · 4화", resumeMs = 65000,
            backdrop = "https://server/art/24.jpg")
        assertEquals(item, MediaItem.fromJson(item.toJson()))
    }

    @Test fun navigationAndPrivateContextSurviveWithoutAnyGrantPayload() {
        val source = MediaItem(24, 9, "회차", "episode", showId = 5, seasonNumber = 0,
            episodeNumber = 4, viewItemId = 601, isSpoiler = true, playbackPartId = 90)
        val spec = PlaybackSpec("회차", "https://server/stream/90?ticket=part-only", itemId = 24,
            libraryId = 9, partId = 90, sourceItem = source, navigationQuery = "saved query")
        assertEquals(spec, PlaybackSpec.fromJson(spec.toJson()))
        assertEquals(source, MediaItem.fromJson(source.toJson()))
        assertEquals(true, PlaybackSpec.fromJson(spec.toJson()).sourceItem!!.isSpoiler)
    }
}
