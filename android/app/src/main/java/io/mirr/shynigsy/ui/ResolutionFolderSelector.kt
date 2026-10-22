package io.mirr.shynigsy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("해상도", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ResolutionFolderButton("자동", "auto", selected == null, enabled) { onSelect(null) }
            folders.forEach { folder ->
                val current = folder.versions.any { it.partId == selected }
                ResolutionFolderButton(folder.label, folder.key, current, enabled) {
                    onSelect(if (current) selected else folder.versions.first().partId)
                }
            }
        }
    }
}

@Composable
private fun ResolutionFolderButton(label: String, key: String, current: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.tvOnlyFocus().height(40.dp).testTag("resolution-folder-$key").semantics { selected = current },
        shape = RoundedCornerShape(3.dp), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
        border = BorderStroke(if (current) 2.dp else 1.dp,
            if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
        Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 13.sp, maxLines = 1)
        if (current) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Check, null, Modifier.size(14.dp))
        }
    }
}
