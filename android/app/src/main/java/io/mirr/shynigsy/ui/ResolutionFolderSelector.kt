package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.isTelevision
import io.mirr.shynigsy.data.MediaVersion
import io.mirr.shynigsy.data.resolutionFolders

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResolutionFolderSelector(
    versions: List<MediaVersion>,
    selected: Int?,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
) {
    val folders = remember(versions) { resolutionFolders(versions) }
    if (folders.size < 2) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("해상도", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            folders.forEach { folder ->
                val current = folder.versions.any { it.partId == selected }
                ResolutionFolderButton(folder.label, folder.key, current, enabled) {
                    onSelect(if (current) selected else folder.versions.first().partId)
                }
            }
        }
    }
}

/** Playback from a poster or an episode row asks only when there are distinct resolutions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResolutionPlaybackDialog(
    title: String,
    versions: List<MediaVersion>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    television: Boolean = LocalContext.current.isTelevision(),
) {
    val folders = remember(versions) { resolutionFolders(versions) }
    if (folders.size < 2) return
    val firstChoice = remember { FocusRequester() }
    var firstChoicePlaced by remember(folders) { mutableStateOf(false) }
    var initialFocusRequested by remember(folders) { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier.testTag("playback-resolution-dialog"),
        onDismissRequest = onDismiss,
        title = { Text("해상도 선택", style = MaterialTheme.typography.titleMedium) },
        text = {
            // A Dialog owns a separate window. The host frame can run before its focus
            // targets are attached, and touch mode can refuse focus on text buttons.
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(television, enabled, windowFocused, firstChoicePlaced, folders) {
                if (television && enabled && windowFocused && firstChoicePlaced && !initialFocusRequested) {
                    inputMode.requestInputMode(InputMode.Keyboard)
                    withFrameNanos { }
                    initialFocusRequested = firstChoice.requestFocus()
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    folders.forEachIndexed { index, folder ->
                        ResolutionFolderButton(folder.label, folder.key, false, enabled,
                            modifier = if (index == 0) Modifier.focusRequester(firstChoice)
                                .onGloballyPositioned { firstChoicePlaced = true } else Modifier,
                            tagPrefix = "playback-resolution") { onSelect(folder.versions.first().partId) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun ResolutionFolderButton(label: String, key: String, current: Boolean, enabled: Boolean,
    modifier: Modifier = Modifier, tagPrefix: String = "resolution-folder", onClick: () -> Unit) {
    CompactMenuAction(onClick = onClick, enabled = enabled, selected = current,
        modifier = modifier.testTag("$tagPrefix-$key").semantics { selected = current },
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) {
        Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 13.sp, maxLines = 1)
        if (current) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Check, null, Modifier.size(14.dp))
        }
    }
}
