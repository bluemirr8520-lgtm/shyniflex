package io.mirr.shynigsy.plex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mirr.shynigsy.data.*
import io.mirr.shynigsy.isTelevision
import io.mirr.shynigsy.ui.*

private enum class BrowserMenu { LIBRARIES, MORE, APPEARANCE, ORDER, ACCOUNT }

@Composable
internal fun PlexBrowser(appName: String, model: PlexBrowseModel, play: (MediaItem, Boolean) -> Unit, onUpdate: () -> Unit) {
    if (model.page == PlexPage.Login) {
        Surface(Modifier.fillMaxSize()) { ConnectionScreen(appName, model, onUpdate) }
        return
    }
    key(model.artworkSession) { PlexSessionBrowser(appName, model, play, onUpdate) }
}

@Composable
private fun PlexSessionBrowser(appName: String, model: PlexBrowseModel, play: (MediaItem, Boolean) -> Unit, onUpdate: () -> Unit) {
    val page = model.page
    val context = LocalContext.current
    val television = context.isTelevision()
    val appearance = LocalCinemaAppearance.current
    var menu by rememberSaveable { mutableStateOf<BrowserMenu?>(null) }
    var watchItem by remember { mutableStateOf<MediaItem?>(null) }
    val orders = remember(context) { LibraryOrderStore(context) }
    val scope = model.libraryOrderScope
    var orderRevision by remember(scope) { mutableIntStateOf(0) }
    val order = remember(scope, orderRevision) { orders.read(scope) }
    val libraries = remember(model.libraries, order) { applyLibraryOrder(model.libraries, order) }
    val orderAction: (() -> Unit)? = if (scope != null) ({ menu = BrowserMenu.ORDER }) else null
    val libraryId = when (page) {
        is PlexPage.Shelf -> page.library.id
        is PlexPage.Detail -> page.item.libraryId
        else -> null
    }
    val home = page is PlexPage.Home
    val mobileDetail = !television && page is PlexPage.Detail
    val search = page is PlexPage.Search
    val title = when (page) {
        is PlexPage.Detail -> page.item.title
        is PlexPage.Shelf -> page.library.title
        is PlexPage.Search -> "검색"
        else -> appName
    }
    val screenKey = when (page) {
        is PlexPage.Detail -> "detail-${page.item.libraryId}-${page.item.id}"
        is PlexPage.Shelf -> "library-${page.library.id}"
        is PlexPage.Search -> "search"
        else -> "home"
    }
    BackHandler(enabled = !home && menu == null && watchItem == null) { model.back() }
    val screens = rememberSaveableStateHolder()
    Scaffold(
        contentWindowInsets = if (mobileDetail) WindowInsets(0, 0, 0, 0)
            else WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            if (!home && !mobileDetail) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = model::back, modifier = Modifier.tvOnlyFocus()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로")
                    }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (television && page is PlexPage.Shelf) LibraryHomeButton(model::goHome)
                }
            }
        },
        bottomBar = {
            if (!television && !mobileDetail) CinemaHomeBottomNavigation(
                homeSelected = home && menu == null, searchSelected = search && menu == null,
                librarySelected = menu == BrowserMenu.LIBRARIES || (page is PlexPage.Shelf && menu == null),
                onHome = model::goHome, onSearch = { if (!search) model.openSearch() },
                onLibraries = { menu = BrowserMenu.LIBRARIES }, onMore = { menu = BrowserMenu.MORE },
                moreSelected = menu == BrowserMenu.MORE,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val content: @Composable () -> Unit = {
                screens.SaveableStateProvider(screenKey) {
                    when (page) {
                        is PlexPage.Home -> PlexCinemaHome(appName, page, model, libraries, play,
                            onAppearance = { menu = BrowserMenu.APPEARANCE }, onWatch = { watchItem = it })
                        is PlexPage.Shelf -> PlexCatalog(page, model) { watchItem = it }
                        is PlexPage.Search -> GlobalSearchContent(page.query, page.items, libraries,
                            model.busy, model.error, 0, false, model::search, model::openItem, { watchItem = it },
                            artwork = { item, modifier -> PlexMediaArtwork(item, model, modifier) },
                            allowUnscopedResults = true)
                        is PlexPage.Detail -> PlexDetails(page, model, play) { watchItem = it }
                        PlexPage.Login -> Unit
                    }
                }
            }
            if (television) TvSidebarDrawer(screenKey = screenKey, sidebar = { close ->
                TvSidebar(onLibraries = { close(); menu = BrowserMenu.LIBRARIES },
                    onRefresh = { close(); model.reload() }, onLogout = { close(); menu = BrowserMenu.ACCOUNT },
                    libraries = libraries, selectedLibraryId = libraryId, homeSelected = home,
                    onHome = { close(); model.goHome() }, onLibrary = { close(); model.openLibrary(it) },
                    onUpdate = { close(); onUpdate() }, onSearch = { close(); if (!search) model.openSearch() }, searchSelected = search,
                    onAppearance = { close(); menu = BrowserMenu.APPEARANCE },
                    onLibraryOrder = orderAction?.let { action -> { close(); action() } }, appName = appName,
                    onServerConnection = { close(); model.switchServer() })
            }, content = content)
            else MobilePullToRefresh(screenKey, model.busy, model::reload, content)
        }
    }
    when (menu) {
        BrowserMenu.LIBRARIES -> LibraryPicker(libraries, libraryId, model.busy, { menu = null },
            { menu = null; model.openLibrary(it) }, false, false, false, null, {}, {}, onLibraryOrder = orderAction,
            showSpoilerControl = false)
        BrowserMenu.MORE -> MobileMoreMenu(false, { menu = null }, model::reload, { menu = BrowserMenu.APPEARANCE },
            onLibraryOrder = orderAction, onUpdate = onUpdate, onTvQrScan = null,
            onLogout = { menu = BrowserMenu.ACCOUNT }, onSpoilerAccess = null, onServerConnection = model::switchServer)
        BrowserMenu.APPEARANCE -> CinemaAppearanceSettings(appearance.backgroundArgb, { menu = null },
            { appearance.save(it); menu = null })
        BrowserMenu.ORDER -> if (scope != null) LibraryOrderDialog(libraries, false, order.isNotEmpty(),
            onMove = { id, direction ->
                orders.save(scope, moveVisibleLibraryOrder(order, libraries.map { it.id }, id, direction))
                orderRevision++
            }, onReset = { orders.reset(scope); orderRevision++ }, onDismiss = { menu = null })
        BrowserMenu.ACCOUNT -> AlertDialog(onDismissRequest = { menu = null }, title = { Text("Plex 계정") },
            text = { Text(model.savedServer) },
            confirmButton = { TextButton(onClick = { menu = null; model.disconnect() }) { Text("로그아웃") } },
            dismissButton = { TextButton(onClick = { menu = null }) { Text("닫기") } })
        null -> Unit
    }
    watchItem?.let { item ->
        WatchedOptionsDialog(item, { watchItem = null }) { watched ->
            watchItem = null
            model.setWatched(item, watched)
        }
    }
}
