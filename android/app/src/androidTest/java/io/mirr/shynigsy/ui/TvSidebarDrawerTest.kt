package io.mirr.shynigsy.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Compose focus/key paths and synthetic sidebar callbacks; no account, server, or settings are used. */
@RunWith(AndroidJUnit4::class)
class TvSidebarDrawerTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Before fun requireIsolatedSyntheticApplication() {
        assertEquals("Only the isolated QA application may render TV drawer fixtures",
            "io.mirr.shyniflex.spoilerqa", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test fun hiddenDrawerKeepsOneFullWidthBodyCompositionAndLocalState() {
        val fixture = Fixture()
        render(fixture)
        screen.onNodeWithTag(DRAWER).assertDoesNotExist()
        screen.onNodeWithTag(FIRST).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        screen.onNodeWithTag("drawer-local-state").assertTextEquals("로컬 상태 1")
        val originalBody = bounds(BODY)
        val originalFixture = bounds("drawer-layout-fixture")
        assertEquals(originalFixture.width, originalBody.width, 1f)
        val initialCompositions = screen.runOnIdle { fixture.bodyCompositions }
        key(FIRST, Key.DirectionLeft)
        screen.onNodeWithTag(DRAWER).assertExists()
        assertSameBounds(originalBody, bounds(BODY))
        screen.onNodeWithTag("drawer-local-state").assertTextEquals("로컬 상태 1")
        screen.onNodeWithText("홈").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        screen.onNodeWithTag(DRAWER).assertDoesNotExist()
        screen.onNodeWithTag(FIRST).assertIsFocused()
        assertSameBounds(originalBody, bounds(BODY))
        screen.runOnIdle {
            assertEquals(1, fixture.bodyMounts)
            assertEquals(0, fixture.bodyDisposals)
            assertEquals("Opening the overlay cannot recompose the retained body slot", initialCompositions, fixture.bodyCompositions)
            assertEquals(listOf("body:first"), fixture.calls)
        }
    }

    @Test fun leftNavigatesInsideContentBeforeOpeningAndFencesTheHeldOpeningPress() {
        val fixture = Fixture().apply { includeCaret = true; outsideHeader = true }
        render(fixture)
        focus("drawer-caret")
        key("drawer-caret", Key.DirectionLeft)
        screen.onNodeWithTag("drawer-caret").assertIsFocused()
        screen.onNodeWithTag(DRAWER).assertDoesNotExist()
        screen.runOnIdle { assertEquals(1, fixture.caret.selection.start) }
        focus(SECOND)
        key(SECOND, Key.DirectionLeft)
        screen.onNodeWithTag(FIRST).assertIsFocused()
        screen.onNodeWithTag(DRAWER).assertDoesNotExist()
        val opening = SystemClock.uptimeMillis()
        nativeKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, opening)
        screen.onNodeWithTag(DRAWER).assertExists()
        screen.onNodeWithText("홈").assertIsFocused()
        nativeKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, opening, repeat = 1)
        nativeKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP, opening)
        screen.onNodeWithText("홈").assertIsFocused()
        screen.onNodeWithTag("drawer-outside-header").assertIsNotFocused()
        screen.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun rightBackAndScrimRestoreContentFocusWithoutLeakingKeys() {
        val fixture = Fixture().apply { grid = true }
        render(fixture)
        val restored = "drawer-grid-8"
        screen.onNodeWithTag("drawer-grid").performScrollToNode(hasTestTag(restored))
        focus(restored)
        key(restored, Key.DirectionLeft)
        screen.onNodeWithText("홈").assertIsFocused()
        val closing = SystemClock.uptimeMillis()
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, closing)
        screen.onNodeWithTag(DRAWER).assertDoesNotExist()
        screen.onNodeWithTag(restored).assertIsFocused()
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, closing, repeat = 1)
        nativeKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP, closing)
        screen.onNodeWithTag(restored).assertIsFocused()
        key(restored, Key.DirectionRight)
        screen.onNodeWithTag("drawer-grid-9").assertIsFocused()
        key("drawer-grid-9", Key.DirectionLeft)
        key(restored, Key.DirectionLeft)
        screen.onNodeWithText("홈").assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        screen.onNodeWithTag(DRAWER).assertDoesNotExist()
        screen.onNodeWithTag(restored).assertIsFocused()
        screen.runOnIdle { assertEquals("Drawer Back must outrank the retained screen handler", 0, fixture.bodyBacks) }
        key(restored, Key.DirectionLeft)
        screen.onNodeWithText("홈").assertIsFocused()
        screen.onNodeWithTag("tv-sidebar-drawer-scrim").performTouchInput { click() }
        screen.onNodeWithTag(DRAWER).assertDoesNotExist()
        screen.onNodeWithTag(restored).assertIsFocused()
        screen.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun sidebarSelectionsCloseAndEmptyDestinationsCanReopenWithoutPrivateTitles() {
        val fixture = Fixture().apply { empty = true; screenKey = "initial-empty" }
        render(fixture)
        screen.onNodeWithTag("tv-drawer-focus-fallback").assertIsFocused()
        key("tv-drawer-focus-fallback", Key.DirectionLeft)
        screen.onNodeWithText("비공개 라이브러리").assertDoesNotExist()
        screen.onNodeWithText("공개 라이브러리").performClick()
        screen.onNodeWithTag(DRAWER).assertDoesNotExist()
        screen.onNodeWithText("빈 라이브러리").assertIsDisplayed()
        screen.onNodeWithTag("tv-drawer-focus-fallback").assertIsFocused()
        key("tv-drawer-focus-fallback", Key.DirectionLeft)
        screen.onNodeWithText("비공개 라이브러리").assertDoesNotExist()
        screen.onNodeWithTag("sidebar-search").performClick()
        screen.onNodeWithTag(DRAWER).assertDoesNotExist()
        screen.onNodeWithTag(FIRST).assertIsFocused()
        screen.runOnIdle { fixture.unlocked = true }
        key(FIRST, Key.DirectionLeft)
        screen.onNodeWithText("비공개 라이브러리").assertIsDisplayed().performClick()
        screen.onNodeWithTag(DRAWER).assertDoesNotExist()
        screen.onNodeWithTag("tv-drawer-focus-fallback").assertIsFocused()
        screen.runOnIdle { assertEquals(listOf("library:1", "search", "library:2"), fixture.calls) }
    }

    private fun render(fixture: Fixture) {
        screen.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val fit = minOf(maxWidth.value / 960f, maxHeight.value / 540f).coerceAtLeast(.01f)
                CompositionLocalProvider(LocalDensity provides Density(density.density * fit)) {
                    MaterialTheme(colorScheme = CinemaHomeColors, typography = CompactTypography, shapes = CompactShapes) {
                        Box(Modifier.requiredSize(960.dp, 540.dp).testTag("drawer-layout-fixture")) {
                            if (fixture.outsideHeader) TextButton(onClick = { fixture.calls += "outside" },
                                modifier = Modifier.align(Alignment.CenterStart).width(24.dp).testTag("drawer-outside-header")) { Text("밖") }
                            TvSidebarDrawer(screenKey = fixture.screenKey, sidebar = { close ->
                                fun navigate(action: String, empty: Boolean = false) {
                                    close(); fixture.calls += action; fixture.empty = empty; fixture.screenKey = action
                                }
                                TvSidebar(onLibraries = { navigate("unlock") }, onRefresh = { navigate("refresh") },
                                    onLogout = { navigate("logout") }, onHome = { navigate("home") },
                                    onSearch = { navigate("search") }, onAppearance = { navigate("appearance") },
                                    onUpdate = { navigate("update") }, onLockSpoiler = { close(); fixture.unlocked = false },
                                    showLocked = !fixture.unlocked, spoilerUnlocked = fixture.unlocked,
                                    libraries = fixture.libraries.filter { fixture.unlocked || !it.isSpoiler },
                                    onLibrary = { navigate("library:${it.id}", empty = true) })
                            }) { DrawerBody(fixture) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DrawerBody(fixture: Fixture) {
        var localCounter by remember { mutableStateOf(0) }
        val first = remember { FocusRequester() }
        SideEffect { fixture.bodyCompositions++ }
        DisposableEffect(Unit) { fixture.bodyMounts++; onDispose { fixture.bodyDisposals++ } }
        LaunchedEffect(fixture.empty) { if (!fixture.empty) { withFrameNanos { }; first.requestFocus() } }
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 27.dp), verticalArrangement = Arrangement.Center) {
            if (fixture.empty) Text("빈 라이브러리") else if (fixture.grid) {
                // Match the real library's lazy grid shape, without adding a test-only focus restorer.
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().height(340.dp)
                    .testTag("drawer-grid"), horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0..11).toList(), key = { it }) { index ->
                        TextButton(onClick = { fixture.calls += "grid:$index" },
                            modifier = Modifier.fillMaxWidth().height(80.dp)
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .testTag("drawer-grid-$index")) { Text("작품 $index") }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TextButton(onClick = { localCounter++; fixture.calls += "body:first" },
                        modifier = Modifier.width(120.dp).height(64.dp).focusRequester(first).testTag(FIRST)) { Text("첫 작품") }
                    TextButton(onClick = { fixture.calls += "body:second" },
                        modifier = Modifier.width(120.dp).height(64.dp).testTag(SECOND)) { Text("다음 작품") }
                    if (fixture.includeCaret) BasicTextField(value = fixture.caret, onValueChange = { fixture.caret = it },
                        keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false),
                        modifier = Modifier.width(160.dp).height(64.dp).testTag("drawer-caret"))
                }
            }
            Text("로컬 상태 $localCounter", Modifier.testTag("drawer-local-state"))
        }
        BackHandler { fixture.bodyBacks++ }
    }

    private fun focus(tag: String) {
        screen.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        screen.onNodeWithTag(tag).assertIsFocused()
    }
    private fun key(tag: String, key: Key) = screen.onNodeWithTag(tag).assertIsFocused().performKeyInput { pressKey(key) }
    private fun nativeKey(key: Int, action: Int, downAt: Long, repeat: Int = 0) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeySync(KeyEvent(downAt, SystemClock.uptimeMillis(), action, key, repeat, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD))
        instrumentation.waitForIdleSync()
    }
    private fun bounds(tag: String): Rect = screen.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun assertSameBounds(before: Rect, after: Rect) {
        assertEquals(before.left, after.left, 1f); assertEquals(before.top, after.top, 1f)
        assertEquals(before.width, after.width, 1f); assertEquals(before.height, after.height, 1f)
    }

    private class Fixture {
        var screenKey by mutableStateOf("home")
        var empty by mutableStateOf(false)
        var unlocked by mutableStateOf(false)
        var caret by mutableStateOf(TextFieldValue("AB", selection = TextRange(2)))
        var includeCaret = false
        var outsideHeader = false
        var grid = false
        var bodyMounts = 0
        var bodyDisposals = 0
        var bodyCompositions = 0
        var bodyBacks = 0
        val calls = mutableListOf<String>()
        val libraries = listOf(Library(1, "공개 라이브러리", "show"), Library(2, "비공개 라이브러리", "movie", true))
    }
    private companion object {
        const val BODY = "tv-drawer-body"
        const val DRAWER = "tv-sidebar-drawer"
        const val FIRST = "drawer-body-first"
        const val SECOND = "drawer-body-second"
    }
}
