package io.mirr.shynigsy.plex

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.ActorWorks
import io.mirr.shynigsy.data.CastMember
import io.mirr.shynigsy.data.actorLibraryWorks
import io.mirr.shynigsy.data.LibraryContentType
import io.mirr.shynigsy.data.LibrarySort
import io.mirr.shynigsy.data.LibraryWatchFilter
import io.mirr.shynigsy.data.MediaFileInfo
import io.mirr.shynigsy.data.MediaVersion
import io.mirr.shynigsy.data.MediaWatchState
import io.mirr.shynigsy.data.RecentLibraryStatus
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.PlexClient
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.player.PlaybackCompletionSignals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

internal sealed interface PlexPage {
    data object Login : PlexPage
    data class Home(val libraries: List<Library>, val ongoing: List<MediaItem> = emptyList(),
        val recent: List<MediaItem> = emptyList(), val recentLibraryStatus: Map<Int, RecentLibraryStatus> = emptyMap(),
        val continueLoading: Boolean = false, val continueError: String? = null) : PlexPage
    data class Shelf(val library: Library, val items: List<MediaItem> = emptyList(),
        val nextOffset: Int = 0, val hasMore: Boolean = false, val total: Int = 0,
        val contentType: LibraryContentType = LibraryContentType.defaultFor(library),
        val sort: LibrarySort = LibrarySort.defaultFor(contentType), val watchFilter: LibraryWatchFilter = LibraryWatchFilter.ALL,
        val query: String = "", val needsRefresh: Boolean = false) : PlexPage
    data class Search(val query: String = "", val items: List<MediaItem> = emptyList(),
        val finished: Boolean = false) : PlexPage
    data class Detail(val item: MediaItem, val children: List<MediaItem> = emptyList(),
        val seasons: List<MediaItem> = emptyList(), val selectedSeasonId: Int? = null,
        val versions: List<MediaVersion> = emptyList(), val selectedPartId: Int? = null,
        val cast: List<CastMember> = emptyList(), val files: List<MediaFileInfo> = emptyList()) : PlexPage
}

internal class PlexBrowseModel(application: Application) : AndroidViewModel(application) {
    private val sessions = SessionStore(application)
    private val accountStore = PlexAccountStore(application)
    private val settings = application.getSharedPreferences("gsy_settings", Application.MODE_PRIVATE)
    val deviceId: String = settings.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        settings.edit().putString("device_id", it).apply()
    }
    var page: PlexPage by mutableStateOf(PlexPage.Login)
        private set
    var busy by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var savedServer by mutableStateOf("")
        private set
    var switchingServer by mutableStateOf(false)
        private set
    var searchText by mutableStateOf("")
    // A new artwork cache namespace also isolates different Plex users on the same server.
    var artworkSession by mutableStateOf("")
        private set
    private val history = mutableListOf<PlexPage>()
    private var client: PlexClient? = null
    private var request: Job? = null
    private var ongoingRequest: Job? = null
    private var heroRequest: Job? = null
    private var selectedHeroId: Int? = null
    private var recentRequest: Job? = null
    private var recentGeneration = 0L
    private var home: PlexPage.Home? by mutableStateOf(null)
    val libraries: List<Library> get() = home?.libraries.orEmpty()
    var libraryOrderScope: String? by mutableStateOf(null)
        private set
    private var launchedItem: MediaItem? = null
    private var playbackRefresh: Job? = null

    init { reconnect() }

    fun reconnect() = runRequest {
        val saved = withContext(Dispatchers.IO) { sessions.loadLast() } ?: return@runRequest
        savedServer = saved.server
        val active = PlexClient(saved.server, saved.token, deviceId)
        val libraries = withTimeoutOrNull(5_000) { active.libraries() }
            ?: throw java.io.IOException("이전 서버 주소가 응답하지 않습니다. 연결할 수 있는 주소를 확인해 주세요.")
        finishSession(active, libraries, saved.token, save = false)
    }

    fun connect(server: String, token: String) = runRequest {
        openSession(server.trim(), token.trim(), save = true)
    }

    fun connect(server: PlexServerResource, preferred: String) = runRequest {
        val (active, libraries) = server.connectFirstAvailable(preferred) { url ->
            val candidate = PlexClient(url, server.token, deviceId)
            candidate to candidate.libraries()
        }
        finishSession(active, libraries, server.token, save = true, machineId = server.id)
    }

    private suspend fun openSession(server: String, token: String, save: Boolean) {
        val client = PlexClient(server, token, deviceId)
        finishSession(client, client.libraries(), token, save)
    }

    private suspend fun finishSession(active: PlexClient, libraries: List<Library>, token: String,
                                     save: Boolean, machineId: String? = null) {
        val switching = switchingServer
        if (save) withContext(Dispatchers.IO) {
            sessions.save(active.server, token, "Plex")
            when {
                machineId != null -> accountStore.rememberServer(machineId)
                switching -> accountStore.rememberServer("")
                else -> accountStore.clear()
            }
            if (savedServer.isNotBlank() && savedServer != active.server) sessions.clear(savedServer)
        }
        switchingServer = false
        savedServer = active.server
        client = active
        artworkSession = UUID.randomUUID().toString()
        history.clear()
        showHome(active, libraries)
    }

    fun switchServer() {
        if (home == null || busy) return
        error = null
        switchingServer = true
        page = PlexPage.Login
    }

    fun cancelSwitch() {
        if (!switchingServer || busy) return
        switchingServer = false
        goHome()
    }

    fun disconnect() {
        request?.cancel()
        ongoingRequest?.cancel()
        recentRequest?.cancel()
        home = null
        client = null
        artworkSession = ""
        history.clear()
        val previous = savedServer
        savedServer = ""
        switchingServer = false
        page = PlexPage.Login
        runRequest { withContext(Dispatchers.IO) {
            if (previous.isNotBlank()) sessions.clear(previous)
            accountStore.clear()
        } }
    }

    fun headersFor(url: String) = client?.headersFor(url).orEmpty()

    suspend fun actorWorks(actor: CastMember): ActorWorks =
        client?.actorLibraryWorks(actor, libraries) ?: ActorWorks(emptyList())

    private fun showHome(active: PlexClient, libraries: List<Library>) {
        libraryOrderScope = PlaybackCompletionSignals.scope(active.server, active.headers.getValue("X-Plex-Token"))
        launchedItem = null
        playbackRefresh?.cancel()
        heroRequest?.cancel()
        selectedHeroId = null
        val initial = PlexPage.Home(libraries)
        home = initial
        page = initial
        refreshOngoing()
        refreshRecent(active, libraries)
    }

    private fun updateHome(transform: (PlexPage.Home) -> PlexPage.Home) {
        val current = home ?: return
        val latest = transform(current)
        home = latest
        if (page is PlexPage.Home) page = latest
        history.indices.filter { history[it] is PlexPage.Home }.forEach { history[it] = latest }
    }

    fun goHome() {
        val current = home ?: return
        request?.cancel()
        history.clear()
        page = current
        busy = false
        error = null
    }

    fun openLibrary(library: Library) {
        goHome()
        history.add(page)
        page = PlexPage.Shelf(library)
        reload()
    }

    fun openItem(item: MediaItem) {
        history.add(page)
        page = PlexPage.Detail(item)
        reload()
    }

    fun openSearch() {
        if (page is PlexPage.Search) return
        goHome()
        history.add(page)
        busy = false
        error = null
        searchText = ""
        page = PlexPage.Search()
    }

    fun search(text: String) {
        val active = client ?: return
        val query = text.trim().takeIf { it.isNotEmpty() } ?: return
        page = PlexPage.Search(query)
        runRequest {
            page = PlexPage.Search(query, active.search(query), finished = true)
        }
    }

    fun back() {
        if (history.isEmpty()) return
        request?.cancel()
        busy = false
        error = null
        page = history.removeAt(history.lastIndex)
        if ((page as? PlexPage.Shelf)?.needsRefresh == true) reload()
    }

    fun playerReturned(returned: MediaItem?) {
        val active = client ?: return
        val launched = launchedItem ?: (page as? PlexPage.Detail)?.item?.takeIf { it.playable } ?: return
        launchedItem = null
        val last = returned?.takeIf { it.playable && it.id > 0 && it.libraryId == launched.libraryId } ?: launched
        val affectedSeasons = mutableListOf<MediaItem>()
        fun PlexPage.followSeason(): PlexPage {
            if (this !is PlexPage.Detail) return this
            if (item.type == "season") affectedSeasons.add(item)
            if (item.type != "show" || item.id != last.showId) return this
            val selected = seasons.firstOrNull { it.id == selectedSeasonId }
            val target = seasons.firstOrNull { it.seasonNumber == last.seasonNumber } ?: selected ?: return this
            affectedSeasons.add(target)
            return if (target.id == selectedSeasonId) this else copy(selectedSeasonId = target.id, children = emptyList())
        }
        page = page.followSeason()
        history.indices.forEach { history[it] = history[it].followSeason() }
        val movedToLast = last.id != launched.id && (page as? PlexPage.Detail)?.item?.id == launched.id
        if (movedToLast) {
            page = PlexPage.Detail(last)
        }
        playbackRefresh?.cancel()
        playbackRefresh = viewModelScope.launch {
            PlaybackCompletionSignals.progressWriters.first { it == 0 }
            if (client !== active) return@launch
            // Continuous play can update every intervening episode, including siblings of the original item.
            for (season in affectedSeasons.distinctBy { it.id }) {
                val children = try { active.children(season) } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) { continue }
                if (client !== active) return@launch
                updateCachedItems(children, season.id)
            }
            for (item in listOf(launched, last).distinctBy { it.id }) {
                val latest = try {
                    if (movedToLast && last.id == item.id) {
                        val info = active.itemInfo(item)
                        val current = page as? PlexPage.Detail
                        if (client === active && current?.item?.id == item.id) {
                            page = current.copy(versions = info.versions, selectedPartId = info.versions.firstOrNull()?.partId,
                                cast = info.cast, files = info.files)
                        }
                        info.item
                    } else active.detail(item)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    if (client === active && (page as? PlexPage.Detail)?.item?.id == item.id) error = readableError(failure)
                    continue
                }
                if (client !== active) return@launch
                updateCachedItems(listOf(latest))
            }
            invalidateWatchFilters()
            refreshOngoing()
        }
    }

    private fun updateCachedItems(updates: List<MediaItem>, seasonId: Int? = null) {
        val updated = updates.associateBy { it.id }
        fun List<MediaItem>.refresh() = map { updated[it.id] ?: it }
        fun PlexPage.refresh(): PlexPage = when (this) {
            is PlexPage.Shelf -> copy(items = items.refresh())
            is PlexPage.Search -> copy(items = items.refresh())
            is PlexPage.Home -> copy(ongoing = ongoing.refresh(), recent = recent.refresh())
            is PlexPage.Detail -> copy(item = updated[item.id] ?: item,
                children = if (seasonId != null && (item.id == seasonId || selectedSeasonId == seasonId)) updates else children.refresh(),
                seasons = seasons.refresh())
            else -> this
        }
        page = page.refresh()
        history.indices.forEach { i -> history[i] = history[i].refresh() }
        home = home?.let { it.copy(ongoing = it.ongoing.refresh(), recent = it.recent.refresh()) }
    }

    fun selectSeason(season: MediaItem) {
        val current = page as? PlexPage.Detail ?: return
        val active = client ?: return
        if (current.seasons.none { it.id == season.id } || current.selectedSeasonId == season.id) return
        val next = current.copy(selectedSeasonId = season.id, children = emptyList())
        page = next
        runRequest { page = next.copy(children = active.children(season)) }
    }

    fun selectVersion(partId: Int?) {
        val current = page as? PlexPage.Detail ?: return
        if (partId == null || current.versions.any { it.partId == partId }) page = current.copy(selectedPartId = partId)
    }

    private fun invalidateWatchFilters() {
        history.indices.forEach { i ->
            val shelf = history[i] as? PlexPage.Shelf
            if (shelf != null && shelf.watchFilter != LibraryWatchFilter.ALL) history[i] = shelf.copy(needsRefresh = true)
        }
    }

    fun setWatched(item: MediaItem, watched: Boolean) {
        val active = client ?: return
        if (busy || !(item.playable || item.type in setOf("show", "season"))) return
        playbackRefresh?.cancel()
        PlaybackCompletionSignals.invalidateForManualChoice(libraryOrderScope, item)
        runRequest {
            PlaybackCompletionSignals.withWatchMutation { active.setWatched(item.id, watched) }
            val visible = (history + page).flatMap {
                when (it) {
                    is PlexPage.Home -> it.recent + it.ongoing
                    is PlexPage.Shelf -> it.items
                    is PlexPage.Search -> it.items
                    is PlexPage.Detail -> it.children + it.seasons + it.item
                    PlexPage.Login -> emptyList()
                }
            }
            val changed = (visible + item).filter {
                it.id == item.id || (item.type == "show" && it.showId == item.id) ||
                    (item.type == "season" && it.type == "episode" && it.folderKey == "/library/metadata/${item.id}/children")
            }.map { it.copy(watched = watched, resumeMs = 0,
                watchState = if (watched) MediaWatchState.WATCHED else MediaWatchState.UNWATCHED) }
            updateCachedItems(changed)
            invalidateWatchFilters()
            refreshOngoing()
            // A watched episode can also change the badges of its season and series.
            val parents = visible.filter { p -> p.id == item.showId ||
                (item.type == "episode" && p.type == "season" && item.showId != null &&
                    p.showId == item.showId && p.seasonNumber == item.seasonNumber) }
            for (parent in parents.distinctBy { it.id }) updateCachedItems(listOf(active.detail(parent)))
            val shelf = page as? PlexPage.Shelf
            if (shelf != null && shelf.watchFilter != LibraryWatchFilter.ALL) page = readShelf(active, shelf, false)
        }
    }

    fun reload() {
        val active = client ?: return
        when (val current = page) {
            PlexPage.Login -> reconnect()
            is PlexPage.Home -> runRequest {
                val libraries = active.libraries()
                val ids = libraries.map { it.id }.toSet()
                updateHome { it.copy(libraries = libraries, recent = it.recent.filter { row -> row.libraryId in ids },
                    ongoing = it.ongoing.filter { row -> row.libraryId in ids }, recentLibraryStatus = it.recentLibraryStatus.filterKeys(ids::contains)) }
                refreshOngoing()
                refreshRecent(active, libraries)
            }
            is PlexPage.Shelf -> loadShelf(active, current, append = false)
            is PlexPage.Search -> if (current.query.isNotBlank()) search(current.query)
            is PlexPage.Detail -> runRequest {
                val info = active.itemInfo(current.item)
                val details = current.copy(item = info.item, versions = info.versions, cast = info.cast, files = info.files,
                    selectedPartId = current.selectedPartId?.takeIf { id -> info.versions.any { it.partId == id } }
                        ?: info.versions.firstOrNull()?.partId)
                page = details
                updateCachedItems(listOf(info.item))
                if (info.item.type == "show") {
                    val seasons = active.children(info.item)
                    val resume = home?.ongoing?.firstOrNull { it.showId == info.item.id }
                    val selected = seasons.firstOrNull { it.id == current.selectedSeasonId } ?:
                        seasons.firstOrNull { it.seasonNumber == resume?.seasonNumber } ?:
                        seasons.firstOrNull { (it.seasonNumber ?: 0) > 0 } ?: seasons.firstOrNull()
                    val overview = details.copy(seasons = seasons, selectedSeasonId = selected?.id)
                    page = overview
                    if (selected != null) page = overview.copy(children = active.children(selected))
                } else if (info.item.type == "season") page = details.copy(children = active.children(info.item))
            }
        }
    }

    fun loadMore() {
        val current = page as? PlexPage.Shelf ?: return
        if (!busy && current.hasMore) client?.let { loadShelf(it, current, append = true) }
    }

    fun setLibraryOptions(sort: LibrarySort? = null, contentType: LibraryContentType? = null,
        watchFilter: LibraryWatchFilter? = null, query: String? = null) {
        val active = client ?: return
        val current = page as? PlexPage.Shelf ?: return
        val type = contentType ?: current.contentType
        if (type !in LibraryContentType.availableFor(current.library)) return
        val order = sort ?: if (type == current.contentType) current.sort else LibrarySort.defaultFor(type)
        val shelf = PlexPage.Shelf(current.library, contentType = type, sort = order.forContentType(type),
            watchFilter = watchFilter ?: current.watchFilter, query = query?.trim() ?: current.query)
        page = shelf
        loadShelf(active, shelf, append = false)
    }

    private fun loadShelf(active: PlexClient, shelf: PlexPage.Shelf, append: Boolean) =
        runRequest { page = readShelf(active, shelf, append) }

    private suspend fun readShelf(active: PlexClient, shelf: PlexPage.Shelf, append: Boolean): PlexPage.Shelf {
        val offset = if (append) shelf.nextOffset else 0
        val batch = active.page(shelf.library, offset, contentType = shelf.contentType, sort = shelf.sort,
            watchFilter = shelf.watchFilter, title = shelf.query)
        val next = batch.nextOffset
        val items = if (append) (shelf.items + batch.items).distinctBy { it.id } else batch.items
        return shelf.copy(items = items, nextOffset = next, hasMore = next > offset && next < batch.total,
            total = batch.total, needsRefresh = false)
    }

    fun retryContinue() = refreshOngoing()

    fun selectContinueItem(item: MediaItem) {
        val active = client ?: return
        val row = home?.ongoing?.firstOrNull { it.id == item.id } ?: return
        if (selectedHeroId == row.id) return
        selectedHeroId = row.id
        heroRequest?.cancel()
        if (row.summary.isNotBlank() && row.backdrop != null) return
        heroRequest = viewModelScope.launch {
            delay(200)
            val detail = try { active.detail(row) } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { return@launch }
            if (client === active && selectedHeroId == row.id) updateHome { current ->
                current.copy(ongoing = current.ongoing.map {
                    if (it.id != row.id) it else it.copy(summary = detail.summary.ifBlank { it.summary },
                        backdrop = detail.backdrop ?: it.backdrop, poster = detail.poster ?: it.poster)
                })
            }
        }
    }

    private fun refreshOngoing() {
        val active = client ?: return
        ongoingRequest?.cancel()
        selectedHeroId = null
        heroRequest?.cancel()
        updateHome { it.copy(continueLoading = true, continueError = null) }
        ongoingRequest = viewModelScope.launch {
            // Some PMS versions do not provide the continue-watching hub.
            val items = try { active.continueWatching() } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (client === active) updateHome { it.copy(continueLoading = false, continueError = readableError(failure)) }
                return@launch
            }
            if (client === active) updateHome { it.copy(ongoing = items, continueLoading = false) }
        }
    }

    private val recentLimit = Semaphore(3)
    private fun refreshRecent(active: PlexClient, libraries: List<Library>) {
        recentRequest?.cancel()
        val generation = ++recentGeneration
        updateHome { it.copy(recentLibraryStatus = libraries.associate { lib -> lib.id to RecentLibraryStatus.LOADING }) }
        recentRequest = viewModelScope.launch {
            for (library in libraries) launch { recentLimit.withPermit { loadRecent(active, library, generation) } }
        }
    }

    fun retryRecentLibrary(library: Library) {
        val active = client ?: return
        if (home?.recentLibraryStatus?.get(library.id) != RecentLibraryStatus.ERROR) return
        updateHome { it.copy(recentLibraryStatus = it.recentLibraryStatus + (library.id to RecentLibraryStatus.LOADING)) }
        val generation = recentGeneration
        viewModelScope.launch { recentLimit.withPermit { loadRecent(active, library, generation) } }
    }

    private suspend fun loadRecent(active: PlexClient, library: Library, generation: Long) {
        val items = try {
            active.page(library, limit = 12, sort = if (library.type == "tv") LibrarySort.LATEST_EPISODE else LibrarySort.RECENT).items
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {
            if (client === active && generation == recentGeneration) updateHome {
                it.copy(recentLibraryStatus = it.recentLibraryStatus + (library.id to RecentLibraryStatus.ERROR))
            }
            return
        }
        if (client === active && generation == recentGeneration && library in libraries) updateHome {
            it.copy(recent = it.recent.filterNot { row -> row.libraryId == library.id } + items,
                recentLibraryStatus = it.recentLibraryStatus + (library.id to if (items.isEmpty()) RecentLibraryStatus.EMPTY else RecentLibraryStatus.READY))
        }
    }

    suspend fun playback(item: MediaItem, fromStart: Boolean): Pair<String, PlaybackSpec>? {
        val active = client ?: return null
        val source = page
        if (busy || !item.playable) return null
        val sourceDetail = source as? PlexPage.Detail
        val available = when (source) {
            is PlexPage.Home -> (source.ongoing + source.recent).any { it.id == item.id }
            is PlexPage.Detail -> source.item.id == item.id || source.children.any { it.id == item.id }
            else -> false
        }
        if (!available) return null
        fun current() = active === client && if (sourceDetail == null) page is PlexPage.Home
            else (page as? PlexPage.Detail)?.item?.id == sourceDetail.item.id
        busy = true
        error = null
        return try {
            val spec = active.playback(item, sourceDetail?.takeIf { it.item.id == item.id }?.selectedPartId)
            if (!current()) return null
            launchedItem = spec.sourceItem ?: item
            active.server to spec.copy(
                resumeMs = if (fromStart) 0L else spec.resumeMs,
                headers = emptyMap(), subtitles = spec.subtitles.map { it.copy(headers = emptyMap()) },
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (current()) error = readableError(failure)
            null
        } finally { if (current()) busy = false }
    }

    private fun runRequest(action: suspend () -> Unit) {
        request?.cancel()
        error = null
        busy = true
        request = viewModelScope.launch {
            try { action(); busy = false } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = readableError(failure)
                busy = false
            }
        }
    }
}

private fun readableError(error: Exception): String = when (error) {
    is UnknownHostException -> "서버 주소를 찾을 수 없습니다. 주소와 네트워크를 확인해 주세요."
    is SocketTimeoutException -> "서버 응답 시간이 초과되었습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요."
    else -> error.message?.takeIf { message -> message.any { it in '가'..'힣' } }
        ?: "Plex 요청을 완료하지 못했습니다. 서버 주소와 연결 상태를 확인해 주세요."
}
