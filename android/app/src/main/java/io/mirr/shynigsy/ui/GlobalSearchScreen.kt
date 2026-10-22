package io.mirr.shynigsy.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mirr.shynigsy.AppState
import io.mirr.shynigsy.AppViewModel
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.isTelevision
import kotlinx.coroutines.launch

@Composable
internal fun GlobalSearchScreen(state: AppState, model: AppViewModel, onWatchOptions: (MediaItem) -> Unit) {
    GlobalSearchContent(state.searchQuery, state.searchItems, state.libraries, state.busy,
        state.error, state.searchFailureCount, state.spoilerUnlocked, model::searchAll, model::openItem,
        onWatchOptions, artwork = { item, modifier ->
            Poster(item, model, modifier, imageUrl = item.poster ?: item.thumbnail ?: item.backdrop)
        })
}

/** Text edits remain local; only submit requests an authorized cross-library search. */
@Composable
internal fun GlobalSearchContent(
    query: String,
    items: List<MediaItem>,
    libraries: List<Library>,
    busy: Boolean,
    error: String?,
    failureCount: Int,
    spoilerUnlocked: Boolean,
    onSearch: (String) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onWatchOptions: (MediaItem) -> Unit,
    artwork: @Composable (MediaItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(query) { mutableStateOf(query) }
    val knownLibraries = remember(libraries, spoilerUnlocked) {
        libraries.filter { spoilerUnlocked || !it.isSpoiler }.associateBy { it.id to it.isSpoiler }
    }
    val visibleItems = remember(items, knownLibraries, spoilerUnlocked) {
        items.filter { (spoilerUnlocked || !it.isSpoiler) && (it.libraryId to it.isSpoiler) in knownLibraries }
    }
    val visibleKeys = remember(visibleItems) { visibleItems.mapTo(HashSet<String>(visibleItems.size), ::compactMediaIdentity) }
    var focusedKey by rememberSaveable(query) { mutableStateOf<String?>(null) }
    val restoreKey = focusedKey?.takeIf { it in visibleKeys }
        ?: visibleItems.firstOrNull()?.let(::compactMediaIdentity)
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val inputFocus = remember { FocusRequester() }
    val resultFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val television = LocalContext.current.isTelevision()
    var focusRequested by remember(query) { mutableStateOf(false) }
    fun submit() {
        if (!busy && text.isNotBlank()) {
            keyboard?.hide()
            focusManager.clearFocus()
            focusedKey = null
            focusRequested = false
            scope.launch { grid.scrollToItem(0) }
            onSearch(text.trim())
        }
    }
    LaunchedEffect(television, busy, restoreKey) {
        if (television && !busy && !focusRequested) {
            val index = visibleItems.indexOfFirst { compactMediaIdentity(it) == restoreKey }
            grid.scrollToItem(if (index >= 0) index + 2 else 0)
            withFrameNanos { }
            if (index >= 0) resultFocus.requestFocus() else inputFocus.requestFocus()
            focusRequested = true
        }
    }
    LazyVerticalGrid(GridCells.Adaptive(if (television) 152.dp else 114.dp), state = grid,
        modifier = modifier.fillMaxSize().testTag("global-search"),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "search-input") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, label = { Text("제목 검색") }, singleLine = true,
                    shape = RectangleShape, modifier = Modifier.weight(1f).focusRequester(inputFocus)
                        .tvOnlyFocus().testTag("global-search-input"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }))
                TvAction("검색", Modifier.widthIn(min = 64.dp).testTag("global-search-submit"),
                    enabled = !busy && text.isNotBlank(), onClick = ::submit)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "search-status") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    busy -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("검색 중입니다", Modifier.testTag("global-search-status"), style = MaterialTheme.typography.bodySmall)
                    }
                    error != null -> Text(error, Modifier.testTag("global-search-error"),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    query.isBlank() -> Text("제목을 입력해 검색하세요", Modifier.testTag("global-search-status"),
                        style = MaterialTheme.typography.bodySmall)
                    visibleItems.isEmpty() && failureCount > 0 -> Text("검색을 완료하지 못했습니다", Modifier.testTag("global-search-status"),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    visibleItems.isEmpty() -> Text("검색 결과가 없습니다", Modifier.testTag("global-search-status"),
                        style = MaterialTheme.typography.bodySmall)
                    else -> Text("${visibleItems.size}개", Modifier.testTag("global-search-status"),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (!busy && error == null && failureCount > 0 && visibleItems.isNotEmpty()) Text(
                    "${failureCount}개 라이브러리의 검색을 완료하지 못했습니다. 확인된 결과를 표시합니다.",
                    Modifier.testTag("global-search-partial"), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        items(visibleItems, key = ::compactMediaIdentity) { item ->
            val identity = compactMediaIdentity(item)
            Column(Modifier.then(if (identity == restoreKey) Modifier.focusRequester(resultFocus) else Modifier)
                .tvFocus().onFocusChanged { if (it.isFocused) focusedKey = identity }
                .onPreviewKeyEvent { event ->
                    if (!busy && canChangeWatchState(item) && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) onWatchOptions(item)
                        true
                    } else false
                }
                .combinedClickable(enabled = !busy, onClick = { onOpen(item) },
                    onLongClickLabel = "시청 상태 변경",
                    onLongClick = if (canChangeWatchState(item)) ({ onWatchOptions(item) }) else null)
                .testTag("global-search-result-$identity").padding(3.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    artwork(item, Modifier.fillMaxSize())
                    if (item.watched) Text("보았음", Modifier.align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.surface).padding(4.dp),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
                Text(item.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    minLines = 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.type == "episode") Text(episodeLabel(item), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(knownLibraries.getValue(item.libraryId to item.isSpoiler).title,
                    Modifier.testTag("global-search-library-$identity"), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
