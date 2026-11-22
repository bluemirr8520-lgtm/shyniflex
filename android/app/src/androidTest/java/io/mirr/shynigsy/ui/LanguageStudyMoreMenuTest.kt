package io.mirr.shynigsy.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Callback-only home navigation: no saved preferences, account or playback is used. */
@RunWith(AndroidJUnit4::class)
class LanguageStudyMoreMenuTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeMoreClosesBeforeOpeningLanguageStudySettings() {
        var open by mutableStateOf(true)
        val calls = mutableListOf<String>()
        screen.setContent {
            MaterialTheme {
                if (open) MobileMoreMenu(spoilerUnlocked = false,
                    onDismiss = { calls += "dismiss"; open = false }, onRefresh = {}, onAppearance = {},
                    onUpdate = {}, onLogout = {}, onSpoilerAccess = {},
                    onLanguageStudy = { assertFalse(open); calls += "study" })
            }
        }
        screen.onNodeWithTag("mobile-more-actions").performScrollToNode(hasTestTag("mobile-more-study"))
        screen.onNodeWithTag("mobile-more-study").assertIsDisplayed().performClick()
        screen.runOnIdle { assertEquals(listOf("dismiss", "study"), calls) }
        screen.onNodeWithTag("mobile-more-sheet").assertDoesNotExist()
    }
}
