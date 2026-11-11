package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobilePullToRefreshTest {
    @get:Rule val screen = createComposeRule()

    @Test fun pullAtTopRefreshesOnceWhileBusy() {
        val busy = mutableStateOf(false)
        var count = 0
        screen.setContent {
            MaterialTheme {
                MobilePullToRefresh("home", busy.value, onRefresh = { count++; busy.value = true }) {
                    LazyColumn(Modifier.testTag("list")) {
                        items((0..30).toList()) { Text("항목 $it", Modifier.fillMaxWidth().height(96.dp)) }
                    }
                }
            }
        }

        screen.onNodeWithTag("list").performTouchInput { swipeDown(durationMillis = 500) }
        screen.waitForIdle()
        screen.runOnIdle { assertEquals(1, count) }
        screen.onNodeWithTag("list").performTouchInput { swipeDown(durationMillis = 500) }
        screen.waitForIdle()
        screen.runOnIdle { assertEquals(1, count) }
    }

    @Test fun pullInsideScrolledListScrollsBeforeRefreshing() {
        var count = 0
        screen.setContent {
            MaterialTheme {
                MobilePullToRefresh("library", busy = false, onRefresh = { count++ }) {
                    LazyColumn(Modifier.testTag("list")) {
                        items((0..50).toList()) { Text("항목 $it", Modifier.fillMaxWidth().height(96.dp)) }
                    }
                }
            }
        }

        screen.onNodeWithTag("list").performScrollToIndex(20)
        screen.onNodeWithTag("list").performTouchInput { swipeDown(durationMillis = 500) }
        screen.waitForIdle()
        screen.runOnIdle { assertEquals(0, count) }
    }
}
