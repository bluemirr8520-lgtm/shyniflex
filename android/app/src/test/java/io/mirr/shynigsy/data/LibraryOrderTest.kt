package io.mirr.shynigsy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryOrderTest {
    private fun libraries(vararg ids: Int) = ids.map { Library(it, "라이브러리 $it", "tv") }

    @Test fun noPreferenceUsesCurrentServerOrderAndResetReturnsToIt() {
        val catalogue = libraries(7, 2, 9)
        assertEquals(listOf(7, 2, 9), applyLibraryOrder(catalogue, emptyList()).map { it.id })
        assertEquals(listOf(9, 2, 7), applyLibraryOrder(catalogue, listOf(9, 2, 7)).map { it.id })
        assertEquals(catalogue, applyLibraryOrder(catalogue, decodeLibraryOrder(null)))
    }

    @Test fun newlyAddedViewsAppendInServerOrderAndMissingViewsAreNotInvented() {
        val catalogue = libraries(8, 3, 9, 1, 4)
        assertEquals(listOf(1, 3, 8, 9, 4), applyLibraryOrder(catalogue, listOf(1, 2, 3)).map { it.id })
    }

    @Test fun visibleMoveRetainsHiddenSlotsUntilUnlock() {
        val saved = listOf(1, 90, 2, 91, 3)
        val changed = moveVisibleLibraryOrder(saved, listOf(1, 2, 3), 3, -1)
        assertEquals(listOf(1, 90, 3, 91, 2), changed)
        assertEquals(listOf(1, 3, 2), applyLibraryOrder(libraries(1, 2, 3), changed).map { it.id })
        assertEquals(listOf(1, 90, 3, 91, 2), applyLibraryOrder(libraries(1, 2, 3, 90, 91), changed).map { it.id })
    }

    @Test fun firstEditRecordsVisibleOrderWhileLaterEditsRetainUnavailableViews() {
        val first = moveVisibleLibraryOrder(emptyList(), listOf(5, 3, 7), 5, 1)
        assertEquals(listOf(3, 5, 7), first)
        assertEquals(listOf(7, 5, 3), moveVisibleLibraryOrder(first, listOf(3, 7), 7, -1))
    }

    @Test fun unauthorizedOrOutOfRangeMovesCannotReorderAnything() {
        val saved = listOf(1, 90, 2)
        assertEquals(saved, moveVisibleLibraryOrder(saved, listOf(1, 2), 90, -1))
        assertEquals(saved, moveVisibleLibraryOrder(saved, listOf(1, 2), 1, -1))
        assertEquals(saved, moveVisibleLibraryOrder(saved, listOf(1, 2), 2, 1))
        assertEquals(saved, moveVisibleLibraryOrder(saved, listOf(1, 2), 1, 2))
    }

    @Test fun duplicateAndInvalidStoredIdsDoNotHideOrDuplicateLibraryRows() {
        val decoded = decodeLibraryOrder("3,3,-1,0,bad,2147483648,1")
        assertEquals(listOf(3, 1), decoded)
        assertEquals(listOf(3, 1, 2), applyLibraryOrder(libraries(1, 2, 3), decoded).map { it.id })
        assertEquals(emptyList<Int>(), decodeLibraryOrder("9".repeat(24_001)))
    }

    @Test fun serverAndAccountAreSeparatedWithoutPlaintextPreferenceKeys() {
        val key = libraryOrderScope("https://SERVER.example:443/", "viewer")!!
        assertEquals(key, libraryOrderScope(" https://server.example ", " viewer "))
        assertNotEquals(key, libraryOrderScope("http://server.example", "viewer"))
        assertNotEquals(key, libraryOrderScope("https://server.example:8443", "viewer"))
        assertNotEquals(key, libraryOrderScope("https://server.example", "other"))
        assertEquals(64, key.length)
        assertFalse(key.contains("viewer"))
        assertFalse(key.contains("server"))
    }

    @Test fun missingAccountOrNonOriginAddressCannotSharePreferences() {
        assertNull(libraryOrderScope("https://server.example", ""))
        assertNull(libraryOrderScope("https://server.example", "사용자"))
        assertNull(libraryOrderScope("https://user:password@server.example", "viewer"))
        assertNull(libraryOrderScope("https://server.example/path", "viewer"))
        assertNull(libraryOrderScope("https://server.example?token=value", "viewer"))
    }
}
