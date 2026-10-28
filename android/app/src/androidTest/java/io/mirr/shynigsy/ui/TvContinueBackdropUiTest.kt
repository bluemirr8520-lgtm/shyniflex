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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
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
    private fun renderHome(fixture: Fixture) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        screen.setContent {
            inputMode = LocalInputModeManager.current
            MaterialTheme(colorScheme = CinemaHomeColors, typography = CompactTypography, shapes = CompactShapes) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) { TvHome(fixture.state.value, fixture.model, {}) }
                }
            }
        }
        screen.waitForIdle()
    }

    @Suppress("DEPRECATION")
    private fun renderFocusedHome(fixture: Fixture) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        val tvContext = base.createConfigurationContext(configuration)
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        screen.setContent {
            inputMode = LocalInputModeManager.current
            CompositionLocalProvider(LocalContext provides tvContext) {
                MaterialTheme(colorScheme = CinemaHomeColors, typography = CompactTypography, shapes = CompactShapes) {
                    TvSidebarDrawer(screenKey = "home", sidebar = { close ->
                        TvAction("라이브러리", Modifier.testTag("fixture-sidebar-action"), onClick = close)
                    }) { TvHome(fixture.state.value, fixture.model, {}) }
                }
            }
        }
        screen.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        screen.waitForIdle()
    }

    private fun focusCard(item: MediaItem) {
        screen.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        screen.onNodeWithTag(cardTag(item)).performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        screen.waitForIdle()
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
        val requests: CopyOnWriteArrayList<Pair<String, String>>)

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
