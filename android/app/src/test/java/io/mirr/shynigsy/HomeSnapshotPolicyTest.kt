package io.mirr.shynigsy

import io.mirr.shynigsy.data.HomeSnapshot
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.RecentLibraryStatus
import org.junit.Assert.*
import org.junit.Test

class HomeSnapshotPolicyTest {
    private val publicLibrary = Library(1, "공개", "movie")
    private val otherLibrary = Library(2, "다른 공개", "tv")
    private val item = MediaItem(10, 1, "캐시 영화", "movie", viewItemId = 20,
        durationMs = 100_000, resumeMs = 20_000)

    @Test fun restoreShowsPublicRowsWhileBackgroundRefreshRemainsInteractive() {
        val privateItem = item.copy(id = 11, libraryId = 3, isSpoiler = true)
        val snapshot = HomeSnapshot(1, listOf(publicLibrary, Library(3, "비공개", "movie", true)),
            listOf(item, privateItem), listOf(item, privateItem), mapOf(1 to RecentLibraryStatus.READY))
        val result = restoreHomeSnapshot(AppState(loggedIn = true, busy = true), snapshot)
        assertFalse(result.busy)
        assertTrue(result.homeLoading)
        assertEquals(listOf(publicLibrary), result.libraries)
        assertEquals(listOf(item), result.continueItems)
        assertEquals(listOf(item), result.recentItems)
    }

    @Test fun freshPermissionResponseRemovesCachedHeroBeforeSlowHistoryArrives() {
        val allowed = item.copy(id = 12, libraryId = 2, type = "episode")
        val initial = AppState(loggedIn = true, libraries = listOf(publicLibrary, otherLibrary),
            continueItems = listOf(item, allowed), recentItems = listOf(item), continueDetails = item,
            recentLibraryStatus = mapOf(1 to RecentLibraryStatus.READY, 2 to RecentLibraryStatus.LOADING))
        val result = reconcileHomeLibraryAccess(initial, listOf(otherLibrary))
        assertEquals(listOf(allowed), result.continueItems)
        assertTrue(result.recentItems.isEmpty())
        assertNull(result.continueDetails)
        assertEquals(setOf(2), result.recentLibraryStatus.keys)
        assertTrue(reconcileHomeLibraryAccess(initial, emptyList()).continueItems.isEmpty())
    }

    @Test fun aPublicViewBecomingPrivateCannotReuseCachedPublicArtwork() {
        val state = AppState(loggedIn = true, spoilerUnlocked = true, libraries = listOf(publicLibrary),
            continueItems = listOf(item), recentItems = listOf(item), continueDetails = item)
        val changed = reconcileHomeLibraryAccess(state, listOf(publicLibrary.copy(isSpoiler = true)))
        assertTrue(changed.continueItems.isEmpty())
        assertTrue(changed.recentItems.isEmpty())
        assertNull(changed.continueDetails)
        assertTrue(reconcileHomeLibraryAccess(state, listOf(publicLibrary.copy(type = "tv"))).continueItems.isEmpty())
    }

    @Test fun persistenceIncludesConfirmedWatchChangesAndExcludesKnownPrivateAliases() {
        val second = item.copy(id = 12, libraryId = 2, type = "episode")
        val state = AppState(loggedIn = true, libraries = listOf(publicLibrary, otherLibrary),
            recentItems = listOf(item.copy(watched = true, resumeMs = 0), second), continueItems = listOf(second))
        val snapshot = homeSnapshotForPersistence(state, setOf(2), 55)
        assertEquals(55L, snapshot.savedAtMs)
        assertEquals(listOf(publicLibrary), snapshot.libraries)
        assertTrue(snapshot.recentItems.single().watched)
        assertEquals(0L, snapshot.recentItems.single().resumeMs)
        assertTrue(snapshot.continueItems.isEmpty())
    }

    @Test fun aDeniedViewCannotReturnThroughCachedContinueItemsOrHero() {
        val other = item.copy(id = 12, libraryId = 2, type = "episode")
        val state = AppState(libraries = listOf(publicLibrary, otherLibrary), recentItems = listOf(item, other),
            continueItems = listOf(item, other), continueDetails = item)
        val denied = withoutDeniedHomeRows(state, setOf(1))
        assertEquals(listOf(other), denied.continueItems)
        assertEquals(listOf(other), denied.recentItems)
        assertNull(denied.continueDetails)
        val cached = homeSnapshotForPersistence(denied, emptySet(), 60)
        assertEquals(listOf(other), cached.continueItems)
        assertEquals(listOf(other), cached.recentItems)
    }

    @Test fun delayedHistoryAndTransientFallbackCannotResurrectDeniedCachedRows() {
        val other = item.copy(id = 12, libraryId = 2, type = "episode")
        for (load in listOf(HomeContinueLoad.Success(listOf(item, other)), HomeContinueLoad.Failure(true))) {
            val result = resolveHomeContinueWatching(load, HomeContinueLoad.Success(emptyList()), listOf(item, other),
                listOf(publicLibrary, otherLibrary), spoilerUnlocked = false, sameAccountScope = true,
                privateGrantUnchanged = true, previousOwnerMatches = true, deniedViewIds = setOf(1))
            assertEquals(listOf(other), result)
        }
    }
}
