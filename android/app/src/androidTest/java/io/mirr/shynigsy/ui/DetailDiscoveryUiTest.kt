package io.mirr.shynigsy.ui

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.DetailDiscoveryState
import io.mirr.shynigsy.data.CastMember
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaVersion
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only fake artwork, people, callbacks and state: no account or server. */
@RunWith(AndroidJUnit4::class)
class DetailDiscoveryUiTest {
    @get:Rule val screen = createComposeRule()
    private val parent = MediaItem(100, 7, "합성 회차", "episode", summary = "합성 회차의 소개입니다.",
        seasonNumber = 1, episodeNumber = 1, viewItemId = 700)
    private val actor = CastMember("합성 배우", "주연", portrait = " ", ppkey = "synthetic-person")
    private val unavailable = CastMember("제공 ID 없는 배우", "조연")
    private val work = MediaItem(200, 7, "합성 보유 작품", "show", viewItemId = 701)
    private val privateWork = MediaItem(300, 8, "합성 비공개 작품", "movie", viewItemId = 800, isSpoiler = true)
    private val libraries = listOf(Library(7, "합성 TV", "tv"), Library(8, "합성 비공개", "movie", true),
        Library(9, "합성 영화", "movie"))

    @Test fun selectingCastShowsOwnedWorksInlineAndDoesNotPlayTheSource() {
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(parent,
            relatedItems = listOf(work), relatedLoaded = true))
        var actorRequests = 0
        var plays = 0
        render(frame, onActor = {
            actorRequests++
            frame.value = frame.value!!.copy(selectedActor = it, actorItems = listOf(work), actorLoaded = true)
        }, onPlay = { _, _ -> plays++ })
        screen.onNodeWithTag("compact-detail-play").assertIsDisplayed()
        screen.runOnIdle { assertEquals(0, actorRequests) }
        screen.onNodeWithTag("detail-cast").performScrollTo()
        screen.onNodeWithTag("detail-cast-placeholder-0", useUnmergedTree = true).assertIsDisplayed()
        screen.onNodeWithTag("detail-cast-placeholder-1", useUnmergedTree = true).assertExists()
        screen.onNodeWithTag("detail-cast-1").assertIsNotEnabled()
        screen.onNodeWithTag("detail-cast-0").performClick().assertIsSelected()
        screen.onNodeWithTag("detail-actor-scope").assertDoesNotExist()
        screen.onNodeWithTag("detail-actor-${compactMediaIdentity(work)}").assertExists()
        screen.runOnIdle { assertEquals(1, actorRequests); assertEquals(0, plays) }
        screen.onNodeWithTag("detail-cast").performScrollTo()
        saveLightScreenshot()
    }

    @Test fun actorWorksIncludeMoviesFromAnotherAccessibleLibrary() {
        val movie = MediaItem(400, 9, "합성 영화", "movie", viewItemId = 900)
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(parent,
            selectedActor = actor, actorItems = listOf(movie), actorLoaded = true))
        render(frame)
        screen.onNodeWithTag("detail-actor-${compactMediaIdentity(movie)}").performScrollTo().assertIsDisplayed()
    }

    @Test fun actorCountsIncludeOffscreenWorksAndExcludeLockedResults() {
        val shows = (0 until 15).map { index ->
            work.copy(id = 200 + index, title = "합성 시리즈 $index", viewItemId = 700 + index)
        }
        val movies = (0 until 2).map { index ->
            MediaItem(400 + index, 9, "합성 영화 $index", "movie", viewItemId = 900 + index)
        }
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(parent,
            selectedActor = actor, actorItems = shows + movies + privateWork, actorLoaded = true))
        render(frame)
        screen.onNodeWithTag("detail-actor-heading").performScrollTo()
            .assertTextEquals("합성 배우의 출연작 · 17편")
        screen.onNodeWithTag("detail-actor-counts").performScrollTo()
            .assertTextEquals("확인된 작품: 시리즈 15편 · 영화 2편")
        screen.onNodeWithTag("detail-actor-${compactMediaIdentity(privateWork)}").assertDoesNotExist()
    }

    @Test fun localActorLoadingExplainsTheBroaderSearch() {
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(parent,
            selectedActor = actor, actorLoading = true))
        render(frame)
        screen.onNodeWithTag("detail-actor-loading").performScrollTo()
            .assertTextEquals("서버에서 출연작을 불러오는 중입니다")
    }

    @Test fun libraryProgressAndCountsUpdateWhilePartialWorksRemainUsable() {
        val movie = MediaItem(400, 9, "합성 영화", "movie", viewItemId = 900)
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(parent,
            selectedActor = actor, actorItems = listOf(work), actorLoading = true,
            actorChecked = 1, actorTotal = 3))
        var opened: MediaItem? = null
        render(frame, onOpen = { opened = it })
        screen.onNodeWithTag("detail-actor-loading").performScrollTo()
            .assertTextEquals("라이브러리 1/3 확인 · 출연작을 불러오는 중입니다")
        screen.onNodeWithTag("detail-actor-heading").performScrollTo()
            .assertTextEquals("합성 배우의 출연작 · 1편")
        screen.onNodeWithTag("detail-actor-${compactMediaIdentity(work)}").performScrollTo()
            .assertIsEnabled().performClick()
        screen.runOnIdle {
            assertEquals(work, opened)
            frame.value = frame.value!!.copy(actorItems = listOf(work, movie), actorChecked = 2)
        }
        screen.onNodeWithTag("detail-actor-heading").performScrollTo()
            .assertTextEquals("합성 배우의 출연작 · 2편")
        screen.onNodeWithTag("detail-actor-counts").performScrollTo()
            .assertTextEquals("확인된 작품: 시리즈 1편 · 영화 1편")
        screen.onNodeWithTag("detail-actor-loading").performScrollTo()
            .assertTextEquals("라이브러리 2/3 확인 · 출연작을 불러오는 중입니다")
        screen.onNodeWithTag("detail-actor-${compactMediaIdentity(movie)}").performScrollTo()
            .assertIsEnabled().performClick()
        screen.runOnIdle { assertEquals(movie, opened) }
    }

    @Test fun actorSearchDisplaysProgressAlongsideAlreadyFoundMovies() {
        val movie = MediaItem(400, 9, "합성 영화", "movie", viewItemId = 900)
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(parent,
            selectedActor = actor, actorItems = listOf(movie), actorLoading = true,
            actorChecked = 2, actorTotal = 10, movieSearchRunning = true))
        render(frame)
        screen.onNodeWithTag("detail-actor-loading").performScrollTo()
            .assertTextEquals("영화 출연작을 검색 중입니다. 2/10 확인")
        screen.onNodeWithTag("detail-actor-${compactMediaIdentity(movie)}").performScrollTo().assertIsDisplayed()
    }

    @Test fun movieDetailScanStartsOnlyWhenRequestedAndCanBeCanceled() {
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(parent,
            selectedActor = actor, actorItems = listOf(work), actorLoaded = true,
            tvLibrariesOnly = true, movieSearchAvailable = true))
        val busyState = mutableStateOf(false)
        var requests = 0
        var cancellations = 0
        render(frame, busyState = busyState, onMovieSearch = { requests++ }, onCancelMovieSearch = { cancellations++ })
        screen.onNodeWithTag("detail-actor-${compactMediaIdentity(work)}").performScrollTo().assertExists()
        screen.runOnIdle { assertEquals(0, requests) }
        screen.onNodeWithTag("detail-actor-movies-search").performScrollTo().performClick()
        screen.runOnIdle {
            assertEquals(1, requests)
            frame.value = frame.value!!.copy(actorLoading = true, movieSearchRunning = true,
                movieSearchAvailable = false, actorChecked = 2, actorTotal = 100)
            busyState.value = true
        }
        screen.onNodeWithTag("detail-actor-${compactMediaIdentity(work)}").assertExists()
        screen.onNodeWithTag("detail-actor-movies-cancel").performScrollTo().assertIsEnabled().performClick()
        screen.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test fun incompleteActorResultsKeepConfirmedWorksAndOfferRetry() {
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(parent,
            selectedActor = actor, actorItems = listOf(work), actorLoaded = true,
            tvLibrariesOnly = true, actorIncomplete = true))
        var retries = 0
        render(frame, onActor = { retries++ })
        screen.onNodeWithTag("detail-actor-${compactMediaIdentity(work)}").performScrollTo().assertExists()
        screen.onNodeWithTag("detail-actor-incomplete").performScrollTo().assertIsDisplayed()
        screen.onNodeWithText("다시 확인").performScrollTo().performClick()
        screen.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun relatedAndActorCardsOpenTheirExactDetailAndKeepWatchLongPressSeparate() {
        val unresolved = work.copy(id = 201, viewItemId = null)
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(parent,
            relatedItems = listOf(work, unresolved), relatedLoaded = true,
            selectedActor = actor, actorItems = listOf(work), actorLoaded = true))
        var opened: MediaItem? = null
        var watched: MediaItem? = null
        var plays = 0
        render(frame, onOpen = { opened = it }, onWatch = { watched = it }, onPlay = { _, _ -> plays++ })
        val related = screen.onNodeWithTag("detail-related-${compactMediaIdentity(work)}")
        related.performScrollTo().performTouchInput { longClick() }
        screen.runOnIdle { assertEquals(work, watched); assertNull(opened); assertEquals(0, plays) }
        related.performClick()
        screen.runOnIdle { assertEquals(work, opened); opened = null }
        screen.onNodeWithTag("detail-actor-${compactMediaIdentity(work)}").performScrollTo().performClick()
        screen.runOnIdle { assertEquals(work, opened); assertEquals(0, plays) }
        screen.onNodeWithTag("detail-related-${compactMediaIdentity(unresolved)}").performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick)).performClick()
        screen.runOnIdle { assertEquals(unresolved, opened); assertEquals(work, watched) }
    }

    @Test fun relockingRemovesPrivateWorksImmediatelyAndAnEntirePrivateDetail() {
        val unlocked = mutableStateOf(true)
        val detail = mutableStateOf(MediaDetails(parent, castMembers = listOf(actor)))
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(parent,
            relatedItems = listOf(privateWork, work), relatedLoaded = true,
            selectedActor = actor, actorItems = listOf(privateWork, work), actorLoaded = true))
        render(frame, unlocked = unlocked, detail = detail)
        screen.onNodeWithTag("detail-related-${compactMediaIdentity(privateWork)}").performScrollTo().assertExists()
        screen.runOnIdle { unlocked.value = false }
        screen.onNodeWithTag("detail-related-${compactMediaIdentity(privateWork)}").assertDoesNotExist()
        screen.onNodeWithTag("detail-actor-${compactMediaIdentity(privateWork)}").assertDoesNotExist()
        screen.onNodeWithTag("detail-related-${compactMediaIdentity(work)}").assertExists()
        screen.runOnIdle { detail.value = MediaDetails(privateWork, castMembers = listOf(actor)) }
        screen.onNodeWithTag("compact-detail").assertDoesNotExist()
    }

    @Test fun supplementalLoadingDoesNotDisablePlayResolutionOrMoveEpisodesBelowCast() {
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(parent,
            relatedLoading = true, selectedActor = actor, actorLoading = true))
        val detail = mutableStateOf(MediaDetails(parent, castMembers = listOf(actor)))
        var plays = 0
        render(frame, detail = detail, onPlay = { _, _ -> plays++ })
        screen.onNodeWithTag("compact-detail-play").performClick()
        screen.onNodeWithTag("resolution-folder-1080p").performScrollTo().performClick()
        screen.runOnIdle { assertEquals(1, plays) }
        val show = parent.copy(type = "show", title = "합성 시리즈")
        screen.runOnIdle {
            detail.value = MediaDetails(show, children = listOf(parent), castMembers = listOf(actor))
            frame.value = DetailDiscoveryState(show, relatedLoading = true)
        }
        val episode = screen.onNodeWithTag("compact-episode-${compactMediaIdentity(parent)}").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        // A short viewport can omit the lazy footer entirely; if composed, it follows the episode.
        screen.onAllNodesWithTag("detail-cast").fetchSemanticsNodes().firstOrNull()?.let { cast ->
            if (cast.boundsInRoot.height > 0)
                assertTrue("Cast must follow the legacy vertical episode row", cast.boundsInRoot.top >= episode.bottom)
        }
    }

    @Test fun discoveryShortcutSkipsLongEpisodeListWithoutOpeningOrPlayingAnything() {
        val show = parent.copy(type = "show", title = "합성 시리즈")
        val episodes = (1..40).map { parent.copy(id = 1000 + it, episodeNumber = it, viewItemId = 2000 + it) }
        val detail = mutableStateOf(MediaDetails(show, children = episodes, castMembers = listOf(actor)))
        val frame = mutableStateOf<DetailDiscoveryState?>(DetailDiscoveryState(show, relatedItems = listOf(work), relatedLoaded = true))
        var opened = 0
        var played = 0
        render(frame, detail = detail, onOpen = { opened++ }, onPlay = { _, _ -> played++ })
        screen.onNodeWithTag("compact-episode-${compactMediaIdentity(episodes.first())}").assertExists()
        screen.onNodeWithTag("compact-detail-discovery-jump").assertIsDisplayed().performClick()
        screen.onNodeWithTag("detail-cast").assertIsDisplayed()
        screen.runOnIdle { assertEquals(0, opened); assertEquals(0, played) }
    }

    @Suppress("DEPRECATION")
    private fun render(frame: State<DetailDiscoveryState?>, unlocked: State<Boolean>? = null,
        detail: State<MediaDetails>? = null, busyState: State<Boolean>? = null, onActor: (CastMember) -> Unit = {},
        onOpen: (MediaItem) -> Unit = {}, onWatch: (MediaItem) -> Unit = {},
        onPlay: (MediaItem, Boolean) -> Unit = { _, _ -> },
        onMovieSearch: () -> Unit = {}, onCancelMovieSearch: () -> Unit = {}) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Synthetic UI evidence belongs only to the isolated QA package", instrumentation.targetContext.packageName.endsWith(".spoilerqa"))
        instrumentation.setInTouchMode(true)
        screen.mainClock.autoAdvance = true
        screen.setContent {
            MaterialTheme(colorScheme = ShyniColors, typography = CompactTypography, shapes = CompactShapes) {
                Surface {
                    val current = detail?.value ?: MediaDetails(parent, castMembers = listOf(actor, unavailable))
                    // Production uses a SaveableStateProvider per detail identity.
                    key(compactMediaIdentity(current.item)) {
                    CompactDetailContent(current,
                        versions = listOf(MediaVersion(900, "합성 1080p", resolution = "1080p")), selectedPartId = null,
                        busy = busyState?.value ?: false, error = null, spoilerUnlocked = unlocked?.value ?: false,
                        onPlay = onPlay, onOpenItem = onOpen, onSelectSeason = {}, onSelectVersion = {},
                        onWatchOptions = onWatch, onWatchedChange = { _, _ -> },
                        artwork = { _, modifier -> Box(modifier.background(Color(0xFFD6E7E2))) },
                        discovery = frame.value, libraries = libraries, onSelectActor = onActor,
                        onSearchActorMovies = onMovieSearch, onCancelActorMovieSearch = onCancelMovieSearch,
                        portrait = { _, modifier -> Box(modifier.background(Color(0xFFCBDAD7))) })
                    }
                }
            }
        }
        screen.waitForIdle()
    }

    private fun saveLightScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destination = File(instrumentation.targetContext.cacheDir, "detail-discovery-light.png")
        val bitmap = screen.onRoot().captureToImage().asAndroidBitmap()
        try {
            destination.outputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        } finally { bitmap.recycle() }
        instrumentation.sendStatus(0, Bundle().apply { putString("detail_discovery_screenshot", destination.absolutePath) })
    }
}
