package io.mirr.shynigsy

import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaPage
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPageAppendTest {
    private fun item(id: Int) = MediaItem(id, 1, "작품 $id", "movie")

    @Test fun overlappingPagesKeepOrderAndAppendOnlyNewWorks() {
        val result = appendLibraryPage(listOf(item(1), item(2)), MediaPage(listOf(item(2), item(3)), 5))
        assertEquals(listOf(1, 2, 3), result.items.map { it.id })
        assertEquals(5, result.total)
    }

    @Test fun repeatedNonemptyPageStopsFurtherAutomaticRequests() {
        val result = appendLibraryPage(listOf(item(1), item(2)), MediaPage(listOf(item(2)), 500))
        assertEquals(listOf(1, 2), result.items.map { it.id })
        assertEquals(2, result.total)
    }

    @Test fun emptyPageEndsTheSnapshotWithoutDiscardingLoadedWorks() {
        val result = appendLibraryPage(listOf(item(1)), MediaPage(emptyList(), 500))
        assertEquals(listOf(item(1)), result.items)
        assertEquals(1, result.total)
    }

    @Test fun aChangedServerTotalCannotBeLessThanTheLoadedWorks() {
        val result = appendLibraryPage(listOf(item(1), item(2)), MediaPage(listOf(item(3)), 1))
        assertEquals(3, result.items.size)
        assertEquals(3, result.total)
    }
}
