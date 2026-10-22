package io.mirr.shynigsy.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.data.MediaItem

/** Compact legacy-style episode navigation; the caller owns playback and watch-state writes. */
@Composable
internal fun CompactEpisodeRow(
    item: MediaItem,
    busy: Boolean,
    onOpen: (MediaItem) -> Unit,
    onWatchOptions: (MediaItem) -> Unit,
    artwork: @Composable (MediaItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    onWatchedChange: ((MediaItem, Boolean) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    val accent = MaterialTheme.colorScheme.primary
    val progress = if (!item.watched && item.resumeMs > 0 && item.durationMs > 0)
        (item.resumeMs.toDouble() / item.durationMs).coerceIn(0.0, 1.0).toFloat() else null
    val canChange = canChangeWatchState(item)
    Row(modifier.fillMaxWidth().heightIn(min = 80.dp)
        .onFocusChanged { focused = it.hasFocus }
        .onPreviewKeyEvent { event ->
            if (!busy && canChange && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) onWatchOptions(item)
                true
            } else false
        }
        .clip(shape)
        .background(if (focused) accent.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceContainer)
        .border(2.dp, if (focused) accent else Color.Transparent, shape)
        .combinedClickable(enabled = !busy, onClick = { onOpen(item) },
            onLongClickLabel = "시청 상태 변경",
            onLongClick = if (canChange) ({ onWatchOptions(item) }) else null)
        .testTag("compact-episode-${compactMediaIdentity(item)}")
        .padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(shape)
            .testTag("compact-episode-art-${compactMediaIdentity(item)}")) {
            artwork(item, Modifier.fillMaxSize())
            if (progress != null) LinearProgressIndicator(progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val metadata = listOfNotNull(episodeLabel(item),
                item.durationMs.takeIf { it > 0 }?.let(::timeLabel)).joinToString(" · ")
            Text(metadata, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!item.watched && item.resumeMs > 0) Text("${timeLabel(item.resumeMs)}까지 시청",
                fontSize = 11.sp, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (canChange && onWatchedChange != null) Column(Modifier.width(54.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (item.watched) "보았음" else "보지 않음", fontSize = 10.sp,
                color = if (item.watched) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = item.watched, enabled = !busy,
                onCheckedChange = { onWatchedChange(item, it) },
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("compact-episode-watch-${compactMediaIdentity(item)}")
                    .semantics { contentDescription = "${item.title} 시청 상태" })
        } else if (item.watched) Text("보았음", fontSize = 11.sp, color = accent)
    }
}

internal fun compactMediaIdentity(item: MediaItem): String =
    "${item.libraryId}:${item.type}:${item.id}:${item.viewItemId}:${item.isSpoiler}"
