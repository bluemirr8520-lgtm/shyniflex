package io.mirr.shynigsy.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** Synthetic dialog callbacks only: no preferences, account, backend or player is used. */
@RunWith(AndroidJUnit4::class)
class CinemaAppearanceSettingsTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()
    private val applied = mutableListOf<Int>()
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
        screen.onNodeWithTag(HEX).performTextReplacement("ABCDEF").performImeAction()
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
        val fieldHeight = field.getUnclippedBoundsInRoot().height.value
        val dialogHeight = screen.onNodeWithTag(SETTINGS).getUnclippedBoundsInRoot().height.value
        field.performTextReplacement("12GG56")
        screen.onNodeWithTag(APPLY).assertIsNotEnabled()
        assertEquals("An error reserves the same input height", fieldHeight, field.getUnclippedBoundsInRoot().height.value, 1f)
        assertEquals("An error cannot remeasure the dialog's content height", dialogHeight,
            screen.onNodeWithTag(SETTINGS).getUnclippedBoundsInRoot().height.value, 1f)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("12GG56")))

        field.performClick().performTextInputSelection(TextRange(2)).performTextInput("0")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("120GG56")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        screen.runOnIdle { assertTrue(applied.isEmpty()); assertEquals(0, dismissals) }
        field.performTextReplacement("ABCDEF").performImeAction()
        field.assertIsNotFocused()
        screen.onNodeWithTag(APPLY).assertIsEnabled()
        screen.runOnIdle { assertTrue("Done is not a persistence operation", applied.isEmpty()) }
        screen.onNodeWithTag(APPLY).performClick()
        screen.runOnIdle { assertEquals(listOf(0xFFABCDEF.toInt()), applied) }
    }

    @Suppress("DEPRECATION")
    private fun render(initial: Int, television: Boolean) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(!television)
        changedInputMode = true
        screen.setContent {
            val open = remember { mutableStateOf(true) }
            MaterialTheme(colorScheme = CinemaHomeColors, typography = CompactTypography, shapes = CompactShapes) {
                Box(Modifier.fillMaxSize()) {
                    if (open.value) CinemaAppearanceSettings(initialBackground = initial,
                        onDismiss = { dismissals++; open.value = false },
                        onApply = { applied.add(it); open.value = false }, television = television)
                }
            }
        }
        screen.waitForIdle()
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
