package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.LibraryContentType
import io.mirr.shynigsy.data.LibrarySort
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlexLibraryToolbarTest {
    @get:Rule val screen = createComposeRule()
    private lateinit var inputMode: InputModeManager

    @Test fun movieLibraryKeepsDisplayAndSortControlsWithoutLibraryFilters() {
        val state = Fixture()
        render(state)
        screen.onNodeWithTag("library-filter").assertDoesNotExist()
        screen.onNodeWithTag("library-clear-filters").assertDoesNotExist()
        screen.onNodeWithTag("library-type").assertTextEquals("영화 ▾").performClick()
        screen.onNodeWithTag("library-option-MOVIES").assertIsSelected().performClick()
        screen.onNodeWithText("서버가 제공한 표시 종류 정보가 없습니다.").assertDoesNotExist()
        screen.onNodeWithTag("library-sort").assertTextEquals("제목 ▾")
        screen.onNodeWithTag("library-sort-direction").assertExists()
        screen.runOnIdle { assertTrue("Choosing the current type must not reload the catalog", state.changes.isEmpty()) }
    }

    @Test fun tvShowSeasonAndEpisodeDisplayTypesRemainSelectable() {
        val state = Fixture().apply {
            library = Library(9, "합성 시리즈", "tv")
            type = LibraryContentType.SHOWS
        }
        render(state)
        screen.onNodeWithTag("library-type").performClick()
        screen.onNodeWithTag("library-option-SHOWS").assertIsSelected()
        screen.onNodeWithTag("library-option-SEASONS").assertExists()
        screen.onNodeWithTag("library-option-EPISODES").performClick()
        screen.onNodeWithTag("library-type").assertTextEquals("에피소드 ▾")
        screen.runOnIdle { assertEquals(listOf("type:EPISODES"), state.changes) }
    }

    @Test fun sortCriteriaAndDirectionEmitOneExactSelectionEach() {
        val state = Fixture()
        render(state)
        screen.onNodeWithTag("library-sort").performClick()
        screen.onNodeWithTag("library-option-TITLE").assertIsSelected()
        screen.onNodeWithTag("library-option-ADDED").assertExists()
        screen.onNodeWithTag("library-option-YEAR").assertExists()
        screen.onNodeWithTag("library-option-DURATION").performClick()
        screen.onNodeWithTag("library-sort").assertTextEquals("재생시간 ▾")
        screen.onNodeWithTag("library-sort-direction").performClick()
        screen.runOnIdle { assertEquals(listOf("sort:DURATION_ASC", "sort:DURATION_DESC"), state.changes) }
        screen.onNodeWithTag("library-sort").performClick()
        screen.onNodeWithTag("library-option-DURATION").performClick()
        screen.runOnIdle {
            assertEquals("Choosing the same field retains its direction", LibrarySort.DURATION_DESC, state.sort)
            assertEquals(2, state.changes.size)
        }
        screen.onNodeWithTag("library-sort").performClick()
        screen.onNodeWithTag("library-option-ADDED").performClick()
        screen.onNodeWithTag("library-sort-direction").performClick()
        screen.runOnIdle {
            assertEquals(LibrarySort.RECENT_ASC, state.sort)
            assertEquals(listOf("sort:DURATION_ASC", "sort:DURATION_DESC", "sort:RECENT", "sort:RECENT_ASC"), state.changes)
        }
    }

    @Test fun loadingDisablesOpenMenusAndDisplayAndSortActions() {
        val state = Fixture()
        render(state)
        screen.onNodeWithTag("library-type").performClick()
        screen.runOnIdle { state.busy = true }
        screen.onNodeWithTag("library-option-MOVIES").assertIsNotEnabled()
        screen.onNodeWithTag("library-options-close").performClick()
        for (tag in listOf("library-type", "library-sort", "library-sort-direction")) screen.onNodeWithTag(tag).assertIsNotEnabled()
        screen.runOnIdle { assertTrue(state.changes.isEmpty()) }
    }

    @Test fun tvDialogFocusAndLibraryOrPrivacyChangesCloseOpenMenus() {
        val state = Fixture()
        render(state)
        screen.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        screen.onNodeWithTag("library-type").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        screen.onNodeWithTag("library-type").performClick()
        screen.onNodeWithTag("library-option-MOVIES").assertIsFocused()
        screen.runOnIdle { state.library = Library(10, "다른 합성 영화", "movie") }
        screen.onNodeWithTag("library-options").assertDoesNotExist()
        screen.runOnIdle { state.library = state.library.copy(isSpoiler = true); state.unlocked = true }
        screen.onNodeWithTag("library-sort").performClick()
        screen.onNodeWithTag("library-option-TITLE").assertExists()
        screen.runOnIdle { state.unlocked = false }
        screen.onNodeWithTag("library-options").assertDoesNotExist()
        screen.onNodeWithTag("library-toolbar").assertDoesNotExist()
        screen.runOnIdle { assertTrue(state.changes.isEmpty()) }
    }

    @Suppress("DEPRECATION")
    private fun render(state: Fixture) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        screen.mainClock.autoAdvance = true
        screen.setContent {
            inputMode = LocalInputModeManager.current
            MaterialTheme { Surface {
                PlexLibraryToolbar(state.library, state.sort, state.type, state.busy, state.unlocked,
                    onSort = { state.changes.add("sort:$it"); state.sort = it },
                    onContentType = { state.changes.add("type:$it"); state.type = it },
                    modifier = Modifier.fillMaxWidth())
            } }
        }
        screen.waitForIdle()
    }

    private class Fixture {
        var library by mutableStateOf(Library(8, "합성 영화", "movie"))
        var sort by mutableStateOf(LibrarySort.TITLE_ASC)
        var type by mutableStateOf(LibraryContentType.MOVIES)
        var busy by mutableStateOf(false)
        var unlocked by mutableStateOf(false)
        val changes = mutableListOf<String>()
    }
}
