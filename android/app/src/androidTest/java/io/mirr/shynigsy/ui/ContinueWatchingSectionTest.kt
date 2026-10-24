package io.mirr.shynigsy.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
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
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic artwork and callbacks: no account, media URL, or playback Activity is opened. */
@RunWith(AndroidJUnit4::class)
class ContinueWatchingSectionTest {
    @get:Rule val screen = createComposeRule()
    private lateinit var inputModeManager: InputModeManager

    @Before fun requireIsolatedSyntheticApplication() {
        assertEquals("Synthetic UI verification must use the isolated application",
            "io.mirr.shyniflex.spoilerqa", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

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
        // Keep the existing artifact name, capturing the backdrop through the thumbnail row.
        saveScreenshot(SECTION, "continue-watching-hero.png")

        val rowBeforeScroll = screen.onNodeWithTag(ROW).fetchSemanticsNode().boundsInRoot
        assertTrue("The poster row belongs below the hero", rowBeforeScroll.top >= hero.bottom - 1f)
        val backdropSection = screen.onNodeWithTag(SECTION).fetchSemanticsNode().boundsInRoot
        assertTrue("The painted section includes the hero and thumbnail row",
            backdropSection.bottom > hero.bottom && backdropSection.bottom >= rowBeforeScroll.bottom - 1f)
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
        focusPoster(items[1])
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
        screen.onNodeWithTag(RESUME).performScrollTo()
        settle()
        assertHero(details.value!!)
        screen.onNodeWithTag(RESUME).performSemanticsAction(SemanticsActions.RequestFocus) { request ->
            assertTrue("The resume button can retain a remote user's current target", request())
        }
        settle()
        advanceRotation()
        advanceRotation()
        assertHero(details.value!!)
        screen.onNodeWithTag(RESUME).assertIsFocused()
        screen.onNodeWithTag(RESUME).performClick()
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

    @Test fun idleBackdropLoopsWithoutScrollingRequestingMetadataOrStartingPlayback() {
        val items = episodes(3)
        var resumes = 0
        var resumed: MediaItem? = null
        val selections = mutableListOf<MediaItem>()
        render(items, onResume = { resumes++; resumed = it }, onSelected = { selections.add(it) })
        val before = rowScrollPosition()
        val manualRequests = screen.runOnIdle { selections.size }
        listOf(items[1], items[2], items[0], items[1]).forEach { expected ->
            advanceRotation()
            assertHero(expected)
            screen.onNodeWithTag(backdropTag(expected), useUnmergedTree = true).assertIsDisplayed()
            assertEquals("Only the backdrop changes; the finite shelf stays put", before, rowScrollPosition(), .001f)
            screen.runOnIdle {
                assertEquals("An automatic slide does not fetch descriptive metadata", manualRequests, selections.size)
                assertEquals("An automatic slide never starts playback", 0, resumes)
            }
        }
        screen.onNodeWithTag(RESUME).performClick()
        screen.runOnIdle {
            assertEquals(1, resumes)
            assertEquals("The CTA uses the exact source currently shown in the hero", items[1], resumed)
        }
    }

    @Test fun heroSwipesChangeArtworkAndSelectionWhilePlayUsesTheExactSelectedSource() {
        val items = episodes(3)
        val details = mutableStateOf<MediaItem?>(null)
        var selected: MediaItem? = null
        var resumed: MediaItem? = null
        render(items, onSelected = { selected = it }, onResume = { resumed = it }, selectedDetails = details)
        screen.onNodeWithTag(backdropTag(items[0]), useUnmergedTree = true).assertIsDisplayed()

        screen.onNodeWithTag(HERO).performTouchInput { swipeLeft(durationMillis = 300) }
        settle()
        assertHero(items[1])
        screen.onNodeWithTag(backdropTag(items[0]), useUnmergedTree = true).assertDoesNotExist()
        screen.onNodeWithTag(backdropTag(items[1]), useUnmergedTree = true).assertIsDisplayed()
        screen.onNodeWithTag(itemTag(items[1])).assertIsSelected()
        screen.runOnIdle {
            assertEquals(items[1], selected)
            assertNull("Swiping the background selects a work without playing it", resumed)
            details.value = items[1].copy(resumeMs = 1, summary = "스와이프로 선택한 작품의 상세 소개")
        }
        settle()
        screen.onNodeWithTag(RESUME).performClick()
        screen.runOnIdle {
            assertEquals("Descriptive metadata must not replace the source/resume snapshot", items[1], resumed)
            resumed = null
        }

        screen.onNodeWithTag(HERO).performTouchInput { swipeRight(durationMillis = 300) }
        settle()
        assertHero(items[0])
        screen.onNodeWithTag(backdropTag(items[0]), useUnmergedTree = true).assertIsDisplayed()
        screen.onNodeWithTag(backdropTag(items[1]), useUnmergedTree = true).assertDoesNotExist()
        screen.runOnIdle {
            assertEquals(items[0], selected)
            assertNull("The reverse swipe must not trigger the play button", resumed)
        }
    }

    @Test fun heroSwipesWrapAtBothEndsWhileTheShelfKeepsTheSelectedWork() {
        val items = episodes(3)
        val selections = mutableListOf<MediaItem>()
        var resumes = 0
        render(items, onSelected = { selections.add(it) }, onResume = { resumes++ })
        screen.onNodeWithTag(HERO).performTouchInput { swipeRight(durationMillis = 300) }
        settle()
        assertHero(items.last())
        screen.onNodeWithTag(itemTag(items.last())).assertIsSelected()
        screen.onNodeWithTag(HERO).performTouchInput { swipeLeft(durationMillis = 300) }
        settle()
        assertHero(items.first())
        // A programmatic reveal must not let the row's earlier anchor undo a hero selection.
        screen.mainClock.advanceTimeBy(1_200)
        screen.waitForIdle()
        assertHero(items.first())
        screen.onNodeWithTag(backdropTag(items.first()), useUnmergedTree = true).assertIsDisplayed()

        items.asReversed().forEach { expected ->
            screen.onNodeWithTag(HERO).performTouchInput { swipeRight(durationMillis = 300) }
            settle()
            assertHero(expected)
        }
        assertHero(items.first())
        screen.runOnIdle {
            assertEquals("Every explicit swipe selects metadata for that real work", items.first(), selections.last())
            assertEquals(0, resumes)
        }
    }

    @Test fun verticalHeroScrollAndBusyHorizontalSwipeDoNotSelectOrResume() {
        val items = episodes(3)
        val busy = mutableStateOf(false)
        var selected: MediaItem? = null
        var resumes = 0
        render(items, onSelected = { selected = it }, onResume = { resumes++ }, busy = busy, scrollTail = true)
        // Semantic scroll-to needs animation frames while bringing the hero back into view.
        screen.mainClock.autoAdvance = true
        val before = screen.onNodeWithTag(CONTENT).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        screen.onNodeWithTag(HERO).performTouchInput { swipeUp(durationMillis = 300) }
        settle()
        val after = screen.onNodeWithTag(CONTENT).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("A vertical gesture belongs to the surrounding Home scroll", after > before)
        screen.runOnIdle { assertEquals(items.first(), selected); assertEquals(0, resumes); busy.value = true }
        screen.onNodeWithTag(HERO).performScrollTo()
        settle()
        screen.onNodeWithTag(HERO).performTouchInput { swipeLeft(durationMillis = 300) }
        settle()
        assertHero(items.first())
        screen.runOnIdle { assertEquals(items.first(), selected); assertEquals(0, resumes) }
    }

    @Test fun heroSwipesSkipLockedPrivateWorksAndRelockDropsASwipedPrivateHero() {
        val items = episodes(3)
        val publicFirst = items[0]
        val hidden = items[1].copy(isSpoiler = true, summary = "스와이프 비공개 상세 소개")
        val publicLast = items[2]
        val unlocked = mutableStateOf(false)
        val details = mutableStateOf<MediaItem?>(hidden)
        var resumes = 0
        render(listOf(publicFirst, hidden, publicLast), spoilerUnlocked = unlocked,
            selectedDetails = details, onResume = { resumes++ })
        screen.onNodeWithTag(HERO).performTouchInput { swipeLeft(durationMillis = 300) }
        settle()
        assertHero(publicLast)
        screen.onNodeWithTag(backdropTag(hidden), useUnmergedTree = true).assertDoesNotExist()

        screen.runOnIdle { unlocked.value = true }
        settle()
        screen.onNodeWithTag(HERO).performTouchInput { swipeRight(durationMillis = 300) }
        settle()
        assertHero(hidden)
        screen.onNodeWithTag(backdropTag(hidden), useUnmergedTree = true).assertIsDisplayed()
        screen.runOnIdle { unlocked.value = false }
        settle()
        assertHero(publicFirst)
        screen.onNodeWithTag(backdropTag(hidden), useUnmergedTree = true).assertDoesNotExist()
        screen.onNodeWithTag(itemTag(hidden)).assertDoesNotExist()
        screen.runOnIdle { details.value = hidden.copy(summary = "재잠금 뒤 도착한 비공개 소개") }
        settle()
        assertHero(publicFirst)
        advanceRotation()
        assertHero(publicLast)
        screen.onNodeWithTag(backdropTag(hidden), useUnmergedTree = true).assertDoesNotExist()
        screen.runOnIdle { assertEquals("Selection and revocation do not start playback", 0, resumes) }
    }

    @Test fun busyAndChangedListsCannotRotateToARemovedWork() {
        val original = episodes(3)
        val items = mutableStateOf(original)
        val busy = mutableStateOf(true)
        var resumes = 0
        render(original, itemsState = items, busy = busy, onResume = { resumes++ })
        advanceRotation()
        advanceRotation()
        assertHero(original[0])

        screen.runOnIdle {
            items.value = listOf(original[2], original[1])
            busy.value = false
        }
        settle()
        assertHero(original[2])
        advanceRotation()
        assertHero(original[1])
        screen.onNodeWithTag(backdropTag(original[0]), useUnmergedTree = true).assertDoesNotExist()

        screen.runOnIdle { items.value = listOf(original[1]) }
        settle()
        advanceRotation()
        assertHero(original[1])
        screen.runOnIdle { items.value = emptyList() }
        settle()
        advanceRotation()
        screen.onNodeWithTag(SECTION).assertDoesNotExist()
        screen.runOnIdle { assertEquals(0, resumes) }
    }

    @Test fun scrollingTheHeroOffscreenStopsItsTimerUntilItIsVisibleAgain() {
        val items = episodes(3)
        var resumes = 0
        render(items, onResume = { resumes++ }, scrollTail = true)
        // Scroll-to animations need a running frame clock; the rotation interval is tested manually below.
        screen.mainClock.autoAdvance = true
        screen.onNodeWithTag(TAIL).performScrollTo()
        screen.mainClock.autoAdvance = false
        settle()
        screen.onNodeWithTag(HERO).assertIsNotDisplayed()
        advanceRotation()
        advanceRotation()
        assertHero(items.first())

        screen.mainClock.autoAdvance = true
        screen.onNodeWithTag(HERO).performScrollTo()
        screen.mainClock.autoAdvance = false
        settle()
        screen.onNodeWithTag(HERO).assertIsDisplayed()
        assertHero(items.first())
        advanceRotation()
        assertHero(items[1])
        screen.runOnIdle { assertEquals("Visibility does not resume media automatically", 0, resumes) }
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
        val rowSemantics = screen.onNodeWithTag(ROW).fetchSemanticsNode().config
        val range = rowSemantics[SemanticsProperties.HorizontalScrollAxisRange]
        val collection = rowSemantics[SemanticsProperties.CollectionInfo]
        assertEquals(1, collection.rowCount)
        assertEquals("The collection contains only the real media items", items.size, collection.columnCount)
        assertTrue("The real list exposes a finite scroll range", range.maxValue().isFinite())
        assertEquals("The last card reaches the actual scroll boundary", range.maxValue(), range.value(), .01f)

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
        focusPoster(items[1])
        settle()
        assertHero(items[1])
        screen.onNodeWithTag(itemTag(items[1])).assertIsSelected()
        screen.runOnIdle {
            assertEquals("Selection requests metadata for the focused work", items[1], selected)
            assertNull("Focusing artwork does not start playback", resumed)
        }
        advanceRotation()
        assertHero(items[2])
        screen.onNodeWithTag(itemTag(items[1])).assertIsFocused()
        screen.runOnIdle { assertEquals("Automatic background changes do not fetch new metadata", items[1], selected) }
        screen.onNodeWithTag(itemTag(items[1])).performClick()
        screen.runOnIdle { assertEquals("A poster click resumes directly", items[1], resumed) }
    }

    @Test fun lateDetailsForAnotherWorkCannotReplaceTheFocusedHero() {
        val items = episodes(2)
        val details = mutableStateOf<MediaItem?>(items.first().copy(summary = "첫 작품 상세 줄거리"))
        render(items, selectedDetails = details)
        screen.onNodeWithTag(SUMMARY, useUnmergedTree = true).assertTextEquals("첫 작품 상세 줄거리")
        screen.onNodeWithTag(ROW).performScrollTo()
        focusPoster(items[1])
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
        focusPoster(privateItem)
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
        screen.onNodeWithTag(HERO).performTouchInput { swipeLeft(durationMillis = 300) }
        screen.onNodeWithTag(HERO).performTouchInput { swipeRight(durationMillis = 300) }
        settle()
        assertHero(item)
        screen.runOnIdle { assertNull("A single-work hero has no swipe destination", resumed) }
        screen.onNodeWithTag(ROW).performScrollTo()
        settle()
        screen.onAllNodesWithTag(itemTag(item)).assertCountEquals(1)
        screen.onNodeWithTag(itemTag(item)).assertIsSelected().performClick()
        screen.runOnIdle { assertEquals(item, resumed) }
    }

    @Suppress("DEPRECATION")
    private fun render(
        items: List<MediaItem>,
        onResume: (MediaItem) -> Unit = {},
        onSelected: (MediaItem) -> Unit = {},
        selectedDetails: State<MediaItem?>? = null,
        onWatchOptions: (MediaItem) -> Unit = {},
        spoilerUnlocked: State<Boolean>? = null,
        busy: State<Boolean>? = null,
        scrollTail: Boolean = false,
        itemsState: State<List<MediaItem>>? = null,
    ) {
        // Input mode is device-wide; a preceding TV case must not leave this
        // synthetic touch fixture in keyboard mode with focus-driven scrolling.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        screen.mainClock.autoAdvance = false
        screen.setContent {
            inputModeManager = LocalInputModeManager.current
            MaterialTheme(colorScheme = ShyniColors, typography = CompactTypography, shapes = CompactShapes) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState()).testTag(CONTENT)) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(HOST)) {
                        ContinueWatchingSection(items = itemsState?.value ?: items, busy = busy?.value ?: false, onResume = onResume,
                            poster = { item, modifier -> SyntheticArtwork(item, modifier) },
                            backdrop = { item, modifier -> SyntheticArtwork(item, modifier.testTag(backdropTag(item))) },
                            thumbnail = { item, modifier -> Box(modifier) {
                                SyntheticArtwork(item, Modifier.fillMaxSize().testTag("continue-thumbnail-slot-${item.id}"))
                            } },
                            onSelected = onSelected, selectedDetails = selectedDetails?.value, onWatchOptions = onWatchOptions,
                            spoilerUnlocked = spoilerUnlocked?.value ?: false)
                    }
                    if (scrollTail) Box(Modifier.fillMaxWidth().height(2_000.dp).testTag(TAIL))
                }
            }
        }
        settle()
    }

    private fun assertHero(item: MediaItem) {
        screen.onNodeWithTag(TITLE, useUnmergedTree = true).assertTextEquals(item.showTitle ?: item.title)
        screen.onNodeWithTag(SUMMARY, useUnmergedTree = true).assertTextEquals(item.summary)
    }

    private fun focusPoster(item: MediaItem) {
        // SystemDefined clickables intentionally reject keyboard focus in touch mode.
        // Explicitly simulate the keyboard/D-pad input used by the TV behavior under test.
        screen.runOnIdle {
            assertTrue("The TV fixture can enter keyboard input mode", inputModeManager.requestInputMode(InputMode.Keyboard))
        }
        settle()
        screen.onNodeWithTag(itemTag(item)).performSemanticsAction(SemanticsActions.RequestFocus) { request ->
            assertTrue("A TV user can focus the poster", request())
        }
    }

    private fun rowScrollPosition() = screen.onNodeWithTag(ROW).fetchSemanticsNode()
        .config[SemanticsProperties.HorizontalScrollAxisRange].value()

    private fun settle() {
        screen.mainClock.advanceTimeBy(600)
        screen.waitForIdle()
    }

    private fun advanceRotation() {
        screen.mainClock.advanceTimeBy(CONTINUE_BACKDROP_INTERVAL_MS + 600)
        screen.waitForIdle()
    }

    private fun saveScreenshot(tag: String, filename: String) {
        val bitmap = screen.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(directory, filename).outputStream().use {
            assertTrue("The synthetic layout screenshot must be saved", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Composable private fun SyntheticArtwork(item: MediaItem, modifier: Modifier) {
        val color = if (item.id % 2 == 0) Color(0xFF2C5364) else Color(0xFF8A4A39)
        Box(modifier.background(color))
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
    private fun backdropTag(item: MediaItem) = "continue-backdrop-${item.libraryId}-${item.id}-${item.viewItemId}-${item.isSpoiler}"

    private companion object {
        const val HOST = "continue-test-host"
        const val CONTENT = "continue-test-scroll-content"
        const val TAIL = "continue-test-scroll-tail"
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
