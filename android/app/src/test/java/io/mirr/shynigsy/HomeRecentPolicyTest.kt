package io.mirr.shynigsy

import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRecentPolicyTest {
    private val first = Library(1, "first", "movie")
    private val second = Library(2, "second", "movie")
    private val privateLibrary = Library(3, "private", "movie", isSpoiler = true)
    private fun item(id: Int, view: Int, isPrivate: Boolean = false) =
        MediaItem(id, view, "item", "movie", isSpoiler = isPrivate)

    @Test fun retainsOnlyEligibleMissingViewsInLibraryOrder() {
        val old = listOf(item(1, 1), item(2, 2))
        val fresh = listOf(item(3, 2))
        assertEquals(listOf(old[0], fresh[0]), mergeHomeRecent(fresh, old, listOf(first, second), setOf(1, 2), true))
        assertEquals(fresh, mergeHomeRecent(fresh, old, listOf(first, second), emptySet(), true))
    }

    @Test fun successEmptyAndChangedAccountNeverRestoreOldRows() {
        val old = listOf(item(1, 1))
        assertEquals(emptyList<MediaItem>(), mergeHomeRecent(emptyList(), old, listOf(first), emptySet(), true))
        assertEquals(emptyList<MediaItem>(), mergeHomeRecent(emptyList(), old, listOf(first), setOf(1), false))
    }

    @Test fun excludesRemovedOrPrivacyChangedViews() {
        val old = listOf(item(1, 1), item(2, 3), item(3, 3, true), item(4, 9))
        assertEquals(listOf(old[2]), mergeHomeRecent(emptyList(), old, listOf(privateLibrary), setOf(1, 3, 9), true))
        assertEquals(emptyList<MediaItem>(), mergeHomeRecent(listOf(old[1]), emptyList(), listOf(privateLibrary), emptySet(), true))
    }
}
