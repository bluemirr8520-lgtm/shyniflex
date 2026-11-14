package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mirr.shynigsy.data.MediaFileInfo
import io.mirr.shynigsy.data.MediaFileSubtitle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileInformationSectionTest {
    @get:Rule val screen = createComposeRule()

    @Test fun missingSubtitleMetadataIsNotReportedAsNoSubtitles() {
        val file = mutableStateOf(MediaFileInfo(90))
        screen.setContent { MaterialTheme { FileInformationSection(listOf(file.value), false, null, {}) } }
        screen.onNodeWithTag("detail-file-subtitles-90", useUnmergedTree = true).assertExists()
        screen.onNodeWithText("없음").assertDoesNotExist()
        screen.runOnIdle { file.value = file.value.copy(subtitles = emptyList()) }
        screen.onNodeWithText("없음").assertExists()
    }

    @Test fun failedMetadataCanBeRetriedWithoutAPlayAction() {
        val loading = mutableStateOf(false)
        var retries = 0
        screen.setContent { MaterialTheme {
            FileInformationSection(emptyList(), loading.value, "error", { retries++; loading.value = true })
        } }
        screen.onNodeWithTag("detail-file-retry").performClick()
        screen.runOnIdle { assertEquals(1, retries) }
        screen.onNodeWithTag("detail-file-loading").assertExists()
        screen.onNodeWithTag("detail-file-retry").assertIsNotEnabled()
        screen.onNodeWithText("재생").assertDoesNotExist()
    }

    @Test fun separateFilesKeepTheirMetadataAndLongSubtitleListsExpandWithoutARequest() {
        val file = MediaFileInfo(90, fileName = "first.mkv", videoCodec = "hevc", resolution = "2160p",
            subtitles = (1..4).map { MediaFileSubtitle(language = "ko", format = "srt", title = "자막 트랙 $it") })
        var requests = 0
        screen.setContent { MaterialTheme {
            FileInformationSection(listOf(file, MediaFileInfo(91, fileName = "second.mp4", videoCodec = "h264", resolution = "1080p")),
                false, null, { requests++ })
        } }
        screen.onNodeWithText("first.mkv").assertExists()
        screen.onNodeWithText("second.mp4").assertExists()
        screen.onNodeWithTag("detail-file-subtitles-90-3").assertDoesNotExist()
        screen.onNodeWithTag("detail-file-subtitles-90-toggle").performClick()
        screen.onNodeWithTag("detail-file-subtitles-90-3").assertExists()
        screen.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun tvRemoteCanFocusMetadataRowsAndNetworkResultsDoNotMoveFocus() {
        val files = mutableStateOf(listOf(MediaFileInfo(90, videoCodec = "h264")))
        screen.setContent { MaterialTheme { Column {
            FileInformationSection(files.value, false, null, {}, modifier = Modifier, television = true)
        } } }
        screen.onNodeWithTag("detail-file-video-90").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
        screen.runOnIdle { files.value = files.value + MediaFileInfo(91, videoCodec = "hevc") }
        screen.onNodeWithTag("detail-file-video-90").assertIsFocused()
    }
}
