package io.mirr.shynigsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.data.MediaItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun LazyListScope.mobileDetailEpisodes(
    children: List<MediaItem>,
    busy: Boolean,
    onOpen: (MediaItem) -> Unit,
    onWatchOptions: (MediaItem) -> Unit,
    artwork: @Composable (MediaItem, Modifier) -> Unit,
) {
    val episodes = children.filter { it.type == "episode" }
    if (episodes.isEmpty()) return
    item(key = "mobile-episode-count", contentType = "episode-count") {
        Text("에피소드 ${episodes.size}개", Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            .testTag("mobile-episode-count"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
    items(episodes, key = ::compactMediaIdentity, contentType = { "episode" }) { episode ->
        MobileEpisodeRow(episode, busy, onOpen, onWatchOptions, artwork)
    }
}

@Composable
private fun MobileEpisodeRow(
    item: MediaItem,
    busy: Boolean,
    onOpen: (MediaItem) -> Unit,
    onWatchOptions: (MediaItem) -> Unit,
    artwork: @Composable (MediaItem, Modifier) -> Unit,
) {
    val identity = compactMediaIdentity(item)
    val config = LocalConfiguration.current
    val thumbWidth = (config.screenWidthDp * .303f).dp.coerceIn(80.dp, 160.dp)
    val locale = config.locales[0]
    val date = remember(item.releaseDate, locale) {
        item.releaseDate?.let { day ->
            runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }.getOrNull()
        }
    }
    val progress = item.resumeMs.takeIf { !item.watched && it > 0 }
    val watchDescription = when {
        item.watched -> "보았음"
        progress != null -> "${timeLabel(progress)}까지 시청"
        else -> null
    }
    val secondary = Color.White.copy(alpha = .62f)
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth()
            .combinedClickable(enabled = !busy, onClick = { onOpen(item) },
                onLongClickLabel = "시청 상태 변경", onLongClick = { onWatchOptions(item) })
            .semantics { if (watchDescription != null) stateDescription = watchDescription }
            .testTag("compact-episode-$identity")
            .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(thumbWidth).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                .testTag("compact-episode-art-$identity")) {
                artwork(item, Modifier.fillMaxSize())
                item.episodeNumber?.let { number ->
                    Text("E$number", Modifier.align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = .80f), RoundedCornerShape(bottomStart = 7.dp))
                        .padding(horizontal = 9.dp, vertical = 7.dp).testTag("mobile-episode-number-$identity"),
                        color = Color.White, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                }
                if (item.watched) WatchedBadge(Modifier.align(Alignment.BottomStart).padding(3.dp)
                    .testTag("compact-episode-watched-$identity"), compact = true)
                if (progress != null && item.durationMs > 0) LinearProgressIndicator(
                    progress = { (progress.toDouble() / item.durationMs).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (date != null) Text(date, Modifier.testTag("mobile-episode-date-$identity"),
                    color = secondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                if (item.summary.isNotBlank()) Text(item.summary, color = secondary, fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (progress != null && item.durationMs <= 0) Text(watchDescription.orEmpty(), color = secondary,
                    fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = .12f))
    }
}
