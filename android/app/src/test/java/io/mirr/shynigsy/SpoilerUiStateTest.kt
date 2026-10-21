package io.mirr.shynigsy

import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaVersion
import io.mirr.shynigsy.data.PlaybackSpec
import org.junit.Assert.*
import org.junit.Test

class SpoilerUiStateTest {
    private val publicLibrary = Library(1, "공개", "movie")
    private val privateLibrary = Library(2, "비공개", "movie", isSpoiler = true)
    private val publicItem = MediaItem(10, 1, "공개 영상", "movie")
    private val privateItem = MediaItem(20, 2, "비공개 영상", "movie", isSpoiler = true)

    @Test fun revocationRemovesEveryPrivateScreenAndPendingPlayback() {
        val state = AppState(loggedIn = true, spoilerUnlocked = true, spoilerPromptOpen = true, spoilerBusy = true,
            libraries = listOf(publicLibrary, privateLibrary), recentItems = listOf(publicItem, privateItem),
            continueItems = listOf(privateItem, publicItem), continueDetails = privateItem,
            library = privateLibrary, items = listOf(privateItem), total = 1, query = "private search",
            detailStack = listOf(MediaDetails(privateItem)), versions = listOf(MediaVersion(4, "private file")),
            selectedPartId = 4, playback = playback(privateItem), busy = true, error = "private request failed")
        val locked = withoutSpoilerContent(state)
        assertFalse(locked.spoilerUnlocked)
        assertFalse(locked.spoilerPromptOpen)
        assertFalse(locked.spoilerBusy)
        assertFalse(locked.busy)
        assertEquals(listOf(publicLibrary), locked.libraries)
        assertEquals(listOf(publicItem), locked.recentItems)
        assertEquals(listOf(publicItem), locked.continueItems)
        assertNull(locked.continueDetails)
        assertNull(locked.library)
        assertTrue(locked.items.isEmpty())
        assertTrue(locked.detailStack.isEmpty())
        assertTrue(locked.versions.isEmpty())
        assertEquals(0, locked.total)
        assertEquals("", locked.query)
        assertNull(locked.selectedPartId)
        assertNull(locked.playback)
        assertNull(locked.error)
        assertTrue("Locking private access must not sign out the account", locked.loggedIn)
    }

    @Test fun anUnrelatedPublicBrowseAndPlaybackSurviveRevocation() {
        val state = AppState(library = publicLibrary, items = listOf(publicItem), total = 50, query = "public search",
            detailStack = listOf(MediaDetails(publicItem, listOf(publicItem, privateItem))),
            versions = listOf(MediaVersion(5, "public file")), selectedPartId = 5, playback = playback(publicItem))
        val locked = withoutSpoilerContent(state)
        assertEquals(publicLibrary, locked.library)
        assertEquals(listOf(publicItem), locked.items)
        assertEquals(50, locked.total)
        assertEquals("public search", locked.query)
        assertEquals(listOf(publicItem), locked.detailStack.single().children)
        assertEquals(state.versions, locked.versions)
        assertEquals(5, locked.selectedPartId)
        assertEquals(state.playback, locked.playback)
    }

    private fun playback(item: MediaItem) = PlaybackSpec("test", "https://example.invalid/test", itemId = item.id,
        libraryId = item.libraryId, partId = 1, sourceItem = item)
}
