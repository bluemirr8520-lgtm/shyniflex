package io.mirr.shynigsy

import io.mirr.shynigsy.data.MediaItem
import org.junit.Assert.*
import org.junit.Test

class SeriesEpisodeNavigationTest {
    private fun season(id: Int, number: Int?, count: Int? = null) =
        MediaItem(id, 9, "합성 시즌", "season", seasonNumber = number, showId = 1, episodeCount = count)

    @Test fun initialShowDetailPrefersSeasonOneOverSpecialsOrServerOrder() {
        assertEquals(7, initialEpisodeSeason(listOf(season(6, 0), season(8, 2), season(7, 1)))?.id)
    }

    @Test fun unavailableFirstSeasonFallsBackToLowestAvailableOrdinarySeason() {
        assertEquals(9, initialEpisodeSeason(listOf(season(7, 1, 0), season(6, 0, 1), season(8, 3, 1), season(9, 2, 4)))?.id)
    }

    @Test fun specialsAndUnknownNumbersRemainUsableWithoutInventingASeason() {
        assertEquals(6, initialEpisodeSeason(listOf(season(6, 0, 1)))?.id)
        assertEquals(8, initialEpisodeSeason(listOf(season(8, null)))?.id)
        assertNull(initialEpisodeSeason(emptyList()))
    }

}
