package io.mirr.shynigsy.plex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.mirr.shynigsy.AppState
import io.mirr.shynigsy.data.*
import io.mirr.shynigsy.isTelevision
import io.mirr.shynigsy.ui.*

@Composable
internal fun PlexCinemaHome(appName: String, home: PlexPage.Home, model: PlexBrowseModel, libraries: List<Library>,
    play: (MediaItem, Boolean) -> Unit, onAppearance: () -> Unit, onWatch: (MediaItem) -> Unit) {
    val state = AppState(libraries = libraries, continueItems = home.ongoing, recentItems = home.recent,
        busy = model.busy, error = model.error, continueLoading = home.continueLoading,
        continueError = home.continueError, recentLibraryStatus = home.recentLibraryStatus)
    CinemaHome(state, appName, onResume = { play(it, false) }, onOpen = model::openItem,
        onSelectContinue = model::selectContinueItem, retryContinue = model::retryContinue,
        retryRecent = { id -> libraries.firstOrNull { it.id == id }?.let(model::retryRecentLibrary) },
        onAppearance = onAppearance, onSearch = model::openSearch,
        backdrop = { item -> PlexArtwork(item.backdrop ?: item.poster, model,
            Modifier.fillMaxSize(), backdrop = true) },
        card = { item, modifier, selected, select, open ->
            CinemaMediaCard(item, modifier, onSelect = select, onClick = open, onWatchOptions = { onWatch(item) },
                cinematic = true, selected = selected) { PlexArtwork(item.poster ?: item.thumbnail, model, Modifier.fillMaxSize()) }
        })
}

@Composable
internal fun PlexCatalog(shelf: PlexPage.Shelf, model: PlexBrowseModel, onWatch: (MediaItem) -> Unit) {
    val tv = LocalContext.current.isTelevision()
    val dataset = listOf(shelf.library.id, shelf.sort, shelf.contentType, shelf.watchFilter, shelf.query)
    key(dataset) {
        val grid = rememberLazyGridState()
        val initial = remember { FocusRequester() }
        var focused by rememberSaveable { mutableStateOf<Int?>(null) }
        val target = focused?.takeIf { id -> shelf.items.any { it.id == id } } ?: shelf.items.firstOrNull()?.id
        var requested by remember { mutableStateOf(false) }
        LaunchedEffect(tv, model.busy, target) {
            if (tv && !model.busy && !requested) {
                withFrameNanos { }
                requested = runCatching { initial.requestFocus() }.getOrDefault(false)
            }
        }
        LibraryAutoLoad(grid, dataset, shelf.nextOffset, shelf.items.size, shelf.total, model.busy,
            model.error != null, model::loadMore)
        LazyVerticalGrid(GridCells.Adaptive(if (tv) 152.dp else 114.dp), state = grid,
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxSize().testTag("tv-library")) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    PlexLibraryToolbar(shelf.library, shelf.sort, shelf.contentType, model.busy, false,
                        onSort = { model.setLibraryOptions(sort = it) },
                        onContentType = { model.setLibraryOptions(contentType = it) },
                        firstControlModifier = if (target == null) Modifier.focusRequester(initial) else Modifier)
                    CatalogFilters(shelf, model)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Text("${shelf.total}개", style = MaterialTheme.typography.titleMedium) }
            if (shelf.personSearchIncomplete) item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("인물 검색을 완료하지 못했습니다. 확인된 결과를 표시합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TvAction("다시 시도", enabled = !model.busy, onClick = model::reload)
                }
            }
            model.error?.let { error -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TvAction("다시 시도", onClick = if (shelf.hasMore) model::loadMore else model::reload)
            } }
            if (shelf.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(if (model.busy) "작품을 불러오는 중입니다" else "표시할 작품이 없습니다")
            }
            items(shelf.items, key = ::tvMediaKey) { item ->
                CinemaMediaCard(item, modifier = if (target == item.id) Modifier.focusRequester(initial) else Modifier,
                    onSelect = { focused = item.id }, onClick = { model.openItem(item) }, onWatchOptions = { onWatch(item) }) {
                    PlexArtwork(item.poster ?: item.thumbnail, model, Modifier.fillMaxSize())
                }
            }
            if (model.busy) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogFilters(shelf: PlexPage.Shelf, model: PlexBrowseModel) {
    var selectingWatch by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(shelf.query.isNotBlank()) }
    var query by rememberSaveable { mutableStateOf(shelf.query) }
    val keyboard = LocalSoftwareKeyboardController.current
    val searchLabel = if (shelf.contentType in setOf(LibraryContentType.MOVIES, LibraryContentType.SHOWS))
        "제목·인물 이름 검색" else "제목 검색"
    val submit = { keyboard?.hide(); model.setLibraryOptions(query = query.trim()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TvAction("시청 상태 · ${shelf.watchFilter.label} ▾", enabled = !model.busy) { selectingWatch = true }
        TvAction(searchLabel, enabled = !model.busy) { searching = !searching }
        if (shelf.query.isNotBlank()) TvAction("검색 지우기") { model.setLibraryOptions(query = "") }
    }
    if (searching) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.weight(1f).tvOnlyFocus(), label = { Text(searchLabel) },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }))
        TvAction("검색", enabled = !model.busy, onClick = submit)
    }
    if (selectingWatch) AlertDialog(onDismissRequest = { selectingWatch = false }, title = { Text("시청 상태") },
        text = { Column {
            LibraryWatchFilter.entries.forEach { filter ->
                TvAction(filter.label, Modifier.fillMaxWidth()) {
                    selectingWatch = false
                    model.setLibraryOptions(watchFilter = filter)
                }
            }
        } }, confirmButton = { TextButton(onClick = { selectingWatch = false }) { Text("닫기") } })
}

@Composable
internal fun PlexDetails(detail: PlexPage.Detail, model: PlexBrowseModel, play: (MediaItem, Boolean) -> Unit,
    onWatch: (MediaItem) -> Unit) {
    val discovery = rememberPlexDiscovery(detail.item, detail.cast, model)
    CompactDetailContent(MediaDetails(detail.item, detail.children, detail.seasons, detail.selectedSeasonId, detail.cast),
        versions = detail.versions, selectedPartId = detail.selectedPartId, busy = model.busy, error = model.error,
        spoilerUnlocked = false, onPlay = play, onOpenItem = model::openItem, onSelectSeason = model::selectSeason,
        onSelectVersion = model::selectVersion, onWatchOptions = onWatch, onWatchedChange = model::setWatched,
        artwork = { item, modifier -> PlexMediaArtwork(item, model, modifier) }, overviewFirst = true,
        onBack = model::back, onRefreshDetail = model::reload, ratingScope = model.libraryOrderScope,
        backdropArtwork = { item, modifier -> PlexArtwork(item.backdrop ?: item.thumbnail ?: item.poster, model,
            modifier, backdrop = true, alignment = Alignment.Center) },
        discovery = discovery.state, libraries = model.libraries, onSelectActor = discovery.selectActor,
        onRetryRelated = discovery.retryRelated, portrait = { actor, modifier -> PlexArtwork(actor.portrait, model, modifier) },
        fileInformation = if (detail.item.playable) ({
            val files = detail.files.filter { detail.selectedPartId == null || it.partId == detail.selectedPartId }
            FileInformationSection(files, model.busy, model.error, onRetry = model::reload)
        }) else null)
}

@Composable
internal fun PlexMediaArtwork(item: MediaItem, model: PlexBrowseModel, modifier: Modifier) {
    val url = if (item.type == "episode") item.thumbnail ?: item.poster else item.poster ?: item.thumbnail
    PlexArtwork(url, model, modifier)
}

@Composable
private fun PlexArtwork(url: String?, model: PlexBrowseModel, modifier: Modifier, backdrop: Boolean = false,
    alignment: Alignment = if (backdrop) Alignment.CenterEnd else Alignment.Center) {
    val context = LocalContext.current
    val request = remember(model.artworkSession, url) {
        val headers = url?.let(model::headersFor).orEmpty()
        if (url.isNullOrBlank() || headers.isEmpty()) null else ImageRequest.Builder(context)
            .data(url).memoryCacheKey("${model.artworkSession}:$url").diskCachePolicy(CachePolicy.DISABLED)
            .httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> set(name, value) } }.build())
            .build()
    }
    Box(if (backdrop) modifier else modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center) {
        if (!backdrop) CinemaIcon(CinemaGlyph.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (request != null) AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            alignment = alignment)
    }
}
