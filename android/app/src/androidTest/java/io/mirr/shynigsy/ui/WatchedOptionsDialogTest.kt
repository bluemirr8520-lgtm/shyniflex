package io.mirr.shynigsy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mirr.shynigsy.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchedOptionsDialogTest {
    @get:Rule val screen = createComposeRule()

    @Test fun aMovieOffersItsOwnWatchStateAndOnlyActsAfterAChoice() {
        var watched: Boolean? = null
        var writes = 0
        screen.setContent {
            MaterialTheme { WatchedOptionsDialog(MediaItem(1, 7, "영화", "movie"), onDismiss = {},
                onSetWatched = { watched = it; writes++ }) }
        }
        screen.onNodeWithTag("watched-mark-seen").assertTextEquals("보았음").assertIsNotSelected().assertIsEnabled()
        screen.onNodeWithTag("watched-mark-unseen").assertTextEquals("보지 않음").assertIsSelected().assertIsEnabled()
        screen.onNodeWithTag("watched-unseen-check", useUnmergedTree = true).assertExists()
        screen.onNodeWithTag("watched-seen-check", useUnmergedTree = true).assertDoesNotExist()
        screen.onNodeWithTag("watched-cancel").assertTextEquals("취소")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        screen.onNodeWithText("영화").assertDoesNotExist()
        screen.runOnIdle { assertNull(watched); assertEquals(0, writes) }
        screen.onNodeWithTag("watched-mark-seen").performClick()
        screen.runOnIdle { assertEquals(true, watched); assertEquals(1, writes) }
    }

    @Test fun aSeriesUsesTheSameCompactLabelsAndPreservesItsWatchStateChoice() {
        var watched: Boolean? = null
        screen.setContent {
            MaterialTheme { WatchedOptionsDialog(MediaItem(2, 7, "시리즈", "show", watched = true),
                onDismiss = {}, onSetWatched = { watched = it }) }
        }
        screen.onNodeWithTag("watched-mark-seen").assertTextEquals("보았음").assertIsSelected().assertIsEnabled()
        screen.onNodeWithTag("watched-mark-unseen").assertTextEquals("보지 않음").assertIsNotSelected().assertIsEnabled()
        screen.onNodeWithTag("watched-seen-check", useUnmergedTree = true).assertExists()
        screen.onNodeWithTag("watched-unseen-check", useUnmergedTree = true).assertDoesNotExist()
        screen.onNodeWithTag("watched-cancel").assertTextEquals("취소")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        screen.onNodeWithText("시리즈").assertDoesNotExist()
        screen.runOnIdle { assertNull(watched) }
        screen.onNodeWithTag("watched-mark-unseen").assertTextEquals("보지 않음").performClick()
        screen.runOnIdle { assertEquals(false, watched) }
    }

    @Test fun selectingTheCurrentStateStillWritesButCancelDoesNot() {
        val writes = mutableListOf<Boolean>()
        var dismissals = 0
        screen.setContent {
            MaterialTheme { WatchedOptionsDialog(MediaItem(3, 7, "확인한 영화", "movie", watched = true),
                onDismiss = { dismissals++ }, onSetWatched = { writes += it }) }
        }
        screen.runOnIdle { assertEquals(emptyList<Boolean>(), writes); assertEquals(0, dismissals) }
        screen.onNodeWithTag("watched-mark-seen").assertIsSelected().assertIsEnabled().performClick()
        screen.runOnIdle { assertEquals(listOf(true), writes); assertEquals(0, dismissals) }
        screen.onNodeWithTag("watched-cancel")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)).performClick()
        screen.runOnIdle { assertEquals(listOf(true), writes); assertEquals(1, dismissals) }
    }
}
