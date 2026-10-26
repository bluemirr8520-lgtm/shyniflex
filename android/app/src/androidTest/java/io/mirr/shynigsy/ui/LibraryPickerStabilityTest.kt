package io.mirr.shynigsy.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real sheet geometry and callback fixtures only; no account, server, or system settings are used. */
@RunWith(AndroidJUnit4::class)
class LibraryPickerStabilityTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Before fun requireIsolatedSyntheticApplication() {
        assertEquals("Only the isolated QA application may render library sheet fixtures",
            "io.mirr.shyniflex.spoilerqa", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test fun scrollingMixedHeightPrivateLibrariesKeepsTheSheetAndViewportStationary() {
        val rows = libraries(1..36, isPrivate = true)
        val fixture = Fixture(rows, unlocked = true)
        render(fixture)
        val sheetBefore = bounds(SHEET)
        val listBefore = bounds(LIST)
        repeat(3) {
            list().performTouchInput { swipeUp() }
            assertStableBounds(sheetBefore, SHEET)
            assertStableBounds(listBefore, LIST)
        }
        list().performScrollToNode(hasTestTag(rowTag(rows.last())))
        screen.onNodeWithTag(rowTag(rows.last())).assertIsDisplayed()
        // A swipe at the list's bottom must not hand the gesture to the surrounding sheet.
        list().performTouchInput { swipeUp() }
        assertStableBounds(sheetBefore, SHEET)
        assertStableBounds(listBefore, LIST)
        screen.runOnIdle { assertTrue(fixture.selected.isEmpty()); assertEquals(0, fixture.dismissals) }
        screen.onNodeWithTag(rowTag(rows.last())).performClick()
        screen.runOnIdle { assertEquals(listOf(rows.last()), fixture.selected) }
        screen.onNodeWithTag(SHEET).assertDoesNotExist()
    }

    @Test fun unlockCatalogArrivalAndStatusRecompositionPreserveTheVisibleLibraryPosition() {
        val publicRows = libraries(1..24)
        val privateRows = libraries(101..116, isPrivate = true)
        val fixture = Fixture(publicRows, unlocked = false)
        render(fixture)
        val anchor = publicRows[11]
        list().performScrollToNode(hasTestTag(rowTag(anchor)))
        val anchorBefore = bounds(rowTag(anchor))
        val sheetBefore = bounds(SHEET)
        val listBefore = bounds(LIST)
        screen.runOnIdle {
            fixture.unlocked = true
            fixture.rows = publicRows + privateRows
            fixture.spoilerBusy = true
        }
        assertStableBounds(sheetBefore, SHEET)
        assertStableBounds(listBefore, LIST)
        assertStableBounds(anchorBefore, rowTag(anchor))
        screen.runOnIdle { fixture.spoilerBusy = false; fixture.error = "합성 목록 갱신 안내" }
        assertStableBounds(sheetBefore, SHEET)
        assertStableBounds(listBefore, LIST)
        assertStableBounds(anchorBefore, rowTag(anchor))
        list().performScrollToNode(hasTestTag(rowTag(privateRows.last())))
        screen.onNodeWithTag(rowTag(privateRows.last())).assertIsDisplayed()
        screen.runOnIdle { assertTrue(fixture.selected.isEmpty()); assertEquals(0, fixture.dismissals) }
    }

    @Test fun boundedLargeTextSheetCanReachTheLastLibraryHidePrivateRowsAndClose() {
        val publicRows = libraries(1..18)
        val privateRows = libraries(101..112, isPrivate = true)
        val fixture = Fixture(publicRows + privateRows, unlocked = true)
        render(fixture, boundedLargeText = true)
        val sheetBefore = bounds(SHEET)
        val listBefore = bounds(LIST)
        val maximumPixels = 260f * screen.activity.resources.displayMetrics.density
        assertTrue("The real modal receives a bounded layout, not a fake configuration height",
            sheetBefore.height <= maximumPixels + 1f && listBefore.height <= maximumPixels + 1f)
        list().performScrollToNode(hasTestTag(rowTag(privateRows.last())))
        screen.onNodeWithTag(rowTag(privateRows.last())).assertIsDisplayed()
        assertStableBounds(sheetBefore, SHEET)
        assertStableBounds(listBefore, LIST)
        screen.runOnIdle { fixture.unlocked = false }
        privateRows.forEach { screen.onNodeWithTag(rowTag(it)).assertDoesNotExist() }
        assertStableBounds(sheetBefore, SHEET)
        assertStableBounds(listBefore, LIST)
        list().performScrollToNode(hasTestTag(rowTag(publicRows.last())))
        screen.onNodeWithTag(rowTag(publicRows.last())).assertIsDisplayed()
        list().performScrollToNode(hasTestTag("mobile-library-close"))
        screen.onNodeWithTag("mobile-library-close").assertIsDisplayed().performClick()
        screen.onNodeWithTag(SHEET).assertDoesNotExist()
        screen.runOnIdle { assertEquals(1, fixture.dismissals); assertTrue(fixture.selected.isEmpty()) }
    }

    private fun render(fixture: Fixture, boundedLargeText: Boolean = false) {
        screen.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides
                if (boundedLargeText) Density(density.density, fontScale = 2f) else density) {
                MaterialTheme(colorScheme = CinemaHomeColors, typography = CompactTypography, shapes = CompactShapes) {
                    Box(Modifier.fillMaxSize()) {
                        if (fixture.open) LibraryPicker(libraries = fixture.rows, selectedLibraryId = null,
                            busy = false, onDismiss = { fixture.dismissals++; fixture.open = false },
                            onSelect = { fixture.selected += it; fixture.open = false },
                            spoilerAvailable = true, spoilerUnlocked = fixture.unlocked,
                            spoilerBusy = fixture.spoilerBusy, spoilerError = fixture.error,
                            onUnlockSpoiler = {}, onLockSpoiler = {}, television = false,
                            modifier = if (boundedLargeText) Modifier.heightIn(max = 260.dp) else Modifier)
                    }
                }
            }
        }
    }

    private fun list() = screen.onNodeWithTag(LIST)
    private fun bounds(tag: String): Rect = screen.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun assertStableBounds(before: Rect, tag: String) {
        val after = bounds(tag)
        assertEquals("$tag left", before.left, after.left, 1f)
        assertEquals("$tag top", before.top, after.top, 1f)
        assertEquals("$tag width", before.width, after.width, 1f)
        assertEquals("$tag height", before.height, after.height, 1f)
    }

    private fun libraries(ids: IntRange, isPrivate: Boolean = false): List<Library> = ids.map { id ->
        val title = if (id % 2 == 0) "합성 라이브러리 $id" else
            "합성 라이브러리 $id · 여러 줄 높이를 확인하는 긴 이름 · 영화와 시리즈의 전체 목록"
        Library(id, title, if (id % 3 == 0) "movie" else "show", isSpoiler = isPrivate)
    }

    private class Fixture(initialRows: List<Library>, unlocked: Boolean) {
        var rows by mutableStateOf(initialRows)
        var unlocked by mutableStateOf(unlocked)
        var open by mutableStateOf(true)
        var spoilerBusy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var dismissals = 0
        val selected = mutableListOf<Library>()
    }

    private fun rowTag(library: Library) = "mobile-library-${library.id}"
    private companion object {
        const val SHEET = "cinema-mobile-libraries-sheet"
        const val LIST = "cinema-mobile-libraries-list"
    }
}
