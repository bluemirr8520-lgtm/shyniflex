package io.mirr.shynigsy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
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
import io.mirr.shynigsy.DetailDiscoveryState
import io.mirr.shynigsy.detailDiscoveryKey
import io.mirr.shynigsy.data.CastMember
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaVersion
import io.mirr.shynigsy.data.MediaFileInfo
import io.mirr.shynigsy.data.SpoilerAccessRegistry
import io.mirr.shynigsy.data.resolutionFolders
import io.mirr.shynigsy.data.libraryOrderScope
import io.mirr.shynigsy.isTelevision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CompactDetailScreen(state: AppState, model: AppViewModel, onWatchOptions: (MediaItem) -> Unit) {
    val detail = state.detailStack.lastOrNull() ?: return
    val grantRevision by SpoilerAccessRegistry.changes.collectAsState()
    val fileLibraries = state.libraries.filter { it.id == detail.item.libraryId &&
        it.type == (if (detail.item.type == "movie") "movie" else "tv") }
    val privateFile = detail.item.isSpoiler || state.libraries.any { it.id == detail.item.libraryId && it.isSpoiler }
    val fileAuthorized = state.loggedIn && detail.item.playable && fileLibraries.isNotEmpty() &&
        (!privateFile || state.spoilerUnlocked)
    val fileKey = listOf(state.server, state.userName, detailDiscoveryKey(detail.item), state.selectedPartId,
        detail.item.navigationPartId, detail.item.playbackPartId, privateFile, fileAuthorized,
        fileLibraries.map { it.id to it.isSpoiler }, if (privateFile) grantRevision else 0L)
    var files by remember(fileKey) { mutableStateOf<List<MediaFileInfo>>(emptyList()) }
    var filesLoading by remember(fileKey) { mutableStateOf(detail.item.playable) }
    var filesError by remember(fileKey) { mutableStateOf<String?>(null) }
    var filesRetry by remember(fileKey) { mutableStateOf(0) }
    val fileDetailsReady = fileAuthorized && !state.busy
    LaunchedEffect(fileKey, fileDetailsReady, filesRetry) {
        if (!detail.item.playable || !fileDetailsReady) return@LaunchedEffect
        filesLoading = true
        filesError = null
        try {
            files = model.detailFileInformation(detail.item, state.selectedPartId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            filesError = "파일 정보를 불러오지 못했습니다. 다시 시도해 주세요."
        } finally {
            filesLoading = false
        }
    }
    CompactDetailContent(detail = detail, versions = state.versions, selectedPartId = state.selectedPartId,
        busy = state.busy && !state.homeLoading, error = state.error, spoilerUnlocked = state.spoilerUnlocked,
        overviewFirst = state.library == null && !state.searchOpen,
        onPlay = { item, startOver -> model.play(item, startOver) }, onOpenItem = model::openItem,
        onSelectSeason = model::selectSeason, onSelectVersion = model::selectVersion,
        onWatchOptions = onWatchOptions, onWatchedChange = { item, watched -> model.setWatched(item, watched) },
        artwork = { item, modifier -> Poster(item, model, modifier,
            imageUrl = if (item.type == "episode") item.thumbnail ?: item.poster else item.poster) },
        onBack = model::back, onRefreshDetail = model::refresh,
        ratingScope = libraryOrderScope(state.server, state.userName),
        backdropArtwork = { item, modifier ->
            val image = item.backdrop ?: item.thumbnail ?: item.poster
            if (image != null) Poster(item, model, modifier, transparentBackground = true, imageUrl = image)
        },
        discovery = state.detailDiscovery[detailDiscoveryKey(detail.item)], libraries = state.libraries,
        onSelectActor = model::selectDetailActor, onRetryRelated = model::retryRelatedWorks,
        onSearchActorMovies = model::searchDetailActorMovies, onCancelActorMovieSearch = model::cancelDetailActorMovieSearch,
        portrait = { actor, modifier -> Poster(detail.item, model, modifier, imageUrl = actor.portrait) },
        fileInformation = if (fileAuthorized) ({
            FileInformationSection(files, filesLoading, filesError, onRetry = { filesRetry++ })
        }) else null)
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
    overviewFirst: Boolean = false,
    discovery: DetailDiscoveryState? = null,
    libraries: List<Library> = emptyList(),
    onSelectActor: (CastMember) -> Unit = {},
    onRetryRelated: () -> Unit = {},
    onSearchActorMovies: () -> Unit = {},
    onCancelActorMovieSearch: () -> Unit = {},
    portrait: @Composable (CastMember, Modifier) -> Unit = { _, _ -> },
    fileInformation: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onRefreshDetail: (() -> Unit)? = null,
    ratingScope: String? = null,
    backdropArtwork: @Composable (MediaItem, Modifier) -> Unit = artwork,
) {
    val item = detail.item
    if (item.isSpoiler && !spoilerUnlocked) return
    val children = detail.children.filter { spoilerUnlocked || !it.isSpoiler }
    val seasons = detail.seasons.filter { spoilerUnlocked || !it.isSpoiler }
    val identity = compactMediaIdentity(item)
    val frame = discovery?.takeIf { detailDiscoveryKey(it.parent) == detailDiscoveryKey(item) }
    val television = LocalContext.current.isTelevision()
    if (!television && LocalConfiguration.current.smallestScreenWidthDp < 600) {
        MobileDetailContent(item, children, seasons, detail.selectedSeasonId, versions, selectedPartId,
            busy, error, onBack, onRefreshDetail, ratingScope, onPlay, onOpenItem, onSelectSeason, onSelectVersion,
            onWatchOptions, artwork, backdropArtwork, modifier, fileInformation,
            hasDiscovery = detail.castMembers.isNotEmpty() || frame?.let {
                it.relatedItems.isNotEmpty() || it.relatedLoading || it.relatedError != null
            } == true,
        ) {
            DetailDiscoverySections(item, detail.castMembers, frame, libraries, spoilerUnlocked, busy,
                onSelectActor, onOpenItem, onWatchOptions, onRetryRelated, artwork, portrait,
                onSearchActorMovies = onSearchActorMovies, onCancelActorMovieSearch = onCancelActorMovieSearch)
        }
        return
    }
    var focusedDiscovery by rememberSaveable(identity) { mutableStateOf<String?>(null) }
    var restoreDiscoveryFocus by remember { mutableStateOf(false) }
    val discoveryFocus = remember { FocusRequester() }
    val discoveryTargets = discoveryFocusTargets(item, detail.castMembers, frame, libraries, spoilerUnlocked)
    val childKeys = children.map(::compactMediaIdentity)
    var focusedChildKey by rememberSaveable(identity) { mutableStateOf<String?>(null) }
    val restoredChild = focusedChildKey?.takeIf { it in childKeys } ?: childKeys.firstOrNull()
    val selectedSeason = seasons.firstOrNull { it.id == detail.selectedSeasonId } ?: seasons.firstOrNull()
    val focus = remember { FocusRequester() }
    val overviewFocus = remember { FocusRequester() }
    val hasOverviewAction = overviewFirst && !item.playable && children.isNotEmpty()
    val list = rememberLazyListState()
    val jumpScope = rememberCoroutineScope()
    val seasonList = rememberLazyListState()
    var focusRequested by remember(identity, detail.selectedSeasonId, childKeys, overviewFirst) { mutableStateOf(false) }
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    val hasFocusTarget = item.playable || children.isNotEmpty() || seasons.isNotEmpty() || discoveryTargets.isNotEmpty()
    val hasResolutionChoice = remember(versions) { resolutionFolders(versions).size > 1 }
    val primaryRows = if (item.playable) 1 + (if (hasResolutionChoice) 1 else 0) else children.size.coerceAtLeast(1)
    val firstEpisodeIndex = (if (seasons.isNotEmpty()) 1 else 0) + (if (error != null) 1 else 0)
    val discoveryIndex = firstEpisodeIndex + primaryRows + (if (item.playable && fileInformation != null) 1 else 0)
    LaunchedEffect(television, busy, hasFocusTarget, restoredChild, detail.selectedSeasonId, overviewFirst) {
        if (!television || busy) return@LaunchedEffect
        if (!hasFocusTarget || focusRequested) return@LaunchedEffect
        if ((focusedDiscovery != null || (!item.playable && children.isEmpty() && seasons.isEmpty())) && discoveryTargets.isNotEmpty()) {
            list.scrollToItem(discoveryIndex)
            restoreDiscoveryFocus = true
            focusRequested = true
            return@LaunchedEffect
        }
        val restoredIndex = childKeys.indexOf(restoredChild)
        list.scrollToItem(if (!item.playable && restoredIndex > 0) firstEpisodeIndex + restoredIndex else 0)
        if (!item.playable && children.isEmpty() && selectedSeason != null)
            seasonList.scrollToItem(seasons.indexOf(selectedSeason))
        withFrameNanos { }
        if (hasOverviewAction && restoredIndex <= 0) overviewFocus.requestFocus() else focus.requestFocus()
        focusRequested = true
    }

    Row(modifier.fillMaxSize().testTag("compact-detail").padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().testTag("detail-overview-pane")
            .verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!television && onBack != null) TvAction("뒤로", onClick = onBack)
            val surface = MaterialTheme.colorScheme.surface
            Box(Modifier.fillMaxWidth().background(surface)) {
                if (item.backdrop != null) {
                    backdropArtwork(item, Modifier.matchParentSize())
                    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                        0f to surface, 0.55f to surface.copy(alpha = .94f), 1f to surface.copy(alpha = .42f))))
                }
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(if (item.type == "episode") 104.dp else 88.dp)
                            .aspectRatio(if (item.type == "episode") 16f / 9f else 2f / 3f)
                            .testTag("compact-detail-art")) {
                            artwork(item, Modifier.fillMaxSize())
                            if (item.watched) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(2.dp)
                                .testTag("compact-detail-watched-${compactMediaIdentity(item)}"), compact = true)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("compact-detail-title"))
                            val metadata = if (item.playable) continueHeroMetadata(item) else listOfNotNull(
                                if (item.type == "season") item.seasonNumber?.let { "시즌 $it" } ?: "시즌" else "시리즈",
                                item.year?.toString(), children.size.takeIf { it > 0 }?.let { "${it}회차" },
                            ).joinToString(" · ")
                            if (metadata.isNotBlank()) Text(metadata, fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("compact-detail-metadata"))
                            if (!item.watched && item.resumeMs > 0) Text("${timeLabel(item.resumeMs)}까지 시청",
                                fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column {
                        if ((!item.isSpoiler || spoilerUnlocked) &&
                            (detail.castMembers.isNotEmpty() || discoveryTargets.isNotEmpty() || frame?.relatedLoading == true || frame?.relatedError != null)) {
                            TvAction("출연진 · 관련 작품", Modifier.testTag("compact-detail-discovery-jump"), enabled = !busy) {
                                focusedDiscovery = null
                                jumpScope.launch {
                                    list.scrollToItem(discoveryIndex)
                                    if (television && discoveryTargets.isNotEmpty()) restoreDiscoveryFocus = true
                                }
                            }
                        }
                        if (hasOverviewAction) TvAction("회차 보기", Modifier.focusRequester(overviewFocus)
                            .testTag("compact-detail-episodes-jump").onFocusChanged { if (it.hasFocus) focusedDiscovery = null },
                            enabled = !busy) {
                            jumpScope.launch {
                                list.scrollToItem(firstEpisodeIndex + childKeys.indexOf(restoredChild).coerceAtLeast(0))
                                if (television) {
                                    withFrameNanos { }
                                    focus.requestFocus()
                                }
                            }
                        }
                    }
                }
            }
            if (item.summary.isNotBlank()) {
                CompactDetailSummary(item.summary, expanded) { expanded = !expanded }
            }
            if (!television) {
                var showRating by rememberSaveable(identity) { mutableStateOf(false) }
                val cast = rememberDetailCastAction()
                if (ratingScope != null) TvAction("내 평점", onClick = { showRating = true })
                if (onRefreshDetail != null) TvAction("새로고침", enabled = !busy, onClick = onRefreshDetail)
                if (cast != null) TvAction("전송", onClick = cast)
                if (showRating && ratingScope != null) DetailRatingDialog(item, ratingScope) { showRating = false }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("detail-actions-pane"), state = list,
            contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (seasons.isNotEmpty()) item(key = "seasons") {
                LazyRow(state = seasonList, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("compact-seasons")) {
                    items(seasons, key = ::compactMediaIdentity) { season ->
                        TvAction(season.title, modifier = Modifier
                            .then(if (!item.playable && children.isEmpty() && season == selectedSeason) Modifier.focusRequester(focus) else Modifier)
                            .testTag("compact-season-${compactMediaIdentity(season)}")
                            .onFocusChanged { if (it.hasFocus) focusedDiscovery = null }
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
                    val canStartOver = item.resumeMs > 0 && !item.watched
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (canStartOver) TvAction("처음부터 보기",
                                Modifier.weight(1f).testTag("compact-detail-restart"), enabled = !busy) { onPlay(item, true) }
                            TvAction(if (item.watched) "다시 재생" else if (item.resumeMs > 0) "이어보기" else "재생",
                                modifier = Modifier.weight(1f).focusRequester(focus).testTag("compact-detail-play")
                                    .onFocusChanged { if (it.hasFocus) focusedDiscovery = null },
                                enabled = !busy) { onPlay(item, item.watched) }
                            if (!canStartOver && canChangeWatchState(item)) TvAction("시청 상태", Modifier.weight(1f).testTag("compact-detail-watch"),
                                enabled = !busy) { onWatchOptions(item) }
                        }
                        if (canStartOver && canChangeWatchState(item)) TvAction("시청 상태",
                            Modifier.fillMaxWidth().testTag("compact-detail-watch"), enabled = !busy) { onWatchOptions(item) }
                    }
                }
                if (hasResolutionChoice) item(key = "resolution") {
                    ResolutionFolderSelector(versions, selectedPartId, !busy, onSelectVersion)
                }
            } else {
                items(children, key = ::compactMediaIdentity) { child ->
                    CompactEpisodeRow(child, busy, onOpenItem, onWatchOptions, artwork,
                        modifier = Modifier.then(if (compactMediaIdentity(child) == restoredChild) Modifier.focusRequester(focus) else Modifier)
                            .onFocusChanged { if (it.hasFocus) { focusedChildKey = compactMediaIdentity(child); focusedDiscovery = null } },
                        onWatchedChange = onWatchedChange)
                }
                if (children.isEmpty()) item(key = "episode-status") {
                    Text(if (busy) "회차를 불러오는 중입니다" else "표시할 회차가 없습니다", fontSize = 12.sp,
                        modifier = Modifier.testTag(if (busy) "compact-episodes-loading" else "compact-episodes-empty"))
                }
            }
            if (item.playable && fileInformation != null) item(key = "file-information") { fileInformation() }
            item(key = "discovery") {
                DetailDiscoverySections(item, detail.castMembers, frame, libraries, spoilerUnlocked, busy,
                    onSelectActor, onOpenItem, onWatchOptions, onRetryRelated, artwork, portrait,
                    restoreTarget = focusedDiscovery, requestFocus = restoreDiscoveryFocus, focus = discoveryFocus,
                    onFocused = { focusedDiscovery = it }, onRestoreDone = { restoreDiscoveryFocus = false },
                    onSearchActorMovies = onSearchActorMovies, onCancelActorMovieSearch = onCancelActorMovieSearch)
            }
        }
    }
}

@Composable
private fun CompactDetailSummary(summary: String, expanded: Boolean, onToggle: () -> Unit) {
    var truncated by remember(summary) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(summary, fontSize = 16.sp, lineHeight = 23.sp, maxLines = if (expanded) Int.MAX_VALUE else 3,
            onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("compact-detail-summary"))
        if (expanded || truncated) TvAction(if (expanded) "접기" else "줄거리 더 보기",
            Modifier.testTag("compact-summary-toggle"), onClick = onToggle)
    }
}
