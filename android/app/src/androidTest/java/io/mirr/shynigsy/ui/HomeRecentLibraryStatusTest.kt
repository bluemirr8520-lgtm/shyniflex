package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mirr.shynigsy.data.RecentLibraryStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeRecentLibraryStatusTest {
    @get:Rule val screen = createComposeRule()

    @Test fun pendingEmptyAndFailedResponsesKeepTheirLibraryHeading() {
        val status = mutableStateOf(RecentLibraryStatus.LOADING)
        var retries = 0
        screen.setContent {
            MaterialTheme { HomeRecentLibraryStatus("테스트 라이브러리", status.value, { retries++ }) }
        }
        screen.onNodeWithText("최근 추가된 작품 · 테스트 라이브러리").assertIsDisplayed()
        screen.onNodeWithText("불러오는 중입니다").assertIsDisplayed()
        screen.onNodeWithTag("home-recent-retry").assertDoesNotExist()
        screen.runOnIdle { status.value = RecentLibraryStatus.EMPTY }
        screen.onNodeWithText("최근 추가된 작품이 없습니다").assertIsDisplayed()
        screen.onNodeWithTag("home-recent-retry").assertDoesNotExist()
        screen.runOnIdle { status.value = RecentLibraryStatus.ERROR }
        screen.onNodeWithText("목록을 불러오지 못했습니다").assertIsDisplayed()
        screen.onNodeWithTag("home-recent-retry").performClick()
        screen.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun incompleteVisibleDataDoesNotClaimTheLibraryIsEmpty() {
        screen.setContent {
            MaterialTheme { HomeRecentLibraryStatus("테스트 라이브러리", RecentLibraryStatus.READY, {}) }
        }
        screen.onNodeWithText("최근 추가된 작품이 없습니다").assertDoesNotExist()
        screen.onNodeWithTag("home-recent-retry").assertIsDisplayed()
    }

    @Test fun tvCanReachAllSixteenEmptyLibraryHeadingsUsingTheRemote() {
        screen.setContent {
            MaterialTheme {
                LazyColumn(Modifier.height(220.dp)) {
                    items(16) { index ->
                        HomeRecentLibraryStatus("라이브러리 $index", RecentLibraryStatus.EMPTY, {},
                            Modifier.testTag("library-status-$index"), television = true)
                    }
                }
            }
        }
        screen.onNodeWithTag("library-status-0")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        repeat(15) { index ->
            screen.onNodeWithTag("library-status-$index").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            screen.onNodeWithTag("library-status-${index + 1}").assertIsFocused().assertIsDisplayed()
        }
    }
}
