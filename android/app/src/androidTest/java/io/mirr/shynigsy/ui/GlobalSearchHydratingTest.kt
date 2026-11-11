package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalSearchHydratingTest {
    @get:Rule val screen = createComposeRule()

    @Test fun publishedResultsCanOpenOrStartANewSearchWhileOnlyHistoryIsLoading() {
        val item = MediaItem(1, 8, "Synthetic movie", "movie", viewItemId = 10)
        val opened = mutableListOf<MediaItem>()
        val searched = mutableListOf<String>()
        val watched = mutableListOf<MediaItem>()
        var hydrating by mutableStateOf(false)
        screen.setContent {
            MaterialTheme {
                GlobalSearchContent("Synthetic", listOf(item), listOf(Library(8, "Synthetic", "movie")),
                    busy = true, error = null, failureCount = 0, spoilerUnlocked = false,
                    onSearch = { searched += it }, onOpen = { opened += it }, onWatchOptions = { watched += it },
                    artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.fillMaxSize(),
                    searchHydrating = hydrating)
            }
        }
        val resultTag = "global-search-result-${compactMediaIdentity(item)}"
        screen.onNodeWithTag(resultTag).assertIsNotEnabled()
        screen.runOnIdle { hydrating = true }
        screen.onNodeWithTag(resultTag).assertIsEnabled().performTouchInput { longClick() }
        screen.runOnIdle { assertTrue(watched.isEmpty()); assertTrue(opened.isEmpty()) }
        screen.onNodeWithTag(resultTag).performClick()
        screen.onNodeWithTag("global-search-input").performTextClearance()
        screen.onNodeWithTag("global-search-input").performTextInput("Other")
        screen.onNodeWithTag("global-search-submit").assertIsEnabled().performClick()
        screen.runOnIdle { assertEquals(listOf(item), opened); assertEquals(listOf("Other"), searched); assertTrue(watched.isEmpty()) }
    }
}
