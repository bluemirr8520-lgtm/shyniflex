package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackNeighbors
import io.mirr.shynigsy.data.PlaybackSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreditsNextEpisodeTest {
    private val source = MediaItem(23, 9, "6화", "episode", seasonNumber = 1,
        episodeNumber = 6, viewItemId = 601, showId = 5)
    private val next = source.copy(id = 24, title = "7화", episodeNumber = 7,
        folderKey = "/series", navigationPartId = 91, playbackPartId = 91)
    private val spec = PlaybackSpec("시리즈", "https://example.test/video", itemId = 23,
        libraryId = 9, partId = 90, viewItemId = 601, sourceItem = source)

    @Test fun completeSourceUsesVerifiedNextEpisode() {
        assertEquals(next, verifiedCreditsNextEpisode(spec, PlaybackNeighbors(nextEpisode = next)))
    }

    @Test fun missingHomeNumbersUseExactVerifiedCurrentEpisode() {
        val homeSpec = spec.copy(sourceItem = source.copy(seasonNumber = null, episodeNumber = null, showId = null))
        assertEquals(next, verifiedCreditsNextEpisode(homeSpec, PlaybackNeighbors(nextEpisode = next, currentEpisode = source)))
        assertNull(verifiedCreditsNextEpisode(homeSpec, PlaybackNeighbors(nextEpisode = next)))
    }

    @Test fun recoveredCurrentMustMatchSourceAndEveryKnownNumber() {
        val homeSpec = spec.copy(sourceItem = source.copy(episodeNumber = null))
        val invalid = listOf(source.copy(id = 25), source.copy(libraryId = 8), source.copy(viewItemId = 602),
            source.copy(showId = 6), source.copy(seasonNumber = 2), source.copy(isSpoiler = true),
            source.copy(showId = null))
        invalid.forEach { anchor ->
            assertNull(verifiedCreditsNextEpisode(homeSpec, PlaybackNeighbors(nextEpisode = next, currentEpisode = anchor)))
        }
    }

    @Test fun targetMustMatchViewShowAndVerifiedPartAndMoveForward() {
        val invalid = listOf(next.copy(viewItemId = 602), next.copy(showId = 6), next.copy(isSpoiler = true),
            next.copy(episodeNumber = 5), next.copy(folderKey = null), next.copy(navigationPartId = 90),
            next.copy(playbackPartId = 92))
        invalid.forEach { target -> assertNull(verifiedCreditsNextEpisode(spec, PlaybackNeighbors(nextEpisode = target))) }
    }
}
