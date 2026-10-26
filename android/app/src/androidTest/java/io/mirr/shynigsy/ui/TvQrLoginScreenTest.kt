package io.mirr.shynigsy.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.TvQrLoginPhase
import io.mirr.shynigsy.TvQrLoginState
import io.mirr.shynigsy.data.tvLoginQrUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic callback fixtures only: no account, provider, server, preferences, or player is opened. */
@RunWith(AndroidJUnit4::class)
class TvQrLoginScreenTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Before fun requireIsolatedSyntheticApplication() {
        assertEquals("Only the isolated QA application may run the TV pairing fixture",
            "io.mirr.shyniflex.spoilerqa", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test fun firstLaunchStaysBlankAndQrRequiresAnExplicitRemoteAction() {
        val fixture = Fixture()
        render(fixture, initialServer = "")
        screen.onNodeWithTag("login_server").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("")))
        screen.onNodeWithTag("tv-login-qr-start").assertIsNotEnabled()
        screen.runOnIdle { assertTrue(fixture.starts.isEmpty()) }
        screen.onNodeWithTag("login_server").performTextInput(SERVER)
        reveal("tv-login-qr-start").assertIsEnabled().performClick()
        screen.runOnIdle { assertEquals(listOf(SERVER), fixture.starts) }
    }

    @Test fun manualModeCancelsPairingAndSubmitsOnlyTheEnteredCredentials() {
        val fixture = Fixture().apply { state = waiting() }
        render(fixture)
        reveal("tv-login-mode-manual").performClick()
        reveal("tv-login-user").performTextInput("synthetic-user")
        reveal("tv-login-password").performTextInput("synthetic-password")
        reveal("tv-login-manual-submit").assertIsEnabled().performClick()
        screen.onNodeWithTag("tv-login-qr-code").assertDoesNotExist()
        screen.runOnIdle {
            assertTrue(fixture.cancels >= 1)
            assertEquals(listOf(Triple(SERVER, "synthetic-user", "synthetic-password")), fixture.logins)
            assertTrue(fixture.starts.isEmpty())
        }
    }

    @Test fun changingTheServerDiscardsTheOldApprovalCode() {
        val fixture = Fixture().apply { state = waiting() }
        render(fixture)
        waitForQr()
        reveal("login_server").performTextReplacement("https://replacement.example.test")
        screen.onNodeWithTag("tv-login-qr-code").assertDoesNotExist()
        screen.runOnIdle { assertTrue(fixture.cancels >= 1); assertTrue(fixture.starts.isEmpty()) }
    }

    @Test fun expirationRemovesTheOldQrAndAllowsExplicitRegeneration() {
        val fixture = Fixture().apply { state = waiting() }
        render(fixture)
        waitForQr()
        screen.runOnIdle { fixture.state = waiting().copy(phase = TvQrLoginPhase.EXPIRED, remainingSeconds = 0) }
        screen.onNodeWithTag("tv-login-qr-code").assertDoesNotExist()
        reveal("tv-login-qr-start").assertIsEnabled().performClick()
        screen.runOnIdle { assertEquals(listOf(SERVER), fixture.starts) }
    }

    @Test fun leavingTheLoginScreenCancelsItsOutstandingApproval() {
        val fixture = Fixture().apply { state = waiting() }
        var shown by mutableStateOf(true)
        screen.setContent { Theme { if (shown) Content(fixture, SERVER) } }
        screen.runOnIdle { assertEquals(0, fixture.cancels); shown = false }
        screen.waitForIdle()
        screen.runOnIdle { assertEquals(1, fixture.cancels); assertTrue(fixture.logins.isEmpty()) }
    }

    @Test fun smallWindowAndLargeTextKeepTheManualSubmitReachable() {
        val fixture = Fixture()
        screen.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                Theme { Box(Modifier.width(360.dp).height(280.dp)) { Content(fixture, SERVER) } }
            }
        }
        reveal("tv-login-mode-manual").performClick()
        reveal("tv-login-user").performTextInput("synthetic-user")
        reveal("tv-login-password").performTextInput("synthetic-password")
        reveal("tv-login-manual-submit").assertIsDisplayed().performClick()
        screen.runOnIdle { assertEquals(1, fixture.logins.size) }
    }

    @Test fun externalServerChangeClearsUnsubmittedTvPassword() {
        val fixture = Fixture()
        var suppliedServer by mutableStateOf(SERVER)
        screen.setContent { Theme {
            Box(Modifier.width(640.dp).height(520.dp)) { Content(fixture, suppliedServer) }
        } }
        reveal("tv-login-mode-manual").performClick()
        reveal("tv-login-user").performTextInput("synthetic-user")
        reveal("tv-login-password").performTextInput("unsent-synthetic-password")
        reveal("tv-login-manual-submit").assertIsEnabled()
        screen.runOnIdle { suppliedServer = "https://replacement.example.test" }
        reveal("tv-login-password").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("")))
        reveal("tv-login-manual-submit").assertIsNotEnabled()
        screen.runOnIdle { assertTrue("Changing origins cannot send the old credential", fixture.logins.isEmpty()) }
    }

    private fun render(fixture: Fixture, initialServer: String = SERVER) {
        screen.setContent { Theme { Box(Modifier.width(640.dp).height(520.dp)) { Content(fixture, initialServer) } } }
        screen.waitForIdle()
    }

    @Composable private fun Content(fixture: Fixture, initialServer: String) {
        TvQrLoginScreen(initialServer = initialServer, initialUser = "", busy = false, error = null,
            qrState = fixture.state, onStartQr = { fixture.starts += it },
            onCancelQr = { fixture.cancels++; fixture.state = TvQrLoginState() },
            onLogin = { server, user, password -> fixture.logins += Triple(server, user, password) })
    }

    @Composable private fun Theme(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = CinemaHomeColors, typography = CompactTypography, shapes = CompactShapes, content = content)
    }

    private fun waitForQr() {
        reveal("tv-login-qr-panel")
        screen.waitUntil(5_000) { screen.onAllNodesWithTag("tv-login-qr-code").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun reveal(tag: String): SemanticsNodeInteraction {
        screen.onNodeWithTag("tv-login-scroll").performScrollToNode(hasTestTag(tag))
        return screen.onNodeWithTag(tag)
    }

    private class Fixture {
        var state by mutableStateOf(TvQrLoginState())
        var cancels = 0
        val starts = mutableListOf<String>()
        val logins = mutableListOf<Triple<String, String, String>>()
    }

    companion object {
        private const val SERVER = "https://server.example.test:8443"
        private fun waiting() = TvQrLoginState(phase = TvQrLoginPhase.WAITING,
            qrUrl = tvLoginQrUrl(SERVER, "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE"), remainingSeconds = 119)
    }
}
