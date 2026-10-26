package io.mirr.shynigsy.ui

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
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

/** Callback-only navigation fixtures: no account, server, preferences or player is constructed. */
@RunWith(AndroidJUnit4::class)
class MobileBottomNavigationTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Before fun requireIsolatedSyntheticApplication() {
        assertEquals("Only the isolated QA application may run this navigation fixture",
            "io.mirr.shyniflex.spoilerqa",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test fun fourBottomDestinationsDispatchOnlyTheirOwnCallbacks() {
        var destination by mutableStateOf("home")
        val calls = mutableListOf<String>()
        screen.setContent {
            FixtureTheme {
                CinemaHomeBottomNavigation(
                    homeSelected = destination == "home", searchSelected = destination == "search",
                    librarySelected = destination == "libraries", moreSelected = destination == "more",
                    onHome = { calls += "home"; destination = "home" },
                    onSearch = { calls += "search"; destination = "search" },
                    onLibraries = { calls += "libraries"; destination = "libraries" },
                    onMore = { calls += "more"; destination = "more" },
                )
            }
        }
        screen.onNodeWithTag("cinema-mobile-home").assertIsSelected()
        screen.onNodeWithTag("cinema-mobile-more").assertIsNotSelected()
        screen.runOnIdle { assertTrue("Rendering navigation must not change a destination", calls.isEmpty()) }
        for (name in listOf("search", "libraries", "more", "home")) {
            screen.onNodeWithTag("cinema-mobile-$name").assertIsDisplayed().performClick()
            screen.onNodeWithTag("cinema-mobile-$name").assertIsSelected()
        }
        screen.runOnIdle { assertEquals(listOf("search", "libraries", "more", "home"), calls) }
    }

    @Test fun phoneAndWideTabletHeadersExposeNoTopNavigationActions() {
        var compact by mutableStateOf(true)
        val calls = mutableListOf<String>()
        screen.setContent {
            FixtureTheme {
                CinemaHomeHeader(compact = compact, television = false,
                    onAppearance = { calls += "appearance" }, onSearch = { calls += "search" })
            }
        }
        screen.onNodeWithText("SHYNIFLEX").assertExists()
        assertNoMobileHeaderActions()
        screen.runOnIdle { compact = false }
        screen.onNodeWithText("SHYNIFLEX").assertExists()
        assertNoMobileHeaderActions()
        screen.runOnIdle { assertTrue("A wider mobile layout cannot activate TV navigation", calls.isEmpty()) }
    }

    @Test fun televisionHeaderRetainsPaletteSearchAndClock() {
        val calls = mutableListOf<String>()
        screen.setContent {
            FixtureTheme {
                CinemaHomeHeader(compact = false, television = true,
                    onAppearance = { calls += "appearance" }, onSearch = { calls += "search" })
            }
        }
        screen.onNodeWithTag("tv-home-clock").assertExists()
        screen.onNodeWithTag("cinema-home-appearance").assertIsDisplayed().performClick()
        screen.onNodeWithTag("cinema-home-search").assertIsDisplayed().performClick()
        screen.onNodeWithTag("cinema-home-menu").assertDoesNotExist()
        screen.runOnIdle { assertEquals(listOf("appearance", "search"), calls) }
    }

    @Test fun moreMenuKeepsEveryFormerSidebarActionReachable() {
        val fixture = MoreFixture()
        renderMore(fixture)
        val actions = listOf("refresh", "appearance", "spoiler", "update", "account")
        for ((index, name) in actions.withIndex()) {
            if (index > 0) screen.runOnIdle { fixture.open = true }
            screen.onNodeWithTag("mobile-more-actions").performScrollToNode(hasTestTag("mobile-more-$name"))
            screen.onNodeWithTag("mobile-more-$name").assertIsDisplayed().performClick()
            screen.runOnIdle {
                assertFalse("Selecting $name must close the menu before opening its destination", fixture.open)
                assertEquals(listOf("dismiss", name), fixture.calls.takeLast(2))
            }
            screen.onNodeWithTag("mobile-more-sheet").assertDoesNotExist()
        }
        screen.runOnIdle {
            assertEquals(actions.flatMap { listOf("dismiss", it) }, fixture.calls)
        }
    }

    @Test fun spoilerActionTracksLockStateWithoutDispatchingDuringRecomposition() {
        val fixture = MoreFixture()
        renderMore(fixture)
        screen.onNodeWithTag("mobile-more-actions").performScrollToNode(hasTestTag("mobile-more-spoiler"))
        screen.onNodeWithText("잠긴 라이브러리").assertIsDisplayed()
        screen.onNodeWithText("스포일러 잠그기").assertDoesNotExist()
        screen.runOnIdle { fixture.unlocked = true }
        screen.onNodeWithText("스포일러 잠그기").assertIsDisplayed()
        screen.onNodeWithText("잠긴 라이브러리").assertDoesNotExist()
        screen.runOnIdle { assertTrue("Changing the visible lock state cannot unlock or lock by itself", fixture.calls.isEmpty()) }
        screen.onNodeWithTag("mobile-more-spoiler").performClick()
        screen.runOnIdle { assertEquals(listOf("dismiss", "spoiler"), fixture.calls) }
    }

    @Test fun shortLargeTextMenuCanReachItsLastActionAndDismiss() {
        val fixture = MoreFixture()
        renderMore(fixture, shortLargeText = true)
        screen.onNodeWithTag("mobile-more-actions").performScrollToNode(hasTestTag("mobile-more-account"))
        screen.onNodeWithText("계정 / 로그아웃").assertIsDisplayed()
        screen.onNodeWithTag("mobile-more-account").performClick()
        screen.runOnIdle { assertEquals(listOf("dismiss", "account"), fixture.calls); fixture.open = true }
        screen.onNodeWithTag("mobile-more-sheet").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        screen.waitForIdle()
        screen.onNodeWithTag("mobile-more-sheet").assertDoesNotExist()
        screen.runOnIdle {
            assertEquals("Back only dismisses; it cannot repeat the previous account action",
                listOf("dismiss", "account", "dismiss"), fixture.calls)
        }
    }

    @Test fun mobileLibrarySheetHidesLockedTitlesAndSelectsOnlyTheVisibleLibrary() {
        val publicLibrary = Library(31, "합성 공개 라이브러리", "movie")
        val privateLibrary = Library(32, "합성 비공개 라이브러리", "show", true)
        var unlocked by mutableStateOf(false)
        var open by mutableStateOf(true)
        val selected = mutableListOf<Library>()
        screen.setContent {
            FixtureTheme {
                if (open) LibraryPicker(libraries = listOf(publicLibrary, privateLibrary), selectedLibraryId = publicLibrary.id,
                    busy = false, onDismiss = { open = false }, onSelect = { selected += it; open = false },
                    spoilerAvailable = true, spoilerUnlocked = unlocked, spoilerBusy = false, spoilerError = null,
                    onUnlockSpoiler = {}, onLockSpoiler = {}, television = false)
            }
        }
        screen.onNodeWithTag("cinema-mobile-libraries-sheet").assertExists()
        screen.onNodeWithTag("mobile-library-${privateLibrary.id}").assertDoesNotExist()
        screen.onNodeWithText(privateLibrary.title).assertDoesNotExist()
        screen.runOnIdle { unlocked = true }
        screen.onNodeWithTag("cinema-mobile-libraries-list")
            .performScrollToNode(hasTestTag("mobile-library-${privateLibrary.id}"))
        screen.onNodeWithText(privateLibrary.title).assertIsDisplayed()
        screen.runOnIdle { unlocked = false }
        screen.onNodeWithTag("mobile-library-${privateLibrary.id}").assertDoesNotExist()
        screen.onNodeWithText(privateLibrary.title).assertDoesNotExist()
        screen.runOnIdle { assertTrue("Lock-state refreshes cannot select a library", selected.isEmpty()); unlocked = true }
        screen.onNodeWithTag("cinema-mobile-libraries-list")
            .performScrollToNode(hasTestTag("mobile-library-${privateLibrary.id}"))
        screen.onNodeWithTag("mobile-library-${privateLibrary.id}").assertIsDisplayed().performClick()
        screen.runOnIdle { assertEquals(listOf(privateLibrary), selected); assertFalse(open) }
        screen.onNodeWithTag("cinema-mobile-libraries-sheet").assertDoesNotExist()
    }

    private fun assertNoMobileHeaderActions() {
        for (tag in listOf("cinema-home-menu", "cinema-home-appearance", "cinema-home-search", "tv-home-clock")) {
            screen.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    private fun renderMore(fixture: MoreFixture, shortLargeText: Boolean = false) {
        screen.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenHeightDp = 240
                screenWidthDp = 640
            }
            val density = Density(LocalDensity.current.density, fontScale = 2f)
            val content: @Composable () -> Unit = {
                FixtureTheme {
                    if (fixture.open) MobileMoreMenu(spoilerUnlocked = fixture.unlocked,
                        onDismiss = { fixture.calls += "dismiss"; fixture.open = false },
                        onRefresh = { fixture.calls += "refresh" }, onAppearance = { fixture.calls += "appearance" },
                        onUpdate = { fixture.calls += "update" }, onLogout = { fixture.calls += "account" },
                        onSpoilerAccess = { fixture.calls += "spoiler" })
                }
            }
            if (shortLargeText) CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalDensity provides density, content = content) else content()
        }
        screen.waitForIdle()
    }

    @Composable
    private fun FixtureTheme(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = CinemaHomeColors, typography = CompactTypography, shapes = CompactShapes) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }

    private class MoreFixture {
        var open by mutableStateOf(true)
        var unlocked by mutableStateOf(false)
        val calls = mutableListOf<String>()
    }
}
