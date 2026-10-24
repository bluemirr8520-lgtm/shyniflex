package io.mirr.shynigsy.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mirr.shynigsy.AppState
import io.mirr.shynigsy.AppViewModel
import io.mirr.shynigsy.isTelevision
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.Library
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** Applied before clickable/focusable so the same focus target paints its outline. */
internal fun Modifier.tvFocus(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    this.onFocusChanged { focused = it.hasFocus }
        .border(2.dp, if (focused) accent else Color.Transparent, RectangleShape)
        .background(if (focused) accent.copy(alpha = .14f) else Color.Transparent, RectangleShape)
}

internal fun Modifier.tvOnlyFocus(): Modifier = composed {
    if (LocalContext.current.isTelevision()) this.tvFocus() else this
}

@Composable
internal fun TvAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = modifier.tvFocus().heightIn(min = 40.dp), shape = RectangleShape,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun TvHeader(title: String?, onHome: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title ?: "shyniflex", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onHome != null) LibraryHomeButton(onHome)
    }
}

@Composable
internal fun LibraryHomeButton(onHome: () -> Unit) {
    TextButton(onClick = onHome,
        modifier = Modifier.testTag("library-home").tvOnlyFocus().heightIn(min = 48.dp),
        shape = RectangleShape, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Icon(Icons.Rounded.Home, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("홈", style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
internal fun TvSidebar(onLibraries: () -> Unit, onRefresh: () -> Unit, onLogout: () -> Unit,
    libraries: List<Library> = emptyList(), selectedLibraryId: Int? = null, homeSelected: Boolean = true,
    onHome: () -> Unit = {}, onLibrary: (Library) -> Unit = {}, showLocked: Boolean = false,
    spoilerUnlocked: Boolean = false, onLockSpoiler: () -> Unit = {}, onUpdate: () -> Unit = {},
    onSearch: () -> Unit = {}, searchSelected: Boolean = false) {
    Column(Modifier.width(156.dp).fillMaxHeight().background(Color(0xFF0B1018))
        .padding(horizontal = 8.dp, vertical = 8.dp).focusRestorer().focusGroup().testTag("tv-sidebar"),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("shyniflex", Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TvSidebarItem("홈", Icons.Rounded.Home, homeSelected, onHome)
        TvSidebarItem("검색", Icons.Rounded.Search, searchSelected, onSearch, Modifier.testTag("sidebar-search"))
        Text("라이브러리", Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = Color(0xFF929BA9))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(libraries, key = { it.id }) { library ->
                TvSidebarItem(library.title,
                    if (library.type == "movie") Icons.Rounded.Movie else Icons.Rounded.Folder,
                    selectedLibraryId == library.id, onClick = { onLibrary(library) })
            }
            if (showLocked || spoilerUnlocked) item(key = "spoiler-access") {
                TvSidebarItem(if (spoilerUnlocked) "스포일러 잠그기" else "잠긴 라이브러리",
                    Icons.Rounded.Lock, false, if (spoilerUnlocked) onLockSpoiler else onLibraries)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0xFF252B34))
        TvSidebarItem("새로고침", Icons.Rounded.Refresh, false, onRefresh)
        TvSidebarItem("업데이트", Icons.Rounded.SystemUpdate, false, onUpdate)
        TvSidebarItem("계정", Icons.Rounded.AccountCircle, false, onLogout)
    }
}

@Composable
private fun TvSidebarItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val foreground = if (focused) Color(0xFF101318) else if (selected) Color.White else Color(0xFFB3BAC6)
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 36.dp)
        .semantics { this.selected = selected }
        .onFocusChanged { focused = it.isFocused }, shape = RectangleShape,
        color = if (focused) Color.White else if (selected) Color(0xFF30353E) else Color.Transparent) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = foreground)
            Text(label, color = foreground, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TvHome(state: AppState, model: AppViewModel, onWatchOptions: (MediaItem) -> Unit) {
    val modelIsTelevision = LocalContext.current.isTelevision()
    val continuing = remember(state.continueItems, state.spoilerUnlocked) {
        state.continueItems.filter { state.spoilerUnlocked || !it.isSpoiler }
    }
    val recent = remember(state.recentItems, state.spoilerUnlocked) {
        state.recentItems.filter { state.spoilerUnlocked || !it.isSpoiler }
    }
    val libraries = remember(state.libraries, state.spoilerUnlocked) {
        state.libraries.filter { state.spoilerUnlocked || !it.isSpoiler }
    }
    val recentByLibrary = remember(recent) { recent.groupBy { it.libraryId } }
    var selectedKey by rememberSaveable { mutableStateOf("") }
    var selectedShelf by rememberSaveable { mutableStateOf("continue") }
    val hasMedia = continuing.isNotEmpty() || recent.isNotEmpty()
    val heroItem = continuing.firstOrNull { tvMediaKey(it) == selectedKey } ?: continuing.firstOrNull()
    val hero = state.continueDetails?.takeIf { heroItem != null && tvMediaKey(it) == tvMediaKey(heroItem) } ?: heroItem
    val shelfFocus = remember { FocusRequester() }
    // Run once as content arrives, never steal focus on background refreshes.
    var focusRequested by remember { mutableStateOf(false) }
    LaunchedEffect(hasMedia) {
        if (modelIsTelevision && hasMedia && !focusRequested) {
            withFrameNanos { }
            shelfFocus.requestFocus()
            focusRequested = true
        }
    }
    val restoreShelf = selectedShelf.takeIf { key ->
        if (key == "continue") continuing.isNotEmpty()
        else recentByLibrary.keys.any { "recent-$it" == key }
    } ?: if (continuing.isNotEmpty()) "continue" else recent.firstOrNull()?.let { "recent-${it.libraryId}" }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("tv-home")) {
        val compact = maxWidth < 600.dp
        val heroHeight = if (compact) 210.dp else (maxHeight - 260.dp).coerceAtLeast(180.dp)
        val scroll = rememberLazyListState()
        val homeScope = rememberCoroutineScope()
        // TV's default focus pivot scrolls an already visible poster upward and clips its hero.
        val reveal = remember { object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < 0f -> offset
                offset + size > containerSize -> offset + size - containerSize
                else -> 0f
            }
        } }
        CompositionLocalProvider(LocalBringIntoViewSpec provides reveal) {
        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "continue") {
                // The backdrop belongs only to this section; other shelves keep a plain background.
                Box(Modifier.fillMaxWidth()) {
                    if (hero != null) Poster(hero, model, Modifier.matchParentSize(),
                        imageUrl = hero.backdrop ?: hero.poster, imageAlignment = Alignment.CenterEnd)
                    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(
                        Color(0xFF0B1220).copy(alpha = .80f), Color(0xFF0B1220).copy(alpha = .10f)))))
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                        0f to Color(0xFF0B1220).copy(alpha = .08f), .58f to Color.Transparent,
                        1f to Color(0xFF0B1220))))
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        if (hero != null) Column(Modifier.fillMaxWidth(if (compact) 1f else .70f).height(heroHeight)
                            .padding(start = 24.dp, top = 18.dp, end = 12.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
                            Text(hero.showTitle ?: hero.title, style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(continueHeroMetadata(hero), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(hero.summary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (continuing.isNotEmpty()) TvShelf("이어서 보기", continuing, model, onWatchOptions,
                            modifier = if (restoreShelf == "continue") Modifier.focusRequester(shelfFocus) else Modifier,
                            restoreKey = selectedKey, onSelect = {
                                selectedKey = tvMediaKey(it); selectedShelf = "continue"; model.selectContinueItem(it)
                                if (scroll.firstVisibleItemIndex != 0 || scroll.firstVisibleItemScrollOffset != 0) {
                                    homeScope.launch { scroll.animateScrollToItem(0) }
                                }
                            }, onClick = model::playContinue)
                        else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("이어서 보기", style = MaterialTheme.typography.titleMedium)
                            Text(when {
                                state.continueLoading -> "이어보기를 불러오는 중입니다"
                                state.continueError != null -> "이어보기를 불러오지 못했습니다"
                                else -> "이어볼 영상이 없습니다"
                            })
                            if (state.continueError != null) TvAction("다시 시도", onClick = model::refresh)
                        }
                    }
                }
            }
            libraries.forEach { library ->
                val media = recentByLibrary[library.id].orEmpty()
                if (media.isNotEmpty()) item(key = "recent-${library.id}") {
                    TvShelf("최근 추가됨 · ${library.title}", media, model, onWatchOptions,
                        modifier = if (restoreShelf == "recent-${library.id}") Modifier.focusRequester(shelfFocus) else Modifier,
                        restoreKey = selectedKey,
                        onSelect = { selectedKey = tvMediaKey(it); selectedShelf = "recent-${library.id}" }, onClick = model::openItem)
                }
            }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
        }
        TvHomeClock(Modifier.align(Alignment.TopEnd).padding(horizontal = 24.dp, vertical = 16.dp))
    }
}

@Composable
internal fun TvHomeClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val formatter = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val clock by produceState(formatter.format(java.util.Date()), formatter) {
        while (true) {
            value = formatter.format(java.util.Date())
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis().mod(60_000L))
        }
    }
    Text(clock, modifier.testTag("tv-home-clock").semantics { contentDescription = "현재 시각 $clock" },
        style = MaterialTheme.typography.titleMedium.copy(shadow = androidx.compose.ui.graphics.Shadow(
            Color.Black.copy(alpha = .8f), blurRadius = 4f)), color = Color.White,
        fontWeight = FontWeight.Medium)
}

@Composable
private fun TvShelf(title: String, media: List<MediaItem>, model: AppViewModel,
    onWatchOptions: (MediaItem) -> Unit, modifier: Modifier = Modifier,
    onSelect: (MediaItem) -> Unit, onClick: (MediaItem) -> Unit, wide: Boolean = false, restoreKey: String = "") {
    val fallback = remember { FocusRequester() }
    val hasRestoreKey = remember(media, restoreKey) { media.any { tvMediaKey(it) == restoreKey } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyRow(modifier.focusRestorer(fallback).focusGroup(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(media, key = ::tvMediaKey) { item ->
                TvMediaCard(item, model, Modifier.width(if (wide) 200.dp else 114.dp)
                    .then(if (tvMediaKey(item) == restoreKey || (!hasRestoreKey && item == media.first())) Modifier.focusRequester(fallback) else Modifier),
                    wide = wide, onSelect = { onSelect(item) }, onClick = { onClick(item) },
                    onWatchOptions = { onWatchOptions(item) })
            }
        }
    }
}

@Composable
internal fun TvMediaCard(item: MediaItem, model: AppViewModel, modifier: Modifier = Modifier,
    wide: Boolean = false, onSelect: () -> Unit = {}, onClick: () -> Unit, onWatchOptions: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val countRevision by remember(model) { model.state.map { it.seriesCountRevision }.distinctUntilChanged() }.collectAsState(0)
    val remaining by produceState<Int?>(null, item, countRevision) {
        value = null
        if (item.type in setOf("show", "tv", "season")) {
            kotlinx.coroutines.delay(250)
            value = model.seriesUnwatchedCount(item)
        }
    }
    Column(modifier.tvFocus().onFocusChanged { focused = it.isFocused; if (it.isFocused) onSelect() }
        .onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU && canChangeWatchState(item)) {
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) onWatchOptions()
                true
            } else false
        }
        .combinedClickable(onClick = onClick, onLongClickLabel = "시청 상태 변경",
            onLongClick = if (canChangeWatchState(item)) onWatchOptions else null)
        .padding(3.dp).testTag("tv-media-${item.libraryId}-${item.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f)) {
            Poster(item, model, Modifier.fillMaxSize(), imageUrl = if (wide) item.thumbnail ?: item.backdrop ?: item.poster else item.poster)
            if (focused) Surface(Modifier.align(Alignment.Center).size(46.dp),
                color = Color(0xE60B1220), shape = RoundedCornerShape(23.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) {
                Icon(Icons.Rounded.PlayArrow, "이어보기 재생", Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (item.watched && (remaining == null || remaining == 0)) Text("보았음", Modifier.align(Alignment.TopEnd).background(Color(0xDD0B1220)).padding(6.dp),
                color = MaterialTheme.colorScheme.primary)
            UnwatchedCountBadge(remaining, Modifier.align(Alignment.TopEnd).padding(2.dp))
            if (!item.watched && item.resumeMs > 0 && item.durationMs > 0) LinearProgressIndicator(
                progress = { (item.resumeMs.toFloat() / item.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
        Text(item.showTitle ?: item.title, style = MaterialTheme.typography.labelLarge,
            minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.type == "episode") Text(episodeLabel(item), style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun UnwatchedCountBadge(count: Int?, modifier: Modifier = Modifier) {
    if (count == null || count <= 0) return
    Surface(modifier.semantics { contentDescription = "미시청 ${count}편" }
        .size(if (count < 100) 20.dp else if (count < 1000) 26.dp else 34.dp),
        shape = CircleShape, color = MaterialTheme.colorScheme.primary,
        contentColor = Color(0xFF08251E), shadowElevation = 2.dp) {
        Box(contentAlignment = Alignment.Center) {
            Text(count.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
internal fun TvLibrary(state: AppState, model: AppViewModel, onWatchOptions: (MediaItem) -> Unit) {
    val library = state.library ?: return
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val initialFocus = remember { FocusRequester() }
    var focusRequested by remember { mutableStateOf(false) }
    val visibleItems = remember(state.items, state.spoilerUnlocked) {
        state.items.filter { state.spoilerUnlocked || !it.isSpoiler }
    }
    val visibleItemKeys = remember(state.items, state.spoilerUnlocked) {
        visibleItems.mapTo(HashSet<String>(visibleItems.size), ::tvMediaKey)
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    fun changeOptions(change: () -> Unit) {
        focusedKey = null
        scope.launch { grid.scrollToItem(0) }
        change()
    }
    LibraryAutoLoad(grid, listOf(state.library?.id, state.libraryBrowseRevision, state.query,
        state.librarySort, state.libraryContentType, state.spoilerUnlocked), state.items.size, visibleItems.size,
        state.total, state.busy, state.error != null, model::loadMore)
    val restoreKey = focusedKey?.takeIf { it in visibleItemKeys }
        ?: visibleItems.firstOrNull()?.let(::tvMediaKey)
    val modelIsTelevision = LocalContext.current.isTelevision()
    LaunchedEffect(state.busy, state.items.isNotEmpty()) {
        if (modelIsTelevision && !state.busy && !focusRequested) {
            withFrameNanos { }
            initialFocus.requestFocus()
            focusRequested = true
        }
    }
    LazyVerticalGrid(GridCells.Adaptive(if (modelIsTelevision) 152.dp else 114.dp), Modifier.fillMaxSize().testTag("tv-library"),
        state = grid,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PlexLibraryToolbar(library, state.librarySort, state.libraryContentType, state.busy, state.spoilerUnlocked,
                onSort = { changeOptions { model.setLibraryOptions(sort = it) } },
                onContentType = { changeOptions { model.setLibraryOptions(contentType = it) } },
                firstControlModifier = if (restoreKey == null) Modifier.focusRequester(initialFocus) else Modifier)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${state.total}개", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (state.error != null) item(span = { GridItemSpan(maxLineSpan) }) { Text(state.error, color = MaterialTheme.colorScheme.error) }
        if (state.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(if (state.busy) "작품을 불러오는 중입니다" else "표시할 작품이 없습니다")
        }
        items(visibleItems, key = ::tvMediaKey) { item ->
            TvMediaCard(item, model, modifier = if (tvMediaKey(item) == restoreKey) Modifier.focusRequester(initialFocus) else Modifier,
                onSelect = { focusedKey = tvMediaKey(item) },
                onClick = { model.openItem(item) }, onWatchOptions = { onWatchOptions(item) })
        }
        if (state.busy && state.items.isNotEmpty() && state.items.size < state.total) item(span = { GridItemSpan(maxLineSpan) }) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
    }
}

@Composable
internal fun TvDetail(state: AppState, model: AppViewModel, onWatchOptions: (MediaItem) -> Unit) {
    CompactDetailScreen(state, model, onWatchOptions)
}

@Composable
private fun TvSummaryDialog(title: String, summary: String, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Text(summary, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.focusRequester(focus).tvFocus().heightIn(max = 220.dp).verticalScroll(scroll)
                .onPreviewKeyEvent { event ->
                    val direction = when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> if (scroll.canScrollForward) 1 else 0
                        KeyEvent.KEYCODE_DPAD_UP -> if (scroll.canScrollBackward) -1 else 0
                        else -> 0
                    }
                    if (direction == 0) false else {
                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) scope.launch { scroll.animateScrollBy(100f * direction) }
                        true
                    }
                }.focusable())
    }, confirmButton = { TvAction("닫기", onClick = onDismiss) })
}

private fun tvMediaKey(item: MediaItem) = "${item.libraryId}:${item.type}:${item.id}:${item.viewItemId}:${item.isSpoiler}"
