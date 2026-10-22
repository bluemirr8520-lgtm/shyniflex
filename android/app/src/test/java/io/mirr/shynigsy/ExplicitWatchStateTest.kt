package io.mirr.shynigsy

import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaWatchState
import org.junit.Assert.*
import org.junit.Test

class ExplicitWatchStateTest {
    private val movie = MediaItem(23, 8, "영화", "movie", viewItemId = 501)
    private val unrelated = movie.copy(id = 24, title = "다른 영화", watched = true,
        resumeMs = 42_000, watchState = MediaWatchState.WATCHED)

    @Test fun bothExplicitStatesReplaceStaleProgressInEveryPublishedLocation() {
        for (watched in listOf(true, false)) {
            val stale = movie.copy(title = "새로 받은 제목", watched = !watched, resumeMs = 90_000,
                watchState = if (watched) MediaWatchState.IN_PROGRESS else MediaWatchState.WATCHED)
            val refreshed = AppState(items = listOf(stale), searchItems = listOf(stale), recentItems = listOf(stale),
                detailStack = listOf(MediaDetails(stale, children = listOf(stale), seasons = listOf(stale))))

            val result = applyExplicitWatchState(refreshed, movie, listOf(movie), watched)
            val expected = stale.copy(watched = watched, resumeMs = 0,
                watchState = if (watched) MediaWatchState.WATCHED else MediaWatchState.UNWATCHED)
            assertEquals(listOf(expected), result.items)
            assertEquals(listOf(expected), result.searchItems)
            assertEquals(listOf(expected), result.recentItems)
            assertEquals(MediaDetails(expected, children = listOf(expected), seasons = listOf(expected)),
                result.detailStack.single())
            assertEquals("Reconciliation must also be safe on repeated publications", result,
                applyExplicitWatchState(result, movie, listOf(movie), watched))
        }
    }

    @Test fun collectionTargetAndOnlyConfirmedChangedEpisodesAreUpdated() {
        val show = movie.copy(id = 5, type = "show")
        val episode = movie.copy(type = "episode", showId = show.id, seasonNumber = 1, episodeNumber = 1,
            watched = true, resumeMs = 80_000, watchState = MediaWatchState.WATCHED)
        val otherEpisode = episode.copy(id = 25, episodeNumber = 2)
        val detail = MediaDetails(show.copy(watched = true), children = listOf(episode, otherEpisode))
        val result = applyExplicitWatchState(AppState(items = listOf(show), detailStack = listOf(detail)),
            show, listOf(episode), false)

        assertEquals(MediaWatchState.UNWATCHED, result.items.single().watchState)
        assertFalse(result.detailStack.single().item.watched)
        val children = result.detailStack.single().children
        assertEquals(episode.copy(watched = false, resumeMs = 0, watchState = MediaWatchState.UNWATCHED), children[0])
        assertSame(otherEpisode, children[1])
    }

    @Test fun libraryViewTypeAndPrivacyMismatchesRemainUntouched() {
        val mismatches = listOf(movie.copy(libraryId = 9), movie.copy(viewItemId = 502),
            movie.copy(type = "episode"), movie.copy(isSpoiler = true)).map {
            it.copy(resumeMs = 70_000, watched = false, watchState = MediaWatchState.IN_PROGRESS)
        }
        val state = AppState(items = mismatches, searchItems = mismatches, recentItems = mismatches,
            continueItems = mismatches, detailStack = mismatches.map { MediaDetails(it, listOf(it), listOf(it)) })

        assertEquals(state, applyExplicitWatchState(state, movie, listOf(movie), true))
    }

    @Test fun anAbsentViewOnEitherSideRetainsTheExistingIdentityMatchingContract() {
        val withoutView = movie.copy(viewItemId = null, resumeMs = 60_000)
        val first = applyExplicitWatchState(AppState(items = listOf(withoutView)), movie, emptyList(), true)
        assertTrue(first.items.single().watched)
        assertNull(first.items.single().viewItemId)

        val second = applyExplicitWatchState(AppState(items = listOf(movie)), withoutView, emptyList(), true)
        assertTrue(second.items.single().watched)
        assertEquals(movie.viewItemId, second.items.single().viewItemId)
    }

    @Test fun continueRemovalUsesTargetAndChangedIdentitiesWithoutDroppingOtherRows() {
        val changed = movie.copy(id = 26, title = "확인된 다른 파일")
        val otherView = movie.copy(viewItemId = 502)
        val state = AppState(continueItems = listOf(movie, unrelated, changed, otherView))

        for (watched in listOf(true, false)) {
            val result = applyExplicitWatchState(state, movie, listOf(changed), watched)
            assertEquals(listOf(unrelated, otherView), result.continueItems)
            assertTrue(result.items.isEmpty())
        }
    }

    @Test fun invalidatedAggregatesBecomeUnknownWithoutDiscardingTheirResumePosition() {
        val aggregate = movie.copy(id = 5, type = "show", watched = true, resumeMs = 20_000,
            watchState = MediaWatchState.WATCHED)
        val state = AppState(items = listOf(movie, aggregate, unrelated), recentItems = listOf(aggregate),
            detailStack = listOf(MediaDetails(aggregate, seasons = listOf(aggregate))))
        val result = applyExplicitWatchState(state, movie, emptyList(), true) { it.id == movie.id || it.id == aggregate.id }
        val unknown = aggregate.copy(watched = false, watchState = null)

        assertTrue("A confirmed target takes precedence over aggregate invalidation", result.items[0].watched)
        assertEquals(MediaWatchState.WATCHED, result.items[0].watchState)
        assertEquals(unknown, result.items[1])
        assertSame(unrelated, result.items[2])
        assertEquals(listOf(unknown), result.recentItems)
        assertEquals(MediaDetails(unknown, seasons = listOf(unknown)), result.detailStack.single())
    }

    @Test fun reconciliationDoesNotInsertMissingRowsOrRestoreAnEmptyScreen() {
        val populated = AppState(items = listOf(unrelated), total = 1, query = "검색어",
            continueItems = listOf(unrelated), detailStack = listOf(MediaDetails(unrelated)), error = "새로고침 실패")
        assertEquals(populated, applyExplicitWatchState(populated, movie, listOf(movie), true))

        val empty = AppState()
        assertEquals(empty, applyExplicitWatchState(empty, movie, listOf(movie), true))
        assertEquals(empty, applyExplicitWatchState(empty, movie, listOf(movie), false))
    }
}
