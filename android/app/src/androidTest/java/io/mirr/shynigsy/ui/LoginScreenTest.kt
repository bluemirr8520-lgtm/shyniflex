package io.mirr.shynigsy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
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
