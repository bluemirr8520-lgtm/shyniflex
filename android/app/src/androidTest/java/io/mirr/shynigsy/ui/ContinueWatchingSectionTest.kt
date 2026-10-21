package io.mirr.shynigsy.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic artwork and callbacks: no account, media URL, or playback Activity is opened. */
@RunWith(AndroidJUnit4::class)
class ContinueWatchingSectionTest {
    @get:Rule val screen = createComposeRule()

    @Test fun heroFillsTheSectionAndEpisodeThumbnailsHaveCaptionsBelowThem() {
        val items = episodes(3)
        render(items)
        val section = screen.onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot
        val hero = screen.onNodeWithTag(HERO).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("The selected work has a full-width hero", section.width, hero.width, 1f)
        assertHero(items.first())
        val metadata = screen.onNodeWithTag(META, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue("Hero metadata includes season and episode", metadata.contains("S2 E1"))
        assertTrue("Hero metadata includes the 40-minute duration", metadata.contains("40"))
        saveScreenshot(HERO, "continue-watching-hero.png")

        val rowBeforeScroll = screen.onNodeWithTag(ROW).fetchSemanticsNode().boundsInRoot
        assertTrue("The poster row belongs below the hero", rowBeforeScroll.top >= hero.bottom - 1f)
        screen.onNodeWithTag(ROW).performScrollTo()
        settle()
        val poster = screen.onNodeWithTag(posterTag(items.first()), useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val episode = screen.onNodeWithTag(episodeTag(items.first()), useUnmergedTree = true)
            .assertTextEquals("S2 E1 · ${items.first().title}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Episode artwork uses a wide thumbnail", 16f / 9f, poster.width / poster.height, .02f)
        screen.onNodeWithTag("continue-thumbnail-slot-${items.first().id}", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("Each episode label is beneath its poster", episode.top >= poster.bottom - 1f)
        saveScreenshot(ROW, "continue-watching-row.png")
    }

    @Test fun aMixedShelfUsesWideArtworkForMoviesAndEpisodes() {
        val movie = MediaItem(90, 7, "이어보던 영화", "movie", durationMs = 2_400_000, resumeMs = 600_000)
        val episode = episodes(1).single()
        render(listOf(movie, episode))
        screen.onNodeWithTag(ROW).performScrollTo()
        val artwork = screen.onNodeWithTag(posterTag(movie), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(16f / 9f, artwork.width / artwork.height, .02f)
        screen.onNodeWithTag("continue-thumbnail-slot-${movie.id}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun aMovieOnlyShelfKeepsPortraitPosters() {
        val movie = MediaItem(90, 7, "이어보던 영화", "movie", durationMs = 2_400_000, resumeMs = 600_000)
        render(listOf(movie))
        screen.onNodeWithTag(ROW).performScrollTo()
        val artwork = screen.onNodeWithTag(posterTag(movie), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(2f / 3f, artwork.width / artwork.height, .02f)
        screen.onNodeWithTag("continue-thumbnail-slot-${movie.id}", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun heroPlayResumesTheFocusedSourceItemAndShowsItsActualRemainingTime() {
        val items = episodes(2)
        var resumed: MediaItem? = null
        val details = mutableStateOf<MediaItem?>(items[1].copy(resumeMs = 1,
            summary = "선택한 회차의 상세 줄거리"))
        render(items, onResume = { resumed = it }, selectedDetails = details)
        screen.onNodeWithTag(ROW).performScrollTo()
        screen.onNodeWithTag(ROW).performScrollToIndex(1)
        screen.onNodeWithTag(itemTag(items[1])).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        settle()
        screen.onNodeWithTag(REMAINING, useUnmergedTree = true).assertTextEquals("30분 남음")
        val range = screen.onNodeWithTag(PROGRESS, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(.25f, range.current, .0001f)
        val pill = screen.onNodeWithTag(RESUME).fetchSemanticsNode().boundsInRoot
        val progressBounds = screen.onNodeWithTag(PROGRESS, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val remainingBounds = screen.onNodeWithTag(REMAINING, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        listOf(progressBounds, remainingBounds).forEach { child ->
            assertTrue("The smart resume progress and remaining time belong inside the play pill",
                child.left >= pill.left && child.right <= pill.right && child.top >= pill.top && child.bottom <= pill.bottom)
        }
        assertTrue("The progress and remaining label are inline", progressBounds.right <= remainingBounds.left)
        screen.onNodeWithTag(RESUME).performScrollTo().performClick()
        screen.runOnIdle { assertEquals("Metadata cannot change the playback source or resume position", items[1], resumed) }
    }

    @Test fun anUnstartedOrUnknownDurationShowsPlayWithoutInventedProgressAndHonorsBusyState() {
        val item = episodes(1).single().copy(durationMs = 0, resumeMs = 0)
        val busy = mutableStateOf(true)
        var resumed: MediaItem? = null
        render(listOf(item), onResume = { resumed = it }, busy = busy)
        screen.onNodeWithTag(REMAINING, useUnmergedTree = true).assertTextEquals("재생")
        screen.onNodeWithTag(PROGRESS, useUnmergedTree = true).assertDoesNotExist()
        screen.onNodeWithTag(RESUME).assertIsNotEnabled()
        screen.onNodeWithTag(ROW).performScrollTo()
        screen.onNodeWithTag(itemTag(item)).assertIsNotEnabled()
        screen.runOnIdle { assertNull(resumed); busy.value = false }
        settle()
        screen.onNodeWithTag(RESUME).performScrollTo().performClick()
        screen.runOnIdle { assertEquals(item, resumed) }
    }

    @Test fun idleTimeDoesNotChangeTheSelectionOrAutomaticallyScrollTheRow() {
        val items = episodes(20)
        var resumes = 0
        render(items, onResume = { resumes++ })
        val before = rowScrollPosition()
        screen.mainClock.advanceTimeBy(30_000)
        screen.waitForIdle()
        assertHero(items.first())
        screen.onNodeWithTag(itemTag(items.first())).assertIsSelected()
        assertEquals("The list never auto-advances", before, rowScrollPosition(), .001f)
        screen.runOnIdle { assertEquals("Idle time must not start playback", 0, resumes) }
    }

    @Test fun posterRowStopsAtBothEndsWithoutWrappingOrVirtualPages() {
        val items = episodes(20)
        render(items)
        screen.onNodeWithTag(ROW).performScrollTo()
        screen.onNodeWithTag(ROW).performScrollToIndex(items.lastIndex)
        settle()
        val last = screen.onNodeWithTag(itemTag(items.last())).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        screen.onNodeWithTag(ROW).performTouchInput { swipeLeft(durationMillis = 300) }
        settle()
        val afterEnd = screen.onNodeWithTag(itemTag(items.last())).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Swiping after the last poster stays at the last poster", last.left, afterEnd.left, 1f)
        val range = screen.onNodeWithTag(ROW).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("Scroll range is bounded by the real media list", range.maxValue() <= items.size + 1f)

        screen.onNodeWithTag(ROW).performScrollToIndex(0)
        settle()
        val first = screen.onNodeWithTag(itemTag(items.first())).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        screen.onNodeWithTag(ROW).performTouchInput { swipeRight(durationMillis = 300) }
        settle()
        val beforeStart = screen.onNodeWithTag(itemTag(items.first())).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Swiping before the first poster stays at the first poster", first.left, beforeStart.left, 1f)
        screen.onAllNodesWithTag(itemTag(items.first())).assertCountEquals(1)
    }

    @Test fun focusingAPosterSelectsItsHeroAndClickingItResumesDirectly() {
        val items = episodes(3)
        var selected: MediaItem? = null
        var resumed: MediaItem? = null
        render(items, onResume = { resumed = it }, onSelected = { selected = it })
        screen.onNodeWithTag(ROW).performScrollTo()
        screen.onNodeWithTag(ROW).performScrollToIndex(1)
        settle()
        screen.onNodeWithTag(itemTag(items[1])).performSemanticsAction(SemanticsActions.RequestFocus) { request ->
            assertTrue("A TV user can focus the poster", request())
        }
        settle()
        assertHero(items[1])
        screen.onNodeWithTag(itemTag(items[1])).assertIsSelected()
        screen.runOnIdle {
            assertEquals("Selection requests metadata for the focused work", items[1], selected)
            assertNull("Focusing artwork does not start playback", resumed)
        }
        screen.onNodeWithTag(itemTag(items[1])).performClick()
        screen.runOnIdle { assertEquals("A poster click resumes directly", items[1], resumed) }
    }

    @Test fun lateDetailsForAnotherWorkCannotReplaceTheFocusedHero() {
        val items = episodes(2)
        val details = mutableStateOf<MediaItem?>(items.first().copy(summary = "첫 작품 상세 줄거리"))
        render(items, selectedDetails = details)
        screen.onNodeWithTag(SUMMARY, useUnmergedTree = true).assertTextEquals("첫 작품 상세 줄거리")
        screen.onNodeWithTag(ROW).performScrollTo()
        screen.onNodeWithTag(itemTag(items[1])).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        settle()
        screen.runOnIdle { details.value = items.first().copy(summary = "뒤늦게 도착한 다른 작품의 정보") }
        settle()
        assertHero(items[1])
        screen.runOnIdle { details.value = items[1].copy(viewItemId = 99_999, summary = "다른 뷰의 상세 정보") }
        settle()
        assertHero(items[1])
        screen.runOnIdle { details.value = items[1].copy(type = "movie", summary = "같은 숫자 ID를 가진 영화 정보") }
        settle()
        assertHero(items[1])
        screen.runOnIdle { details.value = items[1].copy(summary = "선택한 작품의 보강된 상세 줄거리") }
        settle()
        screen.onNodeWithTag(SUMMARY, useUnmergedTree = true).assertTextEquals("선택한 작품의 보강된 상세 줄거리")
        screen.onNodeWithTag(itemTag(items[1])).assertIsSelected()
    }

    @Test fun longPressOpensWatchOptionsWithoutResumingAndOrdinaryTapStillResumes() {
        val items = episodes(2)
        var options: MediaItem? = null
        var resumed: MediaItem? = null
        render(items, onResume = { resumed = it }, onWatchOptions = { options = it })
        screen.onNodeWithTag(ROW).performScrollTo()
        settle()
        screen.onNodeWithTag(itemTag(items.first())).performTouchInput { longClick() }
        settle()
        screen.runOnIdle {
            assertEquals("Long press requests watch-state choices for this exact poster", items.first(), options)
            assertNull("Long press must not trigger the ordinary resume action", resumed)
        }
        screen.onNodeWithTag(itemTag(items.first())).performClick()
        screen.runOnIdle { assertEquals("Ordinary tap still resumes directly", items.first(), resumed) }
    }

    @Test fun emptyListRendersNoContinueWatchingSection() {
        render(emptyList())
        screen.mainClock.advanceTimeBy(30_000)
        screen.waitForIdle()
        screen.onNodeWithTag(SECTION).assertDoesNotExist()
    }

    @Test fun lockedMixedShelfShowsOnlyPublicArtworkAndDetails() {
        val publicItem = episodes(1).single()
        val privateItem = episodes(2).last().copy(isSpoiler = true, summary = "숨겨야 할 비공개 줄거리")
        val details = mutableStateOf<MediaItem?>(privateItem)
        render(listOf(privateItem, publicItem), selectedDetails = details)
        assertHero(publicItem)
        screen.onNodeWithTag(itemTag(privateItem)).assertDoesNotExist()
        screen.onNodeWithTag(ROW).performScrollTo()
        screen.onNodeWithTag(itemTag(publicItem)).assertIsDisplayed().assertIsSelected()
    }

    @Test fun relockingFocusedPrivatePosterImmediatelyDropsItsArtworkAndStaleHero() {
        val publicItem = episodes(1).single()
        val privateItem = episodes(2).last().copy(isSpoiler = true, summary = "선택한 비공개 작품의 줄거리")
        val unlocked = mutableStateOf(true)
        val details = mutableStateOf<MediaItem?>(privateItem)
        render(listOf(publicItem, privateItem), selectedDetails = details, spoilerUnlocked = unlocked)
        screen.onNodeWithTag(ROW).performScrollTo()
        screen.onNodeWithTag(itemTag(privateItem)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        settle()
        assertHero(privateItem)

        screen.runOnIdle { unlocked.value = false }
        settle()
        screen.onNodeWithTag(itemTag(privateItem)).assertDoesNotExist()
        assertHero(publicItem)
        screen.onNodeWithTag(itemTag(publicItem)).assertIsSelected()
        // Leave the old response in the input to model a late response after revocation.
        screen.runOnIdle { details.value = privateItem.copy(summary = "잠긴 뒤 도착한 비공개 응답") }
        settle()
        assertHero(publicItem)
    }

    @Test fun aPrivateOnlyShelfDisappearsOnLockAndCanReappearAfterUnlock() {
        val privateItem = episodes(1).single().copy(isSpoiler = true)
        val unlocked = mutableStateOf(true)
        var resumed: MediaItem? = null
        render(listOf(privateItem), onResume = { resumed = it }, spoilerUnlocked = unlocked)
        assertHero(privateItem)
        screen.runOnIdle { unlocked.value = false }
        settle()
        screen.onNodeWithTag(SECTION).assertDoesNotExist()
        screen.runOnIdle { assertNull("Revocation cannot trigger resume", resumed) }

        screen.runOnIdle { unlocked.value = true }
        settle()
        assertHero(privateItem)
        screen.onNodeWithTag(ROW).performScrollTo()
        screen.onNodeWithTag(itemTag(privateItem)).performClick()
        screen.runOnIdle { assertEquals(privateItem, resumed) }
    }

    @Test fun privateDetailsCannotEnrichAPublicHeroWithTheSameNumericIdentity() {
        val publicItem = episodes(1).single()
        val details = mutableStateOf<MediaItem?>(publicItem.copy(isSpoiler = true,
            showTitle = "비공개 제목", summary = "비공개 줄거리"))
        val unlocked = mutableStateOf(false)
        render(listOf(publicItem), selectedDetails = details, spoilerUnlocked = unlocked)
        assertHero(publicItem)
        screen.runOnIdle { unlocked.value = true }
        settle()
        assertHero(publicItem)
        screen.runOnIdle { details.value = publicItem.copy(summary = "공개 상세 줄거리") }
        settle()
        screen.onNodeWithTag(SUMMARY, useUnmergedTree = true).assertTextEquals("공개 상세 줄거리")
    }

    @Test fun aSinglePosterRemainsSelectedAndStillResumesDirectly() {
        val item = episodes(1).single()
        var resumed: MediaItem? = null
        render(listOf(item), onResume = { resumed = it })
        screen.mainClock.advanceTimeBy(30_000)
        screen.waitForIdle()
        assertHero(item)
        screen.onNodeWithTag(ROW).performScrollTo()
        settle()
        screen.onAllNodesWithTag(itemTag(item)).assertCountEquals(1)
        screen.onNodeWithTag(itemTag(item)).assertIsSelected().performClick()
        screen.runOnIdle { assertEquals(item, resumed) }
    }

    private fun render(
        items: List<MediaItem>,
        onResume: (MediaItem) -> Unit = {},
        onSelected: (MediaItem) -> Unit = {},
        selectedDetails: State<MediaItem?>? = null,
        onWatchOptions: (MediaItem) -> Unit = {},
        spoilerUnlocked: State<Boolean>? = null,
        busy: State<Boolean>? = null,
    ) {
        screen.mainClock.autoAdvance = false
        screen.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF83E3C5), background = Color(0xFF0B1220),
                surface = Color(0xFF0B1220), surfaceContainer = Color(0xFF142033), onSurface = Color(0xFFF0F4FA),
                onSurfaceVariant = Color(0xFFA6B6CB))) {
                Column(Modifier.fillMaxSize().background(Color(0xFF0B1220)).verticalScroll(rememberScrollState())) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(HOST)) {
                        ContinueWatchingSection(items = items, busy = busy?.value ?: false, onResume = onResume,
                            poster = { item, modifier -> SyntheticArtwork(item, modifier, backdrop = false) },
                            backdrop = { item, modifier -> SyntheticArtwork(item, modifier, backdrop = true) },
                            thumbnail = { item, modifier -> Box(modifier) {
                                SyntheticArtwork(item, Modifier.fillMaxSize().testTag("continue-thumbnail-slot-${item.id}"), backdrop = false)
                            } },
                            onSelected = onSelected, selectedDetails = selectedDetails?.value, onWatchOptions = onWatchOptions,
                            spoilerUnlocked = spoilerUnlocked?.value ?: false)
                    }
                }
            }
        }
        settle()
    }

    private fun assertHero(item: MediaItem) {
        screen.onNodeWithTag(TITLE, useUnmergedTree = true).assertTextEquals(item.showTitle ?: item.title)
        screen.onNodeWithTag(SUMMARY, useUnmergedTree = true).assertTextEquals(item.summary)
    }

    private fun rowScrollPosition() = screen.onNodeWithTag(ROW).fetchSemanticsNode()
        .config[SemanticsProperties.HorizontalScrollAxisRange].value()

    private fun settle() {
        screen.mainClock.advanceTimeBy(600)
        screen.waitForIdle()
    }

    private fun saveScreenshot(tag: String, filename: String) {
        val bitmap = screen.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(directory, filename).outputStream().use {
            assertTrue("The synthetic layout screenshot must be saved", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Composable private fun SyntheticArtwork(item: MediaItem, modifier: Modifier, backdrop: Boolean) {
        val color = if (item.id % 2 == 0) Color(0xFF2C5364) else Color(0xFF8A4A39)
        Box(modifier.background(if (backdrop) color.copy(alpha = .75f) else color))
    }

    private fun episodes(count: Int) = (1..count).map { number ->
        MediaItem(id = number, libraryId = 7, title = "에피소드 제목 $number", type = "episode",
            showTitle = "테스트 시리즈 $number", summary = "선택한 작품 $number 의 테스트 줄거리입니다.",
            seasonNumber = 2, episodeNumber = number, viewItemId = 1_000 + number,
            durationMs = 2_400_000, resumeMs = 600_000)
    }

    private fun itemTag(item: MediaItem) = "continue-item-${item.id}"
    private fun episodeTag(item: MediaItem) = "continue-episode-${item.id}"
    private fun posterTag(item: MediaItem) = "continue-poster-${item.id}"

    private companion object {
        const val HOST = "continue-test-host"
        const val SECTION = "continue-watching-section"
        const val HERO = "continue-watching-hero"
        const val ROW = "continue-watching-row"
        const val TITLE = "continue-hero-title"
        const val META = "continue-hero-meta"
        const val SUMMARY = "continue-hero-summary"
        const val RESUME = "continue-hero-resume"
        const val PROGRESS = "continue-hero-progress"
        const val REMAINING = "continue-hero-remaining"
    }
}
