package io.mirr.shynigsy.ui

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaVersion
import io.mirr.shynigsy.data.resolutionFolders
import kotlinx.coroutines.launch

private val DetailBackground = Color(0xFF10171C)
private val DetailSecondary = Color(0xFFB9BEC2)
private val DetailControl = Color(0xFF343B40)
private val DetailColors = darkColorScheme(
    background = DetailBackground, surface = DetailBackground,
    onSurface = Color.White, onBackground = Color.White, onSurfaceVariant = DetailSecondary,
    surfaceContainer = Color(0xFF20282D), surfaceContainerHigh = Color(0xFF2A3237),
    primary = Color.White, onPrimary = DetailBackground, secondary = DetailSecondary,
)

@Composable
internal fun MobileDetailContent(
    item: MediaItem, children: List<MediaItem>, seasons: List<MediaItem>, selectedSeasonId: Int?,
    versions: List<MediaVersion>, selectedPartId: Int?, busy: Boolean, error: String?,
    onBack: (() -> Unit)?, onRefresh: (() -> Unit)?, ratingScope: String?,
    onPlay: (MediaItem, Boolean) -> Unit, onOpen: (MediaItem) -> Unit,
    onSelectSeason: (MediaItem) -> Unit, onSelectVersion: (Int?) -> Unit,
    onWatchOptions: (MediaItem) -> Unit, artwork: @Composable (MediaItem, Modifier) -> Unit,
    backdrop: @Composable (MediaItem, Modifier) -> Unit, modifier: Modifier,
    fileInformation: (@Composable () -> Unit)?, hasDiscovery: Boolean, discovery: @Composable () -> Unit,
) {
    val identity = compactMediaIdentity(item)
    val list = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val cast = rememberDetailCastAction()
    var rate by rememberSaveable(identity) { mutableStateOf(false) }
    var more by rememberSaveable(identity) { mutableStateOf(false) }
    var resolutionDialog by rememberSaveable(identity) { mutableStateOf(false) }
    val target = if (item.playable) item else children.firstOrNull { it.playable && !it.watched && it.resumeMs > 0 }
        ?: children.firstOrNull { it.playable && !it.watched } ?: children.firstOrNull { it.playable }
    val selectedSeason = seasons.firstOrNull { it.id == selectedSeasonId } ?: seasons.firstOrNull()
    val resolutions = remember(versions) { resolutionFolders(versions) }
    val quality = (resolutions.firstOrNull { folder -> folder.versions.any { it.partId == selectedPartId } }
        ?: resolutions.singleOrNull())?.takeUnless { it.key == "unknown" }?.label
        ?: target?.resolutionLabel ?: item.resolutionLabel
    val metadata = mobileDetailMetadata(item, selectedSeason, quality)
    val canRestart = target != null && target.resumeMs > 0 && !target.watched
    val hasMore = canRestart || resolutions.size > 1 || children.isNotEmpty() ||
        fileInformation != null || hasDiscovery || onRefresh != null
    // The reference places the system status icons on top of the artwork, including in a light app theme.
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousStatus = bars?.isAppearanceLightStatusBars
        val previousNavigation = bars?.isAppearanceLightNavigationBars
        val previousContrast = if (Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        if (Build.VERSION.SDK_INT >= 29 && window != null) window.isNavigationBarContrastEnforced = false
        onDispose {
            if (previousStatus != null) bars.isAppearanceLightStatusBars = previousStatus
            if (previousNavigation != null) bars.isAppearanceLightNavigationBars = previousNavigation
            if (Build.VERSION.SDK_INT >= 29 && previousContrast != null) window?.isNavigationBarContrastEnforced = previousContrast
        }
    }
    MaterialTheme(colorScheme = DetailColors) {
        BoxWithConstraints(modifier.fillMaxSize().background(DetailBackground)) {
            val heroHeight = minOf(maxWidth, maxHeight * .64f, 520.dp)
            val pageInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            val bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
            LazyColumn(Modifier.fillMaxSize().testTag("compact-detail"), state = list,
                contentPadding = PaddingValues(bottom = bottom + 20.dp)) {
                item(key = "header") {
                    Column {
                        MobileDetailHero(item, heroHeight, selectedSeason, seasons, metadata, busy, onSelectSeason, backdrop)
                        Column(Modifier.fillMaxWidth().windowInsetsPadding(pageInsets).padding(horizontal = 16.dp)) {
                            val playLabel = when {
                                target?.watched == true -> "다시 재생"
                                canRestart -> "이어보기"
                                else -> "재생"
                            }
                            Button(onClick = { target?.let { onPlay(it, it.watched) } }, enabled = !busy && target != null,
                                modifier = Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = 44.dp)
                                    .testTag("compact-detail-play"),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White,
                                    contentColor = Color(0xFF14181B)), shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(playLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp)
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.Top) {
                                if (ratingScope != null) DetailAction("내 평점", Icons.Outlined.StarBorder,
                                    Modifier.testTag("compact-detail-rating")) { rate = true }
                                if (canChangeWatchState(item)) DetailAction(
                                    if (item.watched) "시청 완료" else "시청 상태", Icons.Rounded.Check,
                                    Modifier.testTag("compact-detail-watch"), enabled = !busy) { onWatchOptions(item) }
                                if (hasMore) Box {
                                    DetailAction("더보기", Icons.Rounded.MoreHoriz, Modifier.testTag("compact-detail-more")) { more = true }
                                    DropdownMenu(more, onDismissRequest = { more = false }) {
                                        fun closeAnd(action: () -> Unit) { more = false; action() }
                                        if (canRestart && target != null) DropdownMenuItem(
                                            text = { Text("처음부터 보기") }, enabled = !busy,
                                            modifier = Modifier.testTag("compact-detail-restart"),
                                            onClick = { closeAnd { onPlay(target, true) } })
                                        if (resolutions.size > 1) DropdownMenuItem(text = { Text("해상도 선택") },
                                            modifier = Modifier.testTag("compact-detail-resolution"),
                                            onClick = { closeAnd { resolutionDialog = true } })
                                        if (children.isNotEmpty()) DropdownMenuItem(text = { Text("회차 보기") },
                                            modifier = Modifier.testTag("compact-detail-episodes-jump"),
                                            onClick = { closeAnd { coroutineScope.launch { list.scrollToItem(if (error == null) 1 else 2) } } })
                                        if (fileInformation != null) DropdownMenuItem(text = { Text("파일 정보") },
                                            onClick = { closeAnd { coroutineScope.launch {
                                                list.scrollToItem((list.layoutInfo.totalItemsCount - 2).coerceAtLeast(0))
                                            } } })
                                        if (hasDiscovery) DropdownMenuItem(text = { Text("출연진 · 관련 작품") },
                                            modifier = Modifier.testTag("compact-detail-discovery-jump"),
                                            onClick = { closeAnd { coroutineScope.launch {
                                                list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                                            } } })
                                        if (onRefresh != null) DropdownMenuItem(text = { Text("새로고침") },
                                            enabled = !busy, onClick = { closeAnd(onRefresh) })
                                    }
                                }
                            }
                            if (item.summary.isNotBlank()) MobileDetailSynopsis(item.summary)
                            Spacer(Modifier.height(18.dp))
                        }
                    }
                }
                if (error != null) item(key = "error") {
                    Text(error, Modifier.windowInsetsPadding(pageInsets).padding(horizontal = 16.dp)
                        .testTag("compact-detail-error"), color = MaterialTheme.colorScheme.error)
                }
                if (!item.playable) {
                    if (children.isNotEmpty()) mobileDetailEpisodes(children, busy, onOpen, onWatchOptions, artwork)
                    else item(key = "episode-status") {
                        Text(if (busy) "회차를 불러오는 중입니다" else "표시할 회차가 없습니다",
                            Modifier.windowInsetsPadding(pageInsets).padding(horizontal = 16.dp)
                                .testTag(if (busy) "compact-episodes-loading" else "compact-episodes-empty"),
                            color = DetailSecondary, fontSize = 14.sp)
                    }
                }
                if (fileInformation != null) item(key = "file-information") {
                    Box(Modifier.windowInsetsPadding(pageInsets).padding(horizontal = 16.dp, vertical = 8.dp)) { fileInformation() }
                }
                item(key = "discovery") {
                    Box(Modifier.windowInsetsPadding(pageInsets).padding(horizontal = 16.dp, vertical = 8.dp)) { discovery() }
                }
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()
                .windowInsetsPadding(pageInsets).padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                FloatingDetailAction("뒤로", Icons.AutoMirrored.Rounded.ArrowBack, Modifier.testTag("compact-detail-back"),
                    onBack ?: { backDispatcher?.onBackPressed(); Unit })
                if (cast != null) FloatingDetailAction("화면 전송", Icons.Outlined.Cast, Modifier.testTag("compact-detail-cast"), cast)
            }
        }
        if (rate && ratingScope != null) DetailRatingDialog(item, ratingScope) { rate = false }
        if (resolutionDialog) AlertDialog(onDismissRequest = { resolutionDialog = false },
            title = { Text("재생 해상도") }, text = {
                ResolutionFolderSelector(versions, selectedPartId, !busy) {
                    resolutionDialog = false
                    onSelectVersion(it)
                }
            }, confirmButton = { TextButton(onClick = { resolutionDialog = false }) { Text("닫기") } })
    }
}

private fun mobileDetailMetadata(item: MediaItem, season: MediaItem?, quality: String?): String {
    val seasonNumber = season?.seasonNumber ?: item.seasonNumber
    val parts = when (item.type) {
        "show", "tv", "season" -> listOfNotNull(seasonNumber?.let { "시즌 $it" }
            ?: season?.title ?: item.year?.toString(), quality)
        "episode" -> listOfNotNull(seasonNumber?.let { "시즌 $it" }, item.episodeNumber?.let { "${it}화" }, quality)
        else -> listOfNotNull(item.year?.toString(), (item.durationMs / 60_000).takeIf { it > 0 }?.let { "${it}분" }, quality)
    }
    return parts.joinToString("  ")
}

@Composable
private fun MobileDetailHero(item: MediaItem, height: Dp, selectedSeason: MediaItem?, seasons: List<MediaItem>,
    metadata: String, busy: Boolean, onSelectSeason: (MediaItem) -> Unit,
    backdrop: @Composable (MediaItem, Modifier) -> Unit) {
    var showSeasons by rememberSaveable(compactMediaIdentity(item)) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().heightIn(min = height), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.matchParentSize().testTag("compact-detail-art")) { backdrop(item, Modifier.fillMaxSize()) }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .20f), .34f to Color.Black.copy(alpha = .10f),
            .58f to DetailBackground.copy(alpha = .52f), .79f to DetailBackground.copy(alpha = .88f), 1f to DetailBackground)))
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(start = 18.dp, end = 18.dp, top = height * .57f, bottom = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (item.type == "season") item.showTitle?.takeIf { it.isNotBlank() } ?: item.title else item.title,
                Modifier.testTag("compact-detail-title"), color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 26.sp, lineHeight = 33.sp, textAlign = TextAlign.Center)
            if (metadata.isNotBlank()) Box {
                val selector = if (seasons.isNotEmpty()) Modifier.clip(CircleShape)
                    .clickable(enabled = !busy, role = Role.Button) { showSeasons = true }.testTag("compact-seasons") else Modifier
                Row(selector.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(metadata, Modifier.testTag("compact-detail-metadata"), color = DetailSecondary,
                        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    if (seasons.size > 1) Icon(Icons.Rounded.ExpandMore, "시즌 선택", Modifier.size(20.dp), tint = DetailSecondary)
                }
                DropdownMenu(showSeasons, onDismissRequest = { showSeasons = false }) {
                    seasons.forEach { season -> DropdownMenuItem(
                        text = { Text(season.title) }, modifier = Modifier
                            .testTag("compact-season-${compactMediaIdentity(season)}").semantics { selected = season.id == selectedSeason?.id },
                        onClick = { showSeasons = false; onSelectSeason(season) }) }
                }
            }
        }
    }
}

@Composable
private fun MobileDetailSynopsis(summary: String) {
    var expanded by rememberSaveable(summary) { mutableStateOf(false) }
    var clipped by remember(summary) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(summary, Modifier.testTag("compact-detail-summary"), color = DetailSecondary, fontSize = 14.sp,
            lineHeight = 20.sp, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow })
        if (clipped || expanded) TextButton(onClick = { expanded = !expanded },
            modifier = Modifier.testTag("compact-summary-toggle"), contentPadding = PaddingValues(0.dp)) {
            Text(if (expanded) "접기" else "줄거리 더 보기")
        }
    }
}

@Composable
private fun DetailAction(label: String, image: ImageVector, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit) {
    Column(modifier.widthIn(min = 66.dp).clip(MaterialTheme.shapes.small)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).background(DetailControl, CircleShape), contentAlignment = Alignment.Center) {
            Icon(image, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = if (enabled) 1f else .45f))
        }
        Text(label, Modifier.padding(top = 5.dp), color = DetailSecondary, fontSize = 11.sp,
            lineHeight = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FloatingDetailAction(label: String, image: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Box(Modifier.size(36.dp).background(DetailBackground.copy(alpha = .66f), CircleShape),
            contentAlignment = Alignment.Center) { Icon(image, label, Modifier.size(21.dp), tint = Color.White) }
    }
}
