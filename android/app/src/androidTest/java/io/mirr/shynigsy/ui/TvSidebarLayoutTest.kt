package io.mirr.shynigsy.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual TV-sized constraints and synthetic callbacks; no account, server, or device setting is used. */
@RunWith(AndroidJUnit4::class)
class TvSidebarLayoutTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Before fun requireIsolatedSyntheticApplication() {
        assertEquals("Only the isolated QA application may render TV sidebar fixtures",
            "io.mirr.shyniflex.spoilerqa", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test fun shortTelevisionSidebarKeepsBrandAndHeadingInsideSafeLeadingBounds() {
        val fixture = Fixture()
        render(fixture, heightDp = 540)
        screen.onNodeWithTag("tv-sidebar-scroll").assertExists()
        assertSidebarGeometry(fixture, expectedWidthDp = 240f, expectedHeightDp = 540f)
        assertCompleteLabel("tv-sidebar-brand", "SHYNIFLEX")
        assertCompleteLabel("tv-sidebar-library-heading", "라이브러리")
        assertSafeHeaderBounds(fixture)
        screen.runOnIdle { assertTrue("Layout and initial focus cannot invoke an action", fixture.calls.isEmpty()) }
    }

    @Test fun tallTelevisionSidebarRetainsTheSameWidthAndUnclippedHeaderLabels() {
        val fixture = Fixture()
        render(fixture, heightDp = 720)
        screen.onNodeWithTag("tv-sidebar-scroll").assertDoesNotExist()
        assertSidebarGeometry(fixture, expectedWidthDp = 240f, expectedHeightDp = 720f)
        assertCompleteLabel("tv-sidebar-brand", "SHYNIFLEX")
        assertCompleteLabel("tv-sidebar-library-heading", "라이브러리")
        assertSafeHeaderBounds(fixture)
        screen.onNodeWithText("계정").assertIsDisplayed().performClick()
        screen.runOnIdle { assertEquals(listOf("logout"), fixture.calls) }
    }

    @Test fun largeTextSidebarRemoteCanReachLongLibrariesAndEveryFinalAction() {
        val rows = (1..12).map { id ->
            Library(id, "합성 라이브러리 $id · 긴 영화와 드라마 이름을 두 줄로 표시하는 목록", "show")
        }
        val fixture = Fixture(rows, initialFontScale = 1.5f)
        render(fixture, heightDp = 540)
        assertSidebarGeometry(fixture, expectedWidthDp = 280f, expectedHeightDp = 540f)
        screen.runOnIdle { fixture.fontScale = 2f }
        assertSidebarGeometry(fixture, expectedWidthDp = 320f, expectedHeightDp = 540f)
        assertCompleteLabel("tv-sidebar-brand", "SHYNIFLEX")
        assertCompleteLabel("tv-sidebar-library-heading", "라이브러리")
        assertSafeHeaderBounds(fixture)
        screen.onNodeWithText("홈").performSemanticsAction(SemanticsActions.RequestFocus) {
            assertTrue("The first action accepts remote focus", it())
        }
        activate("홈")
        moveDown("홈", "검색")
        activate("검색")
        var previous = "검색"
        rows.forEach { row -> moveDown(previous, row.title); previous = row.title }
        activate(rows.last().title)
        moveDown(rows.last().title, "잠긴 라이브러리")
        activate("잠긴 라이브러리")
        moveDown("잠긴 라이브러리", "새로고침")
        activate("새로고침")
        moveDown("새로고침", "화면 색상")
        activate("화면 색상")
        moveDown("화면 색상", "업데이트")
        activate("업데이트")
        moveDown("업데이트", "계정")
        activate("계정")
        screen.onNodeWithText("계정").performKeyInput { pressKey(Key.DirectionUp) }
        screen.onNodeWithText("업데이트").assertIsFocused().assertIsDisplayed()
        screen.runOnIdle {
            assertEquals(listOf("home", "search", "library:12", "unlock", "refresh", "appearance", "update", "logout"),
                fixture.calls)
        }
    }

    private fun render(fixture: Fixture, heightDp: Int) {
        screen.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Fit a real 960-dp-wide layout into the QA window without faking configuration or resizing the device.
                val density = LocalDensity.current
                val fit = minOf(maxWidth.value / 960f, maxHeight.value / heightDp).coerceAtLeast(.01f)
                val pixelsPerDp = density.density * fit
                SideEffect { fixture.pixelsPerDp = pixelsPerDp }
                CompositionLocalProvider(LocalDensity provides Density(pixelsPerDp, fixture.fontScale)) {
                    MaterialTheme(colorScheme = CinemaHomeColors, typography = CompactTypography, shapes = CompactShapes) {
                        Row(Modifier.requiredSize(960.dp, heightDp.dp).testTag("tv-sidebar-layout-fixture")) {
                            TvSidebar(onLibraries = { fixture.calls += "unlock" },
                                onRefresh = { fixture.calls += "refresh" }, onLogout = { fixture.calls += "logout" },
                                libraries = fixture.libraries, onHome = { fixture.calls += "home" },
                                onLibrary = { fixture.calls += "library:${it.id}" }, showLocked = true,
                                onSearch = { fixture.calls += "search" }, onAppearance = { fixture.calls += "appearance" },
                                onUpdate = { fixture.calls += "update" })
                            Box(Modifier.weight(1f).fillMaxHeight().testTag("tv-sidebar-fixture-content"))
                        }
                    }
                }
            }
        }
    }

    private fun assertSidebarGeometry(fixture: Fixture, expectedWidthDp: Float, expectedHeightDp: Float) {
        val sidebar = bounds("tv-sidebar")
        val parent = bounds("tv-sidebar-layout-fixture")
        val content = bounds("tv-sidebar-fixture-content")
        val density = screen.runOnIdle { fixture.pixelsPerDp }
        assertEquals("The fixture has an actual 960-dp constraint", 960f * density, parent.width, 1f)
        assertEquals("The sidebar reserves enough label width", expectedWidthDp * density, sidebar.width, 1f)
        assertEquals("The sidebar uses the available TV height", expectedHeightDp * density, sidebar.height, 1f)
        assertEquals("Sidebar starts at the layout edge", parent.left, sidebar.left, 1f)
        assertTrue("Content must retain the remaining width", content.width > 0f)
        assertEquals("Sidebar and content cannot overlap", sidebar.right, content.left, 1f)
    }

    private fun assertSafeHeaderBounds(fixture: Fixture) {
        val sidebar = bounds("tv-sidebar")
        val density = screen.runOnIdle { fixture.pixelsPerDp }
        for (tag in listOf("tv-sidebar-brand", "tv-sidebar-library-heading")) {
            val label = bounds(tag)
            assertTrue("$tag must remain beyond the TV-safe leading inset", label.left >= sidebar.left + 48f * density - 1f)
            assertTrue("$tag must retain trailing space", label.right <= sidebar.right - 12f * density + 1f)
            assertTrue("$tag must be vertically inside the sidebar", label.top >= sidebar.top && label.bottom <= sidebar.bottom)
        }
        assertTrue("The brand must retain TV-safe top space", bounds("tv-sidebar-brand").top >= sidebar.top + 27f * density - 1f)
    }

    private fun assertCompleteLabel(tag: String, expected: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        screen.onNodeWithTag(tag).assertIsDisplayed().assertTextEquals(expected)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("$tag must produce one text layout", 1, layouts.size)
        val layout = layouts.single()
        assertEquals("$tag must remain on one complete line", 1, layout.lineCount)
        assertFalse("$tag cannot overflow its text box", layout.hasVisualOverflow)
        assertFalse("$tag cannot replace its ending with an ellipsis", layout.isLineEllipsized(0))
    }

    private fun moveDown(from: String, to: String) {
        screen.onNodeWithText(from).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        screen.onNodeWithText(to).assertIsFocused().assertIsDisplayed()
    }

    private fun activate(label: String) {
        screen.onNodeWithText(label).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
    }

    private fun bounds(tag: String): Rect = screen.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private class Fixture(
        val libraries: List<Library> = listOf(Library(1, "드라마", "show"), Library(2, "영화", "movie")),
        initialFontScale: Float = 1f,
    ) {
        var fontScale by mutableStateOf(initialFontScale)
        var pixelsPerDp = 1f
        val calls = mutableListOf<String>()
    }
}
