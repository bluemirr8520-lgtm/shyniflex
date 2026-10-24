package io.mirr.shynigsy.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.DetailDiscoveryState
import io.mirr.shynigsy.canDiscoverActorWorks
import io.mirr.shynigsy.data.CastMember
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.detailDiscoveryKey
import io.mirr.shynigsy.visibleDetailWorks

internal fun discoveryActorTarget(actor: CastMember) = "cast:${actor.key}"
internal fun discoveryWorkTarget(section: String, item: MediaItem) = "$section:${detailDiscoveryKey(item)}"

internal fun discoveryFocusTargets(parent: MediaItem, cast: List<CastMember>, frame: DetailDiscoveryState?, libraries: List<Library>,
    unlocked: Boolean): List<String> = cast.filter { canDiscoverActorWorks(it, parent, libraries) }.map(::discoveryActorTarget) +
    visibleDetailWorks(frame?.actorItems.orEmpty(), libraries, unlocked).map { discoveryWorkTarget("actor", it) } +
    visibleDetailWorks(frame?.relatedItems.orEmpty(), libraries, unlocked).map { discoveryWorkTarget("related", it) }

@Composable
internal fun DetailDiscoverySections(
    parent: MediaItem, castMembers: List<CastMember>, frame: DetailDiscoveryState?, libraries: List<Library>, spoilerUnlocked: Boolean,
    busy: Boolean, onActor: (CastMember) -> Unit, onOpen: (MediaItem) -> Unit,
    onWatchOptions: (MediaItem) -> Unit, onRetryRelated: () -> Unit,
    artwork: @Composable (MediaItem, Modifier) -> Unit,
    portrait: @Composable (CastMember, Modifier) -> Unit,
    restoreTarget: String? = null, requestFocus: Boolean = false, focus: FocusRequester? = null,
    onFocused: (String) -> Unit = {}, onRestoreDone: () -> Unit = {},
) {
    if (parent.isSpoiler && !spoilerUnlocked) return
    if (castMembers.isEmpty() && frame == null) return
    val cast = castMembers.distinctBy { it.key }
    val actorWorks = visibleDetailWorks(frame?.actorItems.orEmpty(), libraries, spoilerUnlocked)
    val related = visibleDetailWorks(frame?.relatedItems.orEmpty(), libraries, spoilerUnlocked)
    val targets = discoveryFocusTargets(parent, cast, frame, libraries, spoilerUnlocked)
    val target = restoreTarget?.takeIf { it in targets } ?: targets.firstOrNull()
    val castScroll = rememberLazyListState()
    val actorScroll = rememberLazyListState()
    val relatedScroll = rememberLazyListState()
    // Only Back restoration requests focus. Network arrivals never take it from Play/episodes.
    LaunchedEffect(requestFocus, target) {
        if (!requestFocus || target == null || focus == null) return@LaunchedEffect
        try {
            when {
                target.startsWith("cast:") -> castScroll.scrollToItem(cast.indexOfFirst { discoveryActorTarget(it) == target }.coerceAtLeast(0))
                target.startsWith("actor:") -> actorScroll.scrollToItem(actorWorks.indexOfFirst { discoveryWorkTarget("actor", it) == target }.coerceAtLeast(0))
                else -> relatedScroll.scrollToItem(related.indexOfFirst { discoveryWorkTarget("related", it) == target }.coerceAtLeast(0))
            }
            withFrameNanos { }
            focus.requestFocus()
        } finally { onRestoreDone() }
    }
    fun targetModifier(key: String): Modifier = Modifier
        .then(if (target == key && focus != null) Modifier.focusRequester(focus) else Modifier)
        .onFocusChanged { if (it.hasFocus) onFocused(key) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("detail-discovery")) {
        if (cast.isNotEmpty()) {
            Text("출연진", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            LazyRow(state = castScroll, modifier = Modifier.focusRestorer().focusGroup().testTag("detail-cast"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cast.size, key = { cast[it].key }) { index ->
                    val actor = cast[index]
                    val supported = canDiscoverActorWorks(actor, parent, libraries)
                    val selected = frame?.selectedActor?.key == actor.key
                    CompactMenuAction(onClick = { onActor(actor) },
                        modifier = targetModifier(discoveryActorTarget(actor)).width(88.dp)
                            .testTag("detail-cast-$index").semantics { this.selected = selected },
                        enabled = !busy && supported, selected = selected,
                        contentPadding = PaddingValues(6.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (!actor.portrait.isNullOrBlank()) portrait(actor, Modifier.size(72.dp, 96.dp).clearAndSetSemantics { })
                            else Box(Modifier.size(72.dp, 96.dp).testTag("detail-cast-placeholder-$index")
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Person, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(actor.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = LocalContentColor.current)
                            if (actor.role.isNotBlank()) Text(actor.role, fontSize = 10.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!supported) Text("작품 정보 없음", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        frame?.selectedActor?.let { actor ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("detail-actor-works")) {
                Text("${actor.name}의 작품", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (frame.currentLibraryOnly) Text("현재 라이브러리", fontSize = 11.sp,
                    modifier = Modifier.testTag("detail-actor-scope"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    frame.actorLoading -> Text("작품을 불러오는 중입니다", fontSize = 12.sp, modifier = Modifier.testTag("detail-actor-loading"))
                    frame.actorError != null -> {
                        Text(frame.actorError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        TvAction("다시 시도", enabled = !busy) { onActor(actor) }
                    }
                    actorWorks.isEmpty() && frame.actorLoaded -> Text("표시할 작품이 없습니다", fontSize = 12.sp)
                }
                if (actorWorks.isNotEmpty()) DiscoveryWorksRow(actorWorks, actorScroll, "actor", busy,
                    onOpen, onWatchOptions, artwork, ::targetModifier)
            }
        }
        if (frame != null && (frame.relatedLoading || frame.relatedError != null || related.isNotEmpty())) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("detail-related")) {
                Text("관련 작품 · 출연진 기준", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (frame.relatedLoading) Text("관련 작품을 불러오는 중입니다", fontSize = 12.sp)
                if (frame.relatedError != null) {
                    Text(frame.relatedError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    TvAction("다시 시도", enabled = !busy, onClick = onRetryRelated)
                }
                if (related.isNotEmpty()) DiscoveryWorksRow(related, relatedScroll, "related", busy,
                    onOpen, onWatchOptions, artwork, ::targetModifier)
            }
        }
    }
}

@Composable
private fun DiscoveryWorksRow(items: List<MediaItem>, scroll: androidx.compose.foundation.lazy.LazyListState,
    section: String, busy: Boolean, onOpen: (MediaItem) -> Unit, onWatchOptions: (MediaItem) -> Unit,
    artwork: @Composable (MediaItem, Modifier) -> Unit, targetModifier: (String) -> Modifier) {
    LazyRow(state = scroll, modifier = Modifier.focusRestorer().focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = ::compactMediaIdentity) { item ->
            val canWatch = canChangeWatchState(item) && item.viewItemId?.let { it > 0 } == true
            Column(targetModifier(discoveryWorkTarget(section, item)).width(112.dp).tvFocus()
                .onPreviewKeyEvent { event ->
                    if (!busy && canWatch && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) onWatchOptions(item)
                        true
                    } else false
                }
                .combinedClickable(enabled = !busy, onClick = { onOpen(item) },
                    onLongClickLabel = if (canWatch) "시청 상태 변경" else null,
                    onLongClick = if (canWatch) ({ onWatchOptions(item) }) else null)
                .testTag("detail-$section-${compactMediaIdentity(item)}"),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(112.dp).aspectRatio(2f / 3f)) {
                    artwork(item, Modifier.fillMaxSize())
                    if (item.watched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(2.dp), compact = true)
                }
                Text(item.showTitle ?: item.title, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
