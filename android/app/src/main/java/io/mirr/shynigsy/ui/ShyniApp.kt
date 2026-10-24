package io.mirr.shynigsy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.crossfade
import androidx.compose.ui.layout.ContentScale
import io.mirr.shynigsy.AppViewModel
import io.mirr.shynigsy.isTelevision
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.MediaVersion
import io.mirr.shynigsy.data.PlaybackSpec

private val AppBackground = Color(0xFFF8FAFB)
private val Panel = Color(0xFFEEF2F4)
private val Accent = Color(0xFF006B5B)
private val Muted = Color(0xFF52616F)
internal val ShyniColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EEE7),
    onPrimaryContainer = Color(0xFF002C24),
    secondary = Color(0xFF285F91),
    onSecondary = Color.White,
    background = AppBackground,
    onBackground = Color(0xFF18242F),
    surface = Color.White,
    surfaceContainer = Panel,
    surfaceVariant = Color(0xFFE6ECEF),
    onSurface = Color(0xFF18242F),
    onSurfaceVariant = Muted,
    outline = Color(0xFF829199),
    outlineVariant = Color(0xFFCED8DD),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE5E2),
    onErrorContainer = Color(0xFF641A14),
)

@Composable
fun ShyniApp(
    viewModel: AppViewModel,
    onUpdate: () -> Unit = {},
    onPlay: (PlaybackSpec) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val tv = LocalContext.current.isTelevision()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val tvScreens = rememberSaveableStateHolder()
    var logoutConfirm by rememberSaveable { mutableStateOf(false) }
    var libraryPickerOpen by rememberSaveable { mutableStateOf(false) }
    var watchOptions by remember { mutableStateOf<MediaItem?>(null) }
    val onWatchOptions: (MediaItem) -> Unit = { if (canChangeWatchState(it)) watchOptions = it }
    val detail = state.detailStack.lastOrNull()
    LaunchedEffect(state.loggedIn) {
        if (!state.loggedIn) libraryPickerOpen = false
    }
    LaunchedEffect(state.loggedIn, state.spoilerUnlocked, watchOptions) {
        if (!state.loggedIn || (watchOptions?.isSpoiler == true && !state.spoilerUnlocked)) watchOptions = null
    }
    LaunchedEffect(state.spoilerUnlocked) {
        if (state.spoilerUnlocked) libraryPickerOpen = true
    }
    LaunchedEffect(state.playback) {
        state.playback?.let { spec ->
            onPlay(spec)
            viewModel.consumePlayback()
        }
    }
    BackHandler(enabled = state.loggedIn && (detail != null || state.library != null || state.searchOpen)) {
        viewModel.back()
    }
    MaterialTheme(colorScheme = ShyniColors, typography = CompactTypography, shapes = CompactShapes) {
        Scaffold(
            containerColor = AppBackground,
            topBar = {
                if (state.loggedIn) {
                    Column(Modifier.statusBarsPadding()) {
                    if (!tv) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { scope.launch { drawer.open() } },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("☰  메뉴") }
                        Text(detail?.item?.title ?: state.library?.title ?: if (state.searchOpen) "검색" else "shyniflex", Modifier.weight(1f).padding(start = 8.dp),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.library != null) LibraryHomeButton(onHome = viewModel::openHome)
                    }
                    else if (detail != null || state.library != null || state.searchOpen) TvHeader(
                        title = detail?.item?.title ?: state.library?.title ?: "검색",
                        onHome = if (state.library != null) viewModel::openHome else null,
                    )
                    }
                } else
                AppHeader(
                    loggedIn = state.loggedIn,
                    title = detail?.item?.title ?: state.library?.title,
                    userName = state.userName,
                    onBack = viewModel::back,
                    onRefresh = { viewModel.refresh() },
                    onLogout = { logoutConfirm = true },
                    onLibraries = { libraryPickerOpen = true },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    !state.loggedIn -> LoginScreen(
                        initialServer = state.server,
                        initialUser = state.userName,
                        busy = state.busy,
                        error = state.error,
                        totpChallenge = state.totpChallenge,
                        onLogin = viewModel::login,
                        onTotp = viewModel::submitTotp,
                        onCancelTotp = viewModel::cancelTotp,
                    )
                    state.loggedIn -> {
                        val sidebar: @Composable () -> Unit = {
                        TvSidebar(onLibraries = { libraryPickerOpen = true }, onRefresh = viewModel::refresh,
                            onLogout = { logoutConfirm = true },
                            libraries = state.libraries.filter { state.spoilerUnlocked || !it.isSpoiler },
                            selectedLibraryId = state.library?.id,
                            homeSelected = state.library == null && detail == null && !state.searchOpen,
                            onHome = { viewModel.openHome(); scope.launch { drawer.close() } },
                            onLibrary = { viewModel.selectLibrary(it); scope.launch { drawer.close() } },
                            showLocked = !state.spoilerUnlocked,
                            spoilerUnlocked = state.spoilerUnlocked, onLockSpoiler = viewModel::lockSpoiler,
                            onUpdate = onUpdate, searchSelected = state.searchOpen,
                            onSearch = { viewModel.openSearch(); scope.launch { drawer.close() } })
                        }
                        val content: @Composable () -> Unit = {
                        Box(Modifier.fillMaxSize()) {
                        val screenKey = detail?.item?.let { "detail:${it.libraryId}:${it.type}:${it.id}:${it.viewItemId}" }
                            ?: if (state.searchOpen) "search" else state.library?.let { "library:${it.id}" } ?: "home"
                        tvScreens.SaveableStateProvider(screenKey) {
                            when {
                                detail != null -> TvDetail(state, viewModel, onWatchOptions)
                                state.searchOpen -> GlobalSearchScreen(state, viewModel, onWatchOptions)
                                state.library != null -> TvLibrary(state, viewModel, onWatchOptions)
                                else -> TvHome(state, viewModel, onWatchOptions)
                            }
                        }
                        }
                        }
                        if (tv) Row(Modifier.fillMaxSize()) { sidebar(); Box(Modifier.weight(1f)) { content() } }
                        else ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                            ModalDrawerSheet { sidebar() }
                        }) { content() }
                    }

                }
                if (state.busy && state.loggedIn) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
        if (logoutConfirm) AlertDialog(
            onDismissRequest = { logoutConfirm = false },
            title = { Text("로그아웃") },
            text = { Text("이 기기에서 로그아웃할까요?") },
            confirmButton = {
                TextButton(onClick = { logoutConfirm = false; viewModel.logout() }, modifier = Modifier.tvOnlyFocus()) { Text("로그아웃") }
            },
            dismissButton = { TextButton(onClick = { logoutConfirm = false }, modifier = Modifier.tvOnlyFocus()) { Text("취소") } },
        )
        if (libraryPickerOpen && state.loggedIn) LibraryPicker(
            libraries = state.libraries,
            selectedLibraryId = state.library?.id,
            busy = state.busy && !state.homeLoading,
            onDismiss = { libraryPickerOpen = false },
            onSelect = { library -> libraryPickerOpen = false; viewModel.selectLibrary(library) },
            spoilerAvailable = state.spoilerAvailable,
            spoilerUnlocked = state.spoilerUnlocked,
            spoilerBusy = state.spoilerBusy,
            spoilerError = state.spoilerError,
            onUnlockSpoiler = {
                if (state.spoilerPinEnabled) libraryPickerOpen = false
                viewModel.requestSpoilerUnlock()
            },
            onLockSpoiler = viewModel::lockSpoiler,
        )
        if (state.spoilerPromptOpen && state.loggedIn) SpoilerPinDialog(
            busy = state.spoilerBusy, error = state.spoilerError,
            onDismiss = viewModel::dismissSpoilerUnlock, onUnlock = viewModel::unlockSpoiler,
        )
        watchOptions?.takeIf { !it.isSpoiler || state.spoilerUnlocked }?.let { item ->
            WatchedOptionsDialog(item, onDismiss = { watchOptions = null }, onSetWatched = { watched ->
                watchOptions = null
                viewModel.setWatched(item, watched)
            })
        }
    }
}

@Composable
private fun AppHeader(
    loggedIn: Boolean,
    title: String?,
    userName: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onLibraries: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.padding(horizontal = 10.dp).size(38.dp).clip(RoundedCornerShape(12.dp)).background(Accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary) }
            Column(Modifier.weight(1f)) {
                Text(
                    title ?: "shyniflex",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (loggedIn) {
                IconButton(onClick = onLibraries) {
                    Icon(Icons.Rounded.Folder, "라이브러리")
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "새로고침") }
                IconButton(onClick = onLogout) {
                    Surface(color = Panel, shape = CircleShape, modifier = Modifier.size(30.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(userName.take(1).uppercase().ifBlank { "S" }, fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { contentDescription = "로그아웃" })
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun LoginScreen(
    initialServer: String,
    initialUser: String,
    busy: Boolean,
    error: String?,
    totpChallenge: String?,
    onLogin: (String, String, String) -> Unit,
    onTotp: (String) -> Unit,
    onCancelTotp: () -> Unit,
) {
    var server by rememberSaveable(initialServer) { mutableStateOf(initialServer) }
    var user by rememberSaveable(initialUser) { mutableStateOf(initialUser) }
    // Passwords and one-time codes deliberately stay in memory, outside saved instance state.
    var password by remember { mutableStateOf("") }
    var code by remember(totpChallenge) { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val tv = LocalContext.current.isTelevision()
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(tv, totpChallenge, busy) {
        if (tv && !busy) { androidx.compose.runtime.withFrameNanos { }; initialFocus.requestFocus() }
    }
    fun submitLogin() {
        focus.clearFocus()
        onLogin(server.trim(), user.trim(), password)
    }
    LazyColumn(
        modifier = if (tv) Modifier.fillMaxSize().wrapContentSize(Alignment.TopCenter).widthIn(max = 640.dp) else Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Spacer(Modifier.height(16.dp)) }
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
                Icon(Icons.Rounded.SmartDisplay, null, tint = Accent, modifier = Modifier.padding(24.dp).size(40.dp))
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (totpChallenge == null) "Shyni에 연결하세요" else "2단계 인증",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        if (totpChallenge == null) {
            item {
                OutlinedTextField(server, { server = it }, label = { Text("서버 주소") },
                    enabled = !busy,
                    singleLine = true, modifier = Modifier.fillMaxWidth().then(if (tv) Modifier.focusRequester(initialFocus) else Modifier).tvOnlyFocus().testTag("login_server"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
            }
            item {
                OutlinedTextField(user, { user = it }, label = { Text("아이디") }, enabled = !busy,
                    singleLine = true, modifier = Modifier.fillMaxWidth().tvOnlyFocus(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            }
            item {
                OutlinedTextField(password, { password = it }, label = { Text("비밀번호") }, enabled = !busy,
                    singleLine = true, modifier = Modifier.fillMaxWidth().tvOnlyFocus(),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.tvOnlyFocus()) {
                            Icon(if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                if (passwordVisible) "비밀번호 숨기기" else "비밀번호 표시")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!busy && user.isNotBlank() && password.isNotEmpty()) submitLogin() }))
            }
        } else item {
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("인증 코드") },
                enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().then(if (tv) Modifier.focusRequester(initialFocus) else Modifier).tvOnlyFocus(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!busy && code.length == 6) { focus.clearFocus(); onTotp(code) } }))
        }
        if (error != null) item { ErrorMessage(error) }
        item {
            Button(
                onClick = { if (totpChallenge == null) submitLogin() else { focus.clearFocus(); onTotp(code) } },
                enabled = !busy && if (totpChallenge == null) server.isNotBlank() && user.isNotBlank() && password.isNotEmpty() else code.length == 6,
                modifier = Modifier.fillMaxWidth().tvOnlyFocus().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (totpChallenge == null) "로그인" else "인증하고 로그인", fontWeight = FontWeight.Bold)
            }
        }
        if (totpChallenge != null) item { TextButton(onClick = onCancelTotp, enabled = !busy, modifier = Modifier.tvOnlyFocus()) { Text("로그인 화면으로 돌아가기") } }
    }
}

@Composable
private fun HomeScreen(
    libraries: List<Library>,
    recent: List<MediaItem>,
    continuing: List<MediaItem>,
    continueDetails: MediaItem?,
    continueLoading: Boolean,
    continueError: String?,
    recentLoading: Boolean,
    spoilerUnlocked: Boolean,
    busy: Boolean,
    error: String?,
    viewModel: AppViewModel,
    onWatchOptions: (MediaItem) -> Unit,
) {
    val visibleContinuing = continuing.filter { spoilerUnlocked || !it.isSpoiler }
    val heroAlignment = if (LocalConfiguration.current.screenWidthDp >= 600) Alignment.CenterEnd else Alignment.Center
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = if (visibleContinuing.isNotEmpty()) 0.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
                HomeContinueWatchingSection(
                    items = visibleContinuing,
                    loading = continueLoading,
                    error = continueError,
                    spoilerUnlocked = spoilerUnlocked,
                    busy = busy,
                    onRetry = viewModel::refresh,
                    onResume = viewModel::playContinue,
                    onSelected = viewModel::selectContinueItem,
                    selectedDetails = continueDetails,
                    onWatchOptions = onWatchOptions,
                    poster = { item, modifier -> Poster(item, viewModel, modifier) },
                    thumbnail = { item, modifier -> Poster(item, viewModel, modifier,
                        imageUrl = if (item.type == "episode") item.thumbnail ?: item.backdrop ?: item.poster
                            else item.backdrop ?: item.poster) },
                    backdrop = { item, modifier -> Poster(item, viewModel, modifier,
                        imageUrl = item.backdrop ?: item.poster, imageAlignment = heroAlignment) },
                )
        }
        item { SectionTitle("최근 추가됨") }
        if (error != null) item { ErrorMessage(error, Modifier.padding(horizontal = 20.dp)) }
        if (recent.isEmpty() && error == null) item {
            EmptyMessage(if (recentLoading) "작품을 불러오는 중입니다" else "최근 추가된 작품이 없습니다",
                Modifier.padding(horizontal = 20.dp))
        } else if (recent.isNotEmpty()) {
            libraries.forEach { library ->
                val media = recent.filter { it.libraryId == library.id }
                if (media.isNotEmpty()) {
                    item(key = "recent-library-title:${library.id}") { SectionTitle(library.title) }
                    item(key = "recent-library-row:${library.id}") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(media, key = { "${it.libraryId}:${it.id}" }) { item ->
                                PosterCard(item, viewModel, modifier = Modifier.width(154.dp), onWatchOptions = onWatchOptions)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryPicker(
    libraries: List<Library>,
    selectedLibraryId: Int?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Library) -> Unit,
    spoilerAvailable: Boolean,
    spoilerUnlocked: Boolean,
    spoilerBusy: Boolean,
    spoilerError: String?,
    onUnlockSpoiler: () -> Unit,
    onLockSpoiler: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("라이브러리") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (spoilerAvailable || spoilerUnlocked) CompactMenuAction(onClick = if (spoilerUnlocked) onLockSpoiler else onUnlockSpoiler,
                enabled = !spoilerBusy, modifier = Modifier.fillMaxWidth().testTag(if (spoilerUnlocked) "spoiler-lock" else "spoiler-open-unlock")) {
                Icon(if (spoilerUnlocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null)
                Spacer(Modifier.width(8.dp))
                Text(if (spoilerUnlocked) "스포일러 라이브러리 잠금" else "스포일러 라이브러리 열기")
            }
            if (spoilerBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (spoilerError != null) Text(spoilerError, color = MaterialTheme.colorScheme.error)
            if (libraries.isEmpty()) EmptyMessage(if (busy) "라이브러리를 불러오는 중입니다" else "표시할 라이브러리가 없습니다")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(libraries, key = { it.id }) { library ->
                    CompactMenuAction(onClick = { onSelect(library) }, modifier = Modifier.fillMaxWidth(),
                        selected = library.id == selectedLibraryId, contentPadding = PaddingValues(0.dp)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (library.type == "movie") Icons.Rounded.Movie else Icons.Rounded.Folder, null, tint = Accent)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(library.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text((if (library.isSpoiler) "비공개 · " else "") + if (library.type == "movie") "영화" else "시리즈", color = Muted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (library.id == selectedLibraryId) Icon(Icons.Rounded.Check, "선택됨", tint = Accent)
                        }
                    }
                }
            }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.tvOnlyFocus()) { Text("닫기") } },
    )
}

/** PIN text exists only while this dialog is composed; it never enters saved UI or account state. */
@Composable
private fun SpoilerPinDialog(busy: Boolean, error: String?, onDismiss: () -> Unit, onUnlock: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    fun submit() {
        if (!busy && pin.isNotBlank()) {
            val entered = pin
            pin = ""
            onUnlock(entered)
        }
    }
    AlertDialog(onDismissRequest = { pin = ""; onDismiss() }, modifier = Modifier.testTag("spoiler-pin-dialog"),
        title = { Text("스포일러 라이브러리") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("PIN을 입력해 잠금을 해제하세요.")
                OutlinedTextField(value = pin, onValueChange = { pin = it.take(16) }, enabled = !busy,
                    label = { Text("PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().tvOnlyFocus().testTag("spoiler-pin"))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = !busy && pin.isNotBlank(),
            modifier = Modifier.tvOnlyFocus().testTag("spoiler-unlock")) { Text("잠금 해제") } },
        dismissButton = { TextButton(onClick = { pin = ""; onDismiss() }, modifier = Modifier.tvOnlyFocus()) { Text("취소") } },
    )
}

@Composable
private fun LibraryScreen(
    library: Library,
    media: List<MediaItem>,
    total: Int,
    query: String,
    busy: Boolean,
    error: String?,
    viewModel: AppViewModel,
    onWatchOptions: (MediaItem) -> Unit,
) {
    var searchText by rememberSaveable(library.id, query) { mutableStateOf(query) }
    val focus = LocalFocusManager.current
    val browseState by viewModel.state.collectAsState()
    val grid = rememberLazyGridState()
    LibraryAutoLoad(grid, listOf(library.id, query, browseState.libraryBrowseRevision),
        media.size, media.size, total, busy, error != null, viewModel::loadMore)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(130.dp),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(searchText, { searchText = it }, label = { Text("제목 검색") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { IconButton(onClick = { focus.clearFocus(); viewModel.search(searchText.trim()) }, enabled = !busy) {
                    Icon(Icons.Rounded.Search, "검색")
                } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); viewModel.search(searchText.trim()) }))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (query.isBlank()) "전체 작품" else "‘$query’ 검색 결과", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${total}개", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { ErrorMessage(error) }
        if (media.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyMessage(if (busy) "작품을 불러오는 중입니다" else if (query.isBlank()) "표시할 작품이 없습니다" else "검색 결과가 없습니다")
        }
        items(media, key = { "${it.libraryId}:${it.id}" }) { item -> PosterCard(item, viewModel, onWatchOptions = onWatchOptions) }
        if (busy && media.size < total && media.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun DetailScreen(
    detail: MediaDetails,
    versions: List<MediaVersion>,
    selectedPart: Int?,
    busy: Boolean,
    error: String?,
    viewModel: AppViewModel,
    onWatchOptions: (MediaItem) -> Unit,
) {
    val item = detail.item
    var descriptionExpanded by rememberSaveable(item.id) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Poster(item, viewModel, Modifier.width(118.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))
                    .combinedClickable(enabled = !busy && canChangeWatchState(item), onClick = {},
                        onLongClickLabel = "시청 상태 변경", onLongClick = { onWatchOptions(item) }))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(7.dp)) {
                        Text(typeLabel(item), modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            color = Accent, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (item.durationMs > 0) Text("${item.durationMs / 60_000}분", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    if (item.episodeNumber != null) Text(episodeLabel(item), style = MaterialTheme.typography.bodyMedium, color = Muted)
                    if (!item.watched && item.resumeMs > 0 && item.playable) Text("${timeLabel(item.resumeMs)}까지 시청", style = MaterialTheme.typography.bodySmall, color = Accent)
                }
            }
        }
        if (error != null) item { ErrorMessage(error) }
        if (item.playable) {
            if (versions.isNotEmpty()) item {
                ResolutionFolderSelector(versions, selectedPart, enabled = !busy, onSelect = viewModel::selectVersion)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.play(item, item.watched) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (item.watched) "다시 재생" else if (item.resumeMs > 0) "이어보기 · ${timeLabel(item.resumeMs)}" else "재생", fontWeight = FontWeight.Bold)
                    }
                    if (item.resumeMs > 0 && !item.watched) CompactMenuAction(onClick = { viewModel.play(item, true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("처음부터 재생")
                    }
                    Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
                        WatchedSwitch(
                            checked = item.watched,
                            enabled = !busy,
                            onCheckedChange = { viewModel.setWatched(item, it) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                                .semantics { contentDescription = "${item.title} 시청 상태" },
                        )
                    }
                }
            }
        }
        if (item.summary.isNotBlank()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("줄거리", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(item.summary, style = MaterialTheme.typography.bodyMedium, color = Muted,
                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
                if (item.summary.length > 160) TextButton(onClick = { descriptionExpanded = !descriptionExpanded }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (descriptionExpanded) "접기" else "더 보기")
                }
            }
        }
        if (!item.playable) item {
            Text(if (item.type == "show") "시즌" else "에피소드", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (!item.playable && detail.children.isEmpty()) item {
            EmptyMessage(if (busy) "목록을 불러오는 중입니다" else "표시할 항목이 없습니다")
        }
        items(detail.children, key = { "${it.libraryId}:${it.id}" }) { child ->
            Card(modifier = Modifier.fillMaxWidth().combinedClickable(enabled = !busy,
                onClick = { viewModel.openItem(child) }, onLongClickLabel = "시청 상태 변경",
                onLongClick = if (canChangeWatchState(child)) ({ onWatchOptions(child) }) else null),
                shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Poster(child, viewModel, Modifier.width(62.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(9.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(child.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (child.type == "episode") episodeLabel(child) else typeLabel(child), color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (child.durationMs > 0) Text("${child.durationMs / 60_000}분", color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (!child.watched && child.resumeMs > 0) Text("${timeLabel(child.resumeMs)}까지 시청", color = Accent, style = MaterialTheme.typography.labelSmall)
                    }
                    if (child.playable) WatchedSwitch(
                        checked = child.watched,
                        enabled = !busy,
                        onCheckedChange = { viewModel.setWatched(child, it) },
                        compact = true,
                        modifier = Modifier.semantics { contentDescription = "${child.title} 시청 상태" },
                    ) else if (child.watched) Icon(Icons.Rounded.Check, "보았음", tint = Accent)
                    else Icon(Icons.Rounded.PlayArrow, "${child.title} 상세 보기", tint = Accent)
                }
            }
        }
    }
}

@Composable
private fun PosterCard(item: MediaItem, viewModel: AppViewModel, modifier: Modifier = Modifier, showProgress: Boolean = false,
    onWatchOptions: (MediaItem) -> Unit) {
    Card(modifier = modifier.combinedClickable(onClick = { viewModel.openItem(item) },
        onLongClickLabel = "시청 상태 변경", onLongClick = if (canChangeWatchState(item)) ({ onWatchOptions(item) }) else null),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
                Poster(item, viewModel, Modifier.fillMaxSize())
                if (item.watched && canChangeWatchState(item)) {
                    WatchedBadge(Modifier.align(Alignment.TopEnd).padding(7.dp))
                } else if (showProgress && item.resumeMs > 0) {
                    Surface(Modifier.align(Alignment.TopEnd).padding(7.dp), color = AppBackground.copy(alpha = 0.85f), shape = CircleShape) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Accent, modifier = Modifier.padding(5.dp).size(18.dp))
                    }
                    if (item.durationMs > 0) LinearProgressIndicator(
                        progress = { (item.resumeMs.toFloat() / item.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                        color = Accent, trackColor = Panel,
                    )
                }
            }
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            if (showProgress && !item.watched) Text(if (item.type == "episode") episodeLabel(item) else "${timeLabel(item.resumeMs)}까지 시청",
                style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun canChangeWatchState(item: MediaItem) = item.playable || item.type in setOf("show", "tv", "season")

@Composable
internal fun WatchedOptionsDialog(item: MediaItem, onDismiss: () -> Unit, onSetWatched: (Boolean) -> Unit) {
    val television = LocalContext.current.isTelevision()
    val cancelFocus = remember(item) { FocusRequester() }
    var confirmStartedHere by remember(item) { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(192.dp).onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (!television || key.keyCode !in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                    android.view.KeyEvent.KEYCODE_BUTTON_A)) false
            else when (key.action) {
                android.view.KeyEvent.ACTION_DOWN -> {
                    if (key.repeatCount == 0) confirmStartedHere = true
                    !confirmStartedHere
                }
                android.view.KeyEvent.ACTION_UP -> {
                    val consume = !confirmStartedHere
                    confirmStartedHere = false
                    consume
                }
                else -> true
            }
        }, shape = androidx.compose.ui.graphics.RectangleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(4.dp)) {
                TextButton(onClick = { onSetWatched(true) },
                    shape = androidx.compose.ui.graphics.RectangleShape, contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp).tvOnlyFocus().testTag("watched-mark-seen")) {
                    Text("보았음", style = MaterialTheme.typography.labelLarge, fontSize = 13.sp)
                }
                TextButton(onClick = { onSetWatched(false) },
                    shape = androidx.compose.ui.graphics.RectangleShape, contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp).tvOnlyFocus().testTag("watched-mark-unseen")) {
                    Text("보지 않음", style = MaterialTheme.typography.labelLarge, fontSize = 13.sp)
                }
                TextButton(onClick = onDismiss,
                    shape = androidx.compose.ui.graphics.RectangleShape, contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                        .focusRequester(cancelFocus).tvOnlyFocus().testTag("watched-cancel")) {
                    Text("취소", style = MaterialTheme.typography.labelLarge, fontSize = 13.sp)
                }
            }
        }
        LaunchedEffect(cancelFocus, television) { if (television) cancelFocus.requestFocus() }
    }
}

@Composable
internal fun Poster(item: MediaItem, viewModel: AppViewModel, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop, transparentBackground: Boolean = false,
    imageUrl: String? = item.poster, imageAlignment: Alignment = Alignment.Center) {
    val context = LocalContext.current
    val headers = imageUrl?.let(viewModel::posterHeaders).orEmpty()
    val request = remember(context, imageUrl, headers, item.isSpoiler) {
        val cacheKey = viewModel.posterCacheKey(imageUrl.orEmpty())
        ImageRequest.Builder(context).data(imageUrl).crossfade(false)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .memoryCachePolicy(if (item.isSpoiler) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .diskCachePolicy(if (item.isSpoiler) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> set(name, value) } }.build())
            .build()
    }
    Box(if (transparentBackground) modifier else modifier.background(Panel), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Movie, null, tint = Color(0xFF42566D), modifier = Modifier.size(38.dp))
        AsyncImage(model = request, contentDescription = "${item.title} 포스터",
            modifier = Modifier.fillMaxSize(), contentScale = contentScale, alignment = imageAlignment)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun WatchedBadge(modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(modifier = modifier, color = AppBackground.copy(alpha = 0.92f), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(horizontal = if (compact) 4.dp else 7.dp, vertical = if (compact) 2.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Rounded.Check, null, tint = Accent, modifier = Modifier.size(if (compact) 12.dp else 14.dp))
            Text("보았음", color = Accent, style = MaterialTheme.typography.labelSmall,
                fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun WatchedSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val label = if (checked) "보았음" else "보지 않았음"
    if (compact) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (checked) Accent else Muted)
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                color = if (checked) Accent else MaterialTheme.colorScheme.onSurface)
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun EmptyMessage(message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Movie, null, tint = Muted, modifier = Modifier.size(32.dp))
        Text(message, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorMessage(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
    }
}

private fun typeLabel(item: MediaItem): String = when (item.type) {
    "movie" -> "영화"
    "show" -> "시리즈"
    "season" -> "시즌 ${item.seasonNumber ?: ""}"
    "episode" -> "에피소드"
    else -> "미디어"
}

internal fun episodeLabel(item: MediaItem): String = item.displaySubtitle?.takeIf { it.isNotBlank() } ?: listOfNotNull(
    item.seasonNumber?.let { "시즌 $it" }, item.episodeNumber?.let { "${it}화" },
).joinToString(" · ").ifBlank { "에피소드" }

internal fun timeLabel(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0)
    return if (seconds >= 3_600) "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
