package io.mirr.shynigsy.ui

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.TvLoginApprovalState
import io.mirr.shynigsy.data.TvLoginLink
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Consent rendering and callbacks only: this fixture does not open an account or make requests. */
@RunWith(AndroidJUnit4::class)
class TvLoginApprovalDialogTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Before fun requireIsolatedSyntheticApplication() {
        assertEquals("Only the isolated QA application may run phone pairing confirmation",
            "io.mirr.shyniflex.spoilerqa", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test fun approvalRequiresAnExplicitClickAndShowsTheAccountAndTargetServer() {
        var approvals = 0
        screen.setContent { MaterialTheme {
            TvLoginApprovalDialog(state(), SERVER, "synthetic-user", { approvals++ }, {})
        } }
        screen.onNodeWithTag("tv-login-approval-server").assertTextEquals(SERVER)
        screen.onNodeWithText("로그인 계정: synthetic-user").assertExists()
        screen.runOnIdle { assertEquals(0, approvals) }
        screen.onNodeWithTag("tv-login-approve").assertIsEnabled().performClick()
        screen.runOnIdle { assertEquals(1, approvals) }
    }

    @Test fun aDifferentServerOrPortCannotApproveWithTheExistingAccount() {
        var accountServer by mutableStateOf("https://server.example.test:9443")
        var approvals = 0
        screen.setContent { MaterialTheme {
            TvLoginApprovalDialog(state(), accountServer, "synthetic-user", { approvals++ }, {})
        } }
        screen.onNodeWithTag("tv-login-approve").assertIsNotEnabled()
        screen.runOnIdle { assertEquals(0, approvals); accountServer = "https://SERVER.example.test:8443/" }
        screen.onNodeWithTag("tv-login-approve").assertIsEnabled()
        screen.runOnIdle { assertEquals("Changing a matching account presentation cannot approve by itself", 0, approvals) }
    }

    @Test fun anApprovedRequestHasOnlyCloseAndCannotBeApprovedAgain() {
        var approvals = 0
        var dismissals = 0
        screen.setContent { MaterialTheme {
            TvLoginApprovalDialog(state().copy(approved = true), SERVER, "synthetic-user", { approvals++ }, { dismissals++ })
        } }
        screen.onNodeWithTag("tv-login-approve").assertDoesNotExist()
        screen.onNodeWithTag("tv-login-approval-cancel").assertDoesNotExist()
        screen.onNodeWithText("TV 연결을 승인했습니다. TV 화면에서 로그인을 확인하세요.").assertExists()
        screen.onNodeWithTag("tv-login-approval-close").performClick()
        screen.runOnIdle { assertEquals(0, approvals); assertEquals(1, dismissals) }
    }

    @Test fun busyOrExpiredApprovalAndLoggedOutHintRemainCancelableWithoutApproval() {
        var current by mutableStateOf(state().copy(busy = true))
        var hint by mutableStateOf(false)
        var approvals = 0
        var dismissals = 0
        screen.setContent { MaterialTheme {
            if (hint) TvLoginPendingHint(current) { dismissals++ }
            else TvLoginApprovalDialog(current, SERVER, "synthetic-user", { approvals++ }, { dismissals++ })
        } }
        screen.onNodeWithTag("tv-login-approve").assertIsNotEnabled()
        screen.runOnIdle { current = current.copy(busy = false, expiresAtMs = SystemClock.elapsedRealtime() - 1) }
        screen.onNodeWithTag("tv-login-approve").assertIsNotEnabled()
        screen.onNodeWithTag("tv-login-approval-cancel").assertIsEnabled().performClick()
        screen.runOnIdle { assertEquals(1, dismissals); assertEquals(0, approvals); current = state(); hint = true }
        screen.onNodeWithTag("tv-login-pending-server").assertTextEquals(SERVER)
        screen.onNodeWithText("로그인 후 TV 연결을 승인하세요.").assertExists()
        screen.onNodeWithTag("tv-login-pending-cancel").performClick()
        screen.runOnIdle { assertEquals(2, dismissals); assertEquals(0, approvals) }
    }

    companion object {
        private const val SERVER = "https://server.example.test:8443"
        private fun state() = TvLoginApprovalState(
            TvLoginLink(SERVER, "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE"), SystemClock.elapsedRealtime() + 120_000L)
    }
}
