package io.mirr.shynigsy.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated form rendering: this never opens or clears the installed app's sessions. */
@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule val screen = createComposeRule()

    @Test fun firstLaunchServerFieldIsEmptyAndEditable() {
        render("")
        serverField().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        serverField().performTextInput("https://entered.example.test:8443")
        serverField().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,
            AnnotatedString("https://entered.example.test:8443")))
    }

    @Test fun existingCustomAddressRemainsVisible() {
        val address = "https://saved.example.test:8443"
        render(address)
        serverField().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(address)))
    }

    @Test fun externalServerChangeClearsUnsubmittedPhonePassword() {
        assertEquals("Only the isolated QA application may render the origin-change fixture",
            "io.mirr.shyniflex.spoilerqa", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        var suppliedServer by mutableStateOf("https://saved.example.test:8443")
        var logins = 0
        screen.setContent {
            val parent = LocalContext.current
            val phoneContext = parent.createConfigurationContext(Configuration(parent.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_NORMAL
            })
            CompositionLocalProvider(LocalContext provides phoneContext) {
                MaterialTheme {
                    LoginScreen(initialServer = suppliedServer, initialUser = "synthetic-user", busy = false,
                        error = null, totpChallenge = null, onLogin = { _, _, _ -> logins++ },
                        onTotp = {}, onCancelTotp = {})
                }
            }
        }
        screen.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("login_server")))
            .performScrollToNode(hasText("비밀번호"))
        screen.onNodeWithText("비밀번호").performTextInput("unsent-synthetic-password")
        screen.onNodeWithText("로그인").performScrollTo().assertIsEnabled()
        screen.runOnIdle { suppliedServer = "https://replacement.example.test" }
        screen.onNodeWithText("비밀번호").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("")))
        screen.onNodeWithText("로그인").assertIsNotEnabled()
        screen.runOnIdle { assertTrue("Changing origins cannot send the old credential", logins == 0) }
    }

    private fun serverField() = screen.onNodeWithTag("login_server")

    private fun render(address: String) {
        screen.setContent {
            MaterialTheme {
                LoginScreen(initialServer = address, initialUser = "", busy = false, error = null,
                    totpChallenge = null, onLogin = { _, _, _ -> }, onTotp = {}, onCancelTotp = {})
            }
        }
    }
}
