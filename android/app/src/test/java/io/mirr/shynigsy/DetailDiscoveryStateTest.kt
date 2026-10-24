package io.mirr.shynigsy

import io.mirr.shynigsy.data.CastMember
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaWatchState
import org.junit.Assert.*
import org.junit.Test

class DetailDiscoveryStateTest {
    private val movie = MediaItem(11, 1, "합성 영화", "movie", viewItemId = 10)
    private val privateItem = movie.copy(id = 22, libraryId = 2, viewItemId = 20, isSpoiler = true)
    private val libraries = listOf(Library(1, "공개", "movie"), Library(2, "비공개", "movie", true))

    @Test fun shelvesRequireKnownViewsAndMatchingPrivacyAndHidePrivateRowsOnLock() {
        val rows = listOf(movie, privateItem, movie.copy(libraryId = 999), privateItem.copy(isSpoiler = false),
            movie.copy(isSpoiler = true), movie)
        assertEquals(listOf(movie, privateItem), visibleDetailWorks(rows, libraries, true))
        assertEquals(listOf(movie), visibleDetailWorks(rows, libraries, false))
    }

    @Test fun unknownCreditsReuseOnlyAnExactKnownWatchIdentity() {
        val known = movie.copy(watched = true, watchState = MediaWatchState.WATCHED, durationMs = 80_000)
        val rows = listOf(movie, movie.copy(viewItemId = 99), movie.copy(viewItemId = null), movie.copy(isSpoiler = true))
        val result = retainKnownDetailWatchState(rows, AppState(recentItems = listOf(known)))
        assertTrue(result[0].watched)
        assertEquals(MediaWatchState.WATCHED, result[0].watchState)
        assertEquals(80_000L, result[0].durationMs)
        assertEquals(rows.drop(1), result.drop(1))
    }

    @Test fun ExplicitAndPositiveServerProgressWinsOverOlderSupplementalState() {
        val stale = movie.copy(watched = true, watchState = MediaWatchState.WATCHED)
        val fresh = movie.copy(watchState = MediaWatchState.UNWATCHED)
        val frame = DetailDiscoveryState(movie, relatedItems = listOf(stale))
        val state = AppState(recentItems = listOf(fresh), detailDiscovery = mapOf(detailDiscoveryKey(movie) to frame))
        assertEquals(listOf(fresh), retainKnownDetailWatchState(listOf(movie), state))
        val resumed = movie.copy(resumeMs = 42_000)
        assertEquals(listOf(resumed), retainKnownDetailWatchState(listOf(resumed), state))
        assertEquals(listOf(stale), retainKnownDetailWatchState(listOf(stale), state))
    }

    @Test fun relockingDropsPrivateFramesAndWorksWithoutDiscardingThePublicDetail() {
        val actor = CastMember("합성 배우", tmdbId = 123)
        val frame = DetailDiscoveryState(movie, relatedItems = listOf(movie, privateItem), relatedLoaded = true,
            selectedActor = actor, actorItems = listOf(privateItem, movie), actorLoaded = true)
        val state = AppState(loggedIn = true, libraries = libraries, detailStack = listOf(MediaDetails(movie)),
            detailDiscovery = mapOf(detailDiscoveryKey(movie) to frame,
                detailDiscoveryKey(privateItem) to DetailDiscoveryState(privateItem)))
        val locked = withoutSpoilerContent(state)
        assertEquals(listOf(movie), locked.detailStack.map { it.item })
        assertEquals(setOf(detailDiscoveryKey(movie)), locked.detailDiscovery.keys)
        val kept = locked.detailDiscovery.getValue(detailDiscoveryKey(movie))
        assertEquals(listOf(movie), kept.relatedItems)
        assertEquals(listOf(movie), kept.actorItems)
        assertEquals(actor, kept.selectedActor)
        assertFalse(kept.relatedLoaded)
        assertFalse(kept.actorLoaded)
    }

    @Test fun explicitWatchAcknowledgementUpdatesBothShelvesAndLeavesAnotherViewUntouched() {
        val other = movie.copy(viewItemId = 99, resumeMs = 10_000)
        val frame = DetailDiscoveryState(movie, relatedItems = listOf(movie, other), actorItems = listOf(movie))
        val state = AppState(detailDiscovery = mapOf(detailDiscoveryKey(movie) to frame))
        val result = applyExplicitWatchState(state, movie, listOf(movie), true).detailDiscovery.values.single()
        assertTrue(result.parent.watched)
        assertTrue(result.relatedItems[0].watched)
        assertSame(other, result.relatedItems[1])
        assertEquals(MediaWatchState.WATCHED, result.actorItems.single().watchState)
        assertEquals(0L, result.actorItems.single().resumeMs)
    }

    @Test fun ppkeyRequiresTheParentsAuthorizedTvViewAndANameNeverBecomesAnIdentity() {
        val ppkey = CastMember("합성 배우", ppkey = "provider-123")
        val episode = movie.copy(type = "episode")
        assertFalse(canDiscoverActorWorks(ppkey, movie, libraries))
        assertTrue(canDiscoverActorWorks(ppkey, episode, listOf(Library(1, "TV", "tv"))))
        assertFalse(canDiscoverActorWorks(ppkey, episode, listOf(Library(1, "잠김", "tv", true))))
        assertFalse(canDiscoverActorWorks(CastMember("이름만"), episode, listOf(Library(1, "TV", "tv"))))
        assertTrue(canDiscoverActorWorks(CastMember("정확한 배우", tmdbId = 123), movie, libraries))
    }
}
