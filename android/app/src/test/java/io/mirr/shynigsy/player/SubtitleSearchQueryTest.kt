package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSearchQueryTest {
    @Test fun movieUsesOnlyVisibleTitleAndYear() {
        val spec = spec(MediaItem(1, 2, "터미널", "movie", year = 2004))
        assertEquals("터미널 2004 한국어 자막", defaultSubtitleWebSearchQuery(spec))
        assertFalse(defaultSubtitleWebSearchQuery(spec).contains("secret"))
    }

    @Test fun episodeSearchUsesSeriesNameAndEpisodeIdentity() {
        val item = MediaItem(1, 2, "위기", "episode", showTitle = "닥터 X", year = 2012,
            seasonNumber = 2, episodeNumber = 6)
        assertEquals("닥터 X 2012 시즌 2 6화 한국어 자막", defaultSubtitleWebSearchQuery(spec(item)))
    }

    @Test fun identifiersAlreadyInTitleAreNotRepeated() {
        val item = MediaItem(1, 2, "작품 (2026) S02E06 한국어 자막", "episode", year = 2026,
            seasonNumber = 2, episodeNumber = 6)
        assertEquals(item.title, defaultSubtitleWebSearchQuery(spec(item)))
        val korean = item.copy(title = "작품 2026 시즌 2 제6화")
        assertEquals("작품 2026 시즌 2 제6화 한국어 자막", defaultSubtitleWebSearchQuery(spec(korean)))
    }

    @Test fun differentEpisodeNumbersDoNotMaskTheRequestedEpisode() {
        val item = MediaItem(1, 2, "작품 시즌 12 16화", "episode", seasonNumber = 2, episodeNumber = 6)
        assertEquals("작품 시즌 12 16화 시즌 2 6화 한국어 자막", defaultSubtitleWebSearchQuery(spec(item)))
    }

    @Test fun controlsAndUnicodeSeparatorsBecomeSingleSpaces() {
        assertEquals("영화 제목 한국어 자막", normalizeSubtitleWebSearchQuery(" \n영화\t제목\r\u0000\u202E한국어\u00A0자막\u200B "))
        assertNull(subtitleWebSearchUrl("\n\u0000\t\u200B"))
    }

    @Test fun queryLimitDoesNotLeaveHalfOfASupplementaryCharacter() {
        val query = normalizeSubtitleWebSearchQuery("가".repeat(199) + "\uD83D\uDE00끝")
        assertEquals(199, query.length)
        assertTrue(normalizeSubtitleWebSearchQuery("가".repeat(500)).length <= SUBTITLE_SEARCH_MAX_LENGTH)
    }

    @Test fun urlTextCannotChangeTheDestinationOrInjectParameters() {
        val query = "영화 &q=다른 것 #fragment https://evil.example/ ?x=1"
        val uri = URI(subtitleWebSearchUrl(query)!!)
        assertEquals("https", uri.scheme)
        assertEquals("www.google.com", uri.host)
        assertEquals("/search", uri.path)
        assertNull(uri.fragment)
        assertEquals(1, uri.rawQuery.split('&').size)
        assertEquals(query, URLDecoder.decode(uri.rawQuery.removePrefix("q="), "UTF-8"))
    }

    @Test fun missingMetadataStillHasAnEditableSearch() {
        assertEquals("제목 한국어 자막", defaultSubtitleWebSearchQuery(spec(null).copy(title = "제목")))
    }

    private fun spec(item: MediaItem?) = PlaybackSpec(
        title = item?.title ?: "제목", url = "https://private.invalid/secret/video?token=secret",
        headers = mapOf("Authorization" to "secret"), itemId = 1, libraryId = 2, partId = 3, sourceItem = item,
    )
}
