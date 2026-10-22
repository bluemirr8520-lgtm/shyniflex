package io.mirr.shynigsy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        screen.onNodeWithTag("resolution-folder-auto").assertIsSelected()
        screen.onNodeWithTag("resolution-folder-2160p").performClick().assertIsSelected()
        screen.runOnIdle { assertEquals(listOf(92), choices) }
        screen.onNodeWithText("재생 파일").assertDoesNotExist()
        screen.onNodeWithText("기본 파일").assertDoesNotExist()
        screen.onNodeWithText("private-file-one.mkv").assertDoesNotExist()
    }

    @Test fun selectingCurrentFolderKeepsExactPartAndAutomaticSelectionCanBeRestored() {
        val selected = mutableStateOf<Int?>(91)
        val choices = mutableListOf<Int?>()
        val versions = listOf(MediaVersion(90, "first", "1080p", metadataItemId = 456),
            MediaVersion(91, "selected", "1920x1080", metadataItemId = 789))
        screen.setContent { MaterialTheme {
            ResolutionFolderSelector(versions, selected.value, true) { choices.add(it); selected.value = it }
        } }
        screen.onNodeWithTag("resolution-folder-1080p").assertIsSelected().performClick()
        screen.runOnIdle { assertEquals(listOf(91), choices) }
        screen.onNodeWithTag("resolution-folder-auto").performClick().assertIsSelected()
        screen.runOnIdle { assertEquals(listOf(91, null), choices) }
    }

    @Test fun disabledFoldersAndAutomaticEntryCannotStartSelection() {
        var calls = 0
        screen.setContent { MaterialTheme {
            ResolutionFolderSelector(listOf(MediaVersion(90, "first", "720p"), MediaVersion(91, "unknown")),
                null, false) { calls++ }
        } }
        screen.onNodeWithTag("resolution-folder-auto").assertIsNotEnabled()
        screen.onNodeWithTag("resolution-folder-720p").assertIsNotEnabled()
        screen.onNodeWithTag("resolution-folder-unknown").assertIsNotEnabled()
        screen.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun unknownResolutionStillExposesItsOriginalCandidate() {
        val selected = mutableStateOf<Int?>(null)
        screen.setContent { MaterialTheme {
            ResolutionFolderSelector(listOf(MediaVersion(71, "원본 파일 1080", metadataItemId = 31)),
                selected.value, true) { selected.value = it }
        } }
        screen.onNodeWithTag("resolution-folder-unknown").performClick().assertIsSelected()
        screen.runOnIdle { assertEquals(71, selected.value) }
    }
}
