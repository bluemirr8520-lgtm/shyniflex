package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.AppState
import io.mirr.shynigsy.AppViewModel
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaVersion
import io.mirr.shynigsy.isTelevision

@Composable
internal fun CompactDetailScreen(state: AppState, model: AppViewModel, onWatchOptions: (MediaItem) -> Unit) {
    val detail = state.detailStack.lastOrNull() ?: return
    CompactDetailContent(detail = detail, versions = state.versions, selectedPartId = state.selectedPartId,
        busy = state.busy, error = state.error, spoilerUnlocked = state.spoilerUnlocked,
        onPlay = { item, startOver -> model.play(item, startOver) }, onOpenItem = model::openItem,
        onSelectSeason = model::selectSeason, onSelectVersion = model::selectVersion,
        onWatchOptions = onWatchOptions, onWatchedChange = { item, watched -> model.setWatched(item, watched) },
        artwork = { item, modifier -> Poster(item, model, modifier,
            imageUrl = if (item.type == "episode") item.thumbnail ?: item.poster else item.poster) })
}

/** Callback-based surface also permits synthetic UI verification without accounts or a server. */
@Composable
internal fun CompactDetailContent(
    detail: MediaDetails,
    versions: List<MediaVersion>,
    selectedPartId: Int?,
    busy: Boolean,
    error: String?,
    spoilerUnlocked: Boolean,
    onPlay: (MediaItem, Boolean) -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    onSelectSeason: (MediaItem) -> Unit,
    onSelectVersion: (Int?) -> Unit,
    onWatchOptions: (MediaItem) -> Unit,
    onWatchedChange: (MediaItem, Boolean) -> Unit,
    artwork: @Composable (MediaItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = detail.item
    if (item.isSpoiler && !spoilerUnlocked) return
    val children = detail.children.filter { spoilerUnlocked || !it.isSpoiler }
    val seasons = detail.seasons.filter { spoilerUnlocked || !it.isSpoiler }
    val identity = compactMediaIdentity(item)
    val childKeys = children.map(::compactMediaIdentity)
    var focusedChildKey by rememberSaveable(identity) { mutableStateOf<String?>(null) }
    val restoredChild = focusedChildKey?.takeIf { it in childKeys } ?: childKeys.firstOrNull()
    val selectedSeason = seasons.firstOrNull { it.id == detail.selectedSeasonId } ?: seasons.firstOrNull()
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val seasonList = rememberLazyListState()
    val television = LocalContext.current.isTelevision()
    var focusRequested by remember(identity, detail.selectedSeasonId, childKeys) { mutableStateOf(false) }
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    val hasFocusTarget = item.playable || children.isNotEmpty() || seasons.isNotEmpty()
    LaunchedEffect(television, busy, hasFocusTarget, restoredChild, detail.selectedSeasonId) {
        if (busy) return@LaunchedEffect
        if (!television || !hasFocusTarget || focusRequested) return@LaunchedEffect
        val restoredIndex = childKeys.indexOf(restoredChild)
        // Keep the compact heading visible initially; restore deeper rows only when returning.
        if (!item.playable && restoredIndex > 0) {
            val headers = 1 + (if (seasons.isNotEmpty()) 1 else 0) + (if (error != null) 1 else 0)
            list.scrollToItem(headers + restoredIndex)
        } else list.scrollToItem(0)
        if (!item.playable && children.isEmpty() && selectedSeason != null)
            seasonList.scrollToItem(seasons.indexOf(selectedSeason))
        withFrameNanos { }
        focus.requestFocus()
        focusRequested = true
    }

    LazyColumn(modifier.fillMaxSize().testTag("compact-detail"), state = list,
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "heading") {
            if (item.playable) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                artwork(item, Modifier.width(if (item.type == "episode") 112.dp else 68.dp)
                    .aspectRatio(if (item.type == "episode") 16f / 9f else 2f / 3f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("compact-detail-title"))
                    Text(continueHeroMetadata(item), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!item.watched && item.resumeMs > 0) Text("${timeLabel(item.resumeMs)}까지 시청",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.title, Modifier.weight(1f).testTag("compact-detail-title"), fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (children.isNotEmpty()) Text("${children.size}회차", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (seasons.isNotEmpty()) item(key = "seasons") {
            LazyRow(state = seasonList, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("compact-seasons")) {
                items(seasons, key = ::compactMediaIdentity) { season ->
                    TvAction(season.title, modifier = Modifier
                        .then(if (!item.playable && children.isEmpty() && season == selectedSeason) Modifier.focusRequester(focus) else Modifier)
                        .testTag("compact-season-${compactMediaIdentity(season)}")
                        .semantics { selected = season.id == detail.selectedSeasonId },
                        enabled = !busy) { onSelectSeason(season) }
                }
            }
        }
        if (error != null) item(key = "error") {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.testTag("compact-detail-error"))
        }
        if (item.playable) {
            item(key = "play-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TvAction(if (item.watched) "다시 재생" else if (item.resumeMs > 0) "이어보기" else "재생",
                            modifier = Modifier.weight(1f).focusRequester(focus).testTag("compact-detail-play"),
                            enabled = !busy) { onPlay(item, item.watched) }
                        if (canChangeWatchState(item)) TvAction("시청 상태", Modifier.weight(1f).testTag("compact-detail-watch"),
                            enabled = !busy) { onWatchOptions(item) }
                    }
                    if (item.resumeMs > 0 && !item.watched) TvAction("처음부터 재생",
                        Modifier.fillMaxWidth().testTag("compact-detail-restart"), enabled = !busy) { onPlay(item, true) }
                }
            }
            if (versions.isNotEmpty()) item(key = "resolution") {
                ResolutionFolderSelector(versions, selectedPartId, !busy, onSelectVersion)
            }
        } else {
            items(children, key = ::compactMediaIdentity) { child ->
                CompactEpisodeRow(child, busy, onOpenItem, onWatchOptions, artwork,
                    modifier = Modifier.then(if (compactMediaIdentity(child) == restoredChild) Modifier.focusRequester(focus) else Modifier)
                        .onFocusChanged { if (it.hasFocus) focusedChildKey = compactMediaIdentity(child) },
                    onWatchedChange = onWatchedChange)
            }
            if (children.isEmpty()) item(key = "episode-status") {
                Text(if (busy) "회차를 불러오는 중입니다" else "표시할 회차가 없습니다", fontSize = 12.sp,
                    modifier = Modifier.testTag(if (busy) "compact-episodes-loading" else "compact-episodes-empty"))
            }
        }
        // Series descriptions follow the episodes instead of pushing the list below the fold.
        if (item.summary.isNotBlank()) item(key = "summary") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.summary, fontSize = 12.sp, maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("compact-detail-summary"))
                if (item.summary.length > 140) TvAction(if (expanded) "접기" else "줄거리 더 보기",
                    Modifier.testTag("compact-summary-toggle")) { expanded = !expanded }
            }
        }
    }
}
