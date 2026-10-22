package io.mirr.shynigsy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mirr.shynigsy.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class AppState(
    val loggedIn: Boolean = false,
    val server: String = "",
    val userName: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val homeLoading: Boolean = false,
    val continueLoading: Boolean = false,
    val continueError: String? = null,
    val recentLoading: Boolean = false,
    val libraries: List<Library> = emptyList(),
    val recentItems: List<MediaItem> = emptyList(),
    val continueItems: List<MediaItem> = emptyList(),
    val continueDetails: MediaItem? = null,
    val library: Library? = null,
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val query: String = "",
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchItems: List<MediaItem> = emptyList(),
    val searchFailureCount: Int = 0,
    val librarySort: LibrarySort = LibrarySort.TITLE_ASC,
    val libraryContentType: LibraryContentType = LibraryContentType.MOVIES,
    val libraryBrowseRevision: Long = 0,
    val detailStack: List<MediaDetails> = emptyList(),
    val versions: List<MediaVersion> = emptyList(),
    val selectedPartId: Int? = null,
    val totpChallenge: String? = null,
    val playback: PlaybackSpec? = null,
    val spoilerAvailable: Boolean = false,
    val spoilerPinEnabled: Boolean = true,
    val spoilerUnlocked: Boolean = false,
    val spoilerBusy: Boolean = false,
    val spoilerPromptOpen: Boolean = false,
    val spoilerError: String? = null,
    val seriesCountRevision: Int = 0,
)

/** A first launch has no server; only a user's previously saved address can restore one. */
internal fun initialServerAddress(savedAddress: String?, sessionAddress: String?): String =
    savedAddress?.takeIf { it.isNotBlank() } ?: sessionAddress?.takeIf { it.isNotBlank() } ?: ""

/** Prefer the ordinary first season; specials remain selectable without a detour. */
internal fun initialEpisodeSeason(seasons: List<MediaItem>): MediaItem? {
    val available = seasons.filter { it.episodeCount != 0 }.ifEmpty { seasons }
    return available.firstOrNull { it.seasonNumber == 1 }
        ?: available.filter { (it.seasonNumber ?: -1) > 0 }.minByOrNull { it.seasonNumber!! }
        ?: available.firstOrNull()
}

/** A repeated or empty page ends this snapshot instead of making automatic paging loop. */
internal fun appendLibraryPage(existing: List<MediaItem>, page: MediaPage): MediaPage {
    val merged = (existing + page.items).distinctBy { it.id }
    return page.copy(items = merged, total = if (merged.size <= existing.size) merged.size else maxOf(merged.size, page.total))
}

/** A version chosen in one detail screen must never select a different item's file. */
internal fun selectedVersionForPlayback(state: AppState, item: MediaItem): Int? {
    val detail = state.detailStack.lastOrNull()?.item ?: return null
    return state.selectedPartId.takeIf {
        detail.playable && detail.id == item.id && detail.libraryId == item.libraryId &&
            detail.viewItemId == item.viewItemId && detail.type == item.type
    }
}

/** Preserve a browse filter only when playback starts from that same visible source list. */
internal fun navigationQueryForPlayback(state: AppState, item: MediaItem): String =
    state.query.takeIf {
        state.library?.id == item.libraryId && state.items.any { candidate ->
            candidate.id == item.id && candidate.libraryId == item.libraryId &&
                candidate.type == item.type && candidate.viewItemId == item.viewItemId
        }
    }.orEmpty()

/** Revocation removes private data while preserving an unrelated public browse session. */
internal fun withoutSpoilerContent(state: AppState): AppState {
    val privateScreen = state.library?.isSpoiler == true || state.detailStack.any { it.item.isSpoiler }
    return state.copy(spoilerUnlocked = false, spoilerBusy = false, spoilerPromptOpen = false, spoilerError = null,
        seriesCountRevision = state.seriesCountRevision + 1,
        libraries = state.libraries.filterNot { it.isSpoiler }, recentItems = state.recentItems.filterNot { it.isSpoiler },
        continueItems = state.continueItems.filterNot { it.isSpoiler },
        searchItems = state.searchItems.filterNot { it.isSpoiler }, searchFailureCount = 0,
        continueDetails = state.continueDetails?.takeUnless { it.isSpoiler },
        library = state.library?.takeUnless { it.isSpoiler },
        items = if (privateScreen) emptyList() else state.items.filterNot { it.isSpoiler },
        total = if (privateScreen) 0 else state.total,
        detailStack = if (privateScreen) emptyList() else state.detailStack.map { detail ->
            detail.copy(children = detail.children.filterNot { it.isSpoiler }, seasons = detail.seasons.filterNot { it.isSpoiler })
        },
        versions = if (privateScreen) emptyList() else state.versions,
        selectedPartId = if (privateScreen) null else state.selectedPartId,
        query = if (privateScreen) "" else state.query,
        playback = state.playback?.takeUnless { it.sourceItem?.isSpoiler == true }, busy = false,
        error = if (privateScreen) null else state.error,
        homeLoading = false, continueLoading = false, continueError = null, recentLoading = false)
}

/** A naturally ended video returns to home without changing account or private-access state. */
internal fun homeAfterPlayback(state: AppState): AppState = state.copy(
    library = null, items = emptyList(), total = 0, query = "", detailStack = emptyList(),
    searchOpen = false, searchQuery = "", searchItems = emptyList(), searchFailureCount = 0,
    versions = emptyList(), selectedPartId = null, playback = null, continueDetails = null,
    busy = false, error = null, homeLoading = false, continueLoading = false, continueError = null, recentLoading = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.getSharedPreferences("gsy_settings", Application.MODE_PRIVATE)
    private val sessionStore = SessionStore(application)
    private val deviceId = settings.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        settings.edit().putString("device_id", it).apply()
    }
    private val savedSession = sessionStore.loadLast()
    private val initialState = AppState(server = initialServerAddress(settings.getString("server", null), savedSession?.server))
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    private var client: ShyniClient? = null
    private var requestJob: Job? = null
    private var revision = 0
    private var homeRefreshRevision = 0
    private var continueOwner: HomeContinueOwner? = null
    private data class ContinueIdentity(val libraryId: Int, val id: Int, val type: String, val viewItemId: Int?, val isSpoiler: Boolean)
    private fun continueIdentity(item: MediaItem) = ContinueIdentity(item.libraryId, item.id, item.type, item.viewItemId, item.isSpoiler)
    private var continueDetailsJob: Job? = null
    private var continueDetailsRevision = 0
    private var selectedContinueIdentity: ContinueIdentity? = null
    private val continueDetailsCache = linkedMapOf<ContinueIdentity, MediaItem>()
    private val collectionWatch = linkedMapOf<ContinueIdentity, Pair<MediaItem, Boolean>>()

    private fun collectionWatchState(item: MediaItem): MediaItem {
        val result = collectionWatch[continueIdentity(item)]?.takeIf { it.first.isSpoiler == item.isSpoiler } ?: return item
        return item.copy(watched = result.second, resumeMs = 0)
    }

    private fun collectionWatchState(details: MediaDetails) = details.copy(item = collectionWatchState(details.item),
        children = details.children.map(::collectionWatchState), seasons = details.seasons.map(::collectionWatchState))

    private fun invalidateCollectionWatch(episode: MediaItem) {
        if (episode.type != "episode") return
        collectionWatch.entries.removeAll { (_, result) ->
            val parent = result.first
            parent.libraryId == episode.libraryId && parent.isSpoiler == episode.isSpoiler &&
                (episode.showId == null || (if (parent.type == "show" || parent.type == "tv") parent.id == episode.showId
                    else parent.showId == null || (parent.showId == episode.showId &&
                        (episode.seasonNumber == null || parent.seasonNumber == null || parent.seasonNumber == episode.seasonNumber))))
        }
    }
    private var spoilerJob: Job? = null
    private var spoilerExpiryJob: Job? = null
    private var spoilerRevision = 0

    init {
        viewModelScope.launch {
            SpoilerAccessRegistry.changes.collect {
                val active = client
                val snapshot = mutableState.value
                if (active != null && !active.spoilerUnlocked &&
                    (snapshot.spoilerUnlocked || snapshot.spoilerPromptOpen || snapshot.spoilerBusy ||
                        snapshot.libraries.any { it.isSpoiler } || snapshot.recentItems.any { it.isSpoiler } ||
                        snapshot.continueItems.any { it.isSpoiler } || snapshot.searchItems.any { it.isSpoiler } || snapshot.library?.isSpoiler == true ||
                        snapshot.detailStack.any { it.item.isSpoiler })) clearSpoilerUi()
            }
        }
        savedSession?.let { saved ->
            launchRequest {
                val active = ShyniClient(saved.server, saved.token, deviceId = deviceId)
                requireHttps(saved.server)
                client = active
                mutableState.update { it.copy(loggedIn = true, server = saved.server, userName = saved.userName) }
                loadHome(active, refreshUserName = true)
            }
        }
    }

    private fun requireHttps(server: String) {
        require(server.isNotBlank()) { "서버 주소를 입력하세요." }
        require(server.trim().startsWith("https://", ignoreCase = true)) {
            "HTTPS 서버 주소를 입력하세요."
        }
    }

    private fun launchRequest(block: suspend () -> Unit) {
        requestJob?.cancel()
        val current = ++revision
        mutableState.update { it.copy(busy = true, error = null, homeLoading = false) }
        requestJob = viewModelScope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (current == revision) {
                    if (error is ApiException && error.status == 401 &&
                        (mutableState.value.loggedIn || !client?.token.isNullOrBlank())) {
                        client?.let { sessionStore.clear(it.server) }
                        continueOwner = null
                        collectionWatch.clear()
                        client?.clearSpoilerGrant()
                        clearSpoilerUi()
                        clearContinueSelection()
                        client = null
                        mutableState.value = initialState.copy(
                            server = mutableState.value.server,
                            error = "로그인이 만료되었습니다. 다시 로그인하세요.",
                        )
                    } else mutableState.update { it.copy(error = error.message ?: "요청을 처리하지 못했습니다.") }
                }
            } finally {
                if (current == revision) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun login(server: String, user: String, password: String) {
        if (server.isBlank()) {
            mutableState.update { it.copy(error = "서버 주소를 입력하세요.") }
            return
        }
        if (user.isBlank() || password.isBlank()) {
            mutableState.update { it.copy(error = "아이디와 비밀번호를 입력하세요.") }
            return
        }
        collectionWatch.clear()
        continueOwner = null
        client?.clearSpoilerGrant()
        clearSpoilerUi()
        clearContinueSelection()
        mutableState.update { it.copy(continueItems = emptyList(), continueError = null) }
        launchRequest {
            val address = server.trim().trimEnd('/')
            requireHttps(address)
            val active = ShyniClient(address, deviceId = deviceId)
            client = active
            mutableState.update { it.copy(server = address, totpChallenge = null) }
            acceptLogin(active, active.login(user, password))
        }
    }

    private suspend fun acceptLogin(active: ShyniClient, result: LoginOutcome) {
        when (result) {
            is LoginOutcome.NeedsTotp -> mutableState.update { it.copy(totpChallenge = result.challenge) }
            is LoginOutcome.Success -> {
                clearContinueSelection()
                sessionStore.save(active.server, result.token, result.userName)
                settings.edit().putString("server", active.server).apply()
                mutableState.update { it.copy(loggedIn = true, userName = result.userName,
                    server = active.server, totpChallenge = null, library = null, detailStack = emptyList()) }
                loadHome(active)
            }
        }
    }

    fun submitTotp(code: String) {
        val challenge = mutableState.value.totpChallenge ?: return
        val active = client ?: return
        launchRequest { acceptLogin(active, active.completeTotp(challenge, code)) }
    }

    fun cancelTotp() {
        requestJob?.cancel(); revision++
        client?.clearSpoilerGrant()
        clearSpoilerUi()
        clearContinueSelection()
        client = null
        mutableState.update { it.copy(totpChallenge = null, busy = false, error = null) }
    }

    private suspend fun loadHome(active: ShyniClient, refreshUserName: Boolean = false,
        allowHomeInteraction: Boolean = true,
        reconcile: (AppState) -> AppState = { it }) {
        val homeRevision = ++homeRefreshRevision
        val navigationRevision = revision
        val accessRevision = spoilerRevision
        val grantRevision = SpoilerAccessRegistry.changes.value
        val homeAccountToken = active.token
        val previousContinue = mutableState.value.continueItems
        val previousRecent = mutableState.value.recentItems
        val previousOwnerMatches = continueOwner?.matches(active, homeAccountToken) == true
        var ordinary: List<Library>? = null
        // Until its replacement arrives, known private Views still exclude public duplicates.
        var privateLibraries = mutableState.value.libraries.filter { it.isSpoiler && active.spoilerUnlocked }
        var publicContinue: HomeContinueLoad? = null
        var privateContinue: HomeContinueLoad? = if (active.spoilerUnlocked) null else HomeContinueLoad.Success(emptyList())
        var publicRecent: Pair<List<MediaItem>, String?>? = null
        var privateRecent: List<MediaItem>? = if (active.spoilerUnlocked) null else emptyList()
        fun canPublishHome(): Boolean {
            // Opening a library must not discard an independent unlock-time Home fetch.
            if (active !== client || active.token != homeAccountToken ||
                homeRevision != homeRefreshRevision || accessRevision != spoilerRevision ||
                !mutableState.value.loggedIn) return false
            // An expired grant can be noticed by a response before the expiry ticker.
            if (!active.spoilerUnlocked && mutableState.value.spoilerUnlocked) {
                clearSpoilerUi()
                return false
            }
            return true
        }
        suspend fun <T> privateCatalog(fallback: T, block: suspend () -> T): T = try { block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            if (error is ApiException && error.status == 401) throw error
            if (canPublishHome() && active.spoilerUnlocked) mutableState.update {
                it.copy(spoilerError = "비공개 목록을 불러오지 못했습니다. 새로고침해 주세요.")
            }
            fallback
        }
        fun homeLibraries(): List<Library> {
            val privateCatalogs = if (active.spoilerUnlocked) privateLibraries else emptyList()
            val privateIds = privateCatalogs.map { it.id }.toSet()
            return (ordinary.orEmpty().filterNot { it.id in privateIds } + privateCatalogs).distinctBy { it.id }
        }
        fun publishContinue() {
            val publicLoad = publicContinue ?: return
            if (ordinary == null || !canPublishHome()) return
            val unlocked = active.spoilerUnlocked
            val continueItems = resolveHomeContinueWatching(publicLoad,
                privateContinue ?: HomeContinueLoad.Failure(transient = true), previousContinue,
                homeLibraries(), unlocked, sameAccountScope = active === client && mutableState.value.loggedIn,
                privateGrantUnchanged = accessRevision == spoilerRevision && grantRevision == SpoilerAccessRegistry.changes.value,
                previousOwnerMatches = previousOwnerMatches)
            val continueError = homeContinueLoadError(publicLoad, privateContinue ?: HomeContinueLoad.Success(emptyList()))
            if (selectedContinueIdentity != null && continueItems.none { continueIdentity(it) == selectedContinueIdentity }) {
                clearContinueSelection(clearCache = false)
            }
            continueOwner = HomeContinueOwner(active, homeAccountToken)
            mutableState.update { state ->
                val selected = continueItems.firstOrNull { continueIdentity(it) == selectedContinueIdentity }
                reconcile(state.copy(continueItems = continueItems, spoilerUnlocked = unlocked,
                    continueLoading = false, continueError = continueError,
                    continueDetails = selected?.let { row -> state.continueDetails?.let { continueMetadata(row, it) } },
                    error = if (navigationRevision == revision && !state.searchOpen && state.library == null && state.detailStack.isEmpty())
                        listOfNotNull(publicRecent?.second, continueError).distinct().joinToString("\n").ifBlank { null } else state.error))
            }
        }
        fun publishRecent() {
            val publicLoad = publicRecent ?: return
            if (ordinary == null || !canPublishHome()) return
            val retainedPrivate = if (previousOwnerMatches && grantRevision == SpoilerAccessRegistry.changes.value)
                previousRecent.filter { it.isSpoiler } else emptyList()
            val rows = visibleContinueWatching(publicLoad.first, privateRecent ?: retainedPrivate,
                homeLibraries(), active.spoilerUnlocked).map(::collectionWatchState)
            mutableState.update { state -> reconcile(state.copy(recentItems = rows, recentLoading = false,
                error = if (navigationRevision == revision && !state.searchOpen && state.library == null && state.detailStack.isEmpty())
                    listOfNotNull(publicLoad.second, state.continueError).distinct().joinToString("\n").ifBlank { null } else state.error)) }
        }
        fun publishLibraries() {
            if (ordinary == null || !canPublishHome()) return
            mutableState.update { it.copy(libraries = homeLibraries()) }
            // A new private catalog can disqualify a previously known public View.
            publishContinue()
            publishRecent()
        }
        mutableState.update { it.copy(homeLoading = allowHomeInteraction, continueLoading = true, continueError = null, recentLoading = true) }
        try {
            coroutineScope {
                val publicLibraries = async {
                    active.libraries().filterNot { it.isSpoiler }.also {
                        if (canPublishHome()) { ordinary = it; publishLibraries() }
                    }
                }
                loadHomeSections(
                    loadContinue = {
                        publicContinue = try { HomeContinueLoad.Success(active.continueWatching()) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { failedHomeContinueLoad(error) }
                        publicLibraries.await()
                        publishContinue()
                    },
                    loadRecent = {
                        val libraries = publicLibraries.await()
                        publicRecent = try { active.recentlyAdded(libraries) to null }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) {
                            if (error is ApiException && error.status == 401) throw error
                            emptyList<MediaItem>() to "최근 추가된 작품을 불러오지 못했습니다."
                        }
                        publishRecent()
                    },
                    loadSupplemental = {
                        coroutineScope {
                            launch {
                                val status = try { withTimeoutOrNull(3_000) { active.spoilerStatus() } }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) {
                                    if (error is ApiException && error.status == 401) throw error
                                    null
                                }
                                if (status != null && canPublishHome()) {
                                    mutableState.update { it.copy(spoilerAvailable = status.available,
                                        spoilerPinEnabled = status.pinEnabled != false, spoilerUnlocked = active.spoilerUnlocked) }
                                    if (active.spoilerUnlocked) scheduleSpoilerExpiry(active)
                                }
                            }
                            if (refreshUserName) launch {
                                val name = try { withTimeoutOrNull(5_000) { active.me() } }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) {
                                    if (error is ApiException && error.status == 401) throw error
                                    null
                                }
                                if (name != null && canPublishHome()) mutableState.update { it.copy(userName = name) }
                            }
                            if (active.spoilerUnlocked) launch {
                                privateLibraries = privateCatalog(emptyList()) { active.spoilerLibraries() }
                                publishLibraries()
                                if (privateLibraries.isEmpty()) {
                                    privateContinue = HomeContinueLoad.Success(emptyList())
                                    privateRecent = emptyList()
                                    publishContinue(); publishRecent()
                                } else loadHomeSections(
                                    loadContinue = {
                                        privateContinue = try { HomeContinueLoad.Success(active.spoilerContinueWatching()) }
                                        catch (cancel: CancellationException) { throw cancel }
                                        catch (error: Exception) { failedHomeContinueLoad(error) }
                                        publishContinue()
                                    },
                                    loadRecent = {
                                        privateRecent = privateCatalog(emptyList()) { active.recentlyAdded(privateLibraries) }
                                        publishRecent()
                                    },
                                )
                            }
                        }
                    },
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (canPublishHome()) mutableState.update { it.copy(continueError = "이어보기를 불러오지 못했습니다. 새로고침해 주세요.") }
            throw error
        } finally {
            if (active === client && homeRevision == homeRefreshRevision) mutableState.update {
                it.copy(homeLoading = false, continueLoading = false, recentLoading = false)
            }
        }
    }

    fun requestSpoilerUnlock() {
        if (mutableState.value.spoilerBusy || !mutableState.value.spoilerAvailable) return
        if (!mutableState.value.spoilerPinEnabled) mutableState.update { it.copy(spoilerError = "서버에서 비공개 PIN을 먼저 설정해 주세요.") }
        else mutableState.update { it.copy(spoilerPromptOpen = true, spoilerError = null) }
    }

    fun dismissSpoilerUnlock() {
        spoilerJob?.cancel(); spoilerRevision++
        val clearPendingGrant = !mutableState.value.spoilerUnlocked
        mutableState.update { it.copy(spoilerPromptOpen = false, spoilerBusy = false, spoilerError = null) }
        if (clearPendingGrant) client?.clearSpoilerGrant()
    }

    fun unlockSpoiler(pin: String) {
        val active = client ?: return
        if (!mutableState.value.loggedIn || mutableState.value.spoilerBusy) return
        spoilerJob?.cancel()
        val current = ++spoilerRevision
        mutableState.update { it.copy(spoilerBusy = true, spoilerError = null) }
        spoilerJob = viewModelScope.launch {
            try {
                val status = active.unlockSpoiler(pin)
                if (active !== client || current != spoilerRevision || !mutableState.value.loggedIn) {
                    active.clearSpoilerGrant(); return@launch
                }
                check(status.unlocked && active.spoilerUnlocked)
                requestJob?.cancel(); revision++
                mutableState.update { it.copy(spoilerUnlocked = true, spoilerPromptOpen = false,
                    spoilerAvailable = status.available, spoilerPinEnabled = status.pinEnabled != false, busy = false) }
                scheduleSpoilerExpiry(active)
                loadHome(active)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (active === client && current == spoilerRevision) {
                    mutableState.update { it.copy(spoilerError = if (active.spoilerUnlocked)
                        "비공개 목록을 불러오지 못했습니다. 새로고침해 주세요." else "잠금을 해제하지 못했습니다. PIN과 서버 연결을 확인하세요.") }
                }
            } finally {
                if (active === client && current == spoilerRevision) mutableState.update { it.copy(spoilerBusy = false) }
            }
        }
    }

    fun lockSpoiler() {
        val active = client
        spoilerJob?.cancel()
        // The client captures then clears its in-memory grant before suspending for server revocation.
        active?.let { viewModelScope.launch { runCatching { it.lockSpoiler() } } }
        active?.clearSpoilerGrant()
        clearSpoilerUi()
    }

    private fun scheduleSpoilerExpiry(active: ShyniClient) {
        spoilerExpiryJob?.cancel()
        spoilerExpiryJob = viewModelScope.launch {
            // Recheck the registry's monotonic deadline even when the wall clock changes.
            while (active === client && active.spoilerUnlocked) {
                delay((active.spoilerExpiresAtMs - System.currentTimeMillis()).coerceIn(100, 1000))
            }
            if (active === client && !active.spoilerUnlocked) clearSpoilerUi()
        }
    }

    /** Drop every private screen/result before a revoked grant can leave stale content visible. */
    private fun clearSpoilerUi() {
        spoilerJob?.cancel(); spoilerJob = null; spoilerRevision++
        spoilerExpiryJob?.cancel(); spoilerExpiryJob = null
        requestJob?.cancel(); revision++
        collectionWatch.entries.removeAll { it.value.first.isSpoiler }
        clearContinueSelection()
        mutableState.update(::withoutSpoilerContent)
    }

    /** Hover/focus enriches the hero independently of navigation and playback requests. */
    fun selectContinueItem(item: MediaItem) {
        val active = client ?: return
        val snapshot = mutableState.value
        if (!snapshot.loggedIn || !item.playable) return
        if (item.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        val key = continueIdentity(item)
        val source = snapshot.continueItems.firstOrNull { continueIdentity(it) == key } ?: return
        if (selectedContinueIdentity == key) {
            snapshot.continueDetails?.let { details ->
                mutableState.update { it.copy(continueDetails = continueMetadata(source, details)) }
            }
            return
        }
        continueDetailsJob?.cancel()
        continueDetailsJob = null
        val current = ++continueDetailsRevision
        selectedContinueIdentity = key
        val cached = continueDetailsCache.remove(key)?.also { continueDetailsCache[key] = it }
        mutableState.update { it.copy(continueDetails = cached?.let { details -> continueMetadata(source, details) } ?: source) }
        if (cached != null) return
        continueDetailsJob = viewModelScope.launch {
            try {
                val details = active.details(source).item
                if (active !== client || current != continueDetailsRevision || selectedContinueIdentity != key) return@launch
                val now = mutableState.value
                if (source.isSpoiler && (!now.spoilerUnlocked || !active.spoilerUnlocked)) {
                    clearSpoilerUi(); return@launch
                }
                val latest = now.continueItems.firstOrNull { continueIdentity(it) == key }
                    ?.takeIf { now.loggedIn } ?: return@launch
                // Detail routes may fill missing optional view IDs, but cannot change the selected work.
                if (details.id != source.id || details.libraryId != source.libraryId || details.type != source.type ||
                    (source.viewItemId != null && details.viewItemId != null && details.viewItemId != source.viewItemId)) return@launch
                val enriched = continueMetadata(latest, details)
                continueDetailsCache.remove(key)
                continueDetailsCache[key] = enriched
                while (continueDetailsCache.size > 5) continueDetailsCache.remove(continueDetailsCache.keys.first())
                mutableState.update { it.copy(continueDetails = enriched) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // The original row remains useful while metadata is unavailable.
            }
        }
    }

    /** Keep current progress and row identity when reusing cached descriptive metadata. */
    private fun continueMetadata(source: MediaItem, details: MediaItem) = source.copy(
        title = details.title.ifBlank { source.title },
        summary = details.summary.ifBlank { source.summary },
        poster = details.poster ?: source.poster,
        backdrop = details.backdrop ?: source.backdrop,
        thumbnail = details.thumbnail ?: source.thumbnail,
        durationMs = details.durationMs.takeIf { it > 0 } ?: source.durationMs,
        seasonNumber = details.seasonNumber ?: source.seasonNumber,
        episodeNumber = details.episodeNumber ?: source.episodeNumber,
        showId = details.showId ?: source.showId,
        showTitle = details.showTitle ?: source.showTitle,
        displaySubtitle = details.displaySubtitle ?: source.displaySubtitle,
    )

    private fun clearContinueSelection(clearCache: Boolean = true) {
        continueDetailsJob?.cancel()
        continueDetailsJob = null
        continueDetailsRevision++
        selectedContinueIdentity = null
        if (clearCache) continueDetailsCache.clear()
        mutableState.update { it.copy(continueDetails = null) }
    }

    fun selectLibrary(library: Library) {
        val active = client ?: return
        if (library.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        mutableState.update { it.copy(library = library, items = emptyList(), total = 0,
            searchOpen = false, searchQuery = "", searchItems = emptyList(), searchFailureCount = 0,
            query = "", librarySort = LibrarySort.TITLE_ASC,
            libraryContentType = LibraryContentType.defaultFor(library),
            libraryBrowseRevision = it.libraryBrowseRevision + 1,
            detailStack = emptyList(), versions = emptyList(), selectedPartId = null) }
        launchRequest {
            val page = active.items(library)
            mutableState.update { it.copy(items = page.items.map(::collectionWatchState), total = page.total) }
        }
    }

    fun loadMore() {
        val snapshot = mutableState.value
        val active = client ?: return
        val library = snapshot.library ?: return
        if (snapshot.busy || snapshot.detailStack.isNotEmpty() || snapshot.items.size >= snapshot.total) return
        launchRequest {
            val page = active.items(library, offset = snapshot.items.size, query = snapshot.query, sort = snapshot.librarySort,
                contentType = snapshot.libraryContentType)
            val appended = appendLibraryPage(snapshot.items, page)
            mutableState.update { it.copy(items = appended.items.map(::collectionWatchState), total = appended.total) }
        }
    }

    fun search(query: String) {
        val active = client ?: return
        val snapshot = mutableState.value
        val library = snapshot.library ?: return
        mutableState.update { it.copy(query = query, items = emptyList(), total = 0,
            libraryBrowseRevision = it.libraryBrowseRevision + 1) }
        launchRequest {
            val page = active.items(library, query = query, sort = snapshot.librarySort,
                contentType = snapshot.libraryContentType)
            mutableState.update { it.copy(items = page.items.map(::collectionWatchState), total = page.total) }
        }
    }

    fun setLibraryOptions(sort: LibrarySort = mutableState.value.librarySort,
        contentType: LibraryContentType = mutableState.value.libraryContentType) {
        val active = client ?: return
        val snapshot = mutableState.value
        val library = snapshot.library ?: return
        if (contentType !in LibraryContentType.availableFor(library)) return
        mutableState.update { it.copy(librarySort = sort, libraryContentType = contentType, items = emptyList(), total = 0,
            libraryBrowseRevision = it.libraryBrowseRevision + 1) }
        launchRequest {
            val page = active.items(library, query = snapshot.query, sort = sort, contentType = contentType)
            mutableState.update { it.copy(items = page.items.map(::collectionWatchState), total = page.total) }
        }
    }

    fun openItem(item: MediaItem) {
        val active = client ?: return
        if (item.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        mutableState.update { it.copy(detailStack = it.detailStack + MediaDetails(item),
            versions = emptyList(), selectedPartId = null) }
        launchRequest {
            val detail = collectionWatchState(episodeListDetails(active, active.details(item)))
            mutableState.update { it.copy(detailStack = it.detailStack.dropLast(1) + detail) }
            if (detail.item.playable) {
                val versions = active.versions(detail.item)
                mutableState.update { it.copy(versions = versions) }
            }
        }
    }

    private suspend fun episodeListDetails(active: ShyniClient, details: MediaDetails): MediaDetails {
        if (details.item.type !in setOf("show", "tv")) return details
        val seasons = details.children.filter { it.type == "season" }
        val selected = initialEpisodeSeason(seasons)
        val episodes = selected?.let { active.details(it).children }.orEmpty()
        return details.copy(children = episodes, seasons = seasons, selectedSeasonId = selected?.id)
    }

    fun selectSeason(season: MediaItem) {
        val active = client ?: return
        val snapshot = mutableState.value
        val detail = snapshot.detailStack.lastOrNull() ?: return
        val selected = detail.seasons.firstOrNull { it.id == season.id && it.libraryId == season.libraryId &&
            it.viewItemId == season.viewItemId && it.isSpoiler == season.isSpoiler } ?: return
        if (detail.selectedSeasonId == selected.id || detail.item.type !in setOf("show", "tv")) return
        if (selected.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        launchRequest {
            val episodes = active.details(selected).children.map(::collectionWatchState)
            mutableState.update { state ->
                val current = state.detailStack.lastOrNull()
                if (current != null && current.item == detail.item) state.copy(detailStack = state.detailStack.dropLast(1) +
                    current.copy(children = episodes, selectedSeasonId = selected.id)) else state
            }
        }
    }

    fun selectVersion(partId: Int?) { mutableState.update { it.copy(selectedPartId = partId) } }

    fun setWatched(item: MediaItem, watched: Boolean) {
        val active = client ?: return
        val snapshot = mutableState.value
        if ((!item.playable && item.type !in setOf("show", "tv", "season")) ||
            (snapshot.busy && !snapshot.homeLoading)) return
        if (item.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        launchRequest {
            val changed = try {
                if (item.playable) {
                    active.setWatched(item, watched)
                    listOf(item.copy(watched = watched, resumeMs = 0))
                } else active.setCollectionWatched(item, watched)
            } catch (error: Exception) {
                if (!item.playable) {
                    val previous = collectionWatch.keys.toSet()
                    invalidateCollectionWatch(item.copy(type = "episode",
                        showId = if (item.type == "show" || item.type == "tv") item.id else item.showId))
                    val invalidated = previous - collectionWatch.keys
                    fun uncertain(candidate: MediaItem) = if (continueIdentity(candidate) in invalidated)
                        candidate.copy(watched = false) else candidate
                    mutableState.update { state -> state.copy(items = state.items.map(::uncertain), searchItems = state.searchItems.map(::uncertain),
                        recentItems = state.recentItems.map(::uncertain), detailStack = state.detailStack.map { detail ->
                            detail.copy(item = uncertain(detail.item), children = detail.children.map(::uncertain))
                        }) }
                }
                throw error
            }
            val previousAggregates = collectionWatch.keys.toSet()
            changed.forEach(::invalidateCollectionWatch)
            val invalidatedAggregates = previousAggregates - collectionWatch.keys
            if (!item.playable) {
                val key = continueIdentity(item)
                collectionWatch.remove(key)
                collectionWatch[key] = item to watched
                while (collectionWatch.size > 32) collectionWatch.remove(collectionWatch.keys.first())
            }
            val accountToken = active.token
            val accessRevision = spoilerRevision
            // Every refreshed section publishes the acknowledged change atomically.
            // A slow or failed sibling request must not expose the pre-write state.
            val applyResult: (AppState) -> AppState = { state ->
                if (client !== active || active.token != accountToken || !state.loggedIn ||
                    (item.isSpoiler && (accessRevision != spoilerRevision ||
                        !state.spoilerUnlocked || !active.spoilerUnlocked))) state
                else applyExplicitWatchState(state, item, changed, watched) { candidate ->
                    continueIdentity(candidate) in invalidatedAggregates
                }
            }
            clearContinueSelection()
            mutableState.update(applyResult)
            // A queued playback-completion refresh waits until this confirmed change
            // has finished publishing instead of cancelling its reconciliation.
            refreshCurrent(applyResult, allowHomeInteraction = false)
        }
    }

    fun play(item: MediaItem, startOver: Boolean = false) {
        val snapshot = mutableState.value
        requestPlayback(item, startOver, selectedVersionForPlayback(snapshot, item), navigationQueryForPlayback(snapshot, item))
    }

    /** Home posters resume immediately; a previous detail screen's version is irrelevant. */
    fun playContinue(item: MediaItem) {
        requestPlayback(item, startOver = false, partId = null, navigationQuery = "")
    }

    private fun requestPlayback(item: MediaItem, startOver: Boolean, partId: Int?, navigationQuery: String) {
        val active = client ?: return
        if (item.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        val snapshot = mutableState.value
        if (!item.playable || (snapshot.busy && !snapshot.homeLoading)) return
        launchRequest {
            val resolved = active.playback(item, partId)
            val playback = resolved.copy(sourceItem = resolved.sourceItem ?: item, navigationQuery = navigationQuery)
            mutableState.update { it.copy(playback = if (startOver) playback.copy(resumeMs = 0) else playback) }
        }
    }

    fun consumePlayback() { mutableState.update { it.copy(playback = null) } }

    fun returnHomeAfterPlayback() {
        requestJob?.cancel(); revision++
        clearContinueSelection(clearCache = false)
        mutableState.update(::homeAfterPlayback)
        refresh()
    }

    fun openSearch() {
        requestJob?.cancel(); revision++
        clearContinueSelection(clearCache = false)
        mutableState.update { it.copy(searchOpen = true, library = null, items = emptyList(), total = 0, query = "",
            detailStack = emptyList(), versions = emptyList(), selectedPartId = null, busy = false, error = null,
            homeLoading = false) }
    }

    fun searchAll(query: String) {
        val active = client ?: return
        if (!mutableState.value.loggedIn || !mutableState.value.searchOpen) return
        val normalized = query.trim()
        mutableState.update { it.copy(searchQuery = normalized, searchItems = emptyList(), searchFailureCount = 0,
            detailStack = emptyList(), versions = emptyList(), selectedPartId = null) }
        if (normalized.isEmpty()) {
            requestJob?.cancel(); revision++
            mutableState.update { it.copy(busy = false, error = null) }
            return
        }
        launchRequest { loadSearch(active, normalized) }
    }

    private suspend fun loadSearch(active: ShyniClient, query: String,
        reconcile: (AppState) -> AppState = { it }) {
        if (query.isBlank()) return
        val navigationRevision = revision
        val accountToken = active.token
        val accessRevision = spoilerRevision
        val grantRevision = SpoilerAccessRegistry.changes.value
        val publicLibraries = active.libraries().filterNot { it.isSpoiler }
        var privateCatalogFailed = false
        val privateLibraries = if (active.spoilerUnlocked) try { active.spoilerLibraries() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (error is ApiException && error.status == 401) throw error
                privateCatalogFailed = true
                emptyList()
            } else emptyList()
        val privateIds = privateLibraries.map { it.id }.toSet() + mutableState.value.libraries.filter { it.isSpoiler }.map { it.id }
        val libraries = (publicLibraries.filterNot { it.id in privateIds } + privateLibraries).distinctBy { it.id }
        val result = active.searchLibraries(libraries, query)
        if (active !== client || active.token != accountToken || navigationRevision != revision ||
            accessRevision != spoilerRevision || grantRevision != SpoilerAccessRegistry.changes.value) return
        mutableState.update { state ->
            if (!state.loggedIn || !state.searchOpen || state.searchQuery != query) state
            else reconcile(state.copy(libraries = libraries.filter { !it.isSpoiler || active.spoilerUnlocked },
                searchItems = result.items.filter { !it.isSpoiler || (state.spoilerUnlocked && active.spoilerUnlocked) }
                    .map(::collectionWatchState), searchFailureCount = result.failedLibraryCount + if (privateCatalogFailed) 1 else 0))
        }
    }

    fun openHome() {
        requestJob?.cancel(); revision++
        mutableState.update { it.copy(detailStack = emptyList(), library = null, items = emptyList(),
            total = 0, query = "", searchOpen = false, searchQuery = "", searchItems = emptyList(), searchFailureCount = 0,
            busy = false, error = null, versions = emptyList(), selectedPartId = null) }
    }

    fun back() {
        requestJob?.cancel(); revision++
        mutableState.update {
            if (it.detailStack.isNotEmpty()) it.copy(detailStack = it.detailStack.dropLast(1),
                busy = false, versions = emptyList(), selectedPartId = null, error = null)
            else it.copy(library = null, items = emptyList(), total = 0, query = "",
                searchOpen = false, searchQuery = "", searchItems = emptyList(), searchFailureCount = 0, busy = false, error = null)
        }
    }

    suspend fun seriesUnwatchedCount(item: MediaItem): Int? {
        val active = client ?: return null
        val epoch = mutableState.value.seriesCountRevision
        if (item.isSpoiler && !mutableState.value.spoilerUnlocked) return null
        val result = withTimeoutOrNull(30_000) { active.unwatchedEpisodeCount(item) }
        return result.takeIf { active === client && epoch == mutableState.value.seriesCountRevision &&
            mutableState.value.loggedIn && (!item.isSpoiler || (mutableState.value.spoilerUnlocked && active.spoilerUnlocked)) }
    }

    fun refresh() = refreshCurrent()

    private fun refreshCurrent(reconcile: (AppState) -> AppState = { it },
        allowHomeInteraction: Boolean = true) {
        val active = client ?: return
        active.invalidateSeriesCounts()
        mutableState.update { it.copy(seriesCountRevision = it.seriesCountRevision + 1,
            libraryBrowseRevision = if (it.library != null && it.detailStack.isEmpty()) it.libraryBrowseRevision + 1 else it.libraryBrowseRevision) }
        val snapshot = mutableState.value
        launchRequest {
            coroutineScope {
                launch { loadHome(active, allowHomeInteraction = allowHomeInteraction, reconcile = reconcile) }
                if (snapshot.detailStack.isNotEmpty()) {
                    val item = snapshot.detailStack.last().item
                    var detail = active.details(item)
                    if (item.type in setOf("show", "tv")) {
                        val selectedId = snapshot.detailStack.last().selectedSeasonId
                        val seasons = detail.children.filter { it.type == "season" }
                        val selected = seasons.firstOrNull { it.id == selectedId } ?: initialEpisodeSeason(seasons)
                        detail = detail.copy(children = selected?.let { active.details(it).children }.orEmpty(),
                            seasons = seasons, selectedSeasonId = selected?.id)
                    }
                    detail = collectionWatchState(detail)
                    mutableState.update { reconcile(it.copy(detailStack = it.detailStack.dropLast(1) + detail)) }
                    if (item.playable) {
                        val versions = active.versions(item)
                        mutableState.update { it.copy(versions = versions) }
                    }
                } else if (snapshot.searchOpen) loadSearch(active, snapshot.searchQuery, reconcile)
                else snapshot.library?.let { library ->
                    val page = active.items(library, query = snapshot.query, sort = snapshot.librarySort,
                        contentType = snapshot.libraryContentType)
                    mutableState.update { reconcile(it.copy(items = page.items.map(::collectionWatchState), total = page.total)) }
                }
            }
        }
    }

    fun logout() {
        requestJob?.cancel(); revision++
        continueOwner = null
        collectionWatch.clear()
        client?.clearSpoilerGrant()
        clearSpoilerUi()
        clearContinueSelection()
        val active = client
        active?.let { sessionStore.clear(it.server) }
        client = null
        val snapshot = mutableState.value
        mutableState.value = initialState.copy(server = snapshot.server)
        active?.let { viewModelScope.launch { runCatching { it.logout() } } }
    }

    fun clearError() { mutableState.update { it.copy(error = null) } }

    fun posterHeaders(url: String): Map<String, String> = client?.headersFor(url) ?: emptyMap()

    private var posterAccountDigest: Pair<String, String>? = null

    fun posterCacheKey(url: String): String {
        val scope = client?.token.orEmpty()
        val digest = posterAccountDigest?.takeIf { it.first == scope }?.second
            ?: java.security.MessageDigest.getInstance("SHA-256")
                .digest(scope.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .also { posterAccountDigest = scope to it }
        return "$digest:$url"
    }
}
