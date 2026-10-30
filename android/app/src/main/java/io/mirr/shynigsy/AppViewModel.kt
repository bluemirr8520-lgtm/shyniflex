package io.mirr.shynigsy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mirr.shynigsy.data.*
import io.mirr.shynigsy.player.PlaybackCompletionSignals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val tvQrLogin: TvQrLoginState = TvQrLoginState(),
    val tvLoginApproval: TvLoginApprovalState? = null,
    val playback: PlaybackSpec? = null,
    val completedEpisodePending: MediaItem? = null,
    val spoilerAvailable: Boolean = false,
    val spoilerPinEnabled: Boolean = true,
    val spoilerUnlocked: Boolean = false,
    val spoilerBusy: Boolean = false,
    val spoilerLibrariesLoading: Boolean = false,
    val spoilerPromptOpen: Boolean = false,
    val spoilerError: String? = null,
    val seriesCountRevision: Int = 0,
    val detailDiscovery: Map<String, DetailDiscoveryState> = emptyMap(),
)

/** A first launch has no server; only a user's previously saved address can restore one. */
internal fun initialServerAddress(savedAddress: String?, sessionAddress: String?): String =
    savedAddress?.takeIf { it.isNotBlank() } ?: sessionAddress?.takeIf { it.isNotBlank() } ?: ""

/** Show an actionable PIN result without echoing a server response or submitted credentials. */
internal fun spoilerUnlockFailureMessage(error: Exception, unlocked: Boolean): String {
    if (unlocked) return "비공개 목록을 불러오지 못했습니다. 새로고침해 주세요."
    return when ((error as? ApiException)?.status) {
        401 -> "로그인이 만료되었습니다. 다시 로그인한 뒤 PIN을 입력해 주세요."
        403 -> "PIN이 올바르지 않거나 접근이 허용되지 않습니다. PIN을 확인하고 다시 시도하세요."
        408 -> "PIN 확인 응답이 늦어 중단했습니다. 서버 상태를 확인한 뒤 다시 시도하세요."
        429 -> "PIN 확인 요청이 너무 많습니다. 잠시 후 다시 시도하세요."
        in 500..599 -> "서버가 PIN 확인을 처리하지 못했습니다. 잠시 후 다시 시도하세요."
        else -> "PIN을 확인하지 못했습니다. PIN과 서버 연결 상태를 확인하고 다시 시도하세요."
    }
}

/** An optional catalog status timeout must not remove explicit PIN entry. */
internal fun spoilerUnlockPromptState(state: AppState, hasClient: Boolean): AppState {
    if (!state.loggedIn || !hasClient || state.spoilerBusy) return state
    return state.copy(spoilerPromptOpen = true, spoilerError = if (!state.spoilerPinEnabled)
        "서버에서 비공개 PIN을 먼저 설정해 주세요." else null)
}

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
    return state.copy(spoilerUnlocked = false, spoilerBusy = false, spoilerLibrariesLoading = false,
        spoilerPromptOpen = false, spoilerError = null,
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
        detailDiscovery = if (privateScreen) emptyMap() else state.detailDiscovery
            .filterValues { !it.parent.isSpoiler }.mapValues { it.value.withoutPrivateWorks() },
        versions = if (privateScreen) emptyList() else state.versions,
        selectedPartId = if (privateScreen) null else state.selectedPartId,
        query = if (privateScreen) "" else state.query,
        playback = state.playback?.takeUnless { it.sourceItem?.isSpoiler == true }, busy = false,
        completedEpisodePending = null,
        error = if (privateScreen) null else state.error,
        homeLoading = false, continueLoading = false, continueError = null, recentLoading = false)
}

/** A rotated grant must revalidate Home names and rows without interrupting playback. */
internal fun withoutStalePrivateHomeContent(state: AppState): AppState = state.copy(
    libraries = state.libraries.filterNot { it.isSpoiler },
    recentItems = state.recentItems.filterNot { it.isSpoiler },
    continueItems = state.continueItems.filterNot { it.isSpoiler },
    continueDetails = state.continueDetails?.takeUnless { it.isSpoiler },
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.getSharedPreferences("gsy_settings", Application.MODE_PRIVATE)
    private val sessionStore = SessionStore(application)
    private val deviceId = settings.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        settings.edit().putString("device_id", it).apply()
    }
    private val deviceName by lazy { deviceDisplayName(android.os.Build.MANUFACTURER, android.os.Build.MODEL, application.isTelevision()) }
    private val savedSession = sessionStore.loadLast()
    private val initialState = AppState(server = initialServerAddress(settings.getString("server", null), savedSession?.server))
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    private var client: ShyniClient? = null
    private var requestJob: Job? = null
    private var tvQrLoginJob: Job? = null
    private var tvQrLoginRevision = 0L
    private var tvApprovalJob: Job? = null
    private var tvApprovalRevision = 0L
    private var refreshJob: Job? = null
    private var revision = 0
    private var homeRefreshRevision = 0
    private var continueOwner: HomeContinueOwner? = null
    private data class ContinueIdentity(val libraryId: Int, val id: Int, val type: String, val viewItemId: Int?, val isSpoiler: Boolean)
    private fun continueIdentity(item: MediaItem) = ContinueIdentity(item.libraryId, item.id, item.type, item.viewItemId, item.isSpoiler)
    private var continueDetailsJob: Job? = null
    private var continueDetailsRevision = 0
    private var selectedContinueIdentity: ContinueIdentity? = null
    private var playbackOwner: HomeContinueOwner? = null
    private var playbackGrantRevision: Long? = null
    private val continueDetailsCache = linkedMapOf<ContinueIdentity, MediaItem>()
    private val collectionWatch = linkedMapOf<ContinueIdentity, Pair<MediaItem, Boolean>>()
    private var lastPlaybackConfirmation = 0L
    private val confirmedPlaybackWatches = linkedMapOf<Long, PlaybackCompletionSignals.ConfirmedWatch>()

    internal fun applyConfirmedPlaybackWatch(state: AppState): AppState {
        val active = client ?: return state
        if (!state.loggedIn) return state
        val scope = PlaybackCompletionSignals.scope(active.server, active.token)
        return applyConfirmedPlaybackWatches(state, confirmedPlaybackWatches.values) { event ->
            isCurrentPlaybackConfirmation(event, scope,
                    PlaybackCompletionSignals.isChoiceCurrent(event.scope, event.item, event.choiceRevision),
                    SpoilerAccessRegistry.changes.value, state.spoilerUnlocked && active.spoilerUnlocked)
        }
    }

    fun acceptPlaybackConfirmations(events: List<PlaybackCompletionSignals.ConfirmedWatch>) {
        val active = client ?: return
        for (event in events) {
            if (event.sequence <= lastPlaybackConfirmation) continue
            lastPlaybackConfirmation = event.sequence
            if (!mutableState.value.loggedIn || !isCurrentPlaybackConfirmation(event,
                    PlaybackCompletionSignals.scope(active.server, active.token),
                    PlaybackCompletionSignals.isChoiceCurrent(event.scope, event.item, event.choiceRevision),
                    SpoilerAccessRegistry.changes.value, mutableState.value.spoilerUnlocked && active.spoilerUnlocked)) continue
            confirmedPlaybackWatches[event.sequence] = event
            while (confirmedPlaybackWatches.size > 32) confirmedPlaybackWatches.remove(confirmedPlaybackWatches.keys.first())
            mutableState.update(::applyConfirmedPlaybackWatch)
        }
    }
    private var privateViewOwner: HomeContinueOwner? = null
    private val knownPrivateViewIds = mutableSetOf<Int>()
    private var observedHomeGrantRevision = SpoilerAccessRegistry.changes.value
    private val privateHomeScope = PrivateHomeGrantScope()

    private fun rememberPrivateViews(active: ShyniClient, libraries: List<Library>) {
        if (privateViewOwner?.matches(active, active.token) != true) {
            knownPrivateViewIds.clear()
            privateViewOwner = HomeContinueOwner(active, active.token)
        }
        knownPrivateViewIds.addAll(libraries.filter { it.isSpoiler }.map { it.id })
    }

    /** Fence old private Home data before a new account/grant can capture or publish it. */
    private fun enterPrivateHomeScope(active: ShyniClient, grantRevision: Long) {
        val sameAccount = privateHomeScope.belongsTo(active, active.token)
        if (!privateHomeScope.advance(active, active.token, grantRevision)) return
        if (sameAccount) rememberPrivateViews(active, mutableState.value.libraries)
        collectionWatch.entries.removeAll { it.value.first.isSpoiler }
        mutableState.update(::withoutStalePrivateHomeContent)
    }
    private var relatedWorksJob: Job? = null
    private var actorWorksJob: Job? = null
    private var relatedWorksRevision = 0
    private var actorWorksRevision = 0

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
                // A collector may run after a newer Home fetch has already published.
                val grantRevision = SpoilerAccessRegistry.changes.value
                val grantChanged = grantRevision != observedHomeGrantRevision
                observedHomeGrantRevision = grantRevision
                cancelDetailDiscoveryJobs()
                mutableState.update { state -> state.copy(detailDiscovery = state.detailDiscovery
                    .mapValues { it.value.withoutPrivateWorks() }) }
                val active = client
                val snapshot = mutableState.value
                if (grantChanged && active != null && active.spoilerUnlocked) {
                    enterPrivateHomeScope(active, grantRevision)
                }
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

    private fun launchRequest(keepCompletedEpisodePending: Boolean = false, block: suspend () -> Unit) {
        requestJob?.cancel()
        val current = ++revision
        mutableState.update { it.copy(busy = true, error = null, homeLoading = false,
            completedEpisodePending = if (keepCompletedEpisodePending) it.completedEpisodePending else null) }
        requestJob = viewModelScope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (current == revision) {
                    if (error is ApiException && error.status == 401 &&
                        (mutableState.value.loggedIn || !client?.token.isNullOrBlank())) {
                        cancelTvQrLogin()
                        dismissTvLoginApproval()
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
        cancelTvQrLogin()
        if (server.isBlank()) {
            mutableState.update { it.copy(error = "서버 주소를 입력하세요.") }
            return
        }
        if (user.isBlank() || password.isBlank()) {
            mutableState.update { it.copy(error = "아이디와 비밀번호를 입력하세요.") }
            return
        }
        mutableState.value.tvLoginApproval?.let {
            if (!sameTvLoginServer(it.link.server, server)) dismissTvLoginApproval()
        }
        cancelDetailDiscoveryJobs(clear = true)
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
            acceptLogin(active, active.login(user, password, deviceName))
        }
    }

    /** The relay job never becomes the ordinary home-loading job. */
    fun startTvQrLogin(server: String) {
        cancelTvQrLogin()
        if (mutableState.value.loggedIn || mutableState.value.busy || mutableState.value.totpChallenge != null) return
        val address = server.trim().trimEnd('/')
        val active = runCatching {
            requireHttps(address)
            ShyniClient(address, deviceId = deviceId)
        }.getOrElse {
            mutableState.update { state -> state.copy(tvQrLogin = TvQrLoginState(
                phase = TvQrLoginPhase.ERROR, message = "올바른 HTTPS 서버 주소를 입력하세요.")) }
            return
        }
        cancelDetailDiscoveryJobs(clear = true)
        requestJob?.cancel(); revision++
        client?.clearSpoilerGrant()
        client = null
        continueOwner = null
        collectionWatch.clear()
        clearSpoilerUi()
        clearContinueSelection()
        val generation = ++tvQrLoginRevision
        mutableState.update { it.copy(server = address, error = null, busy = false,
            tvQrLogin = TvQrLoginState(phase = TvQrLoginPhase.CREATING)) }
        fun current(): Boolean = generation == tvQrLoginRevision && !mutableState.value.loggedIn &&
            mutableState.value.totpChallenge == null && sameTvLoginServer(address, mutableState.value.server)
        tvQrLoginJob = viewModelScope.launch {
            try {
                val result = awaitTvPairing(active, deviceName,
                    qrUrlForNonce = { tvLoginQrUrl(address, it) },
                    onState = { status -> if (current()) mutableState.update { it.copy(tvQrLogin = status) } })
                currentCoroutineContext().ensureActive()
                if (!current()) return@launch
                // LoginScreen leaves composition after acceptLogin. Its disposal must not cancel Home.
                tvQrLoginJob = null
                tvQrLoginRevision++
                mutableState.update { it.copy(tvQrLogin = TvQrLoginState()) }
                active.token = result.token
                client = active
                launchRequest { acceptLogin(active, result) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (current() && mutableState.value.tvQrLogin.phase !in setOf(TvQrLoginPhase.ERROR, TvQrLoginPhase.EXPIRED))
                    mutableState.update { it.copy(tvQrLogin = TvQrLoginState(phase = TvQrLoginPhase.ERROR,
                        message = "QR 로그인을 완료하지 못했습니다. 새 코드로 다시 시도하세요.")) }
            } finally {
                if (generation == tvQrLoginRevision) tvQrLoginJob = null
            }
        }
    }

    fun cancelTvQrLogin() {
        if (tvQrLoginJob == null && mutableState.value.tvQrLogin.phase == TvQrLoginPhase.IDLE) return
        tvQrLoginRevision++
        tvQrLoginJob?.cancel()
        tvQrLoginJob = null
        mutableState.update { it.copy(tvQrLogin = TvQrLoginState()) }
    }

    /** A scanned link only stages an explicit phone confirmation; it never approves by itself. */
    fun receiveTvLoginLink(link: TvLoginLink) {
        val checked = runCatching { parseTvLoginLink(tvLoginQrUrl(link.server, link.nonce)) }.getOrNull() ?: return
        cancelTvQrLogin()
        dismissTvLoginApproval()
        if (!mutableState.value.loggedIn) {
            requestJob?.cancel(); revision++
            client?.clearSpoilerGrant()
            client = null
            mutableState.update { it.copy(server = checked.server, totpChallenge = null, busy = false, error = null) }
        }
        mutableState.update { it.copy(tvLoginApproval = TvLoginApprovalState(checked,
            android.os.SystemClock.elapsedRealtime() + 120_000L)) }
    }

    fun dismissTvLoginApproval() {
        tvApprovalRevision++
        tvApprovalJob?.cancel()
        tvApprovalJob = null
        mutableState.update { it.copy(tvLoginApproval = null) }
    }

    fun approveTvLogin() {
        val pending = mutableState.value.tvLoginApproval?.takeUnless { it.busy || it.approved } ?: return
        val active = client ?: return
        if (!mutableState.value.loggedIn) return
        if (!sameTvLoginServer(active.server, pending.link.server)) {
            mutableState.update { it.copy(tvLoginApproval = pending.copy(error = "QR 코드의 서버와 현재 로그인한 서버가 다릅니다.")) }
            return
        }
        if (android.os.SystemClock.elapsedRealtime() >= pending.expiresAtMs) {
            mutableState.update { it.copy(tvLoginApproval = pending.copy(error = "QR 코드가 만료되었습니다. TV에서 새 코드를 만들어 주세요.")) }
            return
        }
        val accountToken = active.token
        val generation = ++tvApprovalRevision
        mutableState.update { it.copy(tvLoginApproval = pending.copy(busy = true, error = null)) }
        fun current(): Boolean = generation == tvApprovalRevision && active === client &&
            active.token == accountToken && mutableState.value.loggedIn &&
            mutableState.value.tvLoginApproval?.link == pending.link
        tvApprovalJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeout(20_000L) { active.approveTvPairing(pending.link.nonce) }
                currentCoroutineContext().ensureActive()
                if (current()) mutableState.update { it.copy(tvLoginApproval = pending.copy(approved = true)) }
            } catch (cancel: CancellationException) {
                if (cancel is kotlinx.coroutines.TimeoutCancellationException && current()) {
                    mutableState.update { it.copy(tvLoginApproval = pending.copy(error = "응답이 지연됩니다. TV의 연결 상태를 확인하고 새 코드로 다시 시도하세요.")) }
                } else throw cancel
            } catch (_: Exception) {
                if (current()) mutableState.update { it.copy(tvLoginApproval = pending.copy(
                    error = "TV 연결을 승인하지 못했습니다. 로그인 상태를 확인하고 TV에서 새 코드를 만들어 주세요.")) }
            } finally {
                if (generation == tvApprovalRevision) tvApprovalJob = null
            }
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
        cancelTvQrLogin()
        cancelDetailDiscoveryJobs(clear = true)
        requestJob?.cancel(); revision++
        client?.clearSpoilerGrant()
        clearSpoilerUi()
        clearContinueSelection()
        client = null
        mutableState.update { it.copy(totpChallenge = null, busy = false, error = null) }
    }

    private suspend fun loadHome(active: ShyniClient, refreshUserName: Boolean = false,
        allowHomeInteraction: Boolean = true,
        prioritizePrivateCatalog: Boolean = false,
        reconcile: (AppState) -> AppState = { it }) {
        val homeRevision = ++homeRefreshRevision
        val navigationRevision = revision
        val accessRevision = spoilerRevision
        val grantRevision = SpoilerAccessRegistry.changes.value
        val homeAccountToken = active.token
        // The collector can be scheduled after this fetch. Do not snapshot old
        // private rows under a new grant while waiting for it.
        enterPrivateHomeScope(active, grantRevision)
        val previousContinue = mutableState.value.continueItems
        val previousRecent = mutableState.value.recentItems
        val previousOwnerMatches = continueOwner?.matches(active, homeAccountToken) == true
        val knownPrivateIds = if (privateViewOwner?.matches(active, homeAccountToken) == true)
            knownPrivateViewIds.toSet() else emptySet()
        val prioritizingPrivate = prioritizePrivateCatalog && active.spoilerUnlocked
        // PIN entry already has a same-account public catalog. Reuse only those names so a
        // fresh private catalog need not wait for an unrelated public refresh to be shown.
        var ordinary: List<Library>? = if (prioritizingPrivate)
            mutableState.value.libraries.filterNot { it.isSpoiler } else null
        var publicLibrariesLoaded = false
        // Until its replacement arrives, known private Views still exclude public duplicates.
        var privateLibraries = mutableState.value.libraries.filter { it.isSpoiler && active.spoilerUnlocked }
        var publicContinue: HomeContinueLoad? = null
        var privateContinue: HomeContinueLoad? = if (active.spoilerUnlocked) null else HomeContinueLoad.Success(emptyList())
        var publicRecent: Pair<List<MediaItem>, String?>? = null
        var privateRecent: List<MediaItem>? = if (active.spoilerUnlocked) null else emptyList()
        var publicRecentComplete = false
        var privateRecentComplete = !active.spoilerUnlocked
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
            if (canPublishHome() && active.spoilerUnlocked &&
                grantRevision == SpoilerAccessRegistry.changes.value) mutableState.update {
                it.copy(spoilerError = "비공개 목록을 불러오지 못했습니다. 새로고침해 주세요.")
            }
            fallback
        }
        fun homeLibraries(): List<Library> {
            return homeLibrariesForGrant(ordinary.orEmpty(), privateLibraries, knownPrivateIds,
                active.spoilerUnlocked && accessRevision == spoilerRevision &&
                    grantRevision == SpoilerAccessRegistry.changes.value)
        }
        fun publishContinue() {
            val publicLoad = publicContinue ?: return
            if (!publicLibrariesLoaded || ordinary == null || !canPublishHome()) return
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
            if (!publicLibrariesLoaded || ordinary == null || !canPublishHome()) return
            val privateGrantCurrent = active.spoilerUnlocked && accessRevision == spoilerRevision &&
                grantRevision == SpoilerAccessRegistry.changes.value
            val retainedPrivate = if (previousOwnerMatches && privateGrantCurrent)
                previousRecent.filter { it.isSpoiler } else emptyList()
            val rows = visibleContinueWatching(publicLoad.first,
                if (privateGrantCurrent) privateRecent ?: retainedPrivate else emptyList(),
                homeLibraries(), privateGrantCurrent).map(::collectionWatchState)
            mutableState.update { state -> reconcile(state.copy(recentItems = rows,
                recentLoading = !publicRecentComplete || !privateRecentComplete,
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
        mutableState.update { it.copy(homeLoading = allowHomeInteraction, continueLoading = true,
            continueError = null, recentLoading = true, spoilerLibrariesLoading = active.spoilerUnlocked) }
        try {
            coroutineScope {
                // Enqueue the requested private names before public shelves occupy the catalog lane.
                val priorityPrivate = if (prioritizingPrivate)
                    startPriorityHomeCatalog { active.spoilerLibraries() } else null
                val publicLibraries = async {
                    active.libraries().filterNot { it.isSpoiler }.also {
                        if (canPublishHome()) {
                            ordinary = it
                            publicLibrariesLoaded = true
                            publishLibraries()
                        }
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
                        publicRecent = try {
                            active.recentlyAdded(libraries, onArtworkReady = { update ->
                                withContext(Dispatchers.Main.immediate) {
                                    if (canPublishHome()) {
                                        publicRecent = mergeHomeRecent(update.items, previousRecent, libraries,
                                            update.retainViewIds, previousOwnerMatches) to null
                                        publishRecent()
                                    }
                                }
                            }) to null
                        }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) {
                            if (error is ApiException && error.status == 401) throw error
                            // Already completed shelves belong to this request;
                            // a slow/failed sibling must not erase them.
                            (if (error is ApiException && error.status == 403) emptyList() else publicRecent?.first.orEmpty()) to
                                "최근 추가된 작품을 불러오지 못했습니다."
                        }
                        publicRecentComplete = true
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
                            if (active.spoilerUnlocked) launch privateCatalogLoad@ {
                                val loadedPrivateLibraries = privateCatalog(emptyList()) {
                                    if (priorityPrivate != null) priorityPrivate.await().getOrThrow()
                                    else active.spoilerLibraries()
                                }
                                if (!canPublishHome()) return@privateCatalogLoad
                                mutableState.update { it.copy(spoilerLibrariesLoading = false) }
                                // A late response from a replaced grant cannot populate the picker.
                                if (grantRevision != SpoilerAccessRegistry.changes.value) return@privateCatalogLoad
                                privateLibraries = loadedPrivateLibraries
                                rememberPrivateViews(active, privateLibraries)
                                publishLibraries()
                                if (privateLibraries.isEmpty()) {
                                    privateContinue = HomeContinueLoad.Success(emptyList())
                                    privateRecent = emptyList()
                                    privateRecentComplete = true
                                    publishContinue(); publishRecent()
                                } else loadHomeSections(
                                    loadContinue = {
                                        privateContinue = try { HomeContinueLoad.Success(active.spoilerContinueWatching()) }
                                        catch (cancel: CancellationException) { throw cancel }
                                        catch (error: Exception) { failedHomeContinueLoad(error) }
                                        publishContinue()
                                    },
                                    loadRecent = {
                                        privateCatalog(Unit) {
                                            privateRecent = try {
                                                active.recentlyAdded(privateLibraries, onArtworkReady = { update ->
                                                    withContext(Dispatchers.Main.immediate) {
                                                        if (canPublishHome() && active.spoilerUnlocked &&
                                                            grantRevision == SpoilerAccessRegistry.changes.value) {
                                                            privateRecent = mergeHomeRecent(update.items, previousRecent, privateLibraries,
                                                                update.retainViewIds, previousOwnerMatches &&
                                                                    grantRevision == SpoilerAccessRegistry.changes.value)
                                                            publishRecent()
                                                        }
                                                    }
                                                })
                                            } catch (error: ApiException) {
                                                // An explicit private denial is not a transient
                                                // slow shelf; do not retain its earlier rows.
                                                if (error.status == 403) privateRecent = emptyList()
                                                throw error
                                            }
                                        }
                                        privateRecent = privateRecent.orEmpty()
                                        privateRecentComplete = true
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
                it.copy(homeLoading = false, continueLoading = false, recentLoading = false,
                    spoilerLibrariesLoading = false)
            }
        }
    }

    fun requestSpoilerUnlock() {
        mutableState.update { spoilerUnlockPromptState(it, hasClient = client != null) }
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
                mutableState.update { it.copy(spoilerUnlocked = true, spoilerPromptOpen = false, spoilerBusy = false,
                    spoilerAvailable = status.available, spoilerPinEnabled = status.pinEnabled != false, busy = false,
                    completedEpisodePending = null) }
                scheduleSpoilerExpiry(active)
                loadHome(active, prioritizePrivateCatalog = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (active === client && current == spoilerRevision) {
                    mutableState.update { it.copy(spoilerError = spoilerUnlockFailureMessage(error, active.spoilerUnlocked)) }
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
        cancelDetailDiscoveryJobs()
        spoilerJob?.cancel(); spoilerJob = null; spoilerRevision++
        spoilerExpiryJob?.cancel(); spoilerExpiryJob = null
        requestJob?.cancel(); revision++
        collectionWatch.entries.removeAll { it.value.first.isSpoiler }
        privateHomeScope.reset()
        clearContinueSelection()
        mutableState.update(::withoutSpoilerContent)
    }

    private fun cancelDetailDiscoveryJobs(clear: Boolean = false) {
        relatedWorksRevision++; actorWorksRevision++
        relatedWorksJob?.cancel(); relatedWorksJob = null
        actorWorksJob?.cancel(); actorWorksJob = null
        mutableState.update { state -> state.copy(detailDiscovery = if (clear) emptyMap() else
            state.detailDiscovery.mapValues { it.value.copy(relatedLoading = false, actorLoading = false) }) }
    }

    private fun discoveryCurrent(active: ShyniClient, account: String?, grantRevision: Long, parent: MediaItem): Boolean {
        val snapshot = mutableState.value
        return active === client && active.token == account && snapshot.loggedIn &&
            SpoilerAccessRegistry.changes.value == grantRevision &&
            snapshot.detailStack.lastOrNull()?.item?.let(::detailDiscoveryKey) == detailDiscoveryKey(parent) &&
            (!parent.isSpoiler || (snapshot.spoilerUnlocked && active.spoilerUnlocked))
    }

    /** Essential detail and file-version jobs stay independent from optional discovery. */
    private fun loadDetailDiscovery(active: ShyniClient, detail: MediaDetails,
        reconcile: (AppState) -> AppState = { it }) {
        val parent = detail.item
        val key = detailDiscoveryKey(parent)
        if (active !== client || mutableState.value.detailStack.lastOrNull()?.item?.let(::detailDiscoveryKey) != key) return
        mutableState.update { state ->
            val frameKeys = state.detailStack.map { detailDiscoveryKey(it.item) }.toSet()
            val previous = state.detailDiscovery[key] ?: DetailDiscoveryState(parent)
            val current = if (previous.selectedActor != null && detail.castMembers.none { it.key == previous.selectedActor.key })
                previous.copy(selectedActor = null, actorItems = emptyList(), actorLoading = false, actorLoaded = false, actorError = null)
                else previous
            state.copy(detailDiscovery = state.detailDiscovery.filterKeys { it in frameKeys } +
                (key to current.copy(parent = parent)))
        }
        val previous = mutableState.value.detailDiscovery[key] ?: return
        if (!previous.relatedLoaded && !previous.relatedLoading && previous.relatedError == null) {
            if (detail.castMembers.none { (it.tmdbId ?: 0) > 0 }) {
                mutableState.update { state -> state.copy(detailDiscovery = state.detailDiscovery +
                    (key to (state.detailDiscovery[key] ?: previous).copy(relatedLoaded = true))) }
            } else {
                relatedWorksJob?.cancel()
                val requestRevision = ++relatedWorksRevision
                val account = active.token
                val grant = SpoilerAccessRegistry.changes.value
                val libraries = mutableState.value.libraries
                mutableState.update { state -> state.copy(detailDiscovery = state.detailDiscovery +
                    (key to (state.detailDiscovery[key] ?: previous).copy(relatedLoading = true, relatedError = null))) }
                relatedWorksJob = viewModelScope.launch {
                    fun current() = requestRevision == relatedWorksRevision && discoveryCurrent(active, account, grant, parent)
                    try {
                        val result = withTimeoutOrNull(20_000) { active.relatedWorks(parent, detail.castMembers, libraries) }
                            ?: throw ApiException("관련 작품을 불러오지 못했습니다.")
                        if (!current()) return@launch
                        mutableState.update { state ->
                            val frame = state.detailDiscovery[key] ?: return@update state
                            val rows = visibleDetailWorks(retainKnownDetailWatchState(result, state), state.libraries,
                                state.spoilerUnlocked && active.spoilerUnlocked).map(::collectionWatchState)
                            reconcile(state.copy(detailDiscovery = state.detailDiscovery +
                                (key to frame.copy(relatedItems = rows, relatedLoading = false, relatedLoaded = true, relatedError = null))))
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        if (current()) {
                            if (error is ApiException && error.status == 401) launchRequest { throw error }
                            else mutableState.update { state ->
                                val frame = state.detailDiscovery[key] ?: return@update state
                                state.copy(detailDiscovery = state.detailDiscovery + (key to frame.copy(relatedItems = emptyList(),
                                    relatedError = "관련 작품을 불러오지 못했습니다.", relatedLoading = false)))
                            }
                        }
                    } finally {
                        if (requestRevision == relatedWorksRevision) {
                            relatedWorksJob = null
                            mutableState.update { state ->
                                val frame = state.detailDiscovery[key] ?: return@update state
                                state.copy(detailDiscovery = state.detailDiscovery + (key to frame.copy(relatedLoading = false)))
                            }
                        }
                    }
                }
            }
        }
        val selected = mutableState.value.detailDiscovery[key]?.takeIf {
            !it.actorLoaded && !it.actorLoading && it.actorError == null
        }?.selectedActor
        if (selected != null) selectDetailActor(selected, reconcile)
    }

    fun retryRelatedWorks() {
        val active = client ?: return
        val detail = mutableState.value.detailStack.lastOrNull() ?: return
        val key = detailDiscoveryKey(detail.item)
        mutableState.update { state ->
            val frame = state.detailDiscovery[key] ?: return@update state
            state.copy(detailDiscovery = state.detailDiscovery + (key to frame.copy(relatedError = null, relatedLoaded = false)))
        }
        loadDetailDiscovery(active, detail)
    }

    fun selectDetailActor(actor: CastMember) {
        val snapshot = mutableState.value
        if (snapshot.busy && !snapshot.homeLoading) return
        selectDetailActor(actor) { it }
    }

    private fun selectDetailActor(actor: CastMember, reconcile: (AppState) -> AppState) {
        val active = client ?: return
        val snapshot = mutableState.value
        val detail = snapshot.detailStack.lastOrNull() ?: return
        val selected = detail.castMembers.firstOrNull { it.key == actor.key && it.canLoadWorks } ?: return
        val parent = detail.item
        if (!canDiscoverActorWorks(selected, parent, snapshot.libraries)) return
        if (parent.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        val key = detailDiscoveryKey(parent)
        val frame = snapshot.detailDiscovery[key] ?: DetailDiscoveryState(parent)
        if (frame.selectedActor?.key == selected.key && (frame.actorLoading || frame.actorLoaded)) return
        actorWorksJob?.cancel()
        val requestRevision = ++actorWorksRevision
        val account = active.token
        val grant = SpoilerAccessRegistry.changes.value
        mutableState.update { state -> state.copy(detailDiscovery = state.detailDiscovery + (key to
            (state.detailDiscovery[key] ?: frame).copy(selectedActor = selected, actorItems = emptyList(),
                actorLoading = true, actorLoaded = false, actorError = null, currentLibraryOnly = false))) }
        actorWorksJob = viewModelScope.launch {
            fun current() = requestRevision == actorWorksRevision && discoveryCurrent(active, account, grant, parent) &&
                mutableState.value.detailDiscovery[key]?.selectedActor?.key == selected.key
            try {
                val result = withTimeoutOrNull(20_000) { active.actorWorks(selected, parent, snapshot.libraries) }
                    ?: throw ApiException("배우의 작품을 불러오지 못했습니다.")
                if (!current()) return@launch
                mutableState.update { state ->
                    val latest = state.detailDiscovery[key] ?: return@update state
                    val rows = visibleDetailWorks(retainKnownDetailWatchState(result.items, state), state.libraries,
                        state.spoilerUnlocked && active.spoilerUnlocked).map(::collectionWatchState)
                    reconcile(state.copy(detailDiscovery = state.detailDiscovery + (key to latest.copy(actorItems = rows,
                        actorLoading = false, actorLoaded = true, actorError = null, currentLibraryOnly = result.currentLibraryOnly))))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (current()) {
                    if (error is ApiException && error.status == 401) launchRequest { throw error }
                    else mutableState.update { state ->
                        val latest = state.detailDiscovery[key] ?: return@update state
                        state.copy(detailDiscovery = state.detailDiscovery + (key to latest.copy(actorItems = emptyList(),
                            actorLoading = false, actorError = "배우의 작품을 불러오지 못했습니다.")))
                    }
                }
            } finally {
                if (requestRevision == actorWorksRevision) {
                    actorWorksJob = null
                    mutableState.update { state ->
                        val latest = state.detailDiscovery[key] ?: return@update state
                        state.copy(detailDiscovery = state.detailDiscovery + (key to latest.copy(actorLoading = false)))
                    }
                }
            }
        }
    }

    /** Hover/focus enriches the hero independently of navigation and playback requests. */
    fun selectContinueItem(item: MediaItem) {
        val active = client ?: return
        val snapshot = mutableState.value
        if (!snapshot.loggedIn || !item.playable) return
        if (item.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        val key = continueIdentity(item)
        val source = snapshot.continueItems.firstOrNull { continueIdentity(it) == key } ?: return
        if (selectedContinueIdentity == key &&
            (continueDetailsJob?.isActive == true || key in continueDetailsCache)) {
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
                // A quick swipe/focus change should not send metadata work to a busy server.
                delay(180)
                if (active !== client || current != continueDetailsRevision || selectedContinueIdentity != key) return@launch
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

    /** Publish poster rows before optional history, only into the navigation that requested them. */
    private fun libraryPagePublisher(active: ShyniClient, owner: AppState,
        prefix: List<MediaItem> = emptyList(), reconcile: (AppState) -> AppState = { it }): suspend (MediaPage) -> Unit {
        val navigation = revision
        val account = active.token
        val accessRevision = SpoilerAccessRegistry.changes.value
        return { page ->
            withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                val current = mutableState.value
                val library = owner.library
                if (active !== client || active.token != account || navigation != revision || !current.loggedIn ||
                    library == null || current.library != library || current.query != owner.query ||
                    current.librarySort != owner.librarySort || current.libraryContentType != owner.libraryContentType ||
                    current.libraryBrowseRevision != owner.libraryBrowseRevision || current.searchOpen ||
                    current.detailStack.isNotEmpty() ||
                    ((library.isSpoiler || page.items.any { it.isSpoiler }) &&
                        (accessRevision != SpoilerAccessRegistry.changes.value ||
                            !current.spoilerUnlocked || !active.spoilerUnlocked))) {
                    throw CancellationException("목록 요청이 종료되었습니다.")
                }
                // Use the original prefix for both stages. Appending the final page to its
                // preliminary copy would keep unknown badges and incorrectly end pagination.
                val displayed = if (prefix.isEmpty()) page else appendLibraryPage(prefix, page)
                mutableState.update { reconcile(it.copy(items = displayed.items.map(::collectionWatchState), total = displayed.total)) }
            }
        }
    }

    fun selectLibrary(library: Library) {
        val active = client ?: return
        if (library.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        cancelDetailDiscoveryJobs(clear = true)
        mutableState.update { it.copy(library = library, items = emptyList(), total = 0,
            searchOpen = false, searchQuery = "", searchItems = emptyList(), searchFailureCount = 0,
            query = "", librarySort = LibrarySort.TITLE_ASC,
            libraryContentType = LibraryContentType.defaultFor(library),
            libraryBrowseRevision = it.libraryBrowseRevision + 1,
            detailStack = emptyList(), versions = emptyList(), selectedPartId = null) }
        launchRequest {
            val publish = libraryPagePublisher(active, mutableState.value)
            val page = active.items(library, onCatalogReady = publish)
            publish(page)
        }
    }

    fun loadMore() {
        val snapshot = mutableState.value
        val active = client ?: return
        val library = snapshot.library ?: return
        if (snapshot.busy || snapshot.detailStack.isNotEmpty() || snapshot.items.size >= snapshot.total) return
        launchRequest {
            val publish = libraryPagePublisher(active, snapshot, prefix = snapshot.items)
            val page = active.items(library, offset = snapshot.items.size, query = snapshot.query, sort = snapshot.librarySort,
                contentType = snapshot.libraryContentType, onCatalogReady = publish)
            publish(page)
        }
    }

    fun search(query: String) {
        val active = client ?: return
        val snapshot = mutableState.value
        val library = snapshot.library ?: return
        mutableState.update { it.copy(query = query, items = emptyList(), total = 0,
            libraryBrowseRevision = it.libraryBrowseRevision + 1) }
        launchRequest {
            val publish = libraryPagePublisher(active, mutableState.value)
            val page = active.items(library, query = query, sort = snapshot.librarySort,
                contentType = snapshot.libraryContentType, onCatalogReady = publish)
            publish(page)
        }
    }

    fun setLibraryOptions(sort: LibrarySort = mutableState.value.librarySort,
        contentType: LibraryContentType = mutableState.value.libraryContentType) {
        val active = client ?: return
        val snapshot = mutableState.value
        val library = snapshot.library ?: return
        if (contentType !in LibraryContentType.availableFor(library)) return
        val selectedSort = if (contentType != snapshot.libraryContentType) LibrarySort.defaultFor(contentType)
            else sort.forContentType(contentType)
        mutableState.update { it.copy(librarySort = selectedSort, libraryContentType = contentType, items = emptyList(), total = 0,
            libraryBrowseRevision = it.libraryBrowseRevision + 1) }
        launchRequest {
            val publish = libraryPagePublisher(active, mutableState.value)
            val page = active.items(library, query = snapshot.query, sort = selectedSort, contentType = contentType,
                onCatalogReady = publish)
            publish(page)
        }
    }

    fun openItem(item: MediaItem) {
        val active = client ?: return
        if (item.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        cancelDetailDiscoveryJobs()
        mutableState.update { it.copy(detailStack = it.detailStack + MediaDetails(item),
            versions = emptyList(), selectedPartId = null) }
        launchRequest {
            val detail = collectionWatchState(episodeListDetails(active, active.details(item)))
            // File-version labels are supplemental; the selected title/episodes are already usable.
            mutableState.update { it.copy(detailStack = it.detailStack.dropLast(1) + detail, busy = false) }
            loadDetailDiscovery(active, detail)
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
        val watchScope = PlaybackCompletionSignals.scope(active.server, active.token)
        PlaybackCompletionSignals.invalidateForManualChoice(watchScope, item)
        cancelDetailDiscoveryJobs()
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
                        }, detailDiscovery = state.detailDiscovery.mapValues { (_, frame) -> frame.copy(
                            parent = uncertain(frame.parent), relatedItems = frame.relatedItems.map(::uncertain),
                            actorItems = frame.actorItems.map(::uncertain)) }) }
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
        cancelDetailDiscoveryJobs()
        continueDetailsJob?.cancel()
        continueDetailsJob = null
        continueDetailsRevision++
        launchRequest {
            val resolved = active.playback(item, partId, deferSubtitles = true)
            val playback = resolved.copy(sourceItem = resolved.sourceItem ?: item, navigationQuery = navigationQuery)
            playbackOwner = HomeContinueOwner(active, active.token)
            playbackGrantRevision = SpoilerAccessRegistry.changes.value
            mutableState.update { it.copy(playback = if (startOver) playback.copy(resumeMs = 0) else playback) }
        }
    }

    fun consumePlayback() { mutableState.update { it.copy(playback = null) } }

    /** Natural episode completion returns to its verified series rather than its episode detail. */
    fun showCompletedEpisodeList(episode: MediaItem) {
        val active = client ?: return
        if (playbackOwner?.matches(active, active.token) != true || !mutableState.value.loggedIn) return
        if (episode.isSpoiler && !active.spoilerUnlocked) { clearSpoilerUi(); return }
        mutableState.update { it.copy(completedEpisodePending = episode, error = null) }
        val reuse = mayReuseCompletedSeries(episode, playbackGrantRevision, SpoilerAccessRegistry.changes.value)
        if (!reuse) {
            // The player can rotate a private grant while it is open. Discard old
            // private screens and revalidate the series under the current grant.
            mutableState.update { state ->
                val privateLibrary = state.library?.isSpoiler == true
                state.copy(detailStack = state.detailStack.filterNot { it.item.isSpoiler },
                    library = state.library?.takeUnless { it.isSpoiler },
                    items = if (privateLibrary) emptyList() else state.items,
                    total = if (privateLibrary) 0 else state.total)
            }
        }
        val stack = mutableState.value.detailStack
        val index = if (reuse) completedEpisodeSeriesIndex(stack, episode) else null
        if (index != null) {
            val series = stack[index]
            val season = episode.seasonNumber?.let { number -> series.seasons.firstOrNull { it.seasonNumber == number } }
            val restored = if (season != null && season.id != series.selectedSeasonId)
                series.copy(children = emptyList(), selectedSeasonId = season.id) else series
            cancelDetailDiscoveryJobs()
            requestJob?.cancel(); revision++
            mutableState.update { it.copy(detailStack = stack.take(index) + restored,
                versions = emptyList(), selectedPartId = null, busy = false, error = null, completedEpisodePending = null) }
            refresh()
            return
        }
        if (episode.type != "episode" || episode.id <= 0 || episode.libraryId <= 0 ||
            episode.viewItemId?.let { it > 0 } != true) {
            requestJob?.cancel(); revision++
            mutableState.update { it.copy(busy = false, homeLoading = false,
                error = "완료한 회차의 시리즈 정보를 확인할 수 없습니다.") }
            return
        }
        val accountToken = active.token
        val grant = SpoilerAccessRegistry.changes.value
        launchRequest(keepCompletedEpisodePending = true) {
            val navigationRevision = revision
            val restored = withTimeoutOrNull(20_000) {
                val series = episode.showId?.takeIf { it > 0 }?.let { showId ->
                    MediaItem(showId, episode.libraryId, episode.showTitle?.takeIf { it.isNotBlank() } ?: "시리즈",
                        "show", viewItemId = episode.viewItemId, isSpoiler = episode.isSpoiler)
                } ?: active.episodeSeries(episode) ?: throw ApiException("완료한 회차의 시리즈 정보를 확인할 수 없습니다.")
                val detail = active.details(series)
                findCompletedEpisodeSeries(detail, episode.copy(showId = series.id)) { season ->
                    active.details(season).children
                } ?: throw ApiException("완료한 회차의 시리즈 목록을 확인할 수 없습니다.")
            } ?: throw ApiException("회차 목록을 불러오는 시간이 초과됐습니다. 다시 열어 주세요.")
            currentCoroutineContext().ensureActive()
            if (active !== client || active.token != accountToken || navigationRevision != revision || !mutableState.value.loggedIn ||
                (episode.isSpoiler && (!active.spoilerUnlocked || grant != SpoilerAccessRegistry.changes.value))) {
                if (navigationRevision == revision) mutableState.update { it.copy(completedEpisodePending = null) }
                return@launchRequest
            }
            mutableState.update { state ->
                val last = state.detailStack.lastOrNull()?.item
                val base = if (last?.type == "episode" && last.libraryId == episode.libraryId &&
                    last.viewItemId == episode.viewItemId && last.showId == episode.showId &&
                    last.isSpoiler == episode.isSpoiler) state.detailStack.dropLast(1) else state.detailStack
                applyConfirmedPlaybackWatch(state.copy(detailStack = base + collectionWatchState(restored),
                    versions = emptyList(), selectedPartId = null, busy = false, error = null, completedEpisodePending = null))
            }
            loadDetailDiscovery(active, restored)
        }
    }

    fun retryCompletedEpisodeList() {
        val episode = mutableState.value.completedEpisodePending ?: return
        if (!mutableState.value.busy) showCompletedEpisodeList(episode)
    }

    fun cancelCompletedEpisodeList() {
        if (mutableState.value.completedEpisodePending == null) return
        requestJob?.cancel(); revision++
        mutableState.update { it.copy(completedEpisodePending = null, busy = false, error = null) }
        refresh()
    }

    fun openSearch() {
        cancelDetailDiscoveryJobs(clear = true)
        requestJob?.cancel(); revision++
        clearContinueSelection(clearCache = false)
        mutableState.update { it.copy(searchOpen = true, library = null, items = emptyList(), total = 0, query = "",
            detailStack = emptyList(), versions = emptyList(), selectedPartId = null, busy = false, error = null,
            homeLoading = false, completedEpisodePending = null) }
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
        cancelDetailDiscoveryJobs(clear = true)
        requestJob?.cancel(); revision++
        mutableState.update { it.copy(detailStack = emptyList(), library = null, items = emptyList(),
            total = 0, query = "", searchOpen = false, searchQuery = "", searchItems = emptyList(), searchFailureCount = 0,
            busy = false, error = null, versions = emptyList(), selectedPartId = null, completedEpisodePending = null) }
    }

    fun back() {
        if (mutableState.value.completedEpisodePending != null) { cancelCompletedEpisodeList(); return }
        cancelDetailDiscoveryJobs()
        requestJob?.cancel(); revision++
        mutableState.update {
            if (it.detailStack.isNotEmpty()) it.copy(detailStack = it.detailStack.dropLast(1),
                busy = false, versions = emptyList(), selectedPartId = null, error = null)
            else it.copy(library = null, items = emptyList(), total = 0, query = "",
                searchOpen = false, searchQuery = "", searchItems = emptyList(), searchFailureCount = 0, busy = false, error = null)
        }
        val detail = mutableState.value.detailStack.lastOrNull()
        if (detail == null) cancelDetailDiscoveryJobs(clear = true)
        else client?.let { loadDetailDiscovery(it, detail) }
    }

    suspend fun seriesUnwatchedCount(item: MediaItem): Int? {
        val active = client ?: return null
        val epoch = mutableState.value.seriesCountRevision
        if (item.isSpoiler && !mutableState.value.spoilerUnlocked) return null
        val result = withTimeoutOrNull(30_000) { active.unwatchedEpisodeCount(item) }
        return result.takeIf { active === client && epoch == mutableState.value.seriesCountRevision &&
            mutableState.value.loggedIn && (!item.isSpoiler || (mutableState.value.spoilerUnlocked && active.spoilerUnlocked)) }
    }

    fun refresh() {
        if (mutableState.value.completedEpisodePending != null) return
        val active = client ?: return
        val navigationRevision = revision
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // Activity results and committed playback updates can describe the same change.
            // Coalesce that burst without letting it refresh a later navigation/session.
            delay(250)
            if (active !== client || navigationRevision != revision || mutableState.value.completedEpisodePending != null) return@launch
            refreshJob = null
            refreshCurrent(reconcile = ::applyConfirmedPlaybackWatch)
        }
    }

    private fun refreshCurrent(reconcile: (AppState) -> AppState = { it },
        allowHomeInteraction: Boolean = true) {
        val active = client ?: return
        cancelDetailDiscoveryJobs()
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
                    loadDetailDiscovery(active, detail, reconcile)
                    if (item.playable) {
                        val versions = active.versions(item)
                        mutableState.update { it.copy(versions = versions) }
                    }
                } else if (snapshot.searchOpen) loadSearch(active, snapshot.searchQuery, reconcile)
                else snapshot.library?.let { library ->
                    val publish = libraryPagePublisher(active, snapshot, reconcile = reconcile)
                    val page = active.items(library, query = snapshot.query, sort = snapshot.librarySort,
                        contentType = snapshot.libraryContentType, onCatalogReady = publish)
                    publish(page)
                }
            }
        }
    }

    fun logout() {
        cancelTvQrLogin()
        dismissTvLoginApproval()
        cancelDetailDiscoveryJobs(clear = true)
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
