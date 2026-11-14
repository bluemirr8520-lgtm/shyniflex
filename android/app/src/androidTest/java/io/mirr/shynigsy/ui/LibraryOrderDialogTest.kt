package io.mirr.shynigsy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.applyLibraryOrder
import io.mirr.shynigsy.data.moveVisibleLibraryOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryOrderDialogTest {
    @get:Rule val screen = createComposeRule()
    private val catalogue = listOf(Library(1, "영화", "movie"), Library(90, "숨겨진 이름", "tv", true),
        Library(2, "드라마", "tv"), Library(3, "예능", "tv"))

    @Test fun movingPublicRowsDoesNotRevealLockedNamesAndResetUsesServerOrder() {
        val saved = mutableStateOf(listOf(1, 90, 2, 3))
        screen.setContent {
            MaterialTheme {
                val ordered = applyLibraryOrder(catalogue, saved.value)
                LibraryOrderDialog(ordered, spoilerUnlocked = false, hasCustomOrder = saved.value.isNotEmpty(),
                    onMove = { id, direction -> saved.value = moveVisibleLibraryOrder(saved.value,
                        ordered.filterNot { it.isSpoiler }.map { it.id }, id, direction) },
                    onReset = { saved.value = emptyList() }, onDismiss = {}, television = false)
            }
        }
        screen.onNodeWithText("숨겨진 이름").assertDoesNotExist()
        screen.onNodeWithTag("library-order-up-1").assertIsNotEnabled()
        screen.onNodeWithTag("library-order-down-3").assertIsNotEnabled()
        screen.onNodeWithTag("library-order-up-3").performClick()
        screen.runOnIdle { assertEquals(listOf(1, 90, 3, 2), saved.value) }
        screen.onNodeWithTag("library-order-reset").performClick()
        screen.runOnIdle { assertEquals(emptyList<Int>(), saved.value) }
        screen.onNodeWithTag("library-order-reset").assertIsNotEnabled()
    }

    @Test fun tvFocusStaysOnMovedRowWhenItReachesTheEdge() {
        val saved = mutableStateOf(emptyList<Int>())
        screen.setContent {
            MaterialTheme {
                val ordered = applyLibraryOrder(catalogue.filterNot { it.isSpoiler }, saved.value)
                LibraryOrderDialog(ordered, spoilerUnlocked = false, hasCustomOrder = saved.value.isNotEmpty(),
                    onMove = { id, direction -> saved.value = moveVisibleLibraryOrder(saved.value, ordered.map { it.id }, id, direction) },
                    onReset = {}, onDismiss = {}, television = true)
            }
        }
        screen.onNodeWithTag("library-order-up-2")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        screen.waitForIdle()
        screen.onNodeWithTag("library-order-up-2").assertIsNotEnabled()
        screen.onNodeWithTag("library-order-down-2").assertIsFocused()
        screen.onNodeWithTag("library-order-reset").assertIsDisplayed()
        screen.onNodeWithTag("library-order-close").performClick()
    }
}
