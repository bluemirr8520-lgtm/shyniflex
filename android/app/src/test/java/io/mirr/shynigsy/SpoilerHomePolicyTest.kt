package io.mirr.shynigsy

import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoilerHomePolicyTest {
    private val publicLibrary = Library(1, "공개", "movie")
    private val privateLibrary = Library(2, "스포일러", "tv", isSpoiler = true)
    private val libraries = listOf(publicLibrary, privateLibrary)
    private val publicItem = MediaItem(10, 1, "공개 영화", "movie", viewItemId = 101)
    private val privateItem = MediaItem(20, 2, "스포일러 회차", "episode", viewItemId = 202)

    @Test fun lockedHomeAcceptsOnlyKnownPublicViewsAndUnflaggedPublicRows() {
        val publicRows = listOf(publicItem, privateItem, publicItem.copy(id = 11, isSpoiler = true),
            publicItem.copy(id = 12, libraryId = 999))
        val result = visibleContinueWatching(publicRows, listOf(privateItem), libraries, spoilerUnlocked = false)
        assertEquals(listOf(publicItem), result)
        assertFalse(result.any { it.isSpoiler })
    }

    @Test fun unlockedHomeUsesNativePrivateRowsOnlyForKnownPrivateViews() {
        val result = visibleContinueWatching(listOf(publicItem, privateItem),
            listOf(privateItem, publicItem.copy(id = 99), privateItem.copy(id = 21, libraryId = 999)),
            libraries, spoilerUnlocked = true)
        assertEquals(listOf(publicItem, privateItem.copy(isSpoiler = true)), result)
        assertTrue("A native private response cannot retain an accidental public flag", result.last().isSpoiler)
    }

    @Test fun aViewListedAsBothPublicAndPrivateIsAlwaysPrivate() {
        val overlap = publicItem.copy(title = "겹치는 뷰")
        val overlapLibraries = listOf(publicLibrary, publicLibrary.copy(isSpoiler = true))
        assertTrue(visibleContinueWatching(listOf(overlap), listOf(overlap), overlapLibraries,
            spoilerUnlocked = false).isEmpty())
        assertEquals(listOf(overlap.copy(isSpoiler = true)),
            visibleContinueWatching(listOf(overlap), listOf(overlap), overlapLibraries, spoilerUnlocked = true))
    }

    @Test fun exactDuplicatesAreRemovedWithoutMergingDifferentViewsOrMediaTypes() {
        val otherView = publicItem.copy(viewItemId = 102)
        val episode = publicItem.copy(type = "episode")
        val result = visibleContinueWatching(listOf(publicItem, publicItem, otherView, episode),
            listOf(privateItem, privateItem), libraries, spoilerUnlocked = true)
        assertEquals(listOf(publicItem, otherView, episode, privateItem.copy(isSpoiler = true)), result)
    }

    @Test fun relockingDiscardsPreviouslyFetchedPrivateProgressWithoutRemovingPublicProgress() {
        val rows = visibleContinueWatching(listOf(publicItem), listOf(privateItem), libraries, spoilerUnlocked = true)
        assertEquals(2, rows.size)
        val locked = visibleContinueWatching(rows, listOf(privateItem), libraries, spoilerUnlocked = false)
        assertEquals(listOf(publicItem), locked)
    }

    @Test fun rotatedGrantCannotRepublishOldPrivateNamesRowsOrPublicShadows() {
        val overlap = privateLibrary.copy(isSpoiler = false)
        val oldPrivate = privateItem.copy(isSpoiler = true)
        val staleLibraries = homeLibrariesForGrant(listOf(publicLibrary, overlap), listOf(privateLibrary),
            setOf(privateLibrary.id), grantCurrent = false)
        assertEquals(listOf(publicLibrary), staleLibraries)
        assertEquals(listOf(publicItem), visibleContinueWatching(listOf(publicItem, privateItem), listOf(oldPrivate),
            staleLibraries, spoilerUnlocked = false))
        val freshLibraries = homeLibrariesForGrant(listOf(publicLibrary, overlap), listOf(privateLibrary),
            setOf(privateLibrary.id), grantCurrent = true)
        assertEquals(libraries, freshLibraries)
        assertEquals(listOf(publicItem, oldPrivate), visibleContinueWatching(listOf(publicItem), listOf(privateItem),
            freshLibraries, spoilerUnlocked = true))
    }

    @Test fun grantRotationClearsOnlyOldPrivateHomeContentWithoutInterruptingPlayback() {
        val oldPrivate = privateItem.copy(isSpoiler = true)
        val playback = PlaybackSpec("video", "https://test.invalid/video", itemId = 20, libraryId = 2, partId = 1,
            sourceItem = oldPrivate)
        val state = AppState(spoilerUnlocked = true, libraries = libraries, recentItems = listOf(publicItem, oldPrivate),
            continueItems = listOf(oldPrivate, publicItem), continueDetails = oldPrivate, playback = playback)
        val cleared = withoutStalePrivateHomeContent(state)
        assertEquals(listOf(publicLibrary), cleared.libraries)
        assertEquals(listOf(publicItem), cleared.recentItems)
        assertEquals(listOf(publicItem), cleared.continueItems)
        assertEquals(null, cleared.continueDetails)
        assertTrue(cleared.spoilerUnlocked)
        assertEquals(playback, cleared.playback)
    }

    @Test fun newGrantClearsBeforeSnapshotButLateCollectorKeepsFreshHomeRows() {
        val client = Any()
        val scope = PrivateHomeGrantScope()
        val old = privateItem.copy(isSpoiler = true, title = "old")
        val fresh = privateItem.copy(isSpoiler = true, title = "fresh")
        assertTrue(scope.advance(client, "account", 10))
        var state = AppState(spoilerUnlocked = true, libraries = libraries, recentItems = listOf(publicItem, old))
        // Grant B may start loading before its registry collector gets CPU time.
        if (scope.advance(client, "account", 11)) state = withoutStalePrivateHomeContent(state)
        assertEquals(listOf(publicItem), state.recentItems)
        state = state.copy(libraries = libraries, recentItems = listOf(publicItem, fresh))
        // The delayed B notification must not erase B's newly published data.
        if (scope.advance(client, "account", 11)) state = withoutStalePrivateHomeContent(state)
        assertEquals(listOf(publicItem, fresh), state.recentItems)
        assertEquals(libraries, state.libraries)
    }

    @Test fun homeGrantScopeIsBoundToAccountClientAndResetOnLock() {
        val client = Any()
        val scope = PrivateHomeGrantScope()
        assertTrue(scope.advance(client, "one", 1))
        assertFalse(scope.advance(client, "one", 1))
        assertTrue(scope.advance(client, "two", 1))
        assertTrue(scope.advance(Any(), "two", 1))
        scope.reset()
        assertTrue(scope.advance(client, "one", 1))
    }
}
