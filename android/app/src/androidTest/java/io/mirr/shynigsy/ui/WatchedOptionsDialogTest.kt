package io.mirr.shynigsy.ui

import androidx.compose.material3.MaterialTheme
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
        screen.setContent {
            MaterialTheme { WatchedOptionsDialog(MediaItem(1, 7, "영화", "movie"), onDismiss = {}, onSetWatched = { watched = it }) }
        }
        screen.onNodeWithTag("watched-mark-seen").assertTextEquals("보았음")
        screen.onNodeWithTag("watched-mark-unseen").assertTextEquals("보지 않음")
        screen.onNodeWithTag("watched-cancel").assertTextEquals("취소")
        screen.onNodeWithText("영화").assertDoesNotExist()
        screen.runOnIdle { assertNull(watched) }
        screen.onNodeWithTag("watched-mark-seen").performClick()
        screen.runOnIdle { assertEquals(true, watched) }
    }

    @Test fun aSeriesUsesTheSameCompactLabelsAndPreservesItsWatchStateChoice() {
        var watched: Boolean? = null
        screen.setContent {
            MaterialTheme { WatchedOptionsDialog(MediaItem(2, 7, "시리즈", "show"), onDismiss = {}, onSetWatched = { watched = it }) }
        }
        screen.onNodeWithTag("watched-mark-seen").assertTextEquals("보았음")
        screen.onNodeWithTag("watched-cancel").assertTextEquals("취소")
        screen.onNodeWithText("시리즈").assertDoesNotExist()
        screen.onNodeWithTag("watched-mark-unseen").assertTextEquals("보지 않음").performClick()
        screen.runOnIdle { assertEquals(false, watched) }
    }
}
