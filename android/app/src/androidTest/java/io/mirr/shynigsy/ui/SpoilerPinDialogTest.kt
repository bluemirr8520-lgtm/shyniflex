package io.mirr.shynigsy.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PIN callbacks and keyboard presentation only; no account, server, or preference is used. */
@RunWith(AndroidJUnit4::class)
class SpoilerPinDialogTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Before fun requireIsolatedSyntheticApplication() {
        assertEquals("Only the isolated QA application may render PIN fixtures",
            "io.mirr.shyniflex.spoilerqa", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test fun confirmButtonSubmitsOnePinClearsItsInputAndHidesTheKeyboard() {
        val fixture = Fixture()
        render(fixture)
        pin().performTextInput("1234")
        screen.onNodeWithTag("spoiler-unlock").assertIsEnabled().performClick()
        pin().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        pin().assertIsNotFocused()
        screen.onNodeWithTag("spoiler-unlock").assertIsNotEnabled()
        // A second IME action after the same submit must not send the cleared PIN.
        pin().performImeAction()
        screen.runOnIdle { assertEquals(listOf("1234"), fixture.requests); assertTrue(fixture.keyboard.hides >= 1) }
    }

    @Test fun keyboardDoneUsesTheSameOneShotSubmissionPath() {
        val fixture = Fixture()
        render(fixture)
        pin().performTextInput("5678")
        pin().performImeAction()
        pin().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        screen.onNodeWithTag("spoiler-unlock").assertIsNotEnabled()
        pin().performImeAction()
        screen.runOnIdle { assertEquals(listOf("5678"), fixture.requests); assertTrue(fixture.keyboard.hides >= 1) }
    }

    @Test fun busyFeedbackAndFailureRemainVisibleAndAllowAnExplicitRetry() {
        val fixture = Fixture().apply { becomeBusyOnSubmit = true }
        render(fixture, shortLargeText = true)
        pin().performScrollTo().performTextInput("1234")
        pin().performImeAction()
        screen.onNodeWithTag("spoiler-unlock").assertTextEquals("확인 중…").assertIsNotEnabled()
        screen.onNodeWithTag("spoiler-pin-progress").assertExists()
        pin().assertIsNotEnabled()
        screen.runOnIdle { fixture.busy = false; fixture.error = "합성 PIN 확인 오류" }
        screen.onNodeWithTag("spoiler-pin-error").performScrollTo().assertIsDisplayed().assertTextEquals("합성 PIN 확인 오류")
        screen.onNodeWithTag("spoiler-pin-progress").assertDoesNotExist()
        pin().performScrollTo().assertIsEnabled().performTextInput("4321")
        screen.onNodeWithTag("spoiler-unlock").performClick()
        screen.runOnIdle { assertEquals(listOf("1234", "4321"), fixture.requests) }
    }

    @Test fun cancelNeverSubmitsAndReopeningDoesNotRestoreThePin() {
        val fixture = Fixture()
        render(fixture)
        pin().performTextInput("1234")
        screen.onNodeWithTag("spoiler-pin-cancel").performClick()
        screen.onNodeWithTag("spoiler-pin-dialog").assertDoesNotExist()
        screen.runOnIdle { assertEquals(1, fixture.dismissals); assertTrue(fixture.requests.isEmpty()); fixture.open = true }
        pin().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        screen.onNodeWithTag("spoiler-unlock").assertIsNotEnabled()
    }

    private fun pin() = screen.onNodeWithTag("spoiler-pin")

    private fun render(fixture: Fixture, shortLargeText: Boolean = false) {
        screen.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { screenHeightDp = 240 }
            val density = Density(LocalDensity.current.density, fontScale = if (shortLargeText) 2f else LocalDensity.current.fontScale)
            CompositionLocalProvider(LocalSoftwareKeyboardController provides fixture.keyboard,
                LocalDensity provides density, LocalConfiguration provides
                    if (shortLargeText) configuration else LocalConfiguration.current) {
                MaterialTheme(colorScheme = CinemaHomeColors, typography = CompactTypography, shapes = CompactShapes) {
                    if (fixture.open) SpoilerPinDialog(fixture.busy, fixture.error,
                        onDismiss = { fixture.dismissals++; fixture.open = false },
                        onUnlock = { fixture.requests += it; if (fixture.becomeBusyOnSubmit) fixture.busy = true })
                }
            }
        }
    }

    private class Fixture {
        var open by mutableStateOf(true)
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var becomeBusyOnSubmit = false
        var dismissals = 0
        val requests = mutableListOf<String>()
        val keyboard = FakeKeyboard()
    }

    private class FakeKeyboard : SoftwareKeyboardController {
        var hides = 0
        override fun hide() { hides++ }
        override fun show() = Unit
    }
}
