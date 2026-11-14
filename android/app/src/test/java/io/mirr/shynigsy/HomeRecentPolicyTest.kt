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

    @Test fun lateFullRefreshCannotOverwriteASuccessfulLibraryRetry() {
        val retried = item(11, 1)
        val untouched = item(22, 2)
        val incoming = listOf(untouched, item(10, 1))
        val current = listOf(item(20, 2), retried)
        assertEquals(listOf(retried, untouched),
            mergeRetriedHomeRecent(incoming, current, listOf(first, second), setOf(1)))
        assertEquals(listOf(incoming[1], untouched),
            mergeRetriedHomeRecent(incoming, current, listOf(first, second), emptySet()))
    }

    @Test fun lateFullRefreshCannotRestoreASuccessfullyEmptyLibrary() {
        val untouched = item(22, 2)
        assertEquals(listOf(untouched), mergeRetriedHomeRecent(
            incoming = listOf(item(10, 1), untouched), current = listOf(item(20, 2)),
            libraries = listOf(first, second), retriedViewIds = setOf(1)))
    }

    @Test fun retryProtectionNeverRestoresRemovedOrPrivacyChangedLibraries() {
        val publicThird = privateLibrary.copy(isSpoiler = false)
        val incoming = listOf(item(10, 1), item(30, 3), item(31, 3, true), item(90, 9))
        val current = listOf(item(11, 1), item(32, 3), item(33, 3, true), item(91, 9))
        assertEquals(listOf(current[2]), mergeRetriedHomeRecent(
            incoming, current, listOf(privateLibrary), setOf(1, 3, 9)))
        assertEquals(listOf(current[1]), mergeRetriedHomeRecent(
            incoming, current, listOf(publicThird), setOf(1, 3, 9)))
        assertEquals(emptyList<MediaItem>(), mergeRetriedHomeRecent(
            incoming, listOf(current[1]), listOf(privateLibrary), setOf(3)))
        assertEquals(listOf(incoming[2]), mergeRetriedHomeRecent(
            incoming, current, listOf(privateLibrary), setOf(1, 9)))
    }

    @Test fun retryMergeKeepsAllSixteenAuthorizedLibrariesInPickerOrder() {
        val libraries = (1..16).map { Library(it, "library $it", "movie", isSpoiler = it % 4 == 0) }
        val incoming = libraries.reversed().map { item(it.id * 10, it.id, it.isSpoiler) }
        val current = (libraries.drop(5) + libraries.take(5)).map { item(it.id * 10 + 1, it.id, it.isSpoiler) }
        val retried = setOf(2, 8, 16)
        val result = mergeRetriedHomeRecent(incoming, current, libraries, retried)
        assertEquals((1..16).toList(), result.map { it.libraryId })
        assertEquals((1..16).map { it * 10 + if (it in retried) 1 else 0 }, result.map { it.id })
        assertEquals(libraries.map { it.isSpoiler }, result.map { it.isSpoiler })
    }
}
