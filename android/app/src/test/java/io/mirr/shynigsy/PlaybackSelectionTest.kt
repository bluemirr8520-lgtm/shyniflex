package io.mirr.shynigsy

import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSelectionTest {
    private val movie = MediaItem(23, 8, "Movie", "movie", viewItemId = 501)
    private val selected = AppState(detailStack = listOf(MediaDetails(movie)), selectedPartId = 90)

    @Test fun versionSelectionAppliesOnlyToItsExactDetailItem() {
        assertEquals(90, selectedVersionForPlayback(selected, movie))
        assertNull(selectedVersionForPlayback(selected, movie.copy(id = 24)))
    }

    @Test fun sameMetadataInAnotherViewCannotInheritSelectedVersion() {
        assertNull(selectedVersionForPlayback(selected, movie.copy(libraryId = 9)))
        assertNull(selectedVersionForPlayback(selected, movie.copy(viewItemId = 502)))
    }

    @Test fun homeItemWithoutAnOpenDetailNeverInheritsStalePart() {
        assertNull(selectedVersionForPlayback(AppState(selectedPartId = 90), movie))
    }

    @Test fun navigationRetainsTheSearchForAnItemInTheCurrentLibraryList() {
        val browse = selected.copy(library = Library(8, "영화", "movie"), items = listOf(movie), query = "space")
        assertEquals("space", navigationQueryForPlayback(browse, movie))
        assertEquals("The enriched detail is still the same list item", "space",
            navigationQueryForPlayback(browse, movie.copy(summary = "Fresh detail", resumeMs = 12_000)))
    }

    @Test fun unrelatedLibraryAndHomeItemsCannotInheritAnOldSearch() {
        val browse = selected.copy(library = Library(8, "영화", "movie"), items = listOf(movie), query = "space")
        assertEquals("", navigationQueryForPlayback(browse, movie.copy(id = 24)))
        assertEquals("", navigationQueryForPlayback(browse, movie.copy(libraryId = 9)))
        assertEquals("", navigationQueryForPlayback(browse, movie.copy(viewItemId = 502)))
        assertEquals("", navigationQueryForPlayback(browse, movie.copy(type = "episode")))
        assertEquals("", navigationQueryForPlayback(browse.copy(library = null), movie))
    }
}
