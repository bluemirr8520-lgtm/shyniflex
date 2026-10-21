package io.mirr.shynigsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mirr.shynigsy.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise Home's real status/carousel branch without an account or a ViewModel network request. */
@RunWith(AndroidJUnit4::class)
class ContinueWatchingUiTest {
    @get:Rule val screen = createComposeRule()
    private val publicItem = MediaItem(1, 1, "공개 영상", "movie", summary = "공개 소개", durationMs = 6_000, resumeMs = 2_000)
    private val privateItem = MediaItem(2, 2, "비공개 작품 제목", "episode", summary = "비공개 상세 소개", isSpoiler = true)

    @Test fun firstLoadKeepsContinueHeadingAndShowsLoadingInsteadOfHidingTheWholeSection() {
        render(loading = true)
        screen.onNodeWithTag(STATUS_TITLE).assertTextEquals("이어보기").assertIsDisplayed()
        screen.onNodeWithTag(LOADING).assertIsDisplayed()
        screen.onNodeWithTag(EMPTY).assertDoesNotExist()
        screen.onNodeWithTag(RETRY).assertDoesNotExist()
    }

    @Test fun successfulEmptyHistoryKeepsAnExplicitContinueMessage() {
        render()
        screen.onNodeWithTag(STATUS_TITLE).assertIsDisplayed()
        screen.onNodeWithTag(EMPTY).assertTextEquals("이어볼 영상이 없습니다. 시청 중인 영상이 여기에 표시됩니다.").assertIsDisplayed()
        screen.onNodeWithTag(LOADING).assertDoesNotExist()
        screen.onNodeWithTag(ERROR).assertDoesNotExist()
    }

    @Test fun failedFirstLoadOffersRetryWithoutRenderingRawServerDetails() {
        var retries = 0
        render(error = "비공개 작품 제목: https://private.invalid/secret", onRetry = { retries++ })
        screen.onNodeWithTag(ERROR).assertTextEquals("이어보기를 불러오지 못했습니다. 새로고침해 주세요.")
        screen.onNodeWithText("비공개 작품 제목: https://private.invalid/secret").assertDoesNotExist()
        screen.onNodeWithTag(RETRY).performClick()
        screen.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun retryRemainsDisabledWhileAnotherRequestIsBusy() {
        val busy = mutableStateOf(true)
        var retries = 0
        render(error = "failed", busy = busy, onRetry = { retries++ })
        screen.onNodeWithTag(RETRY).assertIsNotEnabled()
        screen.runOnIdle { assertEquals(0, retries); busy.value = false }
        screen.onNodeWithTag(RETRY).performClick()
        screen.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun existingPublicRowsKeepTheRealCarouselWhenRefreshIsPendingOrFailed() {
        var resumed: MediaItem? = null
        render(items = listOf(publicItem), loading = true, error = "refresh failed", onResume = { resumed = it })
        screen.onNodeWithTag("continue-hero-title", useUnmergedTree = true).assertTextEquals(publicItem.title)
        screen.onNodeWithTag(STATUS_TITLE).assertDoesNotExist()
        screen.onNodeWithTag("continue-hero-resume").performScrollTo().performClick()
        screen.runOnIdle { assertEquals(publicItem, resumed) }
    }

    @Test fun lockingPrivateOnlyHistoryReplacesItsHeroAndPosterWithSafeStatusImmediately() {
        val unlocked = mutableStateOf(true)
        render(items = listOf(privateItem), error = "비공개 작품 제목: private request failed", unlocked = unlocked,
            selectedDetails = privateItem)
        screen.onNodeWithTag("continue-hero-title", useUnmergedTree = true).assertTextEquals(privateItem.title)
        screen.runOnIdle { unlocked.value = false }
        screen.onNodeWithTag("continue-watching-section").assertDoesNotExist()
        screen.onNodeWithTag("continue-item-${privateItem.id}").assertDoesNotExist()
        screen.onNodeWithText(privateItem.title).assertDoesNotExist()
        screen.onNodeWithText(privateItem.summary).assertDoesNotExist()
        screen.onNodeWithTag(STATUS_TITLE).assertTextEquals("이어보기")
        screen.onNodeWithTag(ERROR).assertTextEquals("이어보기를 불러오지 못했습니다. 새로고침해 주세요.")
    }

    private fun render(
        items: List<MediaItem> = emptyList(), loading: Boolean = false, error: String? = null,
        busy: State<Boolean>? = null, unlocked: State<Boolean>? = null, selectedDetails: MediaItem? = null,
        onResume: (MediaItem) -> Unit = {}, onRetry: () -> Unit = {},
    ) {
        screen.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    HomeContinueWatchingSection(items = items, loading = loading, error = error,
                        busy = busy?.value ?: false, spoilerUnlocked = unlocked?.value ?: false,
                        onResume = onResume, onRetry = onRetry, selectedDetails = selectedDetails,
                        poster = { _, modifier -> Box(modifier.background(Color.DarkGray)) })
                }
            }
        }
        screen.waitForIdle()
    }

    private companion object {
        const val STATUS_TITLE = "continue-watching-status-title"
        const val LOADING = "continue-watching-loading"
        const val EMPTY = "continue-watching-empty"
        const val ERROR = "continue-watching-error"
        const val RETRY = "continue-watching-retry"
    }
}
