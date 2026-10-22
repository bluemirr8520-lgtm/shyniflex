package io.mirr.shynigsy.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaDetails
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
    private val show = MediaItem(100, 7, "합성 시리즈", "show", summary = "합성 시리즈 소개")
    private val special = MediaItem(10, 7, "스페셜", "season", seasonNumber = 0, showId = 100, viewItemId = 700)
    private val regular = MediaItem(11, 7, "시즌 1", "season", seasonNumber = 1, showId = 100, viewItemId = 700)
    private val episode = MediaItem(21, 7, "합성 첫 회차", "episode", seasonNumber = 1, episodeNumber = 1,
        showId = 100, viewItemId = 700, resumeMs = 45_000, durationMs = 2_400_000)
    private fun series(children: List<MediaItem> = listOf(episode)) =
        MediaDetails(show, children, listOf(special, regular), regular.id)

    @Test fun seriesOpensWithThumbnailRowsAndEpisodeTapOpensItsExactDetail() {
        var opened: MediaItem? = null
        var plays = 0
        val detail = mutableStateOf(series())
        render(detail.value, detailState = detail, onOpen = { opened = it }, onPlay = { _, _ -> plays++ })
        screen.onNodeWithTag("compact-detail-title").assertTextEquals(show.title)
        saveScreenshot("compact-episode-list.png")
        screen.onNodeWithTag(artTag(show), useUnmergedTree = true).assertDoesNotExist()
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
        screen.onNodeWithTag(seasonTag(special)).assertIsSelected().performClick()
        screen.runOnIdle { assertEquals(special, selected); assertNull(opened); busy.value = true }
        screen.waitForIdle()
        screen.onNodeWithTag(seasonTag(special)).assertIsNotEnabled()
        screen.onNodeWithTag(seasonTag(regular)).assertIsNotEnabled()
        screen.onNodeWithTag(rowTag(episode)).assertIsNotEnabled()
        screen.onNodeWithTag(watchTag(episode), useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test fun watchSwitchAndLongPressDoNotTriggerEpisodeNavigationAndTvFocusWorks() {
        var opened: MediaItem? = null
        var changed: Pair<MediaItem, Boolean>? = null
        var options: MediaItem? = null
        render(series(), onOpen = { opened = it }, onWatched = { item, value -> changed = item to value },
            onWatchOptions = { options = it })
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
        screen.onNodeWithTag(detailBadgeTag(episode), useUnmergedTree = true).assertDoesNotExist()
        screen.runOnIdle { detail.value = MediaDetails(episode.copy(watched = true, resumeMs = 0)) }
        assertBadgeInsideArtwork(detailBadgeTag(episode), "compact-detail-art")
        screen.onNodeWithTag("compact-detail-play").assertTextEquals("다시 재생")
        val watchedMovie = MediaItem(31, 7, "합성 영화", "movie", watched = true)
        screen.runOnIdle { detail.value = MediaDetails(watchedMovie) }
        assertBadgeInsideArtwork(detailBadgeTag(watchedMovie), "compact-detail-art")
        screen.runOnIdle { detail.value = MediaDetails(episode) }
        screen.onNodeWithTag(detailBadgeTag(episode), useUnmergedTree = true).assertDoesNotExist()
        screen.onNodeWithTag(detailBadgeTag(watchedMovie), useUnmergedTree = true).assertDoesNotExist()
        screen.onNodeWithText("재생 파일").assertDoesNotExist()
        screen.onNodeWithTag("resolution-folder-2160p").performClick()
        screen.onNodeWithTag("resolution-folder-2160p").assertIsSelected()
        screen.runOnIdle { assertEquals(901, selected.value); assertTrue(plays.isEmpty()) }
        screen.onNodeWithTag("compact-detail-play").performClick()
        screen.onNodeWithTag("compact-detail-restart").performClick()
        screen.runOnIdle { assertEquals(listOf(episode to false, episode to true), plays) }
    }

    @Test fun relockRemovesPrivateEpisodesSeasonsAndAnEntirePrivateDetail() {
        val hiddenEpisode = episode.copy(id = 22, isSpoiler = true, title = "합성 비공개 회차")
        val hiddenSeason = special.copy(id = 12, isSpoiler = true, title = "합성 비공개 시즌")
        val unlocked = mutableStateOf(true)
        val detail = mutableStateOf(series(listOf(episode, hiddenEpisode)).copy(seasons = listOf(regular, hiddenSeason)))
        render(detail.value, detailState = detail, unlocked = unlocked)
        screen.onNodeWithTag(rowTag(hiddenEpisode)).performScrollTo().assertIsDisplayed()
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
    ) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        screen.mainClock.autoAdvance = true
        screen.setContent {
            inputModeManager = LocalInputModeManager.current
            MaterialTheme(colorScheme = darkColorScheme(), typography = CompactTypography, shapes = CompactShapes) {
                Surface {
                CompactDetailContent(detailState?.value ?: detail, versions, selectedPart?.value,
                    busy?.value ?: false, error?.value, unlocked?.value ?: false,
                    onPlay, onOpen, onSeason, onVersion, onWatchOptions, onWatched,
                    artwork = { item, modifier -> Box(modifier.background(Color.DarkGray).testTag(artTag(item))) })
                }
            }
        }
        screen.waitForIdle()
    }

    private fun saveScreenshot(filename: String) {
        val bitmap = screen.onNodeWithTag("compact-detail").captureToImage().asAndroidBitmap()
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
    private fun detailBadgeTag(item: MediaItem) = "compact-detail-watched-${compactMediaIdentity(item)}"
    private fun rowTag(item: MediaItem) = "compact-episode-${compactMediaIdentity(item)}"
    private fun watchTag(item: MediaItem) = "compact-episode-watch-${compactMediaIdentity(item)}"
    private fun seasonTag(item: MediaItem) = "compact-season-${compactMediaIdentity(item)}"
    private fun artTag(item: MediaItem) = "compact-art-${compactMediaIdentity(item)}"
}
