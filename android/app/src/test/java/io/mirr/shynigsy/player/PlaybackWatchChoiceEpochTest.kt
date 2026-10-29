package io.mirr.shynigsy.player

import io.mirr.shynigsy.data.MediaItem
import org.junit.Assert.*
import org.junit.Test

class PlaybackWatchChoiceEpochTest {
    private val first = MediaItem(11, 8, "first", "episode", viewItemId = 501,
        showId = 9, seasonNumber = 1, episodeNumber = 1)
    private val second = first.copy(id = 12, episodeNumber = 2)

    @Test fun otherWorkViewOrAccountDoesNotInvalidateAnEpisode() {
        val fence = PlaybackWatchChoiceEpoch()
        val snapshot = fence.snapshot()
        fence.record("account", second)
        fence.record("other-account", first)
        fence.record("account", first.copy(id = 25, viewItemId = 502, episodeNumber = 4))
        assertTrue(fence.isCurrent("account", first, snapshot))
        fence.record("account", first)
        assertFalse(fence.isCurrent("account", first, snapshot))
    }

    @Test fun seriesAndSeasonChoicesFenceOnlyTheirMatchingEpisodes() {
        val fence = PlaybackWatchChoiceEpoch()
        val snapshot = fence.snapshot()
        fence.record("account", MediaItem(40, 8, "season", "season", showId = 9, seasonNumber = 1))
        assertFalse(fence.isCurrent("account", first, snapshot))
        assertTrue(fence.isCurrent("account", first.copy(seasonNumber = 2), snapshot))
        assertTrue(fence.isCurrent("account", first.copy(showId = 10), snapshot))
        val next = fence.snapshot()
        fence.record("account", MediaItem(9, 8, "show", "show"))
        assertFalse(fence.isCurrent("account", first.copy(seasonNumber = 2), next))
        assertTrue(fence.isCurrent("account", first.copy(showId = 10), next))
    }

    @Test fun alternatePhysicalEpisodeIdentityConflictsOnlyWithSameCoordinates() {
        val fence = PlaybackWatchChoiceEpoch()
        val snapshot = fence.snapshot()
        fence.record("account", first.copy(id = 99))
        assertFalse(fence.isCurrent("account", first, snapshot))
        assertTrue(fence.isCurrent("account", second, snapshot))
    }

    @Test fun canonicalContentConflictsAcrossViewsAndLibrariesButNotAcrossAccounts() {
        val fence = PlaybackWatchChoiceEpoch()
        val before = fence.snapshot()
        fence.record("account", first.copy(libraryId = 42, viewItemId = 777))
        assertFalse(fence.isCurrent("account", first, before))
        assertTrue(fence.isCurrent("other-account", first, before))
        assertTrue(fence.isCurrent("account", second, before))
        val next = fence.snapshot()
        fence.record("account", first.copy(id = 99, libraryId = 42, viewItemId = 777))
        assertFalse(fence.isCurrent("account", first, next))
        assertTrue(fence.isCurrent("account", second, next))
        val latest = fence.snapshot()
        fence.record("account", MediaItem(9, 42, "show", "show", viewItemId = 777))
        assertFalse(fence.isCurrent("account", first, latest))
    }

}
