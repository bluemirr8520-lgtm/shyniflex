package io.mirr.shynigsy.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalSearchScreenTest {
    @get:Rule val screen = createComposeRule()
    private lateinit var inputMode: InputModeManager

    @Test fun typingDoesNotSearchAndOnlyExplicitNonblankIdleSubmissionRuns() {
        val state = Fixture()
        render(state)
        screen.onNodeWithTag("global-search-submit").assertIsNotEnabled()
        screen.onNodeWithTag("global-search-input").performTextInput("  별  ")
        screen.runOnIdle { assertTrue(state.queries.isEmpty()); state.busy = true }
        screen.onNodeWithTag("global-search-submit").assertIsNotEnabled()
        screen.onNodeWithTag("global-search-input").performImeAction()
        screen.runOnIdle { assertTrue(state.queries.isEmpty()); state.busy = false }
        screen.onNodeWithTag("global-search-input").performImeAction()
        screen.runOnIdle { assertEquals(listOf("별"), state.queries) }
    }

    @Test fun resultsOpenTheirExactSourceAndOnlyAuthorizedMatchingViewsRemainVisible() {
        val movie = media(1)
        val show = media(2, type = "show")
        val privateMovie = media(3, libraryId = 9, privateItem = true)
        val unknownView = media(4, libraryId = 99)
        val wrongPrivacy = media(5, libraryId = 9)
        val state = Fixture().apply { query = "합성"; items = listOf(movie, show, privateMovie, unknownView, wrongPrivacy) }
        render(state)
        screen.onNodeWithTag(libraryTag(movie)).assertTextEquals("합성 공개")
        screen.onNodeWithTag(resultTag(movie)).performClick()
        screen.onNodeWithTag(resultTag(show)).performTouchInput { longClick() }
        screen.runOnIdle { assertEquals(listOf(movie), state.opened); assertEquals(listOf(show), state.watchOptions) }
        for (item in listOf(privateMovie, unknownView, wrongPrivacy)) screen.onNodeWithTag(resultTag(item)).assertDoesNotExist()
        screen.runOnIdle { state.unlocked = true }
        screen.onNodeWithTag(resultTag(privateMovie)).assertExists()
        screen.onNodeWithTag(libraryTag(privateMovie)).assertTextEquals("합성 비공개")
        screen.onNodeWithTag(resultTag(wrongPrivacy)).assertDoesNotExist()
        screen.runOnIdle { state.unlocked = false }
        screen.onNodeWithTag(resultTag(privateMovie)).assertDoesNotExist()
        screen.onNodeWithTag(libraryTag(privateMovie)).assertDoesNotExist()
        screen.onNodeWithTag(resultTag(movie)).assertExists()
    }

    @Test fun loadingErrorPartialEmptyAndSuccessfulStatesStayDistinct() {
        val state = Fixture()
        render(state)
        screen.onNodeWithTag("global-search-status").assertTextEquals("제목을 입력해 검색하세요")
        screen.runOnIdle { state.query = "합성"; state.busy = true }
        screen.onNodeWithTag("global-search-status").assertTextEquals("검색 중입니다")
        screen.runOnIdle { state.busy = false; state.error = "검색을 완료하지 못했습니다" }
        screen.onNodeWithTag("global-search-error").assertTextEquals("검색을 완료하지 못했습니다")
        screen.runOnIdle { state.error = null }
        screen.onNodeWithTag("global-search-status").assertTextEquals("검색 결과가 없습니다")
        screen.runOnIdle { state.failures = 1 }
        screen.onNodeWithTag("global-search-status").assertTextEquals("검색을 완료하지 못했습니다")
        screen.onNodeWithTag("global-search-partial").assertDoesNotExist()
        screen.runOnIdle { state.items = listOf(media(1)) }
        screen.onNodeWithTag("global-search-partial").assertExists()
        screen.runOnIdle { state.failures = 0 }
        screen.onNodeWithTag("global-search-status").assertTextEquals("1개")
        screen.onNodeWithTag("global-search-partial").assertDoesNotExist()
    }

    @Test fun televisionDpadMovesBetweenResultsAndOpensOnlyTheFocusedItem() {
        val first = media(1)
        val second = media(2)
        val state = Fixture().apply { query = "합성"; items = listOf(first, second) }
        render(state, television = true)
        screen.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        screen.onNodeWithTag(resultTag(first)).performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        val firstBounds = screen.onNodeWithTag(resultTag(first)).fetchSemanticsNode().boundsInRoot
        val secondBounds = screen.onNodeWithTag(resultTag(second)).fetchSemanticsNode().boundsInRoot
        val direction = if (secondBounds.top > firstBounds.top) Key.DirectionDown else Key.DirectionRight
        screen.onNodeWithTag(resultTag(first)).performKeyInput { pressKey(direction) }
        screen.onNodeWithTag(resultTag(second)).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        screen.runOnIdle { assertEquals(listOf(second), state.opened); assertTrue(state.watchOptions.isEmpty()) }
    }

    @Suppress("DEPRECATION")
    private fun render(state: Fixture, television: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(true)
        val base = instrumentation.targetContext
        val context = if (television) base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }) else base
        screen.mainClock.autoAdvance = true
        screen.setContent {
            inputMode = LocalInputModeManager.current
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { Surface(Modifier.fillMaxSize()) {
                    GlobalSearchContent(state.query, state.items, state.libraries, state.busy, state.error,
                        state.failures, state.unlocked, { state.queries.add(it) }, { state.opened.add(it) }, { state.watchOptions.add(it) },
                        artwork = { _, modifier -> Box(modifier.background(Color.DarkGray)) })
                } }
            }
        }
        screen.waitForIdle()
    }

    private fun media(id: Int, libraryId: Int = 8, privateItem: Boolean = false, type: String = "movie") =
        MediaItem(id, libraryId, "합성 작품 $id", type, isSpoiler = privateItem)
    private fun resultTag(item: MediaItem) = "global-search-result-${compactMediaIdentity(item)}"
    private fun libraryTag(item: MediaItem) = "global-search-library-${compactMediaIdentity(item)}"

    private class Fixture {
        var query by mutableStateOf("")
        var items by mutableStateOf<List<MediaItem>>(emptyList())
        val libraries = listOf(Library(8, "합성 공개", "movie"), Library(9, "합성 비공개", "movie", true))
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var failures by mutableStateOf(0)
        var unlocked by mutableStateOf(false)
        val queries = mutableListOf<String>()
        val opened = mutableListOf<MediaItem>()
        val watchOptions = mutableListOf<MediaItem>()
    }
}
