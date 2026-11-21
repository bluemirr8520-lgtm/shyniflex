package io.mirr.shynigsy.ui

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.R
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic artwork and callbacks only: no account, server, or media Activity. */
@RunWith(AndroidJUnit4::class)
class CompactDetailScreenTest {
    @get:Rule val screen = createComposeRule()
    private lateinit var inputModeManager: InputModeManager
    private val show = MediaItem(100, 7, "시네마 파라디소", "show",
        summary = "오래된 영화관의 불빛을 따라, 영사 기사와 소년의 기억을 되짚는 이야기.")
    private val special = MediaItem(10, 7, "스페셜", "season", seasonNumber = 0, showId = 100, viewItemId = 700)
    private val regular = MediaItem(11, 7, "시즌 1", "season", seasonNumber = 1, showId = 100, viewItemId = 700)
    private val episode = MediaItem(21, 7, "합성 첫 회차", "episode", seasonNumber = 1, episodeNumber = 1,
        showId = 100, viewItemId = 700, resumeMs = 45_000, durationMs = 2_400_000,
        releaseDate = "2026-11-19", resolutionLabel = "4K", summary = "극장에 모인 사람들이 영화를 기다린다.")
    private fun series(children: List<MediaItem> = listOf(episode)) =
        MediaDetails(show, children, listOf(special, regular), regular.id)

    @Test fun seriesOpensWithThumbnailRowsAndEpisodeTapOpensItsExactDetail() {
        var opened: MediaItem? = null
        var plays = 0
        val detail = mutableStateOf(series())
        render(detail.value, detailState = detail, onOpen = { opened = it }, onPlay = { _, _ -> plays++ })
        screen.onNodeWithTag("compact-detail-title").assertTextEquals(show.title)
        saveScreenshot("compact-episode-list.png")
        screen.onNodeWithTag("compact-detail-art", useUnmergedTree = true).assertIsDisplayed()
        scrollToRow(episode)
        val art = screen.onNodeWithTag("compact-episode-art-${compactMediaIdentity(episode)}", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Episode artwork is a horizontal thumbnail", 16f / 9f, art.width / art.height, .02f)
        screen.onNodeWithTag(episodeBadgeTag(episode), useUnmergedTree = true).assertDoesNotExist()
        screen.runOnIdle { detail.value = series(listOf(episode.copy(watched = true, resumeMs = 0))) }
        assertBadgeInsideArtwork(episodeBadgeTag(episode), "compact-episode-art-${compactMediaIdentity(episode)}")
        screen.runOnIdle { detail.value = series() }
        screen.onNodeWithTag(episodeBadgeTag(episode), useUnmergedTree = true).assertDoesNotExist()
        screen.onNodeWithTag(rowTag(episode)).performClick()
        screen.runOnIdle {
            assertEquals("The episode detail retains the authorized View and resume snapshot", episode, opened)
            assertEquals("Opening an episode is not an implicit play request", 0, plays)
        }
    }

    @Test fun seasonSelectorPreservesSpecialSeasonZeroAndBusyDisablesActions() {
        val busy = mutableStateOf(false)
        var selected: MediaItem? = null
        var opened: MediaItem? = null
        render(series().copy(selectedSeasonId = special.id), busy = busy,
            onSeason = { selected = it }, onOpen = { opened = it })
        screen.onNodeWithTag("compact-seasons").performClick()
        screen.onNodeWithTag(seasonTag(special)).assertIsSelected().performClick()
        screen.runOnIdle { assertEquals(special, selected); assertNull(opened); busy.value = true }
        screen.waitForIdle()
        screen.onNodeWithTag("compact-seasons").assertIsNotEnabled()
        scrollToRow(episode)
        screen.onNodeWithTag(rowTag(episode)).assertIsNotEnabled()
    }

    @Test fun watchSwitchAndLongPressDoNotTriggerEpisodeNavigationAndTvFocusWorks() {
        var opened: MediaItem? = null
        var changed: Pair<MediaItem, Boolean>? = null
        var options: MediaItem? = null
        render(series(), onOpen = { opened = it }, onWatched = { item, value -> changed = item to value },
            onWatchOptions = { options = it }, television = true)
        screen.onNodeWithTag(watchTag(episode), useUnmergedTree = true).performClick()
        screen.runOnIdle { assertEquals(episode to true, changed); assertNull(opened); assertNull(options) }
        screen.onNodeWithTag(rowTag(episode)).performTouchInput { longClick() }
        screen.runOnIdle { assertEquals(episode, options); assertNull(opened) }
        screen.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        screen.waitForIdle()
        screen.onNodeWithTag(rowTag(episode)).performSemanticsAction(SemanticsActions.RequestFocus) { request ->
            assertTrue("A D-pad user can focus the whole episode row", request())
        }
        screen.onNodeWithTag(rowTag(episode)).assertIsFocused()
    }

    @Test fun resolutionSelectionDoesNotPlayAndPlayActionsKeepTheOriginalEpisode() {
        val selected = mutableStateOf<Int?>(null)
        val plays = mutableListOf<Pair<MediaItem, Boolean>>()
        val detail = mutableStateOf(MediaDetails(episode))
        render(detail.value, detailState = detail, versions = listOf(
            MediaVersion(901, "합성 고해상도", resolution = "2160p"),
            MediaVersion(902, "합성 일반 해상도", resolution = "1080p")), selectedPart = selected,
            onVersion = { selected.value = it }, onPlay = { item, restart -> plays.add(item to restart) })
        saveScreenshot("compact-resolution-detail.png")
        screen.runOnIdle { detail.value = MediaDetails(episode.copy(watched = true, resumeMs = 0)) }
        screen.onNodeWithTag("compact-detail-play").assertTextEquals("다시 재생")
        screen.onNodeWithTag("compact-detail-restart").assertDoesNotExist()
        screen.runOnIdle { detail.value = MediaDetails(episode) }
        screen.onNodeWithTag("compact-detail-more").performClick()
        screen.onNodeWithTag("compact-detail-resolution").performClick()
        screen.onNodeWithTag("resolution-folder-2160p").performClick()
        screen.runOnIdle { assertEquals(901, selected.value); assertTrue(plays.isEmpty()) }
        screen.onNodeWithTag("compact-detail-more").performClick()
        screen.onNodeWithTag("compact-detail-restart").performClick()
        screen.onNodeWithTag("compact-detail-play").assertTextEquals("이어보기").performClick()
        screen.runOnIdle { assertEquals(listOf(episode to true, episode to false), plays) }
    }

    @Test fun relockRemovesPrivateEpisodesSeasonsAndAnEntirePrivateDetail() {
        val hiddenEpisode = episode.copy(id = 22, isSpoiler = true, title = "합성 비공개 회차")
        val hiddenSeason = special.copy(id = 12, isSpoiler = true, title = "합성 비공개 시즌")
        val unlocked = mutableStateOf(true)
        val detail = mutableStateOf(series(listOf(episode, hiddenEpisode)).copy(seasons = listOf(regular, hiddenSeason)))
        render(detail.value, detailState = detail, unlocked = unlocked)
        scrollToRow(hiddenEpisode)
        screen.onNodeWithTag(rowTag(hiddenEpisode)).assertIsDisplayed()
        screen.runOnIdle { unlocked.value = false }
        screen.waitForIdle()
        screen.onNodeWithTag(rowTag(hiddenEpisode)).assertDoesNotExist()
        screen.onNodeWithTag(seasonTag(hiddenSeason)).assertDoesNotExist()
        screen.onNodeWithTag(rowTag(episode)).assertExists()
        screen.runOnIdle { detail.value = MediaDetails(hiddenEpisode) }
        screen.waitForIdle()
        screen.onNodeWithTag("compact-detail").assertDoesNotExist()
    }

    @Test fun loadingEmptyAndErrorStatusesNeverStartPlayback() {
        val busy = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        var plays = 0
        render(series(emptyList()), busy = busy, error = error, onPlay = { _, _ -> plays++ })
        screen.onNodeWithTag("compact-episodes-loading").assertExists()
        screen.runOnIdle { busy.value = false }
        screen.waitForIdle()
        screen.onNodeWithTag("compact-episodes-loading").assertDoesNotExist()
        screen.onNodeWithTag("compact-episodes-empty").assertExists()
        screen.runOnIdle { error.value = "합성 목록 오류" }
        screen.waitForIdle()
        screen.onNodeWithTag("compact-detail-error").assertTextEquals("합성 목록 오류")
        screen.runOnIdle { assertEquals(0, plays) }
    }

    @Test fun homeOverviewShowsPosterAndSummaryBeforeALongEpisodeList() {
        val episodes = (1..80).map { number -> episode.copy(id = 20 + number, episodeNumber = number, title = "합성 $number 회차") }
        var opened: MediaItem? = null
        var plays = 0
        render(series(episodes), overviewFirst = true, onOpen = { opened = it }, onPlay = { _, _ -> plays++ })
        screen.onNodeWithTag("compact-detail-art", useUnmergedTree = true).assertIsDisplayed()
        screen.onNodeWithTag(artTag(show), useUnmergedTree = true).assertIsDisplayed()
        screen.onNodeWithTag("compact-detail-metadata", useUnmergedTree = true).assertTextContains("시즌 1", substring = true)
        val summary = screen.onNodeWithTag("compact-detail-summary").assertIsDisplayed()
            .assertTextEquals(show.summary).fetchSemanticsNode().boundsInRoot
        saveScreenshot("compact-home-overview.png")
        val play = screen.onNodeWithTag("compact-detail-play").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val full = screen.onNodeWithTag("compact-detail").fetchSemanticsNode().boundsInRoot
        assertTrue("The primary action spans the content width", play.width > full.width * .85f)
        assertTrue("Summary follows playback", play.bottom <= summary.top)
        screen.onNodeWithTag("compact-detail-play").performClick()
        scrollToRow(episodes.first())
        screen.onNodeWithTag(rowTag(episodes.first())).assertIsDisplayed()
        screen.onNodeWithTag(rowTag(episodes.last())).assertDoesNotExist()
        screen.runOnIdle { assertNull(opened); assertEquals(1, plays) }
    }

    @Test fun ratingCanBeSavedReopenedAndClearedWithoutPlaying() {
        var plays = 0
        render(MediaDetails(episode), onPlay = { _, _ -> plays++ })
        screen.onNodeWithTag("compact-detail-rating").performClick()
        screen.onNodeWithContentDescription("4점").performClick()
        screen.onNodeWithText("저장").performClick()
        screen.onNodeWithTag("compact-detail-rating").performClick()
        screen.onNodeWithContentDescription("4점").assertIsSelected()
        screen.onNodeWithText("평점 지우기").performClick()
        screen.onNodeWithTag("compact-detail-rating").performClick()
        screen.onNodeWithText("저장").assertIsNotEnabled()
        screen.onNodeWithText("취소").performClick()
        screen.runOnIdle { assertEquals(0, plays) }
    }

    @Test fun tvHomeOverviewKeepsInitialFocusAboveEpisodesUntilTheUserChoosesThem() {
        val episodes = (1..80).map { number -> episode.copy(id = 20 + number, episodeNumber = number, title = "합성 $number 회차") }
        var opened: MediaItem? = null
        var plays = 0
        render(series(episodes), overviewFirst = true, television = true,
            onOpen = { opened = it }, onPlay = { _, _ -> plays++ })
        screen.onNodeWithTag("compact-detail-episodes-jump").assertIsFocused().assertIsDisplayed()
        screen.onNodeWithTag("compact-detail-art", useUnmergedTree = true).assertIsDisplayed()
        screen.onNodeWithTag("compact-detail-summary").assertIsDisplayed()
        screen.onNodeWithTag("compact-detail-episodes-jump").performKeyInput { pressKey(Key.DirectionCenter) }
        screen.onNodeWithTag(rowTag(episodes.first())).assertIsDisplayed().assertIsFocused()
        screen.runOnIdle { assertNull("Jumping to the list must not open or play an episode", opened); assertEquals(0, plays) }
        screen.onNodeWithTag(rowTag(episodes.first())).performKeyInput { pressKey(Key.DirectionCenter) }
        screen.runOnIdle { assertEquals(episodes.first(), opened); assertEquals(0, plays) }
    }

    @Suppress("DEPRECATION")
    private fun render(
        detail: MediaDetails,
        detailState: State<MediaDetails>? = null,
        busy: State<Boolean>? = null,
        error: State<String?>? = null,
        unlocked: State<Boolean>? = null,
        versions: List<MediaVersion> = emptyList(),
        selectedPart: State<Int?>? = null,
        onOpen: (MediaItem) -> Unit = {},
        onPlay: (MediaItem, Boolean) -> Unit = { _, _ -> },
        onSeason: (MediaItem) -> Unit = {},
        onVersion: (Int?) -> Unit = {},
        onWatchOptions: (MediaItem) -> Unit = {},
        onWatched: (MediaItem, Boolean) -> Unit = { _, _ -> },
        overviewFirst: Boolean = false,
        television: Boolean = false,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(!television)
        val base = instrumentation.targetContext
        val context = if (television) base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }) else base
        screen.mainClock.autoAdvance = true
        screen.setContent {
            inputModeManager = LocalInputModeManager.current
            CompositionLocalProvider(LocalContext provides context) {
            MaterialTheme(colorScheme = darkColorScheme(), typography = CompactTypography, shapes = CompactShapes) {
                Surface {
                CompactDetailContent(detailState?.value ?: detail, versions, selectedPart?.value,
                    busy?.value ?: false, error?.value, unlocked?.value ?: false,
                    onPlay, onOpen, onSeason, onVersion, onWatchOptions, onWatched,
                    artwork = { item, modifier -> Image(painterResource(R.drawable.cinema_paradiso_backdrop), null,
                        modifier.testTag(artTag(item)), contentScale = ContentScale.Crop) },
                    overviewFirst = overviewFirst, ratingScope = "device-test-account")
                }
            }
            }
        }
        screen.waitForIdle()
    }

    private fun saveScreenshot(filename: String) {
        val bitmap = screen.onRoot().captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(directory, filename).outputStream().use { output ->
            assertTrue("The synthetic detail screenshot must be saved", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun assertBadgeInsideArtwork(badgeTag: String, artworkTag: String) {
        val badge = screen.onNodeWithTag(badgeTag, useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val artwork = screen.onNodeWithTag(artworkTag, useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("The watched state stays on top of its own artwork",
            badge.left >= artwork.left && badge.top >= artwork.top &&
                badge.right <= artwork.right && badge.bottom <= artwork.bottom)
    }

    private fun episodeBadgeTag(item: MediaItem) = "compact-episode-watched-${compactMediaIdentity(item)}"
    private fun scrollToRow(item: MediaItem) {
        screen.onNodeWithTag("compact-detail").performScrollToNode(hasTestTag(rowTag(item)))
    }
    private fun rowTag(item: MediaItem) = "compact-episode-${compactMediaIdentity(item)}"
    private fun watchTag(item: MediaItem) = "compact-episode-watch-${compactMediaIdentity(item)}"
    private fun seasonTag(item: MediaItem) = "compact-season-${compactMediaIdentity(item)}"
    private fun artTag(item: MediaItem) = "compact-art-${compactMediaIdentity(item)}"
}
