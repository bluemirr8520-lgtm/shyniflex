package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mirr.shynigsy.data.MediaItem

/** Home keeps an explicit Continue Watching status even before the server supplies any rows. */
@Composable
internal fun HomeContinueWatchingSection(
    items: List<MediaItem>,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    spoilerUnlocked: Boolean,
    onResume: (MediaItem) -> Unit,
    onRetry: () -> Unit,
    poster: @Composable (MediaItem, Modifier) -> Unit,
    thumbnail: @Composable (MediaItem, Modifier) -> Unit = poster,
    backdrop: @Composable (MediaItem, Modifier) -> Unit = poster,
    onSelected: (MediaItem) -> Unit = {},
    selectedDetails: MediaItem? = null,
    onWatchOptions: (MediaItem) -> Unit = {},
) {
    val visible = items.filter { spoilerUnlocked || !it.isSpoiler }
    if (visible.isNotEmpty()) {
        ContinueWatchingSection(items = visible, busy = busy, onResume = onResume,
            poster = poster, thumbnail = thumbnail, backdrop = backdrop, onSelected = onSelected,
            selectedDetails = selectedDetails, onWatchOptions = onWatchOptions, spoilerUnlocked = spoilerUnlocked)
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
        .testTag("continue-watching-status"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("이어보기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("continue-watching-status-title"))
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("continue-watching-loading")) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("이어보기를 불러오는 중입니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error != null -> {
                // An upstream error can mention a private title or URL. Home only needs a safe retry message.
                Text("이어보기를 불러오지 못했습니다. 새로고침해 주세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("continue-watching-error"))
                OutlinedButton(onClick = onRetry, enabled = !busy,
                    modifier = Modifier.testTag("continue-watching-retry")) { Text("다시 시도") }
            }
            else -> Text("이어볼 영상이 없습니다. 시청 중인 영상이 여기에 표시됩니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("continue-watching-empty"))
        }
    }
}
