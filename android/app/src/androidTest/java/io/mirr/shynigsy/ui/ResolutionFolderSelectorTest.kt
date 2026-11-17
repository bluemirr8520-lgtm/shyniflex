package io.mirr.shynigsy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaVersion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResolutionFolderSelectorTest {
    @get:Rule val screen = createComposeRule()

    @Test fun choosingQualityUsesTheFirstServerCandidateWithoutAFileMenu() {
        val selected = mutableStateOf<Int?>(null)
        val choices = mutableListOf<Int?>()
        val versions = listOf(MediaVersion(92, "private-file-one.mkv", "4K", metadataItemId = 5),
            MediaVersion(90, "private-file-two.mkv", "1080p", metadataItemId = 6),
            MediaVersion(91, "private-file-three.mkv", "2160p", metadataItemId = 7))
        screen.setContent { MaterialTheme {
            ResolutionFolderSelector(versions, selected.value, true) { choices.add(it); selected.value = it }
        } }
        screen.onNodeWithText("해상도").assertTextEquals("해상도")
        screen.onNodeWithTag("resolution-folder-auto").assertDoesNotExist()
        screen.onNodeWithTag("resolution-folder-2160p").performClick().assertIsSelected()
        screen.runOnIdle { assertEquals(listOf(92), choices) }
        screen.onNodeWithText("재생 파일").assertDoesNotExist()
        screen.onNodeWithText("기본 파일").assertDoesNotExist()
        screen.onNodeWithText("private-file-one.mkv").assertDoesNotExist()
    }

    @Test fun selectingCurrentFolderKeepsExactPartWithoutAnAutomaticEntry() {
        val selected = mutableStateOf<Int?>(91)
        val choices = mutableListOf<Int?>()
        val versions = listOf(MediaVersion(90, "first", "1080p", metadataItemId = 456),
            MediaVersion(91, "selected", "1920x1080", metadataItemId = 789), MediaVersion(92, "UHD", "4K"))
        screen.setContent { MaterialTheme {
            ResolutionFolderSelector(versions, selected.value, true) { choices.add(it); selected.value = it }
        } }
        screen.onNodeWithTag("resolution-folder-1080p").assertIsSelected().performClick()
        screen.runOnIdle { assertEquals(listOf(91), choices) }
        screen.onNodeWithTag("resolution-folder-auto").assertDoesNotExist()
    }

    @Test fun disabledFoldersCannotStartSelection() {
        var calls = 0
        screen.setContent { MaterialTheme {
            ResolutionFolderSelector(listOf(MediaVersion(90, "first", "720p"), MediaVersion(91, "unknown")),
                null, false) { calls++ }
        } }
        screen.onNodeWithTag("resolution-folder-auto").assertDoesNotExist()
        screen.onNodeWithTag("resolution-folder-720p").assertIsNotEnabled()
        screen.onNodeWithTag("resolution-folder-unknown").assertIsNotEnabled()
        screen.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun aSingleResolutionHidesTheSelectorEvenForSeveralEncodes() {
        screen.setContent { MaterialTheme {
            ResolutionFolderSelector(listOf(MediaVersion(71, "first", "1080p"), MediaVersion(72, "second", "FHD")),
                null, true) { fail("Single-resolution selector must not be offered") }
        } }
        screen.onNodeWithText("해상도").assertDoesNotExist()
        screen.onNodeWithTag("resolution-folder-1080p").assertDoesNotExist()
        screen.onNodeWithTag("resolution-folder-auto").assertDoesNotExist()
    }

    @Suppress("DEPRECATION")
    @Test fun playbackDialogSelectsTheFirstServerCandidateWithTvFocus() {
        var selected: Int? = null
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        screen.setContent { MaterialTheme {
            ResolutionPlaybackDialog("테스트 작품", listOf(MediaVersion(92, "UHD", "4K"),
                MediaVersion(90, "FHD", "1080p"), MediaVersion(91, "alternate", "2160p")), true,
                onSelect = { selected = it }, onDismiss = {}, television = true)
        } }
        screen.waitUntil(5_000) {
            screen.onNodeWithTag("playback-resolution-2160p").fetchSemanticsNode().config[SemanticsProperties.Focused]
        }
        screen.onNodeWithTag("playback-resolution-2160p").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        screen.onNodeWithTag("playback-resolution-1080p").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        screen.onNodeWithTag("playback-resolution-2160p").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        screen.runOnIdle { assertEquals(92, selected) }
        screen.onNodeWithText("자동").assertDoesNotExist()
        screen.onNodeWithText("alternate").assertDoesNotExist()
    }

    @Test fun playbackDialogCanBeCancelledWithoutSelectingAFile() {
        var cancelled = false
        screen.setContent { MaterialTheme {
            ResolutionPlaybackDialog("테스트 작품", listOf(MediaVersion(90, "FHD", "1080p"), MediaVersion(91, "HD", "720p")), true,
                onSelect = { fail("Cancel must not start playback") }, onDismiss = { cancelled = true }, television = false)
        } }
        screen.onNodeWithText("취소").performClick()
        screen.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun playbackDialogIsAbsentForSingleUnknownResolution() {
        screen.setContent { MaterialTheme {
            ResolutionPlaybackDialog("테스트 작품", listOf(MediaVersion(71, "original.mkv")), true,
                onSelect = { fail("No choice is needed") }, onDismiss = {}, television = false)
        } }
        screen.onNodeWithTag("playback-resolution-dialog").assertDoesNotExist()
    }
}
