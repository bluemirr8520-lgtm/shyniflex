package io.mirr.shynigsy.ui

import android.view.KeyEvent
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic dialog callbacks and isolated preference files; no account, backend or player is used. */
@RunWith(AndroidJUnit4::class)
class CinemaAppearanceSettingsTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()
    private val applied = mutableListOf<Int>()
    private val styleApplied = mutableListOf<Pair<CinemaStyle, Map<CinemaStyle, Int>>>()
    private var dismissals = 0
    private var originalTouchMode = true
    private var changedInputMode = false

    @Before fun requireIsolatedSyntheticApplication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("Only the isolated application may run this dialog fixture",
            "io.mirr.shyniflex.spoilerqa", instrumentation.targetContext.packageName)
        instrumentation.runOnMainSync { originalTouchMode = screen.activity.window.decorView.isInTouchMode }
    }

    @Suppress("DEPRECATION")
    @After fun restoreOriginalInputMode() {
        if (changedInputMode) InstrumentationRegistry.getInstrumentation().setInTouchMode(originalTouchMode)
    }

    @Test fun tvCanAdjustAllChannelsAndLeaveForApplyOrCancelWithoutAccidentalSaving() {
        render(0xFF102030.toInt(), television = true)
        focus(RED)
        key(RED, Key.DirectionRight)
        channel(RED, 17)
        key(RED, Key.DirectionDown)
        key(GREEN, Key.DirectionRight)
        channel(GREEN, 33)
        key(GREEN, Key.DirectionDown)
        key(BLUE, Key.DirectionRight)
        channel(BLUE, 49)
        key(BLUE, Key.DirectionDown)
        key(APPLY, Key.DirectionDown)
        screen.onNodeWithTag(CANCEL).assertIsFocused()
        key(CANCEL, Key.DirectionUp)
        key(APPLY, Key.DirectionUp)
        screen.onNodeWithTag(BLUE).assertIsFocused()
        key(BLUE, Key.DirectionCenter)
        screen.onNodeWithTag(APPLY).assertIsFocused()
        screen.runOnIdle { assertTrue("The opening confirm release must not save", applied.isEmpty()) }
        key(APPLY, Key.DirectionCenter)
        screen.runOnIdle { assertEquals(listOf(0xFF112131.toInt()), applied); assertEquals(0, dismissals) }
    }

    @Test fun tvChannelLimitsStillAllowNavigationAndBackDiscardsTheDraft() {
        render(0xFF00FF00.toInt(), television = true)
        focus(RED)
        key(RED, Key.DirectionLeft)
        channel(RED, 0)
        key(RED, Key.DirectionUp)
        screen.onNodeWithTag(EDIT).assertIsFocused()
        focus(GREEN)
        key(GREEN, Key.DirectionRight)
        channel(GREEN, 255)
        key(GREEN, Key.DirectionDown)
        key(BLUE, Key.DirectionLeft)
        channel(BLUE, 0)
        key(BLUE, Key.DirectionCenter)
        key(APPLY, Key.DirectionDown)
        screen.onNodeWithTag(CANCEL).assertIsFocused()
        sendBack()
        screen.onNodeWithTag(SETTINGS).assertDoesNotExist()
        screen.runOnIdle { assertTrue(applied.isEmpty()); assertEquals(1, dismissals) }
    }

    @Test fun tvExplicitHexEditingRestoresFocusAndCancelOrBackDoesNotReplaceTheDraft() {
        render(0xFF102030.toInt(), television = true)
        screen.onNodeWithTag(HEX).assertDoesNotExist()
        screen.onNodeWithTag(EDIT).performScrollTo().performClick()
        screen.onNodeWithTag(HEX).performTextReplacement("ABCDEF")
        screen.onNodeWithTag(HEX).performImeAction()
        screen.onNodeWithTag(HEX).assertDoesNotExist()
        screen.onNodeWithTag(EDIT).assertIsFocused()
        assertHexChannels()
        screen.runOnIdle { assertTrue("Editor Done only changes the unsaved draft", applied.isEmpty()) }

        screen.onNodeWithTag(EDIT).performClick()
        screen.onNodeWithTag(HEX).performTextReplacement("000000")
        screen.onNodeWithTag(HEX_CANCEL).performClick()
        screen.onNodeWithTag(EDIT).assertIsFocused()
        assertHexChannels()

        screen.onNodeWithTag(EDIT).performClick()
        screen.onNodeWithTag(HEX).performTextReplacement("FFFFFF")
        // If a software keyboard is visible, the first Back belongs to the IME.
        repeat(2) { if (screen.onAllNodesWithTag(HEX).fetchSemanticsNodes().isNotEmpty()) sendBack() }
        screen.onNodeWithTag(HEX).assertDoesNotExist()
        screen.onNodeWithTag(EDIT).assertIsFocused()
        assertHexChannels()
        screen.onNodeWithTag(CANCEL).performClick()
        screen.runOnIdle { assertTrue(applied.isEmpty()); assertEquals(1, dismissals) }
    }

    @Test fun mobileInvalidHexKeepsItsSizeTextAndCursorAndSavesOnlyAfterApply() {
        render(0xFF123456.toInt(), television = false)
        val field = screen.onNodeWithTag(HEX)
        field.performScrollTo()
        val fieldHeight = heightOf(field)
        val dialogHeight = heightOf(screen.onNodeWithTag(SETTINGS))
        field.performTextReplacement("12GG56")
        screen.onNodeWithTag(APPLY).assertIsNotEnabled()
        assertEquals("An error reserves the same input height", fieldHeight, heightOf(field), 1f)
        assertEquals("An error cannot remeasure the dialog's content height", dialogHeight,
            heightOf(screen.onNodeWithTag(SETTINGS)), 1f)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("12GG56")))

        field.performClick()
        field.performTextInputSelection(TextRange(2))
        field.performTextInput("0")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("120GG56")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        screen.runOnIdle { assertTrue(applied.isEmpty()); assertEquals(0, dismissals) }
        field.performTextReplacement("ABCDEF")
        field.performImeAction()
        field.assertIsNotFocused()
        screen.onNodeWithTag(APPLY).assertIsEnabled()
        screen.runOnIdle { assertTrue("Done is not a persistence operation", applied.isEmpty()) }
        screen.onNodeWithTag(APPLY).performClick()
        screen.runOnIdle { assertEquals(listOf(0xFFABCDEF.toInt()), applied) }
    }

    @Test fun styleDraftsKeepTheirOwnColorsAndResetOnlyTheSelectedStyleUntilApply() {
        val original = 0xFF123456.toInt()
        render(original, television = false)
        screen.onNodeWithTag("cinema-style-lounge").performScrollTo().performClick().assertIsSelected()
        screen.onNodeWithTag(HEX).performScrollTo().performTextReplacement("E6D9BA")
        screen.onNodeWithTag("cinema-style-ott").performScrollTo().performClick()
        screen.onNodeWithTag(HEX).performScrollTo().performTextReplacement("162B27")
        screen.onNodeWithTag("cinema-style-lounge").performScrollTo().performClick()
        screen.onNodeWithTag(HEX).performScrollTo().assertTextContains("E6D9BA")
        screen.onNodeWithTag("cinema-style-reset").performScrollTo().performClick()
        screen.onNodeWithTag("cinema-style-cinema").performScrollTo().performClick()
        screen.onNodeWithTag(HEX).performScrollTo().assertTextContains("123456")
        screen.onNodeWithTag("cinema-style-lounge").performScrollTo().performClick()
        screen.runOnIdle { assertTrue(styleApplied.isEmpty()); assertTrue(applied.isEmpty()) }
        screen.onNodeWithTag(APPLY).performClick()
        screen.runOnIdle {
            val (style, colors) = styleApplied.single()
            assertEquals(CinemaStyle.LOUNGE, style)
            assertEquals(original, colors[CinemaStyle.CINEMA])
            assertEquals(0xFF162B27.toInt(), colors[CinemaStyle.OTT])
            assertEquals(CinemaStyle.LOUNGE.defaultBackgroundArgb, colors[CinemaStyle.LOUNGE])
            assertEquals(listOf(CinemaStyle.LOUNGE.defaultBackgroundArgb), applied)
        }
    }

    @Test fun styleSelectionAndDefaultResetAreDiscardedByCancel() {
        render(0xFF123456.toInt(), television = true)
        screen.onNodeWithTag("cinema-style-cinema").assertIsFocused()
        key("cinema-style-cinema", Key.DirectionDown)
        key("cinema-style-ott", Key.DirectionDown)
        key("cinema-style-lounge", Key.DirectionCenter)
        key("cinema-style-lounge", Key.DirectionDown)
        screen.onNodeWithTag("cinema-style-reset").assertIsFocused()
        key("cinema-style-reset", Key.DirectionCenter)
        screen.onNodeWithTag(CANCEL).performClick()
        screen.runOnIdle { assertTrue(styleApplied.isEmpty()); assertTrue(applied.isEmpty()); assertEquals(1, dismissals) }
    }

    @Test fun legacyCinemaColorAndAllStyleColorsSurviveReopeningPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "cinema_style_migration_fixture"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            preferences.edit().clear().putInt("background_argb", 0xFF314159.toInt()).commit()
            val store = CinemaAppearancePreferences(preferences)
            assertEquals(0xFF314159.toInt(), store.read().backgrounds[CinemaStyle.CINEMA])
            assertEquals(CinemaStyle.CINEMA, store.read().style)
            store.apply(CinemaStyle.OTT, mapOf(CinemaStyle.OTT to 0xFF123456.toInt()))
            // The legacy color-only callback now saves the active OTT style, never CINEMA.
            store.saveCurrentBackground(0xFF345678.toInt())
            val recreated = CinemaAppearancePreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE))
            assertEquals(CinemaStyle.OTT, recreated.read().style)
            assertEquals(0xFF314159.toInt(), recreated.read().backgrounds[CinemaStyle.CINEMA])
            assertEquals(0xFF345678.toInt(), recreated.read().backgrounds[CinemaStyle.OTT])
            assertEquals(CinemaStyle.LOUNGE.defaultBackgroundArgb, recreated.read().backgrounds[CinemaStyle.LOUNGE])
            recreated.apply(CinemaStyle.CINEMA, emptyMap())
            assertEquals(0xFF314159.toInt(), recreated.read().backgroundArgb)
        } finally { preferences.edit().clear().commit(); context.deleteSharedPreferences(name) }
    }

    @Suppress("DEPRECATION")
    private fun render(initial: Int, television: Boolean) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(!television)
        changedInputMode = true
        screen.setContent {
            val open = remember { mutableStateOf(true) }
            val controller = CinemaStyleController(CinemaStyle.CINEMA,
                CinemaStyle.entries.associateWith { if (it == CinemaStyle.CINEMA) initial else it.defaultBackgroundArgb }) { style, colors ->
                styleApplied.add(style to colors)
            }
            CompositionLocalProvider(LocalCinemaStyleController provides controller) {
              MaterialTheme(colorScheme = CinemaHomeColors, typography = CompactTypography, shapes = CompactShapes) {
                Box(Modifier.fillMaxSize()) {
                    if (open.value) CinemaAppearanceSettings(initialBackground = initial,
                        onDismiss = { dismissals++; open.value = false },
                        onApply = { applied.add(it); open.value = false }, television = television)
                }
              }
            }
        }
        screen.waitForIdle()
    }

    private fun heightOf(node: SemanticsNodeInteraction): Float {
        val bounds = node.getUnclippedBoundsInRoot()
        return (bounds.bottom - bounds.top).value
    }

    private fun focus(tag: String) {
        screen.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) {
            assertTrue("The remote can focus $tag", it())
        }
        screen.waitForIdle()
        screen.onNodeWithTag(tag).assertIsFocused()
    }

    private fun key(tag: String, key: Key) {
        screen.onNodeWithTag(tag).assertIsFocused().performKeyInput { pressKey(key) }
        screen.waitForIdle()
    }

    private fun channel(tag: String, expected: Int) {
        screen.onNodeWithTag(tag).assert(SemanticsMatcher("announces channel value $expected") { node ->
            val description = node.config[SemanticsProperties.StateDescription]
            Regex("[0-9]+").find(description)?.value?.toIntOrNull() == expected
        })
    }

    private fun assertHexChannels() {
        channel(RED, 0xAB); channel(GREEN, 0xCD); channel(BLUE, 0xEF)
    }

    private fun sendBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        screen.waitForIdle()
    }

    private companion object {
        const val SETTINGS = "cinema-appearance-settings"
        const val RED = "cinema-rgb-red"
        const val GREEN = "cinema-rgb-green"
        const val BLUE = "cinema-rgb-blue"
        const val APPLY = "cinema-appearance-apply"
        const val CANCEL = "cinema-appearance-cancel"
        const val EDIT = "cinema-background-edit"
        const val HEX = "cinema-background-hex"
        const val HEX_CANCEL = "cinema-hex-cancel"
    }
}
