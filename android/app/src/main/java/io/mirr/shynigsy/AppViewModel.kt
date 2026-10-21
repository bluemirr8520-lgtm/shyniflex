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
)

/** A first launch has no server; only a user's previously saved address can restore one. */
internal fun initialServerAddress(savedAddress: String?, sessionAddress: String?): String =
    savedAddress?.takeIf { it.isNotBlank() } ?: sessionAddress?.takeIf { it.isNotBlank() } ?: ""

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
        libraries = state.libraries.filterNot { it.isSpoiler }, recentItems = state.recentItems.filterNot { it.isSpoiler },
        continueItems = state.continueItems.filterNot { it.isSpoiler },
        continueDetails = state.continueDetails?.takeUnless { it.isSpoiler },
        library = state.library?.takeUnless { it.isSpoiler },
        items = if (privateScreen) emptyList() else state.items.filterNot { it.isSpoiler },
        total = if (privateScreen) 0 else state.total,
        detailStack = if (privateScreen) emptyList() else state.detailStack.map { detail ->
            detail.copy(children = detail.children.filterNot { it.isSpoiler })
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
        children = details.children.map(::collectionWatchState))

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
                        snapshot.continueItems.any { it.isSpoiler } || snapshot.library?.isSpoiler == true ||
                        snapshot.detailStack.any { it.item.isSpoiler })) clearSpoilerUi()
            }
        }
        savedSession?.let { saved ->
            launchRequest {
                val active = ShyniClient(saved.server, saved.token, deviceId = deviceId)
                requireHttps(saved.server)
                client = active
                val name = active.me()
                mutableState.update { it.copy(loggedIn = true, server = saved.server, userName = name) }
                loadHome(active)
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

    private suspend fun loadHome(active: ShyniClient) {
        val homeRevision = ++homeRefreshRevision
        val navigationRevision = revision
        val accessRevision = spoilerRevision
        val grantRevision = SpoilerAccessRegistry.changes.value
        val homeAccountToken = active.token
        val previousContinue = mutableState.value.continueItems
        val previousOwnerMatches = continueOwner?.matches(active, homeAccountToken) == true
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
        catch (_: Exception) {
            if (canPublishHome() && active.spoilerUnlocked) mutableState.update {
                it.copy(spoilerError = "비공개 목록을 불러오지 못했습니다. 새로고침해 주세요.")
            }
            fallback
        }
        mutableState.update { it.copy(homeLoading = true, continueLoading = true, continueError = null, recentLoading = true) }
        try {
            val status = try { active.spoilerStatus() } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { null }
            if (!canPublishHome()) return
            if (status != null) {
                mutableState.update { it.copy(spoilerAvailable = status.available, spoilerPinEnabled = status.pinEnabled != false,
                    spoilerUnlocked = active.spoilerUnlocked) }
                if (active.spoilerUnlocked) scheduleSpoilerExpiry(active)
            }
            val ordinary = active.libraries().filterNot { it.isSpoiler }
            val publicViewIds = ordinary.map { it.id }.toSet()
            val privateLibraries = if (active.spoilerUnlocked) privateCatalog(emptyList<Library>()) {
                active.spoilerLibraries().filterNot { it.id in publicViewIds }
            } else emptyList()
            if (!canPublishHome()) return
            val libraries = (ordinary + if (active.spoilerUnlocked) privateLibraries else emptyList()).distinctBy { it.id }
            mutableState.update { it.copy(libraries = libraries) }
            loadHomeSections(
                loadRecent = recentSection@{
                    val publicResult = try { active.recentlyAdded(ordinary) to null } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        if (error is ApiException && error.status == 401) throw error
                        emptyList<MediaItem>() to "최근 추가된 작품을 불러오지 못했습니다."
                    }
                    val privateResult = if (active.spoilerUnlocked && privateLibraries.isNotEmpty())
                        privateCatalog(emptyList<MediaItem>()) { active.recentlyAdded(privateLibraries) } else emptyList()
                    if (!canPublishHome()) return@recentSection
                    mutableState.update { state -> state.copy(
                        recentItems = (publicResult.first + privateResult)
                            .filter { active.spoilerUnlocked || !it.isSpoiler }.map(::collectionWatchState),
                        recentLoading = false,
                        error = if (navigationRevision == revision && state.library == null && state.detailStack.isEmpty())
                            listOfNotNull(publicResult.second, state.continueError).distinct().joinToString("\n").ifBlank { null }
                            else state.error,
                    ) }
                },
                loadContinue = continueSection@{
                    val (publicContinue, privateContinue) = coroutineScope {
                        val watching = async {
                            try { HomeContinueLoad.Success(active.continueWatching()) } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                failedHomeContinueLoad(error)
                            }
                        }
                        val privateWatching = async {
                            if (active.spoilerUnlocked && privateLibraries.isNotEmpty()) {
                                try { HomeContinueLoad.Success(active.spoilerContinueWatching()) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) { failedHomeContinueLoad(error) }
                            } else HomeContinueLoad.Success(emptyList())
                        }
                        watching.await() to privateWatching.await()
                    }
                    if (!canPublishHome()) return@continueSection
                    val unlocked = active.spoilerUnlocked
                    val continueItems = resolveHomeContinueWatching(publicContinue, privateContinue, previousContinue,
                        libraries, unlocked, sameAccountScope = active === client && mutableState.value.loggedIn,
                        privateGrantUnchanged = accessRevision == spoilerRevision && grantRevision == SpoilerAccessRegistry.changes.value,
                        previousOwnerMatches = previousOwnerMatches)
                    val continueError = homeContinueLoadError(publicContinue, privateContinue)
                    if (selectedContinueIdentity != null && continueItems.none { continueIdentity(it) == selectedContinueIdentity }) {
                        clearContinueSelection(clearCache = false)
                    }
                    continueOwner = HomeContinueOwner(active, homeAccountToken)
                    mutableState.update { state ->
                        val selected = continueItems.firstOrNull { continueIdentity(it) == selectedContinueIdentity }
                        state.copy(continueItems = continueItems, spoilerUnlocked = unlocked,
                            continueLoading = false, continueError = continueError,
                            continueDetails = selected?.let { row -> state.continueDetails?.let { continueMetadata(row, it) } },
                            error = if (navigationRevision == revision && state.library == null && state.detailStack.isEmpty())
                                listOfNotNull(state.error, continueError).distinct().joinToString("\n").ifBlank { null } else state.error)
                    }
                },
            )
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
            query = "", detailStack = emptyList(), versions = emptyList(), selectedPartId = null) }
        launchRequest {
            val page = active.items(library)
            mutableState.update { it.copy(items = page.items.map(::collectionWatchState), total = page.total) }
        }
    }

    fun loadMore() {
        val snapshot = mutableState.value
        val active = client ?: return
        val library = snapshot.library ?: return
        if (snapshot.busy || snapshot.items.size >= snapshot.total) return
        launchRequest {
            val page = active.items(library, snapshot.items.size, snapshot.query)
            mutableState.update { it.copy(items = (snapshot.items + page.items).map(::collectionWatchState).distinctBy { item -> item.id },
                total = if (page.items.isEmpty()) snapshot.items.size else page.total) }
        }
    }

    fun search(query: String) {
        val active = client ?: return
        val library = mutableState.value.library ?: return
        mutableState.update { it.copy(query = query, items = emptyList(), total = 0) }
        launchRequest {
            val page = active.items(library, query = query)
            mutableState.update { it.copy(items = page.items.map(::collectionWatchState), total = page.total) }
        }
    }

    fun openItem(item: MediaItem) {
        val active = client ?: return
        if (item.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        mutableState.update { it.copy(detailStack = it.detailStack + MediaDetails(item),
            versions = emptyList(), selectedPartId = null) }
        launchRequest {
            val detail = collectionWatchState(active.details(item))
            mutableState.update { it.copy(detailStack = it.detailStack.dropLast(1) + detail) }
            if (detail.item.playable) {
                val versions = active.versions(detail.item)
                mutableState.update { it.copy(versions = versions) }
            }
        }
    }

    fun selectVersion(partId: Int?) { mutableState.update { it.copy(selectedPartId = partId) } }

    fun setWatched(item: MediaItem, watched: Boolean) {
        val active = client ?: return
        if ((!item.playable && item.type !in setOf("show", "tv", "season")) || mutableState.value.busy) return
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
                    mutableState.update { state -> state.copy(items = state.items.map(::uncertain),
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
            val applyResult = {
                fun matches(candidate: MediaItem, target: MediaItem) = candidate.id == target.id &&
                    candidate.libraryId == target.libraryId && candidate.type == target.type &&
                    candidate.isSpoiler == target.isSpoiler && (candidate.viewItemId == null || target.viewItemId == null ||
                        candidate.viewItemId == target.viewItemId)
                fun affected(candidate: MediaItem) = matches(candidate, item) || changed.any { matches(candidate, it) }
                fun updated(candidate: MediaItem) = when {
                    affected(candidate) -> candidate.copy(watched = watched, resumeMs = 0)
                    continueIdentity(candidate) in invalidatedAggregates -> candidate.copy(watched = false)
                    else -> candidate
                }
                clearContinueSelection()
                mutableState.update { state ->
                    state.copy(items = state.items.map(::updated), recentItems = state.recentItems.map(::updated),
                        continueItems = state.continueItems.filterNot(::affected),
                        detailStack = state.detailStack.map { detail ->
                            detail.copy(item = updated(detail.item), children = detail.children.map(::updated))
                        })
                }
            }
            applyResult()
            refreshCurrent(applyResult)
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

    fun back() {
        requestJob?.cancel(); revision++
        mutableState.update {
            if (it.detailStack.isNotEmpty()) it.copy(detailStack = it.detailStack.dropLast(1),
                busy = false, versions = emptyList(), selectedPartId = null, error = null)
            else it.copy(library = null, items = emptyList(), total = 0, query = "", busy = false, error = null)
        }
    }

    fun refresh() = refreshCurrent()

    private fun refreshCurrent(afterRefresh: (() -> Unit)? = null) {
        val active = client ?: return
        val snapshot = mutableState.value
        launchRequest {
            loadHome(active)
            if (snapshot.detailStack.isNotEmpty()) {
                val item = snapshot.detailStack.last().item
                val detail = collectionWatchState(active.details(item))
                mutableState.update { it.copy(detailStack = it.detailStack.dropLast(1) + detail) }
                if (item.playable) {
                    val versions = active.versions(item)
                    mutableState.update { it.copy(versions = versions) }
                }
            } else snapshot.library?.let { library ->
                val page = active.items(library, query = snapshot.query)
                mutableState.update { it.copy(items = page.items.map(::collectionWatchState), total = page.total) }
            }
            afterRefresh?.invoke()
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

    fun posterCacheKey(url: String): String {
        val scope = client?.token.orEmpty()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$digest:$url"
    }
}
