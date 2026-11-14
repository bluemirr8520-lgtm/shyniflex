package io.mirr.shynigsy.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.data.MediaFileInfo
import io.mirr.shynigsy.isTelevision

/** Metadata is visible without another dialog; long track lists expand locally. */
@Composable
internal fun FileInformationSection(
    files: List<MediaFileInfo>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    television: Boolean = LocalContext.current.isTelevision(),
) {
    Column(modifier.fillMaxWidth().testTag("detail-file-information"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("파일 정보", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        }
        if (loading) Text("파일 정보를 불러오는 중입니다", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("detail-file-loading"))
        if (error != null) {
            Text("파일 정보를 불러오지 못했습니다.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("detail-file-error"))
            TvAction("다시 확인", Modifier.testTag("detail-file-retry"), enabled = !loading, onClick = onRetry)
        }
        if (!loading && error == null && files.isEmpty()) Text("표시할 파일 정보가 없습니다.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("detail-file-empty"))
        files.forEachIndexed { index, file ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FileInformation(file, index, television)
        }
    }
}

@Composable
private fun FileInformation(file: MediaFileInfo, index: Int, television: Boolean) {
    Column(Modifier.fillMaxWidth().testTag("detail-file-${file.partId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(mediaFileHeading(file, index), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().informationFocus(television).padding(vertical = 3.dp)
                .testTag("detail-file-name-${file.partId}").semantics { heading() })
        InformationRow("영상", mediaFileVideoLabel(file), television, "detail-file-video-${file.partId}")
        InformationRow("해상도", mediaFileResolutionLabel(file), television, "detail-file-resolution-${file.partId}")
        InformationRow("형식·크기", mediaFileFormatLabel(file), television, "detail-file-format-${file.partId}")
        InformationTracks("오디오", file.audioTracks?.map(::mediaFileAudioLabel),
            mediaFileAudioFallback(file), television, "detail-file-audio-${file.partId}")
        InformationTracks("자막", file.subtitles?.map(::mediaFileSubtitleLabel),
            UnknownFileInformation, television, "detail-file-subtitles-${file.partId}")
    }
}

@Composable
private fun InformationTracks(label: String, tracks: List<String>?, unknown: String, television: Boolean, key: String) {
    var expanded by remember(key, tracks) { mutableStateOf(false) }
    when {
        tracks == null -> InformationRow(label, unknown, television, key)
        tracks.isEmpty() -> InformationRow(label, "없음", television, key)
        else -> {
            val visible = if (expanded) tracks else tracks.take(3)
            visible.forEachIndexed { index, track ->
                InformationRow(if (index == 0) "$label ${tracks.size}개" else "$label ${index + 1}",
                    track, television, "$key-$index")
            }
            if (tracks.size > 3) TvAction(if (expanded) "$label 접기" else "$label ${tracks.size}개 모두 보기",
                Modifier.testTag("$key-toggle")) { expanded = !expanded }
        }
    }
}

@Composable
private fun InformationRow(label: String, value: String, television: Boolean, key: String) {
    Row(Modifier.fillMaxWidth().informationFocus(television).padding(vertical = 3.dp).testTag(key),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(64.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun Modifier.informationFocus(television: Boolean): Modifier =
    if (television) this.tvFocus().focusable() else this
