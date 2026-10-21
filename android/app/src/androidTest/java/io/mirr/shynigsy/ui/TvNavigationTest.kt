package io.mirr.shynigsy.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.isTelevision
import io.mirr.shynigsy.data.Library
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TvNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unwatchedBadgeShowsCountAndHidesZeroOrUnknown() {
        var count by mutableStateOf<Int?>(42)
        compose.setContent { MaterialTheme { UnwatchedCountBadge(count) } }
        compose.onNodeWithContentDescription("미시청 42편").assertExists()
        compose.onNodeWithText("42").assertExists()
        compose.runOnIdle { count = 0 }
        compose.onNodeWithText("42").assertDoesNotExist()
        compose.onNodeWithContentDescription("미시청 0편").assertDoesNotExist()
        compose.runOnIdle { count = null }
        compose.onAllNodesWithText("0").assertCountEquals(0)
    }

    @Test fun unlockedSidebarCanRelockAndHidePrivateLibraries() {
        compose.setContent {
            var unlocked by remember { mutableStateOf(true) }
            MaterialTheme {
                TvSidebar(onLibraries = {}, onRefresh = {}, onLogout = {},
                    libraries = listOf(Library(1, "공개 목록", "show")) +
                        if (unlocked) listOf(Library(2, "비공개 목록", "show", true)) else emptyList(),
                    showLocked = !unlocked, spoilerUnlocked = unlocked,
                    onLockSpoiler = { unlocked = false })
            }
        }
        compose.onNodeWithText("비공개 목록").assertExists()
        compose.onNodeWithText("스포일러 잠그기").performScrollTo().performClick()
        compose.onNodeWithText("비공개 목록").assertDoesNotExist()
        compose.onNodeWithText("스포일러 잠그기").assertDoesNotExist()
        compose.onNodeWithText("잠긴 라이브러리").assertExists()
        compose.onNodeWithText("공개 목록").assertExists()
    }

    @Test fun sidebarCanBeEnteredFromContentAndMovesBetweenActions() {
        var refreshes = 0
        var chosenLibrary: Int? = null
        compose.setContent {
            val content = remember { FocusRequester() }
            MaterialTheme {
                Row {
                    TvSidebar(onLibraries = {}, onRefresh = { refreshes++ }, onLogout = {},
                        libraries = listOf(Library(1, "드라마", "show"), Library(2, "영화", "movie")),
                        onLibrary = { chosenLibrary = it.id })
                    TvAction("작품", Modifier.focusRequester(content)) {}
                }
            }
            LaunchedEffect(Unit) { content.requestFocus() }
        }
        compose.onNodeWithText("작품").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("홈").assertIsFocused()
        compose.onNodeWithText("홈").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("드라마").assertIsFocused()
        compose.onNodeWithText("드라마").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, chosenLibrary) }
        compose.onNodeWithText("드라마").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("영화").assertIsFocused()
        compose.onNodeWithText("영화").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("새로고침").assertIsFocused()
        compose.onNodeWithText("새로고침").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithText("새로고침").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("작품").assertIsFocused()
    }

    @Test fun directionAndConfirmMoveAndActivateOnlyFocusedAction() {
        var firstClicks = 0
        var secondClicks = 0
        compose.setContent {
            val first = remember { FocusRequester() }
            MaterialTheme {
                Row {
                    TvAction("첫 번째", Modifier.focusRequester(first)) { firstClicks++ }
                    TvAction("두 번째") { secondClicks++ }
                }
            }
            LaunchedEffect(Unit) { first.requestFocus() }
        }
        compose.onNodeWithText("첫 번째").assertIsFocused()
        compose.onNodeWithText("첫 번째").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("두 번째").assertIsFocused()
        compose.onNodeWithText("두 번째").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(0, firstClicks); assertEquals(1, secondClicks) }
    }

    @Test fun largeTabletKeepsMobileInterfaceWhileTelevisionSelectsTvInterface() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun configured(mode: Int) = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or mode
            screenWidthDp = 1280
            smallestScreenWidthDp = 800
        })
        assertFalse(configured(Configuration.UI_MODE_TYPE_NORMAL).isTelevision())
        assertTrue(configured(Configuration.UI_MODE_TYPE_TELEVISION).isTelevision())
    }
}
