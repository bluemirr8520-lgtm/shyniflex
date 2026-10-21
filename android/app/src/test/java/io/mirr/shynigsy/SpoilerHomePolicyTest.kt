package io.mirr.shynigsy

import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem
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
}
