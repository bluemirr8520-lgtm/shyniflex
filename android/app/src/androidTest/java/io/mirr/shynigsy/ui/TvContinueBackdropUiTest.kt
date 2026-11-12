package io.mirr.shynigsy.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.AppState
import io.mirr.shynigsy.AppViewModel
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.ShyniClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real TV composables on the isolated QA host; only generated artwork and synthetic HTTP exist. */
@RunWith(AndroidJUnit4::class)
class TvContinueBackdropUiTest {
    @get:Rule val screen = createComposeRule()
    private lateinit var inputMode: InputModeManager
    private val first = MediaItem(101, 7, "합성 이어보기 A", "movie", viewItemId = 701,
        summary = "첫 번째 합성 작품의 설명", durationMs = 6_000_000, resumeMs = 600_000)
    private val second = first.copy(id = 102, viewItemId = 702, title = "합성 이어보기 B")
    private val hidden = first.copy(id = 201, libraryId = 8, viewItemId = 801,
        title = "합성 비공개 이어보기", isSpoiler = true)
    private val recent = first.copy(id = 301, viewItemId = 703, title = "순환하면 안 되는 최근 추가")

    @Test fun threeHomeStylesChangeGeometryAndTypographyWithoutReplacingFocusedMedia() {
        val style = mutableStateOf(CinemaStyle.CINEMA)
        withFixture(AppState(loggedIn = true, continueItems = listOf(first, second),
            recentItems = listOf(recent), libraries = listOf(Library(7, "합성 영화", "movie")))) { fixture ->
            renderFocusedHome(fixture, style, televisionCanvas = true)
            // Enter the row through its restore target, then navigate like the remote. Asking
            // for the second card from outside the group may legitimately restore the first.
            focusCard(first)
            screen.onNodeWithTag(cardTag(first)).performKeyInput { pressKey(Key.DirectionRight) }
            screen.onNodeWithTag(cardTag(second)).assertIsFocused()
            assertEquals("Focus selects the second work before a style changes", second.title, heroTitleText())
            screen.waitUntil(3_000) {
                val job = AppViewModel::class.java.getDeclaredField("continueDetailsJob")
                    .apply { isAccessible = true }.get(fixture.model) as Job?
                fixture.requests.isNotEmpty() && job?.isCompleted == true
            }
            val requestsBefore = fixture.requests.size
            // Hold the independent six-second artwork timer while comparing the selected item.
            screen.runOnIdle { fixture.state.value = fixture.state.value.copy(busy = true) }
            assertEquals("Pausing the artwork timer retains the focus selection", second.title, heroTitleText())
            val heroHeights = mutableListOf<Float>()
            val posterWidths = mutableListOf<Float>()
            CinemaStyle.entries.forEach { candidate ->
                screen.runOnIdle { style.value = candidate }
                screen.waitForIdle()
                saveScreenshot("tv-home", "home-style-${candidate.key}.png")
                assertEquals("Style ${candidate.key} retains the selected work", second.title, heroTitleText())
                val card = screen.onNodeWithTag(cardTag(second)).assertIsFocused().fetchSemanticsNode().boundsInRoot
                heroHeights += screen.onNodeWithTag("tv-continue-hero").fetchSemanticsNode().boundsInRoot.height
                posterWidths += card.width
                val title = heroTitleLayout()
                assertEquals(if (candidate == CinemaStyle.OTT) FontFamily.SansSerif else FontFamily.Serif,
                    title.layoutInput.style.fontFamily)
                val action = screen.onNodeWithTag("cinema-hero-resume").fetchSemanticsNode().boundsInRoot
                val density = fixture.pixelsPerDp
                assertTrue("Every style retains a 48dp play target", action.height >= 48f * density - 1f)
            }
            assertEquals("Styles change composition, not only colors", 3, heroHeights.distinct().size)
            assertEquals("Each style has its own poster density", 3, posterWidths.distinct().size)
            assertEquals("Restyling does not refetch media", requestsBefore, fixture.requests.size)
            screen.runOnIdle { fixture.state.value = fixture.state.value.copy(busy = false) }
            screen.onNodeWithTag("cinema-hero-resume").performClick()
            screen.waitUntil(3_000) { fixture.requests.any { it.first == "/playback/decision" } }
            assertEquals(second.viewItemId, JSONObject(fixture.requests.first { it.first == "/playback/decision" }.second)
                .getInt("view_item_id"))
        }
    }

    @Test fun mobileStyleChangesKeepTheTappedPosterAndNeverReplayIt() {
        val style = mutableStateOf(CinemaStyle.CINEMA)
        withFixture(AppState(loggedIn = true, continueItems = listOf(first, second))) { fixture ->
            renderHome(fixture, style)
            screen.onNodeWithTag(cardTag(second)).performTouchInput { click() }
            screen.waitUntil(3_000) { fixture.requests.any { it.first == "/playback/decision" } }
            screen.runOnIdle { fixture.state.value = fixture.state.value.copy(busy = true) }
            CinemaStyle.entries.forEach { candidate ->
                screen.runOnIdle { style.value = candidate }
                screen.waitForIdle()
                assertEquals("Mobile style ${candidate.key} retains the tapped work", second.title, heroTitleText())
                screen.onNodeWithTag(cardTag(second)).assertIsSelected()
                screen.onNodeWithTag("cinema-hero-art-${second.libraryId}-${second.id}", useUnmergedTree = true).assertExists()
                saveScreenshot("tv-home", "home-mobile-style-${candidate.key}.png")
            }
            val decisions = fixture.requests.filter { it.first == "/playback/decision" }
            assertEquals("Appearance changes do not start playback again", 1, decisions.size)
            assertEquals(second.viewItemId, JSONObject(decisions.single().second).getInt("view_item_id"))
        }
    }

    @Test fun loungeTitleKeepsContrastOnLightAndDarkCustomBackgrounds() {
        val style = mutableStateOf(CinemaStyle.LOUNGE)
        val background = mutableStateOf<Int?>(null)
        withFixture(AppState(loggedIn = true, busy = true, continueItems = listOf(first))) { fixture ->
            renderHome(fixture, style, background)
            listOf(CinemaStyle.LOUNGE.defaultBackgroundArgb, 0xFF101820.toInt(), 0xFFFFFFFF.toInt(), 0xFF777777.toInt())
                .forEach { color ->
                    screen.runOnIdle { background.value = color }
                    screen.waitForIdle()
                    val expected = cinemaColorScheme(Color(color), CinemaStyle.LOUNGE).onBackground
                    val textColor = heroTitleLayout().layoutInput.style.color
                    assertEquals(expected, textColor)
                    val home = screen.onNodeWithTag("tv-home").fetchSemanticsNode().boundsInRoot
                    val title = screen.onNodeWithTag("cinema-hero-title").fetchSemanticsNode().boundsInRoot
                    val bitmap = screen.onNodeWithTag("tv-home").captureToImage().asAndroidBitmap()
                    try {
                        // Sample just left of the text at the same height: the actual scrim over artwork.
                        val x = (title.left - home.left - 6).toInt().coerceIn(0, bitmap.width - 1)
                        val y = (title.center.y - home.top).toInt().coerceIn(0, bitmap.height - 1)
                        assertTrue("The rendered lounge title area preserves readable contrast",
                            cinemaContrastRatio(textColor, Color(bitmap.getPixel(x, y))) >= 4.5f)
                    } finally { bitmap.recycle() }
                }
        }
    }

    private fun heroTitleLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        screen.onNodeWithTag("cinema-hero-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        return layouts.single()
    }

    private fun heroTitleText() = screen.onNodeWithTag("cinema-hero-title", useUnmergedTree = true)
        .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    @Test fun mobileContinueDragAndFlingFollowPostersWithoutStartingPlayback() {
        val items = (0 until 14).map { index -> first.copy(id = 101 + index, viewItemId = 701 + index,
            title = "모바일 이어보기 ${index + 1}") }
        withFixture(AppState(loggedIn = true, continueItems = items)) { fixture ->
            renderHome(fixture)
            screen.mainClock.autoAdvance = false
            val row = screen.onNodeWithTag("home-continue-row")
            row.performTouchInput {
                down(Offset(width * .9f, center.y))
                moveTo(Offset(width * .55f, center.y), delayMillis = 250)
            }
            screen.mainClock.advanceTimeBy(64)
            screen.waitForIdle()
            val duringDrag = items.single { heroExists(it) }
            assertNotEquals("Touch browsing changes the hero before the finger is released", items.first(), duringDrag)
            screen.onNodeWithTag("cinema-hero-art-${duringDrag.libraryId}-${duringDrag.id}", useUnmergedTree = true)
                .assertExists()
            assertTrue("Passing posters does not ask the server for details", fixture.requests.isEmpty())

            row.performTouchInput {
                // A real flick supplies several recent motion samples. A single late event can
                // produce zero velocity; on a wide screen its drag distance alone masked this.
                listOf(.45f, .35f, .25f, .15f, .05f).forEach { fraction ->
                    moveTo(Offset(width * fraction, center.y), delayMillis = 16)
                }
                up()
            }
            screen.mainClock.advanceTimeByFrame()
            screen.waitForIdle()
            val releasePosition = row.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
            val releaseHero = items.single { heroExists(it) }
            val frameHeroes = mutableListOf<String>()
            repeat(10) {
                screen.mainClock.advanceTimeByFrame()
                screen.waitForIdle()
                frameHeroes += heroTitleText()
            }
            val beforeRenderFlush = heroTitleText()
            // Give layoutInfo -> snapshotFlow -> hero state a render frame as well as main-queue
            // time, then log the synthetic before/after values to distinguish clock lag from a bug.
            repeat(2) {
                screen.mainClock.advanceTimeByFrame()
                screen.waitForIdle()
            }
            val flingPosition = row.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("synthetic_fling_render", "scroll=$releasePosition->$flingPosition; " +
                    "released=${releaseHero.id}; frames=${frameHeroes.joinToString(",")}; " +
                    "beforeFlush=$beforeRenderFlush; afterFlush=${heroTitleText()}")
            })
            assertTrue("The shelf keeps moving after release: $releasePosition -> $flingPosition", flingPosition > releasePosition)
            val duringFling = items.single { heroExists(it) }
            assertNotEquals("The backdrop follows the fling after release ($releasePosition -> $flingPosition)", releaseHero, duringFling)
            assertNotEquals("The browsing gesture advances beyond its initial drag", duringDrag, duringFling)
            screen.onNodeWithTag("cinema-hero-art-${duringFling.libraryId}-${duringFling.id}", useUnmergedTree = true)
                .assertExists()
            screen.mainClock.advanceTimeBy(2_500)
            screen.waitForIdle()
            screen.mainClock.advanceTimeByFrame()
            screen.waitForIdle()
            screen.waitUntil(3_000) { fixture.requests.isNotEmpty() }
            assertEquals("Only the final visible work requests details", 1, fixture.requests.size)
            assertTrue("Browsing never starts playback", fixture.requests.none { it.first == "/playback/decision" })
            screen.mainClock.autoAdvance = true
        }
    }

    @Test fun mobilePosterTapSelectsItsBackdropAndImmediatelyResumesTheSameWork() {
        withFixture(AppState(loggedIn = true, continueItems = listOf(first, second))) { fixture ->
            renderHome(fixture)
            screen.onNodeWithTag(cardTag(second)).performTouchInput { click() }
            screen.waitUntil(3_000) { fixture.requests.any { it.first == "/playback/decision" } }
            assertTrue("The tapped poster remains the selected home background", heroExists(second))
            screen.onNodeWithTag("cinema-hero-art-${second.libraryId}-${second.id}", useUnmergedTree = true).assertExists()
            val body = JSONObject(fixture.requests.first { it.first == "/playback/decision" }.second)
            assertEquals(second.viewItemId, body.getInt("view_item_id"))
            assertEquals(second.id, body.getInt("metadata_item_id"))
        }
    }

    @Test fun lockingDuringMobilePosterDragDropsPrivateBackdropAndStaleSelections() {
        val trailing = (0 until 12).map { index -> recent.copy(id = 401 + index, viewItemId = 901 + index,
            title = "스크롤 공간 ${index + 1}") }
        withFixture(AppState(loggedIn = true, spoilerUnlocked = true,
            continueItems = listOf(first, hidden, second, recent) + trailing)) { fixture ->
            renderHome(fixture)
            screen.mainClock.autoAdvance = false
            val distance = screen.onNodeWithTag(cardTag(hidden)).fetchSemanticsNode().boundsInRoot.left -
                screen.onNodeWithTag(cardTag(first)).fetchSemanticsNode().boundsInRoot.left
            val row = screen.onNodeWithTag("home-continue-row")
            row.performTouchInput {
                down(Offset(width * .9f, center.y))
                moveTo(Offset(width * .9f - distance, center.y), delayMillis = 300)
            }
            screen.mainClock.advanceTimeBy(64)
            screen.waitForIdle()
            assertTrue(heroExists(hidden))
            screen.runOnIdle { fixture.state.value = fixture.state.value.copy(spoilerUnlocked = false,
                continueDetails = hidden) }
            screen.mainClock.advanceTimeBy(64)
            screen.waitForIdle()
            assertFalse(heroExists(hidden))
            screen.onNodeWithTag("cinema-hero-art-${hidden.libraryId}-${hidden.id}", useUnmergedTree = true)
                .assertDoesNotExist()
            screen.onNodeWithTag(cardTag(hidden)).assertDoesNotExist()
            row.performTouchInput { up() }
            screen.mainClock.advanceTimeBy(2_500)
            screen.waitForIdle()
            assertFalse("A lingering fling cannot restore a revoked item", heroExists(hidden))
            assertTrue(fixture.requests.none { it.first == "/playback/decision" })
            screen.mainClock.autoAdvance = true
        }
    }

    @Test fun initialHomeShelfTakesFocusOnlyWhenContentArrivesWithoutRemoteInput() {
        withFixture(AppState(loggedIn = true, continueLoading = true)) { fixture ->
            renderFocusedHome(fixture)
            screen.runOnIdle { fixture.state.value = fixture.state.value.copy(continueItems = listOf(first)) }
            screen.waitUntil(3_000) { screen.onAllNodesWithTag(cardTag(first)).fetchSemanticsNodes().isNotEmpty() }
            screen.onNodeWithTag(cardTag(first)).assertIsFocused()
        }
    }

    @Test fun lateShelfNeverStealsFocusAfterRemoteNavigation() {
        withFixture(AppState(loggedIn = true, continueLoading = true)) { fixture ->
            renderFocusedHome(fixture)
            screen.onNodeWithTag("cinema-home-appearance").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                .performKeyInput { pressKey(Key.DirectionRight) }
            screen.onNodeWithTag("cinema-home-search").assertIsFocused()
            screen.runOnIdle { fixture.state.value = fixture.state.value.copy(continueItems = listOf(first)) }
            screen.waitUntil(3_000) { screen.onAllNodesWithTag(cardTag(first)).fetchSemanticsNodes().isNotEmpty() }
            screen.onNodeWithTag("cinema-home-search").assertIsFocused()
        }
    }

    @Test fun lateShelfNeverStealsFocusFromOpenDrawerOrAfterClosingIt() {
        withFixture(AppState(loggedIn = true, continueLoading = true)) { fixture ->
            renderFocusedHome(fixture)
            screen.onNodeWithTag("cinema-home-appearance").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                .performKeyInput { pressKey(Key.DirectionLeft) }
            screen.waitUntil(3_000) { screen.onAllNodesWithTag("fixture-sidebar-action").fetchSemanticsNodes().isNotEmpty() }
            screen.onNodeWithTag("fixture-sidebar-action").assertIsFocused()
            screen.runOnIdle { fixture.state.value = fixture.state.value.copy(continueItems = listOf(first)) }
            screen.waitUntil(3_000) { screen.onAllNodesWithTag(cardTag(first)).fetchSemanticsNodes().isNotEmpty() }
            screen.onNodeWithTag("fixture-sidebar-action").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
            screen.waitForIdle()
            screen.onNodeWithTag("cinema-home-appearance").assertIsFocused()
        }
    }

    @Test fun missingLibraryDoesNotRequestAnUnattachedShelf() {
        withFixture(AppState(loggedIn = true, continueLoading = true)) { fixture ->
            renderFocusedHome(fixture)
            screen.onNodeWithTag("cinema-home-search").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            screen.runOnIdle { fixture.state.value = fixture.state.value.copy(recentItems = listOf(recent)) }
            screen.waitForIdle()
            screen.onNodeWithTag("cinema-home-search").assertIsFocused()
            screen.onNodeWithTag(cardTag(recent)).assertDoesNotExist()
        }
    }

    @Test fun actualTvHomeLoopsOnlyContinueWorksWithoutMovingFocusOrFetchingMetadata() {
        withFixture(AppState(loggedIn = true, continueItems = listOf(first, second),
            recentItems = listOf(recent), libraries = listOf(Library(7, "합성 영화", "movie")))) { fixture ->
            renderHome(fixture)
            focusCard(first)
            screen.waitUntil(3_000) {
                val read = AppViewModel::class.java.getDeclaredField("continueDetailsJob")
                    .apply { isAccessible = true }.get(fixture.model) as Job?
                fixture.requests.isNotEmpty() && read?.isCompleted == true
            }
            val manualReads = fixture.requests.size
            waitForHero(second)
            screen.onNodeWithTag(cardTag(first)).assertIsFocused()
            assertEquals("Only manual focus may request details", manualReads, fixture.requests.size)
            waitForHero(first)
            assertEquals("Wrapping a backdrop must not fetch metadata", manualReads, fixture.requests.size)
            screen.onNodeWithTag(cardTag(first)).assertIsFocused()
            waitForHero(second)
            screen.onNodeWithTag(cardTag(first)).performClick()
            screen.waitUntil(3_000) { fixture.requests.any { it.first == "/playback/decision" } }
            val body = JSONObject(fixture.requests.first { it.first == "/playback/decision" }.second)
            assertEquals("Confirm still belongs to the focused poster, not the rotating backdrop",
                first.viewItemId, body.getInt("view_item_id"))
            assertFalse("The recent shelf never becomes the hero", heroExists(recent))
        }
    }

    @Test fun heroResumePlaysTheRotatedWorkWhileTheOriginalPosterKeepsFocus() {
        withFixture(AppState(loggedIn = true, continueItems = listOf(first, second),
            libraries = listOf(Library(7, "합성 영화", "movie")))) { fixture ->
            renderHome(fixture)
            focusCard(first)
            waitForHero(second)
            screen.onNodeWithTag(cardTag(first)).assertIsFocused()
            assertTrue("Rotation does not start playback",
                fixture.requests.none { it.first == "/playback/decision" })

            val resume = screen.onNodeWithTag("cinema-hero-resume").assertIsDisplayed()
            resume.performSemanticsAction(SemanticsActions.RequestFocus) { request ->
                assertTrue("The hero action supports the TV remote", request())
            }
            screen.mainClock.advanceTimeBy(CONTINUE_BACKDROP_INTERVAL_MS + 600)
            screen.waitForIdle()
            assertTrue("Focusing the hero action holds the work that will play", heroExists(second))
            resume.assertIsFocused().performClick()

            screen.waitUntil(3_000) { fixture.requests.any { it.first == "/playback/decision" } }
            val decisions = fixture.requests.filter { it.first == "/playback/decision" }
            assertEquals("One deliberate hero click makes one playback request", 1, decisions.size)
            val body = JSONObject(decisions.single().second)
            assertEquals("The hero action uses the visible work, not the formerly focused poster",
                second.viewItemId, body.getInt("view_item_id"))
            assertEquals(second.id, body.getInt("metadata_item_id"))
            assertEquals(second.libraryId, body.getInt("library_view_id"))
        }
    }

    @Test fun lockingTheActualTvHeroImmediatelyRemovesPrivateArtworkAndRotationDestinations() {
        withFixture(AppState(loggedIn = true, spoilerUnlocked = true,
            continueItems = listOf(hidden, first), libraries = listOf(Library(7, "합성 영화", "movie"),
                Library(8, "합성 비공개", "movie", true)))) { fixture ->
            renderHome(fixture)
            assertTrue(heroExists(hidden))
            screen.runOnIdle { fixture.state.value = fixture.state.value.copy(spoilerUnlocked = false) }
            screen.onNodeWithTag(cardTag(hidden)).assertDoesNotExist()
            assertTrue(heroExists(first))
            // The old private input remains present to model a refresh racing with a lock.
            Thread.sleep(CONTINUE_BACKDROP_INTERVAL_MS + 250)
            screen.waitForIdle()
            assertTrue(heroExists(first))
            screen.onNodeWithText(hidden.title).assertDoesNotExist()
            assertTrue("Idle rotation needs no server calls", fixture.requests.isEmpty())
            val section = screen.onNodeWithTag("tv-continue-section").fetchSemanticsNode().boundsInRoot
            val hero = screen.onNodeWithTag("tv-continue-hero").fetchSemanticsNode().boundsInRoot
            val poster = screen.onNodeWithTag(cardTag(first)).fetchSemanticsNode().boundsInRoot
            assertTrue("The backdrop section includes the selected hero", section.top <= hero.top && section.bottom > hero.bottom)
            assertTrue("The backdrop continues beneath the poster row", section.bottom > poster.bottom)
            saveScreenshot("tv-continue-section", "tv-continue-vivid.png")
        }
    }

    @Suppress("DEPRECATION")
    @Test fun sidebarSelectionAndFocusUseTheSameSingleHighlight() {
        guardFixture()
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        screen.setContent {
            inputMode = LocalInputModeManager.current
            MaterialTheme(colorScheme = CinemaHomeColors, typography = CompactTypography, shapes = CompactShapes) {
                Surface {
                    TvSidebar(onLibraries = {}, onRefresh = {}, onLogout = {}, libraries = emptyList(),
                        homeSelected = true, searchSelected = false)
                }
            }
        }
        screen.waitForIdle()
        val home = screen.onNodeWithText("홈")
        val before = sampleHighlight(home)
        screen.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        home.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        screen.waitForIdle()
        val focusedSelected = sampleHighlight(home)
        val search = screen.onNodeWithTag("sidebar-search")
        search.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        screen.waitForIdle()
        val focused = sampleHighlight(search)
        assertEquals("Focus must not add a second color over selection", before, focusedSelected)
        assertEquals("Selected and focused menu rows use the same highlight", before, focused)
        saveScreenshot("tv-sidebar", "tv-sidebar-single-highlight.png")
    }

    private fun sampleHighlight(node: SemanticsNodeInteraction): Int {
        val bitmap = node.captureToImage().asAndroidBitmap()
        return try { bitmap.getPixel(bitmap.width - 3, bitmap.height / 2) } finally { bitmap.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun renderHome(fixture: Fixture, style: MutableState<CinemaStyle>? = null,
        background: MutableState<Int?>? = null) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        screen.setContent {
            inputMode = LocalInputModeManager.current
            val selectedStyle = style?.value ?: CinemaStyle.CINEMA
            val colors = cinemaColorScheme(Color(background?.value ?: selectedStyle.defaultBackgroundArgb), selectedStyle)
            CompositionLocalProvider(LocalCinemaStyle provides selectedStyle) {
            MaterialTheme(colorScheme = colors, typography = CompactTypography, shapes = CompactShapes) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) { TvHome(fixture.state.value, fixture.model, {}) }
                }
            }
            }
        }
        screen.waitForIdle()
    }

    @Suppress("DEPRECATION")
    private fun renderFocusedHome(fixture: Fixture, style: MutableState<CinemaStyle>? = null,
        televisionCanvas: Boolean = false) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        val tvContext = base.createConfigurationContext(configuration)
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        screen.setContent {
            inputMode = LocalInputModeManager.current
            val selectedStyle = style?.value ?: CinemaStyle.CINEMA
            val home: @Composable () -> Unit = {
              CompositionLocalProvider(LocalContext provides tvContext, LocalCinemaStyle provides selectedStyle) {
                MaterialTheme(colorScheme = cinemaColorScheme(Color(selectedStyle.defaultBackgroundArgb), selectedStyle),
                    typography = CompactTypography, shapes = CompactShapes) {
                    // Production ShyniApp's Scaffold supplies onBackground as LocalContentColor.
                    // MaterialTheme alone does not; mirror the real content surface for TV captions.
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground) {
                        TvSidebarDrawer(screenKey = "home", sidebar = { close ->
                            TvAction("라이브러리", Modifier.testTag("fixture-sidebar-action"), onClick = close)
                        }) { TvHome(fixture.state.value, fixture.model, {}) }
                    }
                }
              }
            }
            if (televisionCanvas) BoxWithConstraints(Modifier.fillMaxSize()) {
                val physicalDensity = LocalDensity.current
                val fit = minOf(maxWidth.value / 960f, maxHeight.value / 540f).coerceAtLeast(.01f)
                val fittedDensity = physicalDensity.density * fit
                SideEffect { fixture.pixelsPerDp = fittedDensity }
                CompositionLocalProvider(LocalDensity provides Density(fittedDensity, physicalDensity.fontScale)) {
                    Box(Modifier.requiredSize(960.dp, 540.dp).testTag("tv-home-style-fixture")) { home() }
                }
            } else {
                val density = LocalDensity.current.density
                SideEffect { fixture.pixelsPerDp = density }
                home()
            }
        }
        screen.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        screen.waitForIdle()
    }

    private fun focusCard(item: MediaItem) {
        screen.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        screen.onNodeWithTag(cardTag(item)).performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        screen.waitForIdle()
        screen.onNodeWithTag(cardTag(item)).assertIsFocused()
    }

    private fun waitForHero(item: MediaItem) {
        screen.waitUntil(CONTINUE_BACKDROP_INTERVAL_MS + 3_000) { heroExists(item) }
        assertTrue(heroExists(item))
    }

    private fun heroExists(item: MediaItem): Boolean = screen.onAllNodes(
        hasText(item.title) and !hasAnyAncestor(hasTestTag(cardTag(item))), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    private fun cardTag(item: MediaItem) = "tv-media-${item.libraryId}-${item.id}"

    private fun saveScreenshot(tag: String, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, name)
        val bitmap = screen.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        instrumentation.sendStatus(0, Bundle().apply { putString("tv_continue_preview", file.absolutePath) })
    }

    private fun guardFixture() {
        assumeTrue("Only the isolated QA app may construct the TV fixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName == "io.mirr.shyniflex.spoilerqa")
    }

    private class Fixture(val model: AppViewModel, val state: MutableState<AppState>,
        val requests: CopyOnWriteArrayList<Pair<String, String>>) {
        var pixelsPerDp: Float = 1f
    }

    private fun withFixture(initial: AppState, block: (Fixture) -> Unit) {
        guardFixture()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = FixtureApplication(instrumentation.targetContext.applicationContext)
        val store = ViewModelStore()
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val transport = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw IOException("Fixture DNS is forbidden")
        }).addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            requests += request.url.encodedPath to buffer.readUtf8()
            // Never call proceed: the test cannot contact any account or production server.
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(500).message("synthetic")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val generated = mutableListOf<File>()
        lateinit var model: AppViewModel
        try {
            val publicArt = File(application.cacheDir, "tv-continue-${UUID.randomUUID()}.png")
            generated += publicArt
            val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                bitmap.setPixel(x, y, AndroidColor.rgb(30 + x, 90 + y, 190))
            try { publicArt.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            val state = mutableStateOf(initial.copy(continueItems = initial.continueItems.map {
                it.copy(backdrop = publicArt.toURI().toString())
            }))
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(AppViewModel(application))!!
                })[AppViewModel::class.java]
                AppViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }
                    .set(model, ShyniClient("https://synthetic.invalid", "synthetic-token", transport, "synthetic-device"))
                @Suppress("UNCHECKED_CAST")
                val mutable = AppViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
                    .get(model) as MutableStateFlow<AppState>
                mutable.value = state.value
            }
            block(Fixture(model, state, requests))
        } finally {
            instrumentation.runOnMainSync { store.clear(); application.clearFixturePreferences() }
            generated.forEach { it.delete() }
            transport.dispatcher.executorService.shutdownNow()
            transport.connectionPool.evictAll()
        }
    }

    private class FixtureApplication(private val parent: Context) : Application() {
        private val prefix = "tv_continue_${UUID.randomUUID()}_"
        private val names = linkedSetOf<String>()
        init {
            attachBaseContext(object : ContextWrapper(parent) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val isolated = prefix + name
                    names += isolated
                    return parent.getSharedPreferences(isolated, mode)
                }
            })
        }
        fun clearFixturePreferences() { names.forEach { parent.deleteSharedPreferences(it) } }
    }
}
