package io.mirr.shynigsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.mirr.shynigsy.data.MediaItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A finite poster shelf with a vivid, independently rotating Continue Watching backdrop. */
@Composable
internal fun ContinueWatchingSection(
    items: List<MediaItem>,
    busy: Boolean,
    onResume: (MediaItem) -> Unit,
    poster: @Composable (MediaItem, Modifier) -> Unit,
    backdrop: @Composable (MediaItem, Modifier) -> Unit = poster,
    modifier: Modifier = Modifier,
    onSelected: (MediaItem) -> Unit = {},
    selectedDetails: MediaItem? = null,
    onWatchOptions: (MediaItem) -> Unit = {},
    spoilerUnlocked: Boolean = false,
    thumbnail: @Composable (MediaItem, Modifier) -> Unit = poster,
) {
    val visibleItems = items.filter { spoilerUnlocked || !it.isSpoiler }
    if (visibleItems.isEmpty()) return
    var selectedKey by rememberSaveable { mutableStateOf(continueIdentity(visibleItems.first())) }
    val selectedItem = visibleItems.firstOrNull { continueIdentity(it) == selectedKey } ?: visibleItems.first()
    val row = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    val swipeItems by rememberUpdatedState(visibleItems)
    val swipeEnabled by rememberUpdatedState(!busy)
    val visibleKeys = visibleItems.map(::continueIdentity)
    val dragging by row.interactionSource.collectIsDraggedAsState()
    var touchBrowsing by remember { mutableStateOf(false) }
    var interactionRevision by remember { mutableStateOf(0) }
    var metadataSelectionKey by remember { mutableStateOf(continueIdentity(selectedItem)) }
    var heroVisible by remember { mutableStateOf(false) }
    var heroFocused by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    var heroDragging by remember { mutableStateOf(false) }
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    fun select(item: MediaItem) {
        selectedKey = continueIdentity(item)
        metadataSelectionKey = selectedKey
        interactionRevision++
    }

    rememberContinueBackdropRotation(visibleKeys, continueIdentity(selectedItem),
        enabled = !busy && heroVisible && !dragging && !row.isScrollInProgress &&
            !touching && !heroDragging && !hovered && !heroFocused,
        interactionRevision = interactionRevision, onAdvance = { selectedKey = it })

    fun startHeroSwipe() {
        touchBrowsing = false
        focusManager.clearFocus()
        scope.launch { row.stopScroll() }
    }
    fun swipeHero(direction: Int) {
        scope.launch {
            row.stopScroll()
            if (!swipeEnabled || swipeItems.size < 2) return@launch
            val current = swipeItems.indexOfFirst { continueIdentity(it) == selectedKey }.coerceAtLeast(0)
            val next = (current + direction + swipeItems.size) % swipeItems.size
            if (next == current) return@launch
            touchBrowsing = false
            select(swipeItems[next])
            row.animateScrollToItem(next)
        }
    }

    LaunchedEffect(visibleItems.map(::continueIdentity)) {
        if (visibleItems.none { continueIdentity(it) == selectedKey }) select(visibleItems.first())
    }
    // Automatic slides reuse the row's metadata; only deliberate selection asks the server for detail.
    LaunchedEffect(metadataSelectionKey) {
        visibleItems.firstOrNull { continueIdentity(it) == metadataSelectionKey }?.let(onSelected)
    }
    // Touch browsing follows the visible shelf; D-pad focus selects its own card.
    LaunchedEffect(row, dragging, visibleItems.map(::continueIdentity)) {
        if (dragging) {
            touchBrowsing = true
            snapshotFlow { row.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
                if (touchBrowsing) visibleItems.getOrNull(index)?.let(::select)
            }
        }
    }
    LaunchedEffect(row.isScrollInProgress, dragging) {
        if (touchBrowsing && !dragging && !row.isScrollInProgress) {
            visibleItems.getOrNull(row.firstVisibleItemIndex)?.let(::select)
            touchBrowsing = false
        }
    }
    val hero = selectedDetails?.takeIf { continueIdentity(it) == continueIdentity(selectedItem) } ?: selectedItem
    val background = MaterialTheme.colorScheme.background
    val foreground = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val screenHeight = LocalConfiguration.current.screenHeightDp

    BoxWithConstraints(modifier.fillMaxWidth().hoverable(hoverInteraction)
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touching = true
                interactionRevision++
                try {
                    do { val event = awaitPointerEvent(PointerEventPass.Initial) }
                    while (event.changes.any { it.pressed })
                } finally { touching = false }
            }
        }.testTag("continue-watching-section")) {
        val wide = maxWidth >= 600.dp
        val heroHeight = if (wide) (screenHeight * .30f).dp.coerceIn(180.dp, 240.dp) else 240.dp
        val compact = wide && heroHeight < 210.dp
        val wideCards = visibleItems.any { it.type == "episode" }
        val cardWidth = if (wideCards) {
            if (wide) (maxWidth * .28f).coerceIn(260.dp, 320.dp)
            else ((maxWidth - 52.dp) / 2).coerceIn(150.dp, 260.dp)
        } else if (wide) (screenHeight * .20f).dp.coerceIn(110.dp, 150.dp) else 118.dp
        val heroProgress = continuePlaybackProgress(hero.durationMs, selectedItem.resumeMs)
        backdrop(hero, Modifier.matchParentSize().testTag("continue-section-backdrop"))
        Box(Modifier.matchParentSize().continueSectionBackdropFade(heroHeight, background, wide))
        Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(heroHeight).testTag("continue-watching-hero")
                    .onGloballyPositioned { heroVisible = isContinueBackdropVisible(it) }
                    .pointerInput(visibleKeys, busy, swipeThreshold) {
                        if (busy || visibleKeys.size < 2) return@pointerInput
                        var horizontalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { horizontalDrag = 0f; heroDragging = true; startHeroSwipe() },
                            onDragCancel = { horizontalDrag = 0f; heroDragging = false },
                            onDragEnd = {
                                when {
                                    horizontalDrag <= -swipeThreshold -> swipeHero(1)
                                    horizontalDrag >= swipeThreshold -> swipeHero(-1)
                                }
                                horizontalDrag = 0f
                                heroDragging = false
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                horizontalDrag += amount
                            },
                        )
                    }) {
                    Column(Modifier.fillMaxWidth(if (wide) .66f else 1f).align(if (wide) Alignment.CenterStart else Alignment.Center)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
                        Text(hero.showTitle ?: hero.title, color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontSize = if (wide) 28.sp else 26.sp,
                            textAlign = if (wide) TextAlign.Start else TextAlign.Center,
                            fontWeight = FontWeight.Bold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("continue-hero-title"))
                        Text(continueHeroMetadata(hero), color = Color.White.copy(alpha = .9f),
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("continue-hero-meta"))
                        Button(onClick = { onResume(selectedItem) }, enabled = !busy,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
                            modifier = Modifier.height(40.dp).onFocusChanged { heroFocused = it.isFocused }
                                .testTag("continue-hero-resume").semantics {
                                contentDescription = "${selectedItem.showTitle ?: selectedItem.title}, 이어보기"
                            }) {
                            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            if (heroProgress != null) {
                                LinearProgressIndicator(progress = { heroProgress.fraction },
                                    color = Color.Black, trackColor = Color.Black.copy(alpha = .22f),
                                    modifier = Modifier.width(40.dp).height(4.dp).testTag("continue-hero-progress"))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(heroProgress?.let { "${it.remainingMinutes}분 남음" } ?: "재생",
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 130.dp).testTag("continue-hero-remaining"))
                        }
                        Text(hero.summary, color = Color.White.copy(alpha = .9f),
                            textAlign = if (wide) TextAlign.Start else TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall, minLines = 2, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("continue-hero-summary"))
                    }
                }
                Row(Modifier.padding(horizontal = 24.dp, vertical = if (wide) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("계속 시청", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                LazyRow(state = row, modifier = Modifier.fillMaxWidth().testTag("continue-watching-row"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = if (wide) 12.dp else 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visibleItems, key = ::continueIdentity) { item ->
                        val interaction = remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        LaunchedEffect(hovered) { if (hovered) select(item) }
                        val current = continueIdentity(item) == continueIdentity(selectedItem)
                        val progress = continuePlaybackProgress(item.durationMs, item.resumeMs)
                        Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Card(shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                modifier = Modifier.fillMaxWidth().aspectRatio(if (wideCards) 16f / 9f else 2f / 3f)
                                    .onFocusChanged { if (it.isFocused) select(item) }
                                    .combinedClickable(interactionSource = interaction, indication = LocalIndication.current, enabled = !busy,
                                        onClick = { select(item); onResume(item) }, onLongClickLabel = "시청 상태 변경",
                                        onLongClick = { select(item); onWatchOptions(item) })
                                    .border(if (current) 3.dp else 1.dp, if (current) accent else foreground.copy(alpha = .15f), RoundedCornerShape(8.dp))
                                    .testTag("continue-item-${item.id}").semantics {
                                        selected = current
                                        contentDescription = "${item.showTitle ?: item.title}, ${episodeLabel(item)}, 이어보기"
                                    }) {
                                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
                                    val artworkModifier = Modifier.fillMaxSize().testTag("continue-poster-${item.id}")
                                    if (wideCards) thumbnail(item, artworkModifier) else poster(item, artworkModifier)
                                    if (progress != null) LinearProgressIndicator(
                                        progress = { progress.fraction },
                                        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter))
                                }
                            }
                            Text(item.showTitle ?: item.title, color = foreground,
                                style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(continueEpisodeCaption(item),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("continue-episode-${item.id}"))
                        }
                    }
                }
        }
    }
}

/**
 * Artwork stays behind the entire shelf, while captions finish on the app's plain background.
 * Pixel positions follow the existing hero and actual shelf height; no layout state or extra row is needed.
 */
internal fun Modifier.continueSectionBackdropFade(heroHeight: Dp, background: Color, wide: Boolean): Modifier =
    drawWithCache {
        val height = size.height.coerceAtLeast(1f)
        val heroEnd = heroHeight.toPx().coerceIn(0f, height)
        val cardStart = (heroEnd + 36.dp.toPx()).coerceAtMost(height)
        val captionStart = (height - 56.dp.toPx()).coerceIn(cardStart, height)
        val shade = if (wide) Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = .78f), .58f to Color.Black.copy(alpha = .50f),
            1f to Color.Black.copy(alpha = .02f))
        else Brush.verticalGradient(listOf(Color.Black.copy(alpha = .48f), Color.Black.copy(alpha = .48f)))
        val fade = Brush.verticalGradient(
            0f to background.copy(alpha = 0f),
            (heroEnd * .88f / height) to background.copy(alpha = 0f),
            (heroEnd / height) to background.copy(alpha = .10f),
            (cardStart / height) to background.copy(alpha = .20f),
            (captionStart / height) to background.copy(alpha = .94f),
            1f to background,
        )
        onDrawBehind {
            drawRect(shade)
            drawRect(fade)
        }
    }

private fun continueIdentity(item: MediaItem) = "${item.libraryId}:${item.type}:${item.id}:${item.viewItemId}:${item.isSpoiler}"

internal fun continueHeroMetadata(item: MediaItem): String {
    val episode = if (item.type == "episode") listOfNotNull(item.seasonNumber?.let { "S$it" },
        item.episodeNumber?.let { "E$it" }).joinToString(" ").ifBlank { episodeLabel(item) } else "영화"
    val minutes = item.durationMs / 60_000
    val duration = when {
        minutes >= 60 -> "${minutes / 60}시간" + (minutes % 60).takeIf { it > 0 }?.let { " ${it}분" }.orEmpty()
        minutes > 0 -> "${minutes}분"
        else -> null
    }
    return listOfNotNull(episode, duration).joinToString(" · ")
}
