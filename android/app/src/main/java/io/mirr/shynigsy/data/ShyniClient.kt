package io.mirr.shynigsy.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Keep an unexpected successful response separate from an authoritative empty history. */
class ContinueWatchingResponseException : IOException("이어보기 응답 형식이 올바르지 않습니다. 잠시 후 새로고침해 주세요.")

/** Shyni beta4 public Library View API. Section IDs never substitute for View IDs. */
class ShyniClient(
    val server: String,
    token: String? = null,
    httpClient: OkHttpClient = OkHttpClient(),
    val deviceId: String = UUID.randomUUID().toString(),
) : TvPairingApi {
    private val origin = server.trim().trimEnd('/').toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty()) { "서버 주소에 계정 정보를 넣을 수 없습니다." }
        require(it.encodedPath == "/" && it.query == null && it.fragment == null) {
            "서버 주소에는 호스트와 포트만 입력하세요."
        }
    }
    // Disallow redirects entirely for authenticated API requests. A server-controlled
    // Location must never move login bodies or the account token to another origin.
    private val client = httpClient.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private val requestLanes = ShyniRequestLanes(client)
    private data class CachedItems(val at: Long, val items: List<MediaItem>, val total: Int)
    private val browseCache = ConcurrentHashMap<String, CachedItems>()
    private val browseCacheGeneration = java.util.concurrent.atomic.AtomicLong()
    @Synchronized private fun clearBrowseCache() {
        browseCacheGeneration.incrementAndGet()
        browseCache.clear()
    }
    @Synchronized private fun cacheBrowsePage(key: String, generation: Long, page: CachedItems): CachedItems {
        if (generation != browseCacheGeneration.get()) throw CancellationException("목록 요청이 종료되었습니다.")
        browseCache[key] = page
        return page
    }
    private data class CachedMediaInfo(val at: Long, val data: JSONObject)
    private val mediaInfoCache = ConcurrentHashMap<String, CachedMediaInfo>()
    private val seriesCountSlots = Semaphore(2)
    private val seriesCountGeneration = java.util.concurrent.atomic.AtomicLong()
    private val seriesCounts = ConcurrentHashMap<String, Pair<Long, Int>>()
    fun invalidateSeriesCounts() { seriesCountGeneration.incrementAndGet(); seriesCounts.clear() }
    @Volatile var token: String? = token
        set(value) { if (field != value) clearSpoilerGrant(); field = value }

    @Volatile private var spoilerIdentityCache: Pair<String?, String>? = null

    private fun spoilerKey(): String {
        val currentToken = token
        spoilerIdentityCache?.takeIf { it.first == currentToken }?.let { return it.second }
        val identity = java.security.MessageDigest.getInstance("SHA-256")
            .digest((origin.toString() + "\u0000" + currentToken.orEmpty() + "\u0000" + deviceId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        spoilerIdentityCache = currentToken to identity
        return identity
    }

    private fun spoilerGrant(): SpoilerAccessRegistry.Grant? =
        if (token.isNullOrBlank()) null else SpoilerAccessRegistry.get(spoilerKey())

    val spoilerUnlocked: Boolean get() = spoilerGrant() != null
    val spoilerExpiresAtMs: Long get() = spoilerGrant()?.expiresAtMs ?: 0
    val spoilerRemainingMs: Long get() = if (token.isNullOrBlank()) 0 else SpoilerAccessRegistry.remainingMs(spoilerKey())
    val spoilerLifetimeMs: Long get() = spoilerGrant()?.lifetimeMs ?: 0
    val spoilerPlaybackReauthenticationFailed: Boolean get() =
        !token.isNullOrBlank() && SpoilerAccessRegistry.failedReauthentication(spoilerKey())

    fun clearSpoilerGrant() { SpoilerAccessRegistry.remove(spoilerKey()); clearBrowseCache(); mediaInfoCache.clear(); invalidateSeriesCounts() }

    fun absoluteUrl(path: String): String = resolve(path).toString()

    /** Useful for authenticated posters. Never attach account credentials to external images. */
    fun headersFor(url: String): Map<String, String> = runCatching {
        val target = resolve(url)
        if (sameOrigin(target) && !token.isNullOrBlank()) sessionHeaders(token!!) +
            (spoilerGrant()?.let { mapOf("X-Shyni-Spoiler" to it.token, "X-Device-Id" to deviceId) } ?: emptyMap())
        else emptyMap()
    }.getOrDefault(emptyMap())

    /** Private media receives the live capability only at the player boundary, never in an Intent. */
    fun playbackHeaders(spec: PlaybackSpec): Map<String, String> {
        if (spec.sourceItem?.isSpoiler != true) return spec.headers
        if (spoilerGrant() == null) throw ApiException("스포일러 표시가 잠겼습니다. PIN으로 다시 열어 주세요.", 403)
        val target = runCatching { resolve(spec.url) }.getOrNull()
        if (target == null || !sameOrigin(target)) throw ApiException("스포일러 영상은 현재 서버를 통해 재생해야 합니다.")
        return spec.headers + headersFor(spec.url)
    }

    suspend fun spoilerStatus(): SpoilerStatus = parseSpoilerStatus(
        request("/spoiler/client/status", spoiler = spoilerUnlocked).objectValue())

    suspend fun unlockSpoiler(pin: String): SpoilerStatus = unlockSpoiler(pin, 20_000L)

    /** The manual PIN deadline includes dispatcher queueing; renewal has its own player budget. */
    internal suspend fun unlockSpoiler(pin: String, timeoutMs: Long): SpoilerStatus {
        require(timeoutMs > 0)
        val value = pin.trim()
        if (value.isEmpty() || value.length > 16 || value.any { it == '\r' || it == '\n' }) {
            throw ApiException("스포일러 PIN을 입력해 주세요.")
        }
        val identity = spoilerKey()
        val unlock = SpoilerAccessRegistry.beginUnlock(identity)
        val started = SpoilerAccessRegistry.started()
        val pinChars = value.toCharArray()
        try {
            val json = try {
                withTimeout(timeoutMs) {
                    request("/spoiler/client/unlock", JSONObject().put("pin", value),
                        responseLimit = 32_768, singleAttempt = true).objectValue()
                }
            } catch (_: TimeoutCancellationException) {
                // A cancelled screen, account change or outer timeout must remain cancellation.
                currentCoroutineContext().ensureActive()
                throw ApiException("서버 응답이 지연됩니다. 잠시 후 PIN 잠금 해제를 다시 시도해 주세요.", 408)
            }
            currentCoroutineContext().ensureActive()
            val (grant, seconds) = spoilerGrantResponse(json)
            if (identity != spoilerKey() || !SpoilerAccessRegistry.completeUnlock(identity, unlock,
                    grant, seconds, pinChars, started)) {
                throw ApiException("스포일러 표시 요청이 종료되었습니다. PIN을 다시 입력해 주세요.", 403)
            }
            clearBrowseCache()
            return parseSpoilerStatus(json).copy(available = true, unlocked = true)
        } finally {
            pinChars.fill('\u0000')
        }
    }

    private fun spoilerGrantResponse(json: JSONObject): Pair<String, Long> {
        val grant = json.stringOrNull("token")?.takeIf {
            it.startsWith("spc_") && it.length <= 256 && !it.contains('\n') && !it.contains('\r')
        } ?: throw ApiException("서버의 스포일러 표시 권한을 받지 못했습니다.")
        val seconds = runCatching {
            val value = json.opt("expires_in")
            if (value !is Number) return@runCatching null
            java.math.BigDecimal(value.toString()).longValueExact().takeIf { it > 0 }
        }.getOrNull() ?: throw ApiException("스포일러 표시 권한의 만료 정보를 받지 못했습니다.")
        return grant to seconds
    }

    /** Reauthenticate once, then obtain fresh contexts/tickets for this exact private source. */
    suspend fun reauthenticateSpoilerPlayback(
        current: PlaybackSpec,
        positionMs: Long,
        audioStreamIndex: Int? = current.selectedAudioStreamIndex,
    ): PlaybackSpec {
        val source = current.sourceItem
        val stream = runCatching { current.url.toHttpUrl() }.getOrNull()
        if (source == null || !source.isSpoiler || !source.playable || source.id != current.itemId ||
            source.libraryId != current.libraryId || source.viewItemId != current.viewItemId ||
            current.partId <= 0 || current.libraryId <= 0 || current.viewItemId?.let { it > 0 } != true ||
            positionMs < 0 || (audioStreamIndex != null && audioStreamIndex < 0) ||
            stream == null || !sameOrigin(stream)) {
            throw ApiException("현재 스포일러 재생 정보를 확인할 수 없습니다.")
        }
        val identity = spoilerKey()
        val accountToken = token
        val attempt = SpoilerAccessRegistry.beginReauthentication(identity)
            ?: throw ApiException("스포일러 PIN을 다시 입력해 주세요.", 403)
        var ownedGrant = attempt.grant
        fun requireCurrent() {
            if (identity != spoilerKey() || accountToken != token ||
                !SpoilerAccessRegistry.isCurrent(identity, ownedGrant)) {
                throw ApiException("스포일러 표시 요청이 종료되었습니다. PIN을 다시 입력해 주세요.", 403)
            }
        }
        fun requireSameSource(value: PlaybackSpec) {
            val item = value.sourceItem
            if (value.partId != current.partId || value.itemId != current.itemId ||
                value.libraryId != current.libraryId || value.viewItemId != current.viewItemId ||
                item == null || !item.isSpoiler || item.id != source.id || item.type != source.type ||
                item.libraryId != source.libraryId || item.viewItemId != source.viewItemId) {
                throw ApiException("스포일러 재생 파일이나 라이브러리 항목이 변경되었습니다.", 403)
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            requireCurrent()
            val started = SpoilerAccessRegistry.started()
            // No automatic transport retry: an uncertain PIN result requires explicit user action.
            val body = JSONObject().put("pin", String(attempt.pin))
            val json = try { request("/spoiler/client/unlock", body, singleAttempt = true).objectValue() }
                finally { body.remove("pin") }
            currentCoroutineContext().ensureActive()
            requireCurrent()
            val (grant, seconds) = spoilerGrantResponse(json)
            ownedGrant = SpoilerAccessRegistry.completeReauthentication(identity, attempt, grant, seconds, started)
                ?: throw ApiException("스포일러 표시 요청이 종료되었습니다. PIN을 다시 입력해 주세요.", 403)
            clearBrowseCache()
            val (fresh, originalTicket) = reauthenticatedPlaybackSource(current, source, audioStreamIndex, ::requireCurrent)
            currentCoroutineContext().ensureActive()
            requireCurrent(); requireSameSource(fresh)
            // Negotiate only the final delivery: no discarded initial ticket,
            // subtitle list or historical progress lookup delays this handoff.
            val result = renewPlayback(fresh, positionMs, audioStreamIndex, originalTicket, preserveSessionId = true)
            currentCoroutineContext().ensureActive()
            requireCurrent(); requireSameSource(result)
            if (!SpoilerAccessRegistry.cacheReauthenticatedPin(identity, ownedGrant, attempt)) {
                throw ApiException("스포일러 표시 요청이 종료되었습니다. PIN을 다시 입력해 주세요.", 403)
            }
            return result
        } catch (error: Exception) {
            if (identity == spoilerKey() && accountToken == token &&
                SpoilerAccessRegistry.failReauthentication(identity, ownedGrant)) {
                clearBrowseCache(); mediaInfoCache.clear(); invalidateSeriesCounts()
            }
            throw error
        } finally {
            SpoilerAccessRegistry.finishReauthentication(identity, attempt)
        }
    }

    /** Authorization only; the caller obtains exactly one final set of transport resources. */
    private suspend fun reauthenticatedPlaybackSource(
        current: PlaybackSpec, item: MediaItem, audioStreamIndex: Int?, requireAuthorization: () -> Unit,
    ): Pair<PlaybackSpec, JSONObject?> {
        val url = current.url.toHttpUrl()
        val progressive = url.encodedPath == "/transcode/${current.partId}"
        val mode = if (url.encodedPath == "/transcode/music/${current.partId}") "music"
            else if (progressive || current.conversionHeight != null)
                (url.queryParameter("mode") ?: if (current.conversionHeight != null) "video" else "audio") else null
        if (mode == "video" && isEac3Codec(current.selectedAudioCodec)) {
            val height = current.conversionHeight
                ?: url.queryParameter("max_height")?.toIntOrNull()?.takeIf { it > 0 } ?: 1080
            // This exact-source signed AAC decision also authorizes the fresh
            // context, so a separate generic decision would only be discarded.
            return aacVideoConversionContext(current.copy(aacConversionContext = false), height,
                audioStreamIndex, requireAuthorization) to null
        }
        val sessionId = UUID.randomUUID().toString()
        currentCoroutineContext().ensureActive(); requireAuthorization()
        val decision = try {
            request("/playback/decision", decisionBody(item, current.partId).put("client_session_id", sessionId),
                spoiler = true).objectValue()
        } catch (error: ApiException) {
            // The already-authorized exact episode file retains the same narrow
            // legacy recovery as ordinary playback; permission errors never fall through.
            if (item.type != "episode" || !error.isEpisodeIdentityFailure()) throw error
            null
        }
        currentCoroutineContext().ensureActive(); requireAuthorization()
        val original = if (decision == null) {
            request("/stream-ticket/${current.partId}?library_view_id=${current.libraryId}&view_item_id=${current.viewItemId}",
                JSONObject(), spoiler = true).objectValue().also {
                currentCoroutineContext().ensureActive(); requireAuthorization()
                if (it.exactPositiveInt("library_view_id") != current.libraryId ||
                    it.exactPositiveInt("view_item_id") != current.viewItemId ||
                    (it.has("part_id") && it.exactPositiveInt("part_id") != current.partId) ||
                    it.stringOrNull("ticket") == null) {
                    throw ApiException("파일 재생 권한의 라이브러리 항목 정보가 일치하지 않습니다.", 403)
                }
            }
        } else null
        val selected = decision?.optJSONObject("selected")
        if (decision != null && (selected?.exactPositiveInt("part_id") != current.partId ||
                decision.exactPositiveInt("library_view_id") != current.libraryId ||
                decision.exactPositiveInt("view_item_id") != current.viewItemId)) {
            throw ApiException("스포일러 재생 파일이나 라이브러리 항목이 변경되었습니다.", 403)
        }
        val context = decision?.stringOrNull("playback_context_token")
        if (decision != null && (context == null || context == current.progressToken)) {
            throw ApiException("새 스포일러 재생 권한을 확인하지 못했습니다.", 403)
        }
        val duration = current.durationMs.takeIf { it > 0 }
            ?: selected?.optLong("duration_ms")?.takeIf { it > 0 }
            ?: selected?.optDouble("duration_sec", 0.0)?.times(1000)?.toLong()?.takeIf { it > 0 }
            ?: item.durationMs
        return current.copy(sessionId = sessionId, progressToken = context, durationMs = duration,
            headers = emptyMap(), selectedAudioCodec = current.selectedAudioCodec ?: selected?.stringOrNull("audio_codec"),
            selectedAudioLanguage = current.selectedAudioLanguage ?: decision?.stringOrNull("selected_audio_language"),
            // Old AAC proof is bound to the revoked grant. playbackTransport must negotiate anew.
            aacConversionContext = false,
            sourceItem = item.copy(durationMs = duration)) to original
    }

    suspend fun lockSpoiler() {
        val grant = spoilerGrant()?.token
        clearSpoilerGrant()
        if (grant != null) request("/spoiler/client/lock", JSONObject(), spoiler = true, spoilerToken = grant)
    }

    private fun parseSpoilerStatus(json: JSONObject): SpoilerStatus = SpoilerStatus(
        // A successful native endpoint proves availability. Missing PIN metadata is
        // unknown; the server still authorizes every actual unlock attempt.
        available = json.booleanOrNull("available") ?: true, pinEnabled = json.booleanOrNull("pin_enabled"),
        unlocked = spoilerUnlocked && (json.booleanOrNull("unlocked") ?: true),
        expiresInSeconds = json.optLong("expires_in").coerceAtLeast(0),
    )

    suspend fun spoilerLibraries(): List<Library> = withContext(Dispatchers.Default) {
        parseLibraries(request("/spoiler/client/libraries", spoiler = true), true)
    }

    suspend fun spoilerContinueWatching(): List<MediaItem> = withContext(Dispatchers.Default) {
        parseContinueWatching(request("/spoiler/client/continue-watching?limit=30", spoiler = true, expectContinueResponse = true), true)
    }

    private data class PhysicalPart(val partId: Int, val metadataId: Int, val folderKey: String?,
        val fileName: String, val partIndex: Int?)

    private fun physicalParts(info: JSONObject): List<PhysicalPart> {
        val itemId = info.optJSONObject("item")?.intOrNull("id")
        return rows(info, "parts").mapNotNull { part ->
            val partId = part.intOrNull("id") ?: part.intOrNull("part_id") ?: return@mapNotNull null
            val ownId = part.intOrNull("metadata_item_id")
            if (ownId != null && itemId != null && ownId != itemId) return@mapNotNull null
            val metadataId = ownId ?: itemId ?: return@mapNotNull null
            val path = part.stringOrNull("file_path")
            val normalized = path?.replace('\\', '/')?.takeIf {
                !it.contains("://") && !it.contains('\u0000') &&
                    (it.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(it)) &&
                    it.split('/').none { segment -> segment == ".." }
            }?.replace(Regex("/{2,}"), "/")
            val split = normalized?.lastIndexOf('/') ?: -1
            val parent = normalized?.takeIf { split >= 0 && split < it.lastIndex }?.substring(0, split)
                ?.ifEmpty { "/" }?.let { if (Regex("^[A-Za-z]:").containsMatchIn(it)) it.lowercase(java.util.Locale.ROOT) else it }
            val folder = parent?.let { value -> java.security.MessageDigest.getInstance("SHA-256")
                .digest((origin.toString() + "\u0000folder\u0000" + value).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) } }
            PhysicalPart(partId, metadataId, folder, if (split >= 0) normalized!!.substring(split + 1) else "",
                part.indexOrNull("part_index"))
        }
    }

    private suspend fun mediaInfo(key: String, path: String, privateCatalog: Boolean,
        lane: ShyniRequestLane? = null): JSONObject {
        if (privateCatalog && !spoilerUnlocked) throw ApiException("스포일러 표시가 잠겼습니다. PIN으로 다시 열어 주세요.", 403)
        val scoped = "$key:$privateCatalog:${if (privateCatalog) SpoilerAccessRegistry.changes.value else 0}"
        mediaInfoCache[scoped]?.takeIf { System.currentTimeMillis() - it.at < 120_000 }?.let { return it.data }
        val data = request(path, responseLimit = 2L * 1024 * 1024, spoiler = privateCatalog, lane = lane).objectValue()
        if (privateCatalog && !spoilerUnlocked) throw ApiException("스포일러 표시가 잠겼습니다. PIN으로 다시 열어 주세요.", 403)
        if (mediaInfoCache.size >= 64) mediaInfoCache.clear()
        mediaInfoCache[scoped] = CachedMediaInfo(System.currentTimeMillis(), data)
        return data
    }

    private suspend fun partMediaInfo(spec: PlaybackSpec, lane: ShyniRequestLane? = null): JSONObject {
        val context = contextQuery(spec.progressToken, spec.libraryId,
            spec.viewItemId ?: spec.sourceItem?.takeIf { it.libraryId == spec.libraryId }?.viewItemId)
        return mediaInfo("${spec.libraryId}:part:${spec.partId}",
            "/libraries/${spec.libraryId}/media_info/${spec.partId}?$context", spec.sourceItem?.isSpoiler == true, lane)
    }

    /** Icons only target files whose actual immediate filesystem parent matches the current file. */
    suspend fun playbackNeighbors(spec: PlaybackSpec): PlaybackNeighbors {
        val authorization = playbackTransportAuthorization(spec)
        return withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive(); authorization()
        val current = spec.sourceItem?.takeIf { it.playable && it.libraryId == spec.libraryId }
            ?: return@withContext PlaybackNeighbors()
        val libraryId = spec.libraryId
        val privateCatalog = current.isSpoiler
        val currentInfo = partMediaInfo(spec)
        val parts = physicalParts(currentInfo)
        val active = parts.singleOrNull { it.partId == spec.partId } ?: return@withContext PlaybackNeighbors()
        val folder = active.folderKey ?: return@withContext PlaybackNeighbors()
        fun navigationItem(item: MediaItem, file: PhysicalPart): MediaItem = item.copy(id = file.metadataId,
            folderKey = folder, navigationPartId = file.partId, playbackPartId = file.partId, markers = emptyList())
        val sameItemParts = parts.filter { it.folderKey == folder }
            .sortedWith(compareBy<PhysicalPart> { it.partIndex ?: Int.MAX_VALUE }.thenBy { it.fileName }.thenBy { it.partId })
        val partIndex = sameItemParts.indexOfFirst { it.partId == spec.partId }
        val previousPart = sameItemParts.getOrNull(partIndex - 1)?.let { navigationItem(current, it) }
        val nextPart = sameItemParts.getOrNull(partIndex + 1)?.let { navigationItem(current, it) }
        val canFindNextEpisode = current.type == "episode" && current.id == spec.itemId &&
            current.viewItemId != null && current.viewItemId == spec.viewItemId &&
            current.seasonNumber?.let { it >= 0 } == true && current.episodeNumber?.let { it >= 0 } == true
        if (previousPart != null && nextPart != null && !canFindNextEpisode)
            return@withContext PlaybackNeighbors(previousPart, nextPart)
        var episodeEntries = emptyList<MediaItem>()
        var episodeShowBindingClear = false
        val entries = when (current.type) {
            "movie" -> {
                val query = spec.navigationQuery.trim()
                val path = if (query.isEmpty()) "/libraries/$libraryId/movies?sort=title" else
                    "/libraries/$libraryId/search?q=${encode(query)}&limit=200&metadata_type=1"
                rows(request(path, responseLimit = 16L * 1024 * 1024, spoiler = privateCatalog), "items", "results")
                    .asSequence().filter { !it.optBoolean("remote") && it.stringOrNull("federated_id") == null }
                    .take(10_000).map { row ->
                        parseMedia(row, libraryId, "movie").let { parsed ->
                            parsed.copy(isSpoiler = privateCatalog,
                                viewItemId = parsed.viewItemId ?: current.viewItemId.takeIf { parsed.id == current.id })
                        }
                    }.filter { it.type == "movie" && it.viewItemId != null }
                    .distinctBy { it.viewItemId }.toList()
            }
            "episode" -> {
                try {
                    val showId = current.showId?.also { episodeShowBindingClear = it > 0 }
                        ?: current.viewItemId?.let { viewItemId ->
                            val matches = rows(request("/libraries/$libraryId/shows?sort=title",
                                responseLimit = 16L * 1024 * 1024, spoiler = privateCatalog))
                                .take(10_000).filter { it.intOrNull("view_item_id") == viewItemId }
                            episodeShowBindingClear = matches.mapNotNull { it.intOrNull("id") }.distinct().size == 1
                            matches.firstOrNull()?.intOrNull("id")
                        } ?: return@withContext PlaybackNeighbors(previousPart, nextPart)
                    val parsed = rows(request("/libraries/$libraryId/shows/$showId/episodes",
                        responseLimit = 16L * 1024 * 1024, spoiler = privateCatalog), "items", "episodes")
                        .asSequence().filter { !it.optBoolean("remote") && it.stringOrNull("federated_id") == null }
                        .take(10_000).map { row ->
                            parseMedia(row, libraryId, "episode").let { episode ->
                                val sameShow = episodeShowBindingClear && episode.type == "episode" &&
                                    (row.isNull("library_view_id") || row.exactPositiveInt("library_view_id") == libraryId) &&
                                    (episode.showId == null || episode.showId == showId) &&
                                    (episode.viewItemId == null || episode.viewItemId == current.viewItemId) &&
                                    (episode.isSpoiler || privateCatalog) == current.isSpoiler
                                episode.copy(showTitle = episode.showTitle ?: current.showTitle.takeIf { sameShow })
                            }
                        }.toList()
                    // Keep positive privacy and explicit show/View evidence for the
                    // stricter credits shortcut; generic file navigation stays unchanged.
                    episodeEntries = parsed.filter { it.showId == null || it.showId == showId }
                        .map { it.copy(showId = showId, isSpoiler = it.isSpoiler || privateCatalog,
                            viewItemId = it.viewItemId ?: current.viewItemId) }
                        .filter { it.type == "episode" && it.id > 0 && it.viewItemId == current.viewItemId &&
                            it.isSpoiler == privateCatalog }
                    parsed.map { it.copy(showId = showId, isSpoiler = privateCatalog,
                        viewItemId = it.viewItemId ?: current.viewItemId) }
                        .filter { it.type == "episode" && it.viewItemId != null }
                        .sortedWith(compareBy<MediaItem> { it.seasonNumber ?: Int.MAX_VALUE }
                            .thenBy { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.id })
                        .distinctBy { if (it.seasonNumber != null && it.episodeNumber != null)
                            "${it.seasonNumber}:${it.episodeNumber}" else "id:${it.id}" }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ApiException) {
                    if (error.status == 401 || error.status == 403) throw error
                    return@withContext PlaybackNeighbors(previousPart, nextPart)
                } catch (_: Exception) {
                    return@withContext PlaybackNeighbors(previousPart, nextPart)
                }
            }
            else -> return@withContext PlaybackNeighbors()
        }
        val exact = entries.indexOfFirst { it.id == current.id }
        val index = if (exact >= 0) exact else entries.indexOfFirst {
            if (current.type == "movie") current.viewItemId != null && it.viewItemId == current.viewItemId
            else current.seasonNumber != null && current.episodeNumber != null &&
                it.seasonNumber == current.seasonNumber && it.episodeNumber == current.episodeNumber
        }
        if (index < 0) return@withContext PlaybackNeighbors(previousPart, nextPart)
        suspend fun verified(candidates: List<MediaItem>): MediaItem? {
            for (candidate in candidates.take(8)) {
                val info = try {
                    mediaInfo("$libraryId:item:${candidate.id}",
                        "/libraries/$libraryId/media_info/item/${candidate.id}?library_view_id=$libraryId&view_item_id=${candidate.viewItemId}", privateCatalog)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
                val matching = physicalParts(info).filter { it.folderKey == folder && it.partId != spec.partId }
                    .sortedWith(compareBy<PhysicalPart> { it.partIndex ?: Int.MAX_VALUE }.thenBy { it.fileName }.thenBy { it.partId })
                matching.firstOrNull()?.let { return navigationItem(candidate, it) }
            }
            return null
        }
        suspend fun verifiedNextEpisode(): MediaItem? {
            if (!canFindNextEpisode || !episodeShowBindingClear) return null
            val season = current.seasonNumber ?: return null
            val episode = current.episodeNumber ?: return null
            val anchors = episodeEntries.filter { it.id == current.id || it.id == active.metadataId }
            if (anchors.isEmpty() || anchors.any { it.seasonNumber != season || it.episodeNumber != episode }) return null
            val candidates = episodeEntries.filter {
                it.id != current.id && it.id != active.metadataId &&
                    it.seasonNumber?.let { value -> value >= 0 } == true &&
                    it.episodeNumber?.let { value -> value >= 0 } == true &&
                    (it.seasonNumber!! > season || (it.seasonNumber == season && it.episodeNumber!! > episode))
            }.sortedWith(compareBy<MediaItem> { it.seasonNumber!! }.thenBy { it.episodeNumber!! }.thenBy { it.id })
                .distinctBy { it.id }
            for (candidate in candidates.take(8)) {
                val info = try {
                    mediaInfo("$libraryId:item:${candidate.id}",
                        "/libraries/$libraryId/media_info/item/${candidate.id}?library_view_id=$libraryId&view_item_id=${candidate.viewItemId}", privateCatalog)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ApiException) {
                    if (error.status == 401 || error.status == 403) throw error
                    continue
                } catch (_: Exception) { continue }
                if (info.optJSONObject("item")?.intOrNull("id") != candidate.id) continue
                val matching = physicalParts(info).filter { it.metadataId == candidate.id && it.folderKey == folder &&
                    it.partId != spec.partId }
                    .sortedWith(compareBy<PhysicalPart> { it.partIndex ?: Int.MAX_VALUE }.thenBy { it.fileName }.thenBy { it.partId })
                matching.firstOrNull()?.let { return navigationItem(candidate, it) }
            }
            return null
        }
        coroutineScope {
            val previous = async { previousPart ?: verified(entries.take(index).asReversed()) }
            val next = async { nextPart ?: verified(entries.drop(index + 1)) }
            // Reuse completed media-info reads from generic navigation instead of
            // racing a second HTTP request for the same candidate.
            val previousItem = previous.await()
            val nextItem = next.await()
            PlaybackNeighbors(previousItem, nextItem, verifiedNextEpisode())
        }
        }.also { currentCoroutineContext().ensureActive(); authorization() }
    }

    /** Optional, read-only marker refresh for the actual selected physical file. */
    suspend fun playbackMarkers(spec: PlaybackSpec): List<PlaybackMarker> = withContext(Dispatchers.Default) {
        val info = partMediaInfo(spec)
        // An omitted optional field is unknown; an explicit [] clears the old markers.
        val markers = if (info.isNull("markers")) JSONArray(spec.markers.map { it.asJson() }) else info.opt("markers")
        PlaybackMarker.parse(markers, spec.durationMs)
    }

    override suspend fun createTvPairingSession(): TvPairingSession = parseTvPairingSession(
        request("/auth/tv-login/pairing/sessions", JSONObject(), authenticated = false,
            responseLimit = 32_768, singleAttempt = true).objectValue(),
    )

    override suspend fun claimTvPairing(session: TvPairingSession, deviceName: String): TvPairingClaim {
        require(deviceName.isNotBlank() && deviceName.length <= 200 && deviceName.none { it.code < 32 || it.code == 127 }) {
            "기기 이름이 올바르지 않습니다."
        }
        return parseTvPairingClaim(request("/auth/tv-login/pairing/claim", session.claimBody(deviceName),
            authenticated = false, responseLimit = 32_768, singleAttempt = true).objectValue())
    }

    /** The phone's explicit approval mints a one-use ticket, then hands it to the owned TV nonce. */
    suspend fun approveTvPairing(nonce: String) {
        require(tvPairingCredential.matches(nonce)) { "TV 로그인 코드가 올바르지 않습니다." }
        val account = token?.takeIf { it.isNotBlank() } ?: throw ApiException("로그인이 필요합니다.", 401)
        fun stillOwned() {
            if (token != account) throw CancellationException("로그인 정보가 변경되었습니다.")
        }
        val response = request("/auth/tv-login/tickets", JSONObject(), responseLimit = 32_768,
            singleAttempt = true).objectValue()
        currentCoroutineContext().ensureActive(); stillOwned()
        val ticket = (response.opt("ticket") as? String)?.takeIf(tvPairingCredential::matches)
            ?: throw ApiException("서버의 TV 로그인 승인 정보가 올바르지 않습니다.")
        val seconds = (response.opt("expires_in") as? Number)?.let { value ->
            runCatching { java.math.BigDecimal(value.toString()).longValueExact() }.getOrNull()
        }?.takeIf { it in 1..MAX_TV_PAIRING_SECONDS.toLong() }
            ?: throw ApiException("서버의 TV 로그인 승인 시간이 올바르지 않습니다.")
        // The ticket is deliberately local to this operation, never UI state or saved preferences.
        val approved = kotlinx.coroutines.withTimeoutOrNull(seconds * 1_000L) {
            request("/auth/tv-login/pairing/pair/$nonce", JSONObject().put("ticket", ticket),
                responseLimit = 32_768, singleAttempt = true).objectValue()
        } ?: throw ApiException("TV 로그인 승인 시간이 초과되었습니다. 새 QR 코드로 다시 시도하세요.", 408)
        currentCoroutineContext().ensureActive(); stillOwned()
        if (approved.opt("status") != "approved") throw ApiException("TV 로그인 승인을 확인하지 못했습니다.")
    }

    suspend fun login(username: String, password: String): LoginOutcome = loginResult(
        request("/auth/login", JSONObject().apply {
            put("username", username.trim()); put("password", password)
            put("app_name", "shyniflex"); put("device_name", "shyniflex Android")
        }, authenticated = false).objectValue(),
    )

    suspend fun completeTotp(challenge: String, code: String): LoginOutcome = loginResult(
        request("/auth/login/totp", JSONObject().apply {
            put("challenge_token", challenge); put("code", code.trim()); put("trust_device", false)
        }, authenticated = false).objectValue(),
    )

    private fun loginResult(json: JSONObject): LoginOutcome {
        if (json.optBoolean("require_totp")) {
            val challenge = json.stringOrNull("challenge_token")
                ?: throw ApiException("서버의 2단계 인증 정보가 올바르지 않습니다.")
            return LoginOutcome.NeedsTotp(challenge)
        }
        val value = json.stringOrNull("token") ?: throw ApiException("로그인 토큰을 받지 못했습니다.")
        token = value
        val user = json.optJSONObject("user") ?: json
        return LoginOutcome.Success(value, user.stringOrNull("username") ?: user.stringOrNull("name") ?: "사용자")
    }

    suspend fun me(): String {
        val response = request("/auth/me").objectValue()
        val user = response.optJSONObject("user") ?: response
        return user.stringOrNull("username") ?: user.stringOrNull("name") ?: "사용자"
    }

    suspend fun logout() {
        try { request("/auth/logout", JSONObject()) } finally { token = null }
    }

    suspend fun continueWatching(): List<MediaItem> = withContext(Dispatchers.Default) {
        parseContinueWatching(request("/continue-watching?limit=30", expectContinueResponse = true), false)
    }

    private fun continueRows(value: Any): List<JSONObject> {
        val array = when (value) {
            is JSONArray -> value
            is JSONObject -> {
                var selected: JSONArray? = null
                for (key in listOf("items", "results")) {
                    if (!value.has(key)) continue
                    val candidate = value.opt(key) as? JSONArray ?: throw ContinueWatchingResponseException()
                    if (selected == null) selected = candidate
                }
                selected ?: throw ContinueWatchingResponseException()
            }
            else -> throw ContinueWatchingResponseException()
        }
        return List(array.length()) { index -> array.opt(index) as? JSONObject ?: throw ContinueWatchingResponseException() }
    }

    private fun parseContinueWatching(value: Any, privateCatalog: Boolean): List<MediaItem> = continueRows(value).mapNotNull { row ->
        val libraryId = row.intOrNull("library_view_id") ?: return@mapNotNull null
        if (row.stringOrNull("federated_id") != null || row.optBoolean("remote")) return@mapNotNull null
        val type = row.stringOrNull("item_type") ?: row.stringOrNull("type") ?: "movie"
        if (type !in setOf("movie", "episode")) return@mapNotNull null
        // Home rows can carry their own progress-row id; playback always addresses
        // metadata_item_id. Match the server's poster/show-title/subtitle contract.
        val id = row.intOrNull("metadata_item_id") ?: row.intOrNull("id") ?: return@mapNotNull null
        val item = parseMedia(JSONObject(row.toString()).put("id", id), libraryId, type)
        val poster = row.stringOrNull("poster_url")?.let { runCatching { absoluteUrl(it) }.getOrNull() }
        // Native continue can include ordinary Views too. Keep positive privacy
        // evidence so the caller can filter against its explicit View catalogs.
        item.copy(poster = poster ?: item.poster, isSpoiler = item.isSpoiler || privateCatalog)
    }

    suspend fun libraries(): List<Library> = withContext(Dispatchers.Default) {
        parseLibraries(request("/libraries"), false)
    }

    private fun parseLibraries(value: Any, privateCatalog: Boolean): List<Library> = rows(value, "libraries", "views").mapNotNull { row ->
        val id = row.intOrNull("id") ?: row.intOrNull("library_view_id") ?: return@mapNotNull null
        // /libraries returns public Views even for administrators. Only movie/TV
        // browsing belongs in this first video player; book/music retain their own UIs.
        val type = when (row.optString("type", row.optString("content_type"))) {
            "movies" -> "movie"; "shows", "show" -> "tv"
            else -> row.optString("type", row.optString("content_type"))
        }
        if (type !in setOf("movie", "tv") || row.optBoolean("remote")) return@mapNotNull null
        Library(id, row.stringOrNull("name") ?: row.stringOrNull("title") ?: "라이브러리 $id", type,
            privateCatalog || row.booleanOrNull("spoiler_enabled") == true)
    }

    /** Publish complete, progress-enriched shelves in picker order without waiting for every View. */
    suspend fun recentlyAdded(libraries: List<Library>,
        onPartial: suspend (List<MediaItem>) -> Unit = {},
    ): List<MediaItem> {
        // Capture before the dispatcher hop, including a private grant's generation.
        val accountToken = token
        val privateIdentity = if (libraries.any { it.isSpoiler && it.id > 0 && it.type in setOf("movie", "tv") }) spoilerKey() else null
        val privateGrant = if (privateIdentity != null) spoilerGrant() else null
        suspend fun requireCurrent() {
            currentCoroutineContext().ensureActive()
            if (accountToken != token) throw ApiException("로그인 정보가 변경되었습니다. 목록을 다시 여세요.", 401)
            if (privateIdentity != null && (privateIdentity != spoilerKey() || privateGrant == null ||
                    !SpoilerAccessRegistry.isCurrent(privateIdentity, privateGrant))) {
                throw ApiException("스포일러 표시가 잠겼습니다. PIN으로 다시 열어 주세요.", 403)
            }
        }
        return withContext(Dispatchers.Default) {
            requireCurrent()
            val selected = libraries.filter { it.id > 0 && it.type in setOf("movie", "tv") }.distinctBy { it.id }
            // Match the catalog transport budget: a third slow catalog must not
            // queue ahead of the first View's progress and occupy its last slot.
            val stages = Semaphore(minOf(2, client.dispatcher.maxRequests, client.dispatcher.maxRequestsPerHost))
            val publication = Mutex()
            val completed = arrayOfNulls<List<MediaItem>>(selected.size)
            val recent = coroutineScope {
                selected.mapIndexed { index, library -> async { stages.withPermit {
                    requireCurrent()
                    val tv = library.type == "tv"
                    val path = if (tv) "/libraries/${library.id}/shows?sort=latest_episode&sort_dir=desc"
                        else "/libraries/${library.id}/movies?sort=added&sort_dir=desc"
                    // Both routes return a full, already sorted array. Cap locally;
                    // neither an offset nor a server-side limit is part of their contract.
                    val value = request(path, responseLimit = 16L * 1024 * 1024, spoiler = library.isSpoiler)
                    requireCurrent()
                    val rows = rowSequence(value, "items", "results")
                        .filter { !it.optBoolean("remote") && it.stringOrNull("federated_id") == null }
                        .take(RECENT_ITEMS_PER_LIBRARY)
                        .map { row ->
                            val timestampKeys = if (tv) listOf("latest_ep", "latest_episode_added_at", "added_at") else listOf("added_at")
                            val addedAt = timestampKeys.firstNotNullOfOrNull { key ->
                                row.stringOrNull(key)?.let { value ->
                                    runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
                                }
                            }
                            parseMedia(row, library.id, if (tv) "show" else "movie").copy(isSpoiler = library.isSpoiler) to addedAt
                        }.toList().sortedByDescending { it.second ?: Long.MIN_VALUE }
                        .distinctBy { (item, _) -> item.id to item.type }.map { it.first }
                    // This is still one progress request per playable View, not a
                    // second final pass. Holding the stage permit keeps it ahead
                    // of a backlog of later catalogs on the shared catalog lane.
                    val enriched = withProgress(rows)
                    requireCurrent()
                    publication.withLock {
                        requireCurrent()
                        completed[index] = enriched
                        onPartial(completed.filterNotNull().flatten())
                        requireCurrent()
                    }
                    enriched
                } } }.awaitAll().flatten()
            }
            requireCurrent()
            recent
        }
    }

    /** Fan out only to known authorized Views; no global endpoint or fake search offset. */
    suspend fun searchLibraries(libraries: List<Library>, query: String): LibrarySearchResult = withContext(Dispatchers.Default) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return@withContext LibrarySearchResult(emptyList())
        val accountToken = token
        val grantRevision = SpoilerAccessRegistry.changes.value
        fun privateAccessUnchanged() = spoilerUnlocked && SpoilerAccessRegistry.changes.value == grantRevision
        fun checkAccount() {
            if (token != accountToken) throw CancellationException("검색 요청이 종료되었습니다.")
        }
        data class Identity(val libraryId: Int, val type: String, val id: Int, val viewItemId: Int?)
        fun preferPrivate(items: List<MediaItem>): List<MediaItem> {
            val selected = linkedMapOf<Identity, MediaItem>()
            for (item in items) {
                val identity = Identity(item.libraryId, item.type, item.id, item.viewItemId)
                val previous = selected[identity]
                if (previous == null || (item.isSpoiler && !previous.isSpoiler)) selected[identity] = item
            }
            return selected.values.toList()
        }
        // A private View cannot become a public shadow simply because its grant expired.
        val privateIds = libraries.filter { it.id > 0 && it.isSpoiler }.mapTo(HashSet()) { it.id }
        val scopes = libraries.filter { it.id > 0 && it.type in setOf("movie", "tv") &&
            (it.isSpoiler || it.id !in privateIds) && (!it.isSpoiler || privateAccessUnchanged()) }
            .distinctBy { it.id }
        val slots = Semaphore(3)
        val outcomes = coroutineScope {
            scopes.map { library -> async {
                slots.withPermit {
                    try {
                        checkAccount()
                        currentCoroutineContext().ensureActive()
                        if (library.isSpoiler && !privateAccessUnchanged()) throw ApiException("스포일러 표시가 잠겼습니다.", 403)
                        val catalog = browseCatalog(library, LibraryContentType.defaultFor(library), LibrarySort.TITLE_ASC, normalizedQuery)
                        checkAccount()
                        currentCoroutineContext().ensureActive()
                        val visible = preferPrivate(catalog).take(200).filter { !it.isSpoiler || privateAccessUnchanged() }
                        val results = withProgress(visible)
                        checkAccount()
                        if (library.isSpoiler && !privateAccessUnchanged()) LibrarySearchResult(emptyList())
                        else LibrarySearchResult(results)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        checkAccount()
                        if (error is ApiException && error.status == 401) throw error
                        LibrarySearchResult(emptyList(), failedLibraryCount = 1)
                    }
                }
            } }.awaitAll()
        }
        checkAccount()
        currentCoroutineContext().ensureActive()
        LibrarySearchResult(preferPrivate(outcomes.flatMap { it.items }).filter { !it.isSpoiler || privateAccessUnchanged() },
            outcomes.sumOf { it.failedLibraryCount })
    }

    /** Movies/shows are full arrays in beta4; paginate them locally, never fake API offsets. */
    suspend fun items(library: Library, offset: Int = 0, query: String = "", sort: LibrarySort? = null,
        genre: String? = null, year: Int? = null,
        contentType: LibraryContentType = LibraryContentType.defaultFor(library),
        watchFilter: LibraryWatchFilter = LibraryWatchFilter.ALL, decade: Int? = null,
        onCatalogReady: (suspend (MediaPage) -> Unit)? = null): MediaPage = withContext(Dispatchers.Default) {
        val generation = browseCacheGeneration.get()
        val account = token
        val normalizedQuery = query.trim()
        if (library.isSpoiler && !spoilerUnlocked) throw ApiException("스포일러 표시가 잠겼습니다. PIN으로 다시 열어 주세요.", 403)
        val privateKey = spoilerKey()
        val privateGrant = spoilerGrant()
        suspend fun requireCurrent(privateContent: Boolean = library.isSpoiler) {
            currentCoroutineContext().ensureActive()
            if (generation != browseCacheGeneration.get() || account != token) throw CancellationException("목록 요청이 종료되었습니다.")
            if (privateContent) {
                if (!spoilerUnlocked) throw ApiException("스포일러 표시가 잠겼습니다. PIN으로 다시 열어 주세요.", 403)
                if (privateGrant == null || !SpoilerAccessRegistry.isCurrent(privateKey, privateGrant)) {
                    throw CancellationException("목록 요청이 종료되었습니다.")
                }
            }
        }
        requireCurrent()
        if (contentType !in LibraryContentType.availableFor(library)) throw ApiException("이 라이브러리에서 지원하지 않는 표시 종류입니다.")
        val selectedSort = (sort ?: LibrarySort.defaultFor(contentType)).forContentType(contentType)
        val key = "${library.id}:${library.type}:${library.isSpoiler}:$normalizedQuery:$selectedSort:$contentType:$watchFilter"
        // Keep page offsets in the same catalog snapshot until an explicit first-page refresh.
        val cached = browseCache[key]?.takeIf { offset > 0 }
        val page = cached ?: run {
            val parsed = browseCatalog(library, contentType, selectedSort, normalizedQuery)
            val watched = if (watchFilter == LibraryWatchFilter.ALL) parsed else {
                if (contentType in setOf(LibraryContentType.MOVIES, LibraryContentType.EPISODES)) withProgress(parsed)
                else {
                    // Library-wide episodes are an existing authorized View route.
                    // One full set avoids a request for every show or season.
                    val episodes = withProgress(browseCatalog(library, LibraryContentType.EPISODES, LibrarySort.TITLE_ASC, "", inheritFacets = false))
                    val byView = episodes.filter { it.viewItemId != null }.groupBy { it.viewItemId }
                    val byShow = episodes.filter { it.showId != null }.groupBy { it.showId }
                    parsed.map { item ->
                        val related = if (item.viewItemId != null) byView[item.viewItemId].orEmpty()
                            else byShow[if (contentType == LibraryContentType.SEASONS) item.showId else item.id].orEmpty()
                        aggregateWatchState(item, if (contentType == LibraryContentType.SEASONS) related.filter {
                            item.seasonNumber != null && it.seasonNumber == item.seasonNumber
                        } else related)
                    }
                }
            }
            if (library.isSpoiler && !spoilerUnlocked) throw ApiException("스포일러 표시가 잠겼습니다. PIN으로 다시 열어 주세요.", 403)
            currentCoroutineContext().ensureActive()
            cacheBrowsePage(key, generation, CachedItems(System.currentTimeMillis(), watched, watched.size))
        }
        val filtered = filteredCatalog(page.items, genre, year, watchFilter, decade)
        val start = offset.coerceIn(0, filtered.items.size)
        val visible = filtered.items.subList(start, minOf(start + PAGE_SIZE, filtered.items.size)).toList()
        val privateContent = library.isSpoiler || visible.any { it.isSpoiler }
        requireCurrent(privateContent)
        if (watchFilter == LibraryWatchFilter.ALL && onCatalogReady != null) {
            // Artwork can start before the optional history read. Unknown history
            // must not temporarily become an unwatched/completed assertion.
            val artworkPage = filtered.copy(items = visible.map { item ->
                if (item.playable) item.copy(watchState = null, watched = false, resumeMs = 0) else item
            })
            onCatalogReady(artworkPage)
            requireCurrent(privateContent)
        }
        val enriched = if (watchFilter == LibraryWatchFilter.ALL) withProgress(visible) else visible
        requireCurrent(privateContent)
        filtered.copy(items = enriched)
    }

    private suspend fun browseCatalog(library: Library, contentType: LibraryContentType, sort: LibrarySort,
        query: String, inheritFacets: Boolean = true): List<MediaItem> {
        val nested = contentType in setOf(LibraryContentType.SEASONS, LibraryContentType.EPISODES)
        val path = if (query.isNotEmpty() && !nested)
            "/libraries/${library.id}/search?q=${encode(query)}&limit=200&metadata_type=${if (contentType == LibraryContentType.SHOWS) "2" else "1"}"
        else "/libraries/${library.id}/${contentType.route}?${sort.query(library, contentType)}"
        val value = request(path, spoiler = library.isSpoiler, responseLimit = 16L * 1024 * 1024)
        val catalogRows = if (query.isNotEmpty() && !nested) {
            val array = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("items") ?: value.optJSONArray("results")
                    ?: throw ApiException("검색 응답 형식이 올바르지 않습니다.")
                else -> throw ApiException("검색 응답 형식이 올바르지 않습니다.")
            }
            (0 until array.length()).map { array.optJSONObject(it) ?: throw ApiException("검색 응답 형식이 올바르지 않습니다.") }
        } else rows(value, "items", "results")
        val parsed = catalogRows
            .filter { !it.optBoolean("remote") && it.stringOrNull("federated_id") == null }
            .map { row -> parseMedia(row, library.id, contentType.itemType).let { it.copy(isSpoiler = it.isSpoiler || library.isSpoiler) } }
        val enriched = if (nested && inheritFacets && parsed.isNotEmpty()) {
            val shows = browseCatalog(library, LibraryContentType.SHOWS, LibrarySort.TITLE_ASC, "")
            val byView = shows.filter { it.viewItemId != null }.associateBy { it.viewItemId }
            val byId = shows.associateBy { it.id }
            parsed.map { item ->
                val show = if (item.viewItemId != null) byView[item.viewItemId] else byId[item.showId]
                item.copy(year = item.year ?: show?.year, genres = item.genres.ifEmpty { show?.genres.orEmpty() })
            }
        } else parsed
        val matching = if (query.isNotEmpty() && nested) enriched.filter {
            it.title.contains(query, ignoreCase = true) || it.showTitle?.contains(query, ignoreCase = true) == true
        } else enriched
        // Search exposes only the server's available <=200 results; no guessed total.
        return sortedCatalog(matching, sort, contentType, query.isNotEmpty())
    }

    suspend fun details(item: MediaItem): MediaDetails = withContext(Dispatchers.Default) { when (item.type) {
        "movie" -> {
            val response = request("/libraries/${item.libraryId}/movies/${item.id}", spoiler = item.isSpoiler).objectValue()
            MediaDetails(withProgress(parseMedia(response, item.libraryId, "movie", item)),
                castMembers = parseCastMembers(response))
        }
        "episode" -> {
            var cast = emptyList<CastMember>()
            val enriched = enrichEpisode(item) { cast = parseCastMembers(it) }
            MediaDetails(withProgress(enriched), castMembers = cast)
        }
        "show", "tv" -> {
            // Some servers also resolve a resume episode inside the show header.
            // An unidentifiable episode must not prevent opening the separately
            // authorized season list. Keep the original card's numeric identity.
            var cast = emptyList<CastMember>()
            val fresh = try {
                val response = request("/libraries/${item.libraryId}/shows/${item.id}", spoiler = item.isSpoiler).objectValue()
                cast = parseCastMembers(response)
                parseMedia(response, item.libraryId, "show", item)
            } catch (error: ApiException) {
                if (!error.isEpisodeIdentityFailure()) throw error
                item
            }
            val children = rows(request("/libraries/${item.libraryId}/shows/${item.id}/seasons?platform=mobile", spoiler = item.isSpoiler))
                .map { parseMedia(it, item.libraryId, "season").let { season ->
                    season.copy(showId = item.id, showTitle = season.showTitle ?: fresh.title, isSpoiler = item.isSpoiler)
                } }
            MediaDetails(fresh, children, castMembers = cast)
        }
        "season" -> {
            val showId = item.showId ?: throw ApiException("시리즈 정보를 찾을 수 없습니다.")
            val episodes = mutableListOf<MediaItem>()
            var offset = 0
            do {
                val batch = rows(request("/libraries/${item.libraryId}/shows/$showId/seasons/${item.id}/episodes?limit=100&offset=$offset", spoiler = item.isSpoiler))
                    .map { parseMedia(it, item.libraryId, "episode").let { episode ->
                        episode.copy(showId = showId, showTitle = episode.showTitle ?: item.showTitle, isSpoiler = item.isSpoiler)
                    } }
                episodes.addAll(batch)
                offset += batch.size
            } while (batch.size == 100)
            MediaDetails(item, withProgress(episodes))
        }
        else -> MediaDetails(item)
    } }

    /** Read credits already present in a detail response; never fetch a second detail. */
    internal fun parseCastMembers(detail: JSONObject): List<CastMember> {
        fun portrait(value: String?): String? = value?.let { raw -> runCatching {
            resolve(raw).takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
        }.getOrNull() }
        fun member(row: JSONObject, neutral: Boolean): CastMember? {
            val name = row.stringOrNull(if (neutral) "title" else "name")?.trim()?.take(200)
                ?.takeIf { it.isNotEmpty() } ?: return null
            val action = if (neutral) row.optJSONObject("action") else null
            val tmdb = if (neutral && action?.optString("type") == "open_person")
                action?.stringOrNull("value")?.toIntOrNull()?.takeIf { it > 0 }
                else if (!neutral) row.exactPositiveInt("tmdb_id") else null
            val ppkey = (if (neutral && action?.optString("type") == "actor_ppkey") action?.stringOrNull("value")
                else if (!neutral) row.stringOrNull("ppkey") else null)
                ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 && it.none { character -> character.isISOControl() } }
            return CastMember(name, row.stringOrNull(if (neutral) "subtitle" else "role").orEmpty().take(300),
                portrait(row.stringOrNull(if (neutral) "image_url" else "thumb")), tmdb, ppkey)
        }
        val neutralRows = mutableListOf<JSONObject>()
        var hasNeutralCast = false
        val sections = detail.optJSONArray("metadata_sections")
        if (sections != null) for (index in 0 until minOf(sections.length(), 32)) {
            val section = sections.optJSONObject(index) ?: continue
            if (section.optString("presentation") != "person_row") continue
            val groups = section.optJSONArray("groups")
            val cast = groups?.let { array -> (0 until minOf(array.length(), 16)).asSequence()
                .mapNotNull { array.optJSONObject(it) }.firstOrNull { it.optString("id") == "cast" } }
            // An explicitly empty cast group must not be replaced with crew cards.
            if (cast != null) {
                hasNeutralCast = true
                neutralRows.addAll(rowSequence(cast, "items").take(100))
            } else if (groups == null) {
                hasNeutralCast = true
                neutralRows.addAll(rowSequence(section, "items").take(100))
            }
        }
        val parsed = if (hasNeutralCast) neutralRows.take(100).mapNotNull { member(it, true) }
            else {
                val extra = detail.optJSONObject("extra") ?: detail.stringOrNull("extra")?.let {
                    runCatching { JSONObject(it) }.getOrNull()
                }
                extra?.let { rowSequence(it, "actors").take(100).mapNotNull { row -> member(row, false) }.toList() }.orEmpty()
            }
        val members = linkedMapOf<String, CastMember>()
        for (person in parsed) {
            val previous = members[person.key]
            members[person.key] = if (previous == null) person else previous.copy(
                role = (previous.role.split(" / ") + person.role.split(" / ")).filter { it.isNotBlank() }
                    .distinct().joinToString(" / ").take(300), portrait = previous.portrait ?: person.portrait)
        }
        return members.values.take(50)
    }

    /** Provider filmography is not a library: expose only explicit, authorized local View matches. */
    suspend fun actorWorks(actor: CastMember, parent: MediaItem, libraries: List<Library>): ActorWorks {
        val accountToken = token
        val grantKey = spoilerKey()
        val capturedGrant = spoilerGrant()
        val scopes = linkedMapOf<Int, Library>()
        for (library in libraries.filter { it.id > 0 && it.type in setOf("movie", "tv") }) {
            val previous = scopes[library.id]
            if (previous == null || (library.isSpoiler && !previous.isSpoiler)) scopes[library.id] = library
        }
        val parentLibrary = scopes[parent.libraryId]
            ?: throw ApiException("이 라이브러리에 접근할 수 없습니다.", 403)
        val expectedParentType = when (parent.type) {
            "movie" -> "movie"; "show", "tv", "season", "episode" -> "tv"; else -> null
        }
        if (parent.id <= 0 || expectedParentType != parentLibrary.type) throw ApiException("작품 정보를 확인할 수 없습니다.")
        suspend fun requireCurrent() {
            currentCoroutineContext().ensureActive()
            if (token != accountToken) throw ApiException("로그인 정보가 변경되었습니다. 다시 여세요.", 401)
            if ((capturedGrant != null && (grantKey != spoilerKey() || !SpoilerAccessRegistry.isCurrent(grantKey, capturedGrant))) ||
                ((parent.isSpoiler || parentLibrary.isSpoiler) && capturedGrant == null)) {
                throw ApiException("스포일러 표시가 잠겼습니다. PIN으로 다시 열어 주세요.", 403)
            }
        }
        return withContext(Dispatchers.Default) {
            requireCurrent()
            val tmdbId = actor.tmdbId?.takeIf { it > 0 }
            val ppkey = actor.ppkey?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= 200 && it.none { character -> character.isISOControl() }
            }
            val currentOnly = tmdbId == null && ppkey != null
            if (tmdbId == null && (ppkey == null || parentLibrary.type != "tv")) {
                throw ApiException("이 출연진의 보유 작품 조회는 지원되지 않습니다.")
            }
            val path = if (tmdbId != null) "/people/tmdb/$tmdbId?navigation_context=view"
                else "/libraries/${parentLibrary.id}/shows?sort=title&actor_ppkey=${encode(ppkey!!)}"
            val response = request(path, responseLimit = 4L * 1024 * 1024,
                spoiler = capturedGrant != null, spoilerToken = capturedGrant?.token)
            requireCurrent()
            val creditRows = if (tmdbId != null) {
                val array = (response as? JSONObject)?.optJSONArray("local_credits")
                    ?: throw ApiException("출연작 응답 형식이 올바르지 않습니다.")
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } else {
                if (response !is JSONArray && (response !is JSONObject ||
                        (response.optJSONArray("items") == null && response.optJSONArray("results") == null))) {
                    throw ApiException("출연작 응답 형식이 올바르지 않습니다.")
                }
                rows(response)
            }
            data class Identity(val libraryId: Int, val type: String, val id: Int, val viewItemId: Int?)
            val matched = linkedMapOf<Identity, MediaItem>()
            for (row in creditRows) {
                if (row.optBoolean("remote") || row.stringOrNull("federated_id") != null) continue
                if (row.stringOrNull("context_kind")?.let { it != "view" } == true) continue
                val libraryId = if (currentOnly) parentLibrary.id else row.exactPositiveInt("library_view_id") ?: continue
                if (!row.isNull("library_view_id") && row.exactPositiveInt("library_view_id") != libraryId) continue
                val library = scopes[libraryId] ?: continue
                val type = if (currentOnly) {
                    when (row.stringOrNull("item_type") ?: row.stringOrNull("type") ?: row.opt("metadata_type")?.toString()) {
                        null, "null", "2", "show", "tv" -> "show"; else -> continue
                    }
                } else when (row.exactPositiveInt("metadata_type")) { 1 -> "movie"; 2 -> "show"; else -> continue }
                if ((type == "movie" && library.type != "movie") || (type == "show" && library.type != "tv")) continue
                val metadataId = if (currentOnly) row.exactPositiveInt("id") ?: row.exactPositiveInt("metadata_item_id")
                    else if (!row.isNull("representative_metadata_item_id")) row.exactPositiveInt("representative_metadata_item_id")
                    else row.exactPositiveInt("metadata_item_id")
                if (metadataId == null || row.stringOrNull("title").isNullOrBlank()) continue
                if (!row.isNull("view_item_id") && row.exactPositiveInt("view_item_id") == null) continue
                val normalized = JSONObject(row.toString()).put("id", metadataId).put("type", type).put("item_type", type)
                if (tmdbId != null) row.stringOrNull("poster")?.let { normalized.put("poster_url", it) }
                val item = parseMedia(normalized, libraryId, type).let { it.copy(isSpoiler = it.isSpoiler || library.isSpoiler) }
                val identity = Identity(libraryId, type, metadataId, item.viewItemId)
                val previous = matched[identity]
                if (previous == null || (item.isSpoiler && !previous.isSpoiler)) matched[identity] = item
            }
            requireCurrent()
            ActorWorks(matched.values.filter { !it.isSpoiler || capturedGrant != null }.take(200), currentOnly)
        }
    }

    /** Related means shared cast here, not an unsupported recommendation endpoint. */
    suspend fun relatedWorks(parent: MediaItem, castMembers: List<CastMember>, libraries: List<Library>): List<MediaItem> {
        val actor = castMembers.firstOrNull { it.tmdbId?.let { id -> id > 0 } == true } ?: return emptyList()
        val currentShow = when (parent.type) {
            "show", "tv" -> parent.id
            "season", "episode" -> parent.showId?.takeIf { it > 0 } ?: return emptyList()
            "movie" -> null
            else -> return emptyList()
        }
        return actorWorks(actor, parent, libraries).items.filterNot {
            (parent.type == "movie" && it.type == "movie" && it.id == parent.id) ||
                (currentShow != null && it.type == "show" && it.id == currentShow)
        }.take(20)
    }

    /** Count authorized episode identities, never physical file versions or partial history. */
    suspend fun unwatchedEpisodeCount(item: MediaItem): Int? = withContext(Dispatchers.Default) {
        if (item.type !in setOf("show", "tv", "season")) return@withContext null
        if (item.isSpoiler && !spoilerUnlocked) return@withContext null
        val generation = seriesCountGeneration.get()
        val key = "${item.libraryId}:${item.id}:${item.type}:${item.viewItemId}:${item.isSpoiler}"
        seriesCountSlots.withPermit {
            if (generation != seriesCountGeneration.get()) return@withPermit null
            seriesCounts[key]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return@withPermit it.second }
            try {
                fun episodeRows(value: Any): List<JSONObject> {
                    if (value !is JSONArray && (value !is JSONObject ||
                            (value.optJSONArray("items") == null && value.optJSONArray("results") == null))) {
                        throw ApiException("회차 목록의 응답을 확인하지 못했습니다.")
                    }
                    return rows(value)
                }
                val showId = if (item.type == "season") item.showId ?: return@withPermit null else item.id
                val seasonIds = if (item.type == "season") listOf(item.id) else episodeRows(request(
                    "/libraries/${item.libraryId}/shows/$showId/seasons?platform=mobile", spoiler = item.isSpoiler))
                    .map { it.exactPositiveInt("id") ?: throw ApiException("시즌 정보를 확인하지 못했습니다.") }.distinct()
                val ids = linkedSetOf<Int>()
                for (seasonId in seasonIds) {
                    var offset = 0
                    val seasonIdsSeen = mutableSetOf<Int>()
                    do {
                        val batch = episodeRows(request("/libraries/${item.libraryId}/shows/$showId/seasons/$seasonId/episodes?limit=100&offset=$offset",
                            spoiler = item.isSpoiler))
                        val batchIds = batch.map { it.exactPositiveInt("id") ?: throw ApiException("회차 정보를 확인하지 못했습니다.") }
                        if (batch.isNotEmpty() && batchIds.none { it !in seasonIdsSeen }) throw ApiException("회차 목록이 반복되었습니다.")
                        seasonIdsSeen.addAll(batchIds)
                        ids.addAll(batchIds); offset += batch.size
                    } while (batch.size == 100)
                }
                var remaining = 0
                for (batch in ids.toList().chunked(100)) {
                    val progress = request("/playback/progress?item_ids=${batch.joinToString(",")}&library_view_id=${item.libraryId}",
                        spoiler = item.isSpoiler)
                    if (progress !is JSONObject && progress !is JSONArray) throw ApiException("시청 기록을 확인하지 못했습니다.")
                    // A failed history request is unknown; do not turn it into an unwatched count.
                    remaining += batch.count { progressFor(progress, it)?.watchedState() != true }
                }
                if (generation != seriesCountGeneration.get() || (item.isSpoiler && !spoilerUnlocked)) return@withPermit null
                seriesCounts[key] = System.currentTimeMillis() to remaining
                remaining
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
    }

    private suspend fun enrichEpisode(item: MediaItem, onShowMetadata: (JSONObject) -> Unit = {}): MediaItem {
        // beta4 has no GET /episodes/{id}. Read only the selected show's
        // documented metadata/season routes, and keep playback identity intact.
        val showId = item.showId ?: return item
        return try {
            val response = request("/libraries/${item.libraryId}/shows/$showId", responseLimit = 4L * 1024 * 1024, spoiler = item.isSpoiler).objectValue()
            val show = parseMedia(response, item.libraryId, "show")
            onShowMetadata(response)
            var result = item.copy(
                backdrop = item.backdrop ?: show.backdrop,
                showTitle = item.showTitle ?: show.title,
            )
            val seasonNumber = item.seasonNumber
            if (seasonNumber != null) {
                val seasons = rows(request("/libraries/${item.libraryId}/shows/$showId/seasons?platform=mobile", responseLimit = 2L * 1024 * 1024, spoiler = item.isSpoiler))
                val seasonId = seasons.firstOrNull { row ->
                    (row.numberOrNull("season_number") ?: row.numberOrNull("index_")) == seasonNumber
                }?.intOrNull("id")
                if (seasonId != null) {
                    var offset = 0
                    for (page in 0 until 5) {
                        val batch = rows(request("/libraries/${item.libraryId}/shows/$showId/seasons/$seasonId/episodes?limit=100&offset=$offset",
                            responseLimit = 4L * 1024 * 1024, spoiler = item.isSpoiler))
                        val match = batch.firstOrNull { it.intOrNull("id") == item.id }
                            ?: item.episodeNumber?.let { number -> batch.firstOrNull {
                                it.numberOrNull("episode_number") == number &&
                                    (it.numberOrNull("season_number") ?: seasonNumber) == seasonNumber
                            } }
                        if (match != null) {
                            val parsed = parseMedia(match, item.libraryId, "episode", result)
                            result = parsed.copy(id = item.id, libraryId = item.libraryId, type = item.type,
                                viewItemId = item.viewItemId ?: parsed.viewItemId, showId = showId,
                                poster = item.poster ?: parsed.poster,
                                playbackPartId = if (parsed.id == item.id &&
                                    parsed.viewItemId == (item.viewItemId ?: parsed.viewItemId)) parsed.playbackPartId else item.playbackPartId)
                            break
                        }
                        if (batch.size < 100) break
                        offset += batch.size
                    }
                }
            }
            result.copy(summary = result.summary.ifBlank { show.summary })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            item
        }
    }

    private suspend fun withProgress(item: MediaItem): MediaItem {
        return withProgress(listOf(item)).single()
    }

    /** Browse rows have no watch-history join; refresh only the visible playable items. */
    private suspend fun withProgress(items: List<MediaItem>): List<MediaItem> {
        val updates = mutableMapOf<Triple<Int, Boolean, Int>, JSONObject>()
        val unavailable = mutableSetOf<Triple<Int, Boolean, Int>>()
        items.filter { it.playable }.groupBy { it.libraryId to it.isSpoiler }.forEach { (context, group) ->
            val (viewId, privateCatalog) = context
            group.map { it.id }.distinct().chunked(100).forEach batch@{ ids ->
                val value = try {
                    request("/playback/progress?item_ids=${ids.joinToString(",")}&library_view_id=$viewId", spoiler = privateCatalog)
                } catch (error: ApiException) {
                    if (!error.isEpisodeIdentityFailure()) throw error
                    // A failed optional batch is unknown, not an authoritative
                    // empty watch history. Other successful batches still apply.
                    ids.forEach { unavailable += Triple(viewId, privateCatalog, it) }
                    return@batch
                }
                if (value !is JSONObject && value !is JSONArray) throw ApiException("시청 기록 응답 형식이 올바르지 않습니다.")
                ids.forEach { id -> progressFor(value, id)?.let { updates[Triple(viewId, privateCatalog, id)] = it } }
            }
        }
        return items.map { item ->
            val key = Triple(item.libraryId, item.isSpoiler, item.id)
            if (!item.playable || key in unavailable) item else {
                val progress = updates[key]
                val watched = progress?.watchedState() ?: false
                item.copy(
                    watched = watched,
                    resumeMs = if (watched) 0 else progress?.optLong("position_ms")?.coerceAtLeast(0) ?: 0,
                    durationMs = item.durationMs.takeIf { it > 0 } ?: progress?.optLong("duration_ms")?.coerceAtLeast(0) ?: 0,
                    watchState = when {
                        watched -> MediaWatchState.WATCHED
                        (progress?.optLong("position_ms") ?: 0) > 0 -> MediaWatchState.IN_PROGRESS
                        else -> MediaWatchState.UNWATCHED
                    },
                )
            }
        }
    }

    /** Only a reported episode identity failure in optional browse enrichment. */
    private fun ApiException.isEpisodeIdentityFailure(): Boolean {
        if (status !in setOf(400, 404, 409, 422, 500)) return false
        return message?.trim()?.removeSuffix(".")?.trim() in setOf(
            "에피소드 정체성을 확인할 수 없습니다",
            "에피소드의 정체성을 확인할 수 없습니다",
            "에피소의 정체성을 확인할 수 없습니다",
        )
    }

    /** Available source files supplied by Shyni; this never changes account transcode policy. */
    suspend fun versions(item: MediaItem): List<MediaVersion> = withContext(Dispatchers.Default) {
        val response = try {
            request("/playback/versions", decisionBody(item), spoiler = item.isSpoiler)
        } catch (error: ApiException) {
            val knownFile = knownEpisodeFile(item)
            if (!error.isEpisodeIdentityFailure() || knownFile == null) throw error
            // This is existing browse metadata, not a grant to stream the file.
            // Playback still obtains a ticket through the same View's policy checks.
            return@withContext listOf(knownFile)
        }
        val options = rows(response, "options").mapNotNull(::parseVersion).distinctBy { it.partId }
        if (options.isNotEmpty()) return@withContext options
        listOfNotNull((response as? JSONObject)?.optJSONObject("source_info")?.let(::parseVersion))
    }

    private fun knownEpisodeFile(item: MediaItem): MediaVersion? {
        if (item.type != "episode" || item.id <= 0 || item.libraryId <= 0 ||
            item.viewItemId?.let { it > 0 } != true) return null
        val partId = (item.navigationPartId ?: item.playbackPartId)?.takeIf { it > 0 } ?: return null
        return MediaVersion(partId, "원본 파일 $partId", metadataItemId = item.id)
    }

    private fun parseVersion(row: JSONObject): MediaVersion? {
        val partId = row.intOrNull("part_id") ?: return null
        val resolution = row.stringOrNull("resolution_label") ?: row.stringOrNull("resolution")
        val videoCodec = row.stringOrNull("video_codec")
        val audioCodec = row.stringOrNull("audio_codec")
        val fileSize = row.optLong("file_size").takeIf { it > 0 }
        val bitrate = row.optLong("bitrate_bps").takeIf { it > 0 }
        val labels = listOfNotNull(
            resolution,
            videoCodec?.let { when (it.lowercase(java.util.Locale.ROOT)) { "h264", "avc" -> "H.264"; "h265", "hevc" -> "HEVC"; else -> it.uppercase(java.util.Locale.ROOT) } },
            audioCodec?.uppercase(java.util.Locale.ROOT),
            bitrate?.let { String.format(java.util.Locale.US, "%.1f Mbps", it / 1_000_000.0) },
            fileSize?.let { when {
                it >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.2f GB", it / 1_073_741_824.0)
                it >= 1_048_576L -> String.format(java.util.Locale.US, "%.0f MB", it / 1_048_576.0)
                it >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", it / 1024.0)
                else -> "$it B"
            } },
            if (row.optBoolean("dubbed")) "더빙" else null,
        )
        return MediaVersion(partId, labels.joinToString(" · ").ifBlank { "원본 파일 $partId" },
            resolution, videoCodec, audioCodec, fileSize, bitrate, row.intOrNull("metadata_item_id"))
    }

    /** Explicit user state is separate from the player's server-judged completion reports. */
    suspend fun setWatched(item: MediaItem, watched: Boolean): Unit = withContext(Dispatchers.Default) {
        val versionResponse = try {
            request("/playback/versions", decisionBody(item), spoiler = item.isSpoiler).objectValue()
        } catch (error: ApiException) {
            val knownFile = knownEpisodeFile(item)
            if (!error.isEpisodeIdentityFailure() || knownFile == null) throw error
            // The server checks ownership of this exact part in the supplied View.
            setPartWatched(item, item.id, knownFile.partId, watched)
            return@withContext
        }
        // A View may expose several physical versions. The watch-state endpoint checks
        // exact part ownership, while its history is canonical across source versions.
        val candidates = rows(versionResponse, "options") + listOfNotNull(versionResponse.optJSONObject("source_info"))
        data class WatchedPart(val metadataId: Int, val partId: Int, val row: JSONObject)
        fun integer(row: JSONObject, key: String): Int? = runCatching {
            val raw = row.opt(key)
            if (raw !is Number && raw !is String) return@runCatching null
            java.math.BigDecimal(raw.toString()).intValueExact()
        }.getOrNull()
        fun valid(row: JSONObject): WatchedPart? {
            val metadataId = integer(row, "metadata_item_id") ?: return null
            val partId = integer(row, "part_id") ?: return null
            if (metadataId <= 0 || partId <= 0) return null
            return WatchedPart(metadataId, partId, row)
        }
        val physical = candidates.mapNotNull(::valid)
        val contradictoryParts = physical.groupBy { it.partId }.filterValues {
            it.map { option -> option.metadataId }.distinct().size > 1
        }.keys
        val unambiguous = physical.filter { it.partId !in contradictoryParts }
        fun eligibleFallback(option: WatchedPart): Boolean = when (item.type) {
            // playback/versions resolves the authorized movie's source-version pool.
            "movie" -> true
            "episode" -> item.seasonNumber != null && item.episodeNumber != null &&
                item.seasonNumber >= 0 && item.episodeNumber >= 0 &&
                integer(option.row, "season_number") == item.seasonNumber &&
                integer(option.row, "episode_number") == item.episodeNumber &&
                unambiguous.filter { it.metadataId == option.metadataId && it.partId == option.partId }.none {
                    val season = integer(it.row, "season_number")
                    val episode = integer(it.row, "episode_number")
                    (season != null && season != item.seasonNumber) || (episode != null && episode != item.episodeNumber)
                }
            else -> false
        }
        val exact = unambiguous.firstOrNull { it.metadataId == item.id }
        val source = versionResponse.optJSONObject("source_info")?.let(::valid)?.takeIf {
            it.partId !in contradictoryParts && eligibleFallback(it)
        }
        val fallback = unambiguous.filter(::eligibleFallback).distinctBy { it.metadataId to it.partId }.singleOrNull()
        val target = exact ?: source ?: fallback
            ?: throw ApiException("시청 상태를 변경할 파일을 찾을 수 없습니다. 목록을 새로고침해 주세요.")
        setPartWatched(item, target.metadataId, target.partId, watched)
    }

    private suspend fun setPartWatched(item: MediaItem, metadataId: Int, partId: Int, watched: Boolean) {
        val result = request("/continue-watching/$metadataId/watch-state", JSONObject().apply {
            put("part_id", partId); put("watched", watched)
            put("library_view_id", item.libraryId); put("view_item_id", item.viewItemId)
        }, method = "PUT", spoiler = item.isSpoiler).objectValue()
        if (!result.optBoolean("ok") || result.isNull("watched") || result.optBoolean("watched") != watched) {
            throw ApiException("시청 상태 변경을 확인하지 못했습니다. 새로고침해 주세요.")
        }
        clearBrowseCache()
    }

    /** Explicit completion uses the actual part's metadata ID, not a merged View's representative. */
    suspend fun markPlaybackWatched(spec: PlaybackSpec): Unit = withContext(Dispatchers.Default) {
        val file = physicalParts(partMediaInfo(spec, ShyniRequestLane.CONTROL)).singleOrNull { it.partId == spec.partId }
            ?: throw ApiException("현재 재생 파일의 콘텐츠를 확인할 수 없어 시청 완료를 저장하지 못했습니다.")
        val viewItemId = spec.viewItemId ?: spec.sourceItem?.takeIf { it.libraryId == spec.libraryId }?.viewItemId
            ?: throw ApiException("시청 완료를 저장할 View 항목 정보가 없습니다.")
        val result = request("/continue-watching/${file.metadataId}/watch-state", JSONObject().apply {
            put("part_id", spec.partId); put("watched", true)
            put("library_view_id", spec.libraryId); put("view_item_id", viewItemId)
        }, method = "PUT", spoiler = spec.sourceItem?.isSpoiler == true).objectValue()
        if (!result.optBoolean("ok") || result.booleanOrNull("watched") != true) {
            throw ApiException("시청 완료 저장을 확인하지 못했습니다.")
        }
        clearBrowseCache()
    }

    /** There is no public bulk endpoint: preflight all targets before bounded, per-episode writes. */
    suspend fun setCollectionWatched(item: MediaItem, watched: Boolean): List<MediaItem> = withContext(Dispatchers.Default) {
        if (item.type !in setOf("show", "tv", "season")) throw ApiException("시리즈 또는 시즌을 선택하세요.")
        val showId = if (item.type == "season") item.showId ?: throw ApiException("시리즈 정보를 찾을 수 없습니다.") else item.id
        val seasonNumber = if (item.type == "season") item.seasonNumber ?: rows(request(
            "/libraries/${item.libraryId}/shows/$showId/seasons?platform=mobile", responseLimit = 2L * 1024 * 1024,
            spoiler = item.isSpoiler)).firstOrNull { it.intOrNull("id") == item.id }?.let {
                it.numberOrNull("season_number") ?: it.numberOrNull("index_")
            } ?: throw ApiException("시즌 번호를 확인할 수 없습니다.") else null
        val catalog = rows(request("/libraries/${item.libraryId}/shows/$showId/episodes",
            responseLimit = 16L * 1024 * 1024, spoiler = item.isSpoiler), "items", "episodes")
            .map { parseMedia(it, item.libraryId, "episode").let { episode ->
                episode.copy(showId = showId, isSpoiler = item.isSpoiler,
                    viewItemId = episode.viewItemId ?: item.viewItemId)
            } }.filter { seasonNumber == null || it.seasonNumber == seasonNumber }
            .distinctBy { it.id to it.viewItemId }
        if (catalog.isEmpty()) throw ApiException("시청 상태를 변경할 회차가 없습니다.")
        if (catalog.size > 500) throw ApiException("한 번에 500회차까지만 시청 상태를 변경할 수 있습니다. 시즌별로 선택해 주세요.")
        if (catalog.any { !it.playable || it.id <= 0 || it.viewItemId == null }) {
            throw ApiException("일부 회차의 재생 문맥을 확인할 수 없어 시청 상태를 변경하지 않았습니다.")
        }
        val limit = Semaphore(3)
        val outcomes = coroutineScope { catalog.map { episode -> async {
            limit.withPermit {
                try { setWatched(episode, watched); null }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { failure }
            }
        } }.awaitAll() }
        val failures = outcomes.filterNotNull()
        if (failures.isNotEmpty()) {
            val succeeded = catalog.size - failures.size
            throw ApiException("${catalog.size}회차 중 ${succeeded}회차를 변경했습니다. 나머지는 저장하지 못했습니다. ${failures.first().message.orEmpty()}",
                (failures.first() as? ApiException)?.status ?: 0)
        }
        catalog.map { it.copy(watched = watched, resumeMs = 0) }
    }

    suspend fun playback(item: MediaItem, partId: Int? = null, deferSubtitles: Boolean = false): PlaybackSpec {
        // Capture authorization before the first dispatcher hop or network suspension.
        val accountToken = token
        val privateIdentity = if (item.isSpoiler) spoilerKey() else null
        val privateGrant = if (item.isSpoiler) spoilerGrant() else null
        suspend fun requireCurrentAuthorization() {
            currentCoroutineContext().ensureActive()
            if (accountToken != token) throw ApiException("로그인 정보가 변경되었습니다. 다시 재생해 주세요.", 401)
            if (item.isSpoiler && (privateIdentity == null || privateIdentity != spoilerKey() || privateGrant == null ||
                    !SpoilerAccessRegistry.isCurrent(privateIdentity, privateGrant))) {
                throw ApiException("스포일러 표시가 잠겼습니다. PIN으로 다시 열어 주세요.", 403)
            }
        }
        suspend fun <T> stage(label: String, block: suspend () -> T): T = try {
            requireCurrentAuthorization()
            block().also { requireCurrentAuthorization() }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            throw ApiException("재생 준비 · $label: ${error.message ?: "요청을 처리하지 못했습니다."}",
                (error as? ApiException)?.status ?: 0).apply { initCause(error) }
        }
        val sessionId = UUID.randomUUID().toString()
        val requestedPart = partId ?: item.navigationPartId
        val recoveryPart = (requestedPart ?: item.playbackPartId)?.takeIf { it > 0 }
        val canRecoverExactFile = item.type == "episode" && item.id > 0 && item.libraryId > 0 &&
            item.viewItemId?.let { it > 0 } == true && recoveryPart != null
        val preparedDecision: Triple<JSONObject, JSONObject, Int>? = stage("재생 방식 확인") {
            val requestBody = decisionBody(item, requestedPart).put("client_session_id", sessionId)
            // Native decoders handle playback. Do not send an incomplete browser profile:
            // the server's video/container semantics for an audio-only profile are unknown.
            val response = try {
                request("/playback/decision", requestBody, spoiler = item.isSpoiler).objectValue()
            } catch (error: ApiException) {
                if (!canRecoverExactFile || !error.isEpisodeIdentityFailure()) throw error
                null
            } ?: return@stage null
            val source = response.optJSONObject("selected")
                ?: throw ApiException(response.stringOrNull("reason_text") ?: "재생할 수 있는 파일이 없습니다.")
            val sourcePart = source.intOrNull("part_id") ?: throw ApiException("재생 파일 정보를 받지 못했습니다.")
            if (partId == null && item.navigationPartId != null && sourcePart != item.navigationPartId) {
                throw ApiException("같은 폴더에서 확인한 재생 파일이 변경되었습니다. 목록을 다시 불러오세요.")
            }
            Triple(response, source, sourcePart)
        }
        val recovered: Triple<String, Int, Int>? = if (preparedDecision == null) stage("파일 재생 권한 복구") {
            // The tokenless ticket endpoint independently checks exact part membership,
            // the current View/private grant, stream policy and concurrent-stream limits.
            val response = request("/stream-ticket/$recoveryPart?library_view_id=${item.libraryId}&view_item_id=${item.viewItemId}",
                JSONObject(), spoiler = item.isSpoiler).objectValue()
            val boundView = response.exactPositiveInt("library_view_id")
            val boundItem = response.exactPositiveInt("view_item_id")
            if (boundView == null || boundView != item.libraryId || boundItem == null ||
                (response.has("part_id") && response.exactPositiveInt("part_id") != recoveryPart)) {
                throw ApiException("파일 재생 권한의 라이브러리 항목 정보가 일치하지 않습니다.", 403)
            }
            val authorizedTicket = response.stringOrNull("ticket") ?: throw ApiException("재생 티켓을 받지 못했습니다.")
            Triple(authorizedTicket, boundView, boundItem)
        } else null
        val decision = preparedDecision?.first
        val selected = preparedDecision?.second
        val selectedPart = preparedDecision?.third ?: recoveryPart!!
        val context = decision?.stringOrNull("playback_context_token")
        val viewId = recovered?.second ?: decision?.intOrNull("library_view_id") ?: item.libraryId
        val viewItemId = recovered?.third ?: decision?.intOrNull("view_item_id") ?: item.viewItemId
        val contextParameters = stage("재생 방식 확인") { contextQuery(context, viewId, viewItemId) }
        val transcode = decision?.optJSONObject("transcode")
        val mode = transcode?.stringOrNull("mode")
        val ticketPath = if (mode != null) "/playback/transcode-ticket/$selectedPart?$contextParameters&mode=${encode(mode)}"
            else "/stream-ticket/$selectedPart?$contextParameters"
        // The decision authorizes these account/View-scoped reads independently of ticket issuance.
        // Recovery has already validated its exact-file ticket before reaching this scope.
        val (ticket, subs, progress) = withContext(Dispatchers.Default) { coroutineScope {
            // Enqueue the mandatory media ticket first on this same dispatcher. Two slow metadata
            // reads must not occupy both playback slots while their required ticket stays queued.
            val mediaTicket = async(start = CoroutineStart.UNDISPATCHED) {
                recovered?.first ?: stage("재생 티켓 발급") {
                    request(ticketPath, JSONObject(), spoiler = item.isSpoiler).objectValue()
                        .stringOrNull("ticket") ?: throw ApiException("재생 티켓을 받지 못했습니다.")
                }
            }
            val originalSubtitleTicket = if (deferSubtitles) null else if (mode == null) mediaTicket else async(start = CoroutineStart.UNDISPATCHED) {
                stage("자막 티켓 발급") {
                    request("/stream-ticket/$selectedPart?$contextParameters", JSONObject(), spoiler = item.isSpoiler).objectValue()
                        .stringOrNull("ticket") ?: throw ApiException("자막 재생 티켓을 받지 못했습니다.")
                }
            }
            val subtitleRows = if (deferSubtitles) null else async(start = CoroutineStart.UNDISPATCHED) {
                stage("자막 정보 조회") {
                    rows(request("/subtitles/$selectedPart?$contextParameters", spoiler = item.isSpoiler), "subtitles")
                }
            }
            val playbackProgress = async(start = CoroutineStart.UNDISPATCHED) {
                stage("이어보기 위치 조회") {
                    try {
                        progressFor(request("/playback/progress?item_ids=${item.id}&library_view_id=$viewId", spoiler = item.isSpoiler,
                            lane = ShyniRequestLane.PLAYBACK), item.id)
                    } catch (error: ApiException) {
                        // A selected decision (or recovered ticket) already authorizes this source.
                        // A broken canonical progress identity must not discard its valid stream.
                        // Keep the card's resume/watch state when this optional lookup is unknown.
                        if (!error.isEpisodeIdentityFailure()) throw error
                        null
                    }
                }
            }
            val authorizedTicket = mediaTicket.await()
            val subtitles = if (deferSubtitles) emptyList() else {
                val subtitleTicket = originalSubtitleTicket!!.await()
                val listedSubtitles = subtitleRows!!.await()
                stage("자막 정보 조회") {
                    listedSubtitles.mapNotNull { subtitleTrack(it, subtitleTicket, contextParameters) }
                        .map { progressiveSubtitle(it, mode != null) }
                }
            }
            Triple(authorizedTicket, subtitles, playbackProgress.await())
        } }
        val streamUrl = if (mode != null) {
            val parameters = mutableListOf("ticket=${encode(ticket)}", "mode=${encode(mode)}")
            transcode.intOrNull("max_height")?.let { parameters += "max_height=$it" }
            if (!transcode.isNull("audio_stream_index")) parameters += "audio_stream_index=${transcode.optInt("audio_stream_index")}"
            absoluteUrl("/transcode/$selectedPart?${parameters.joinToString("&")}")
        } else absoluteUrl("/stream/$selectedPart?ticket=${encode(ticket)}")
        stage("재생 권한 확인") { requireCurrentAuthorization() }
        val duration = selected?.optLong("duration_ms")?.takeIf { it > 0 }
            ?: selected?.optDouble("duration_sec", 0.0)?.times(1000)?.toLong()?.takeIf { it > 0 }
            ?: progress?.optLong("duration_ms")?.takeIf { it > 0 } ?: item.durationMs
        val resume = progress?.optLong("position_ms")?.coerceAtLeast(0) ?: item.resumeMs
        val effectiveResume = if ((progress?.watchedState() ?: item.watched) || (duration > 0 && resume >= duration)) 0L else resume
        return PlaybackSpec(item.title, streamUrl, emptyMap(), item.id, viewId, selectedPart,
            effectiveResume,
            duration, subs, sessionId, context, viewItemId, decision?.stringOrNull("decision") ?: if (recovered != null) "direct_play" else null,
            decision?.optJSONObject("client_playback_hints")?.optBoolean("prefer_software_decode") == true,
            selectedAudioStreamIndex = decision?.indexOrNull("selected_audio_stream_index"),
            selectedAudioStreamOrdinal = if (mode != null) 0 else decision?.indexOrNull("selected_audio_stream_ordinal"),
            selectedAudioLanguage = decision?.stringOrNull("selected_audio_language"),
            selectedAudioCodec = selected?.stringOrNull("audio_codec"),
            selectedSubtitleId = decision?.optJSONObject("subtitle")?.intOrNull("id"),
            requestedPartId = requestedPart,
            conversionHeight = if (mode == "video") transcode?.intOrNull("max_height") else null,
            streamStartPositionMs = 0,
            sourceItem = item.copy(libraryId = viewId, viewItemId = viewItemId, durationMs = duration,
                resumeMs = effectiveResume, markers = emptyList(),
                navigationPartId = requestedPart, playbackPartId = when {
                    recovered != null -> selectedPart
                    viewId == item.libraryId && viewItemId == item.viewItemId -> item.playbackPartId
                    else -> null
                }),
            markers = PlaybackMarker.parse(JSONArray(item.markers.map { it.asJson() }), duration),
            subtitlesPending = deferSubtitles)
    }

    /** Load subtitles for the exact source already authorized by playback(). */
    suspend fun playbackSubtitles(current: PlaybackSpec): List<SubtitleTrack> {
        val authorize = playbackTransportAuthorization(current) // capture before the dispatcher hop
        return withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive(); authorize()
            val item = current.sourceItem ?: throw ApiException("자막의 재생 정보를 확인할 수 없습니다.")
            if (!current.subtitlesPending || current.itemId <= 0 || current.partId <= 0 || current.libraryId <= 0 ||
                (current.viewItemId == null && current.progressToken.isNullOrBlank()) || current.sessionId.isNullOrBlank() ||
                item.id != current.itemId || item.libraryId != current.libraryId || item.viewItemId != current.viewItemId) {
                throw ApiException("자막의 재생 정보가 일치하지 않습니다.")
            }
            val url = runCatching { resolve(current.url) }.getOrNull()
                ?: throw ApiException("자막의 재생 주소가 올바르지 않습니다.")
            val converted = current.isServerConversion
            val expectedPath = if (converted) "/transcode/${current.partId}" else "/stream/${current.partId}"
            if (!sameOrigin(url) || url.encodedPath != expectedPath || url.queryParameter("ticket").isNullOrBlank()) {
                throw ApiException("자막의 재생 주소가 일치하지 않습니다.")
            }
            val context = contextQuery(current.progressToken, current.libraryId, current.viewItemId)
            val ticket = if (!converted) url.queryParameter("ticket")!! else {
                val response = request("/stream-ticket/${current.partId}?$context", JSONObject(), spoiler = item.isSpoiler).objectValue()
                currentCoroutineContext().ensureActive(); authorize()
                if ((response.has("library_view_id") && response.exactPositiveInt("library_view_id") != current.libraryId) ||
                    (response.has("view_item_id") && response.exactPositiveInt("view_item_id") != current.viewItemId) ||
                    (response.has("part_id") && response.exactPositiveInt("part_id") != current.partId)) {
                    throw ApiException("자막의 재생 권한 정보가 일치하지 않습니다.", 403)
                }
                response.stringOrNull("ticket") ?: throw ApiException("자막 재생 티켓을 받지 못했습니다.")
            }
            currentCoroutineContext().ensureActive(); authorize()
            val subtitles = rows(request("/subtitles/${current.partId}?$context", spoiler = item.isSpoiler), "subtitles")
                .mapNotNull { subtitleTrack(it, ticket, context) }
                .map { progressiveSubtitle(it, converted) }
            currentCoroutineContext().ensureActive(); authorize()
            subtitles
        }
    }

    /** Explicit data-saving conversion of the current file, without selecting another source. */
    suspend fun convertPlayback(
        current: PlaybackSpec,
        maxHeight: Int?,
        positionMs: Long,
        audioStreamIndex: Int? = current.selectedAudioStreamIndex,
    ): PlaybackSpec {
        val authorization = playbackTransportAuthorization(current)
        return withContext(Dispatchers.Default) {
            if (maxHeight != null && maxHeight !in setOf(480, 720, 1080)) {
                throw ApiException("변환 화질은 480p, 720p, 1080p 중에서 선택하세요.")
            }
            playbackTransport(current, if (maxHeight == null) null else "video", maxHeight, positionMs,
                audioStreamIndex, requireAuthorization = authorization)
        }
    }

    /** Refresh the active delivery method; a retry must not silently restore the original. */
    suspend fun renewPlayback(
        current: PlaybackSpec,
        positionMs: Long,
        audioStreamIndex: Int? = current.selectedAudioStreamIndex,
    ): PlaybackSpec = renewPlayback(current, positionMs, audioStreamIndex, null)

    private suspend fun renewPlayback(
        current: PlaybackSpec,
        positionMs: Long,
        audioStreamIndex: Int?,
        originalStreamTicket: JSONObject?,
        preserveSessionId: Boolean = false,
    ): PlaybackSpec {
        val authorization = playbackTransportAuthorization(current)
        return withContext(Dispatchers.Default) {
            val url = runCatching { current.url.toHttpUrl() }.getOrNull()
            val progressive = url?.encodedPath == "/transcode/${current.partId}"
            val music = url?.encodedPath == "/transcode/music/${current.partId}"
            if (!progressive && !music && current.conversionHeight == null) {
                return@withContext playbackTransport(current, null, null, positionMs, audioStreamIndex,
                    requireAuthorization = authorization, originalStreamTicket = originalStreamTicket,
                    preserveSessionId = preserveSessionId)
            }
            val mode = if (music) "music" else url?.queryParameter("mode")
                ?: if (current.conversionHeight != null) "video" else "audio"
            if (mode !in setOf("remux", "audio", "video", "music")) {
                throw ApiException("현재 변환 방식의 재생 정보를 갱신할 수 없습니다.")
            }
            val height = if (mode == "video") current.conversionHeight
                ?: url?.queryParameter("max_height")?.toIntOrNull()?.takeIf { it > 0 } ?: 1080 else null
            val musicBitrate = if (mode == "music") url?.queryParameter("bitrate_kbps")?.toIntOrNull()?.takeIf { it > 0 } ?: 320 else null
            playbackTransport(current, mode, height, positionMs, audioStreamIndex, musicBitrate, authorization,
                originalStreamTicket, preserveSessionId)
        }
    }

    /** Capture before the dispatcher hop; a late response cannot cross an account/grant change. */
    private fun playbackTransportAuthorization(current: PlaybackSpec): () -> Unit {
        val account = token
        val identity = if (current.sourceItem?.isSpoiler == true) spoilerKey() else null
        val grant = if (identity != null) spoilerGrant() else null
        return {
            if (account != token) throw ApiException("로그인 정보가 변경되었습니다. 다시 재생해 주세요.", 401)
            if (identity != null && (identity != spoilerKey() || grant == null || !SpoilerAccessRegistry.isCurrent(identity, grant))) {
                throw ApiException("스포일러 표시가 잠겼습니다. PIN으로 다시 열어 주세요.", 403)
            }
        }
    }

    private suspend fun aacVideoConversionContext(
        current: PlaybackSpec, height: Int, audioStreamIndex: Int?, requireAuthorization: () -> Unit,
    ): PlaybackSpec {
        if (current.aacConversionContext && !current.progressToken.isNullOrBlank() &&
            audioStreamIndex == current.selectedAudioStreamIndex) return current
        val viewItem = current.viewItemId?.takeIf { it > 0 }
            ?: throw ApiException("AAC 변환에 필요한 라이브러리 항목 정보가 없습니다. 영상을 다시 열어 주세요.")
        current.sourceItem?.let {
            if (!it.playable || it.id != current.itemId || it.libraryId != current.libraryId || it.viewItemId != viewItem) {
                throw ApiException("AAC 변환의 재생 파일 정보가 일치하지 않습니다.", 403)
            }
        }
        currentCoroutineContext().ensureActive(); requireAuthorization()
        val sessionId = UUID.randomUUID().toString()
        val response = request("/playback/decision", JSONObject().apply {
            put("part_id", current.partId); put("library_view_id", current.libraryId); put("view_item_id", viewItem)
            put("platform", "mobile"); put("client_app", "android"); put("device_id", deviceId)
            put("client_session_id", sessionId)
            put("max_video_height", height); put("client_profile", aacConversionProfile(height))
        }, spoiler = current.sourceItem?.isSpoiler == true).objectValue()
        currentCoroutineContext().ensureActive(); requireAuthorization()
        val selected = response.optJSONObject("selected")
        if (selected?.exactPositiveInt("part_id") != current.partId ||
            response.exactPositiveInt("library_view_id") != current.libraryId ||
            response.exactPositiveInt("view_item_id") != viewItem) {
            throw ApiException("AAC 변환의 파일이나 라이브러리 항목이 변경되었습니다.", 403)
        }
        val index = response.exactNonNegativeInt("selected_audio_stream_index")
            ?: throw ApiException("AAC 변환의 원본 오디오 선택을 확인할 수 없습니다.")
        val language = response.stringOrNull("selected_audio_language")
        if ((audioStreamIndex != null && index != audioStreamIndex) ||
            (conversionLanguage(current.selectedAudioLanguage) != null &&
                conversionLanguage(current.selectedAudioLanguage) != conversionLanguage(language))) {
            throw ApiException("AAC 변환에서 선택한 오디오가 변경되었습니다. 원본 오디오를 유지할 수 없습니다.")
        }
        val info = response.optJSONObject("playback_info")
        val audio = info?.optJSONObject("audio")
        // Old servers can report audio_transcode/AAC without retaining the copy restriction
        // in video tickets. Only the explicit video/AAC response proves this contract.
        if (response.stringOrNull("decision") != "video_transcode" ||
            response.optJSONObject("transcode")?.stringOrNull("mode") != "video" ||
            info?.stringOrNull("playback_method") != "video_transcode" || info?.opt("is_transcoding") != true ||
            !isEac3Codec(selected.stringOrNull("audio_codec")) || !isEac3Codec(audio?.stringOrNull("source_codec")) ||
            !audio?.stringOrNull("output_codec").equals("aac", ignoreCase = true)) {
            throw ApiException("서버가 이 영상의 AAC 오디오 변환을 확인하지 못했습니다. 원본 재생을 사용해 주세요.")
        }
        val context = response.stringOrNull("playback_context_token")?.takeIf { it != current.progressToken }
            ?: throw ApiException("새 AAC 변환 재생 권한을 받지 못했습니다.")
        return current.copy(progressToken = context, sessionId = sessionId, selectedAudioStreamIndex = index,
            selectedAudioLanguage = language ?: current.selectedAudioLanguage, aacConversionContext = true)
    }

    private suspend fun playbackTransport(
        current: PlaybackSpec,
        mode: String?,
        maxHeight: Int?,
        positionMs: Long,
        audioStreamIndex: Int?,
        musicBitrateKbps: Int? = null,
        requireAuthorization: () -> Unit,
        originalStreamTicket: JSONObject? = null,
        preserveSessionId: Boolean = false,
    ): PlaybackSpec {
        if (current.itemId <= 0 || current.partId <= 0 || current.libraryId <= 0 || (audioStreamIndex != null && audioStreamIndex < 0)) {
            throw ApiException("재생 파일 정보가 올바르지 않습니다.")
        }
        currentCoroutineContext().ensureActive(); requireAuthorization()
        val source = if (mode == "video" && isEac3Codec(current.selectedAudioCodec)) {
            aacVideoConversionContext(current, maxHeight ?: 1080, audioStreamIndex, requireAuthorization)
        } else current
        val selectedAudio = if (source.aacConversionContext && mode == "video") source.selectedAudioStreamIndex else audioStreamIndex
        val position = positionMs.coerceAtLeast(0).let {
            if (current.durationMs > 0) it.coerceAtMost(current.durationMs) else it
        }
        val context = contextQuery(source.progressToken, source.libraryId, source.viewItemId)
        val transcodeTicket = if (mode != null) {
            request("/playback/transcode-ticket/${current.partId}?$context&mode=${encode(mode)}", JSONObject(), spoiler = current.sourceItem?.isSpoiler == true).objectValue()
                .stringOrNull("ticket") ?: throw ApiException("변환 재생 티켓을 받지 못했습니다.")
        } else null
        currentCoroutineContext().ensureActive(); requireAuthorization()
        // A transcode-only ticket cannot authorize original subtitle endpoints.
        val original = if (originalStreamTicket != null && source === current) originalStreamTicket else
            request("/stream-ticket/${current.partId}?$context", JSONObject(), spoiler = current.sourceItem?.isSpoiler == true).objectValue()
        currentCoroutineContext().ensureActive(); requireAuthorization()
        val originalTicket = original.stringOrNull("ticket") ?: throw ApiException("재생 티켓을 받지 못했습니다.")
        val viewId = original.intOrNull("library_view_id") ?: current.libraryId
        val viewItemId = original.intOrNull("view_item_id") ?: current.viewItemId
        if (source.aacConversionContext && mode == "video" && (viewId != source.libraryId || viewItemId != source.viewItemId)) {
            throw ApiException("AAC 변환의 라이브러리 항목이 변경되었습니다.", 403)
        }
        val refreshedContext = contextQuery(source.progressToken, viewId, viewItemId)
        val subtitles = rows(request("/subtitles/${current.partId}?$refreshedContext", spoiler = current.sourceItem?.isSpoiler == true), "subtitles")
            .mapNotNull { subtitleTrack(it, originalTicket, refreshedContext) }
            .map { progressiveSubtitle(it, mode != null) }
        currentCoroutineContext().ensureActive(); requireAuthorization()
        val streamUrl = if (transcodeTicket != null) {
            val parameters = mutableListOf("ticket=${encode(transcodeTicket)}", "mode=${encode(mode!!)}", "start_ms=$position")
            maxHeight?.let { parameters += "max_height=$it" }
            musicBitrateKbps?.let { parameters += "bitrate_kbps=$it" }
            selectedAudio?.let { parameters += "audio_stream_index=$it" }
            val path = if (mode == "music") "/transcode/music/${current.partId}" else "/transcode/${current.partId}"
            absoluteUrl("$path?${parameters.joinToString("&")}")
        } else absoluteUrl("/stream/${current.partId}?ticket=${encode(originalTicket)}")
        return source.copy(
            url = streamUrl, headers = emptyMap(), libraryId = viewId, viewItemId = viewItemId,
            resumeMs = position, subtitles = subtitles, subtitlesPending = false,
            sessionId = if (preserveSessionId || source !== current) source.sessionId else UUID.randomUUID().toString(),
            decision = when (mode) { "video" -> "video_transcode"; "audio", "music" -> "audio_transcode"; "remux" -> "direct_stream"; else -> "direct_play" },
            selectedAudioStreamIndex = selectedAudio,
            selectedAudioStreamOrdinal = if (mode == null) null else 0,
            sourceItem = current.sourceItem?.copy(libraryId = viewId, viewItemId = viewItemId, resumeMs = position),
            conversionHeight = if (mode == "video") maxHeight else null,
            streamStartPositionMs = if (mode == null) 0 else position,
            aacConversionContext = mode == "video" && source.aacConversionContext,
        )
    }

    private fun JSONObject.exactNonNegativeInt(key: String): Int? = runCatching {
        val raw = opt(key)
        if (raw !is Number && raw !is String) return@runCatching null
        java.math.BigDecimal(raw.toString()).intValueExact().takeIf { it >= 0 }
    }.getOrNull()

    private fun progressiveSubtitle(subtitle: SubtitleTrack, progressive: Boolean): SubtitleTrack =
        // Progressive output omits native subtitle streams; extract text on the original clock.
        if (progressive && subtitle.source in setOf("embedded", "internal")) {
            subtitle.copy(source = "external", streamIndex = null)
        } else subtitle

    private fun subtitleTrack(subtitle: JSONObject, ticket: String, contextQuery: String): SubtitleTrack? {
        val format = subtitle.stringOrNull("format")?.lowercase(java.util.Locale.ROOT)
        // An explicit server rejection always wins. Older text rows may omit playable.
        if (subtitle.has("playable") && !subtitle.isNull("playable") && !subtitle.optBoolean("playable")) return null
        if (format in setOf("pgs", "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "vobsub")) return null
        val id = subtitle.intOrNull("id") ?: return null
        val query = "ticket=${encode(ticket)}&$contextQuery"
        val convertedUrl = absoluteUrl("/subtitles/track/$id.vtt?$query")
        val originalMime = when (format) {
            "srt", "subrip" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "vtt", "webvtt" -> "text/vtt"
            "smi", "sami" -> "application/x-sami"
            "ttml", "dfxp" -> "application/ttml+xml"
            else -> null
        }
        val source = subtitle.stringOrNull("source")?.lowercase(java.util.Locale.ROOT)
        val original = source == "external" && originalMime != null
        return SubtitleTrack(
            id, subtitle.stringOrNull("label") ?: subtitle.stringOrNull("lang_code") ?: "자막 $id",
            if (original) absoluteUrl("/subtitles/raw/$id?$query") else convertedUrl,
            if (original) originalMime!! else "text/vtt",
            fallbackUrl = if (original) convertedUrl else null,
            fallbackMimeType = if (original) "text/vtt" else null,
            source = source,
            language = subtitle.stringOrNull("lang_code"),
            streamIndex = subtitle.indexOrNull("stream_index"),
            format = format,
            forced = subtitle.booleanOrNull("forced"),
            sdh = subtitle.booleanOrNull("sdh"),
            title = subtitle.stringOrNull("title"),
        )
    }

    suspend fun saveProgress(spec: PlaybackSpec, positionMs: Long, durationMs: Long, state: String, watchedMs: Long? = null,
        completionEligible: Boolean? = null) {
        require(state in setOf("playing", "paused", "stopped", "completed", "completed_candidate", "buffering")) { "올바르지 않은 재생 상태입니다." }
        val body = progressBody(spec, positionMs, durationMs, state, watchedMs, completionEligible)
        request("/playback/progress", body, spoiler = spec.sourceItem?.isSpoiler == true)
    }

    internal fun progressBody(spec: PlaybackSpec, positionMs: Long, durationMs: Long, state: String, watchedMs: Long?,
        completionEligible: Boolean? = null): JSONObject =
        JSONObject().apply {
            put("part_id", spec.partId); put("metadata_item_id", spec.itemId)
            put("library_view_id", spec.libraryId); put("view_item_id", spec.viewItemId)
            put("playback_context_token", spec.progressToken); put("client_session_id", spec.sessionId)
            put("position_ms", positionMs.coerceAtLeast(0)); put("duration_ms", durationMs.takeIf { it > 0 })
            put("state", if (state == "completed") "completed_candidate" else state)
            completionEligible?.let { put("completion_eligible", it) }
            put("device_id", deviceId); put("decision", spec.decision)
            put("delivery_mode", "server_proxy"); watchedMs?.let { put("watched_ms", it.coerceAtLeast(0)) }
        }

    private fun decisionBody(item: MediaItem, partId: Int? = null): JSONObject = JSONObject().apply {
        if (!item.playable) throw ApiException("영화 또는 회차를 선택하세요.")
        val viewItemId = item.viewItemId ?: throw ApiException("재생에 필요한 라이브러리 항목 정보가 없습니다. 목록을 다시 불러오세요.")
        if (partId != null) put("part_id", partId) else put("metadata_item_id", item.id)
        put("library_view_id", item.libraryId); put("view_item_id", viewItemId)
        put("platform", "mobile"); put("client_app", "android"); put("device_id", deviceId)
        // MPV/FFmpeg handles native formats; a browser-only profile would incorrectly
        // force the server to transcode codecs the Android player can decode directly.
    }

    private fun contextQuery(context: String?, viewId: Int, viewItemId: Int?): String =
        if (context != null) "playback_context_token=${encode(context)}"
        else "library_view_id=$viewId&view_item_id=${viewItemId ?: throw ApiException("재생 권한 정보를 받지 못했습니다.")}"

    private fun JSONObject.exactPositiveInt(key: String): Int? = runCatching {
        val raw = opt(key)
        if (raw !is Number && raw !is String) return@runCatching null
        java.math.BigDecimal(raw.toString()).intValueExact().takeIf { it > 0 }
    }.getOrNull()

    internal fun parseMedia(row: JSONObject, libraryId: Int, fallbackType: String, previous: MediaItem? = null): MediaItem {
        val type = when (val raw = row.stringOrNull("item_type") ?: row.stringOrNull("type") ?: row.opt("metadata_type")?.toString()) {
            "1", "movie" -> "movie"; "2", "tv", "show" -> "show"; "3", "season" -> "season"
            "4", "episode" -> "episode"; else -> if (raw == "null") fallbackType else raw ?: fallbackType
        }
        val id = row.intOrNull("id") ?: row.intOrNull("metadata_item_id") ?: previous?.id
            ?: throw ApiException("서버의 미디어 항목 정보가 올바르지 않습니다.")
        val season = row.numberOrNull("season_number") ?: if (type == "season")
            row.numberOrNull("logical_season_no") ?: row.numberOrNull("base_season_no") ?: row.numberOrNull("index_") else null
        val episode = row.numberOrNull("episode_number") ?: if (type == "episode") row.numberOrNull("index_") else null
        val duration = row.optLong("duration_ms").takeIf { it > 0 }
            ?: (row.optDouble("duration_sec", 0.0) * 1000).toLong().takeIf { it > 0 } ?: previous?.durationMs ?: 0
        val title = row.stringOrNull("title") ?: row.stringOrNull("name")
            ?: if (type == "season") "시즌 ${season ?: ""}".trim() else previous?.title ?: "제목 없음"
        val poster = row.stringOrNull("thumb_url") ?: row.stringOrNull("poster_url") ?: row.stringOrNull("show_thumb_url")
        val thumbnail = row.stringOrNull("thumb_url")?.let { runCatching { absoluteUrl(it) }.getOrNull() }
        val backdrop = row.stringOrNull("art_url") ?: row.stringOrNull("backdrop_url") ?: row.stringOrNull("show_art_url")
        val watched = row.watchedState() ?: previous?.watched ?: false
        val position = if (!row.isNull("position_ms")) row.optLong("position_ms").coerceAtLeast(0) else previous?.resumeMs ?: 0
        val viewItemId = row.intOrNull("view_item_id") ?: previous?.viewItemId
        val exactPrevious = previous?.takeIf {
            it.id == id && it.libraryId == libraryId && it.type == type && it.viewItemId == viewItemId
        }
        val hintContextMatches = id > 0 && libraryId > 0 && viewItemId?.let { it > 0 } == true &&
            (row.isNull("id") || row.exactPositiveInt("id") == id) &&
            (row.isNull("library_view_id") || row.exactPositiveInt("library_view_id") == libraryId) &&
            (row.isNull("view_item_id") || row.exactPositiveInt("view_item_id") == viewItemId) &&
            (row.isNull("metadata_item_id") || row.exactPositiveInt("metadata_item_id") == id)
        val playbackPart = if (!hintContextMatches || type !in setOf("movie", "episode")) null
            else if (row.has("part_id")) row.exactPositiveInt("part_id") else exactPrevious?.playbackPartId
        return MediaItem(id, libraryId, title, type, row.stringOrNull("summary") ?: previous?.summary ?: "",
            poster?.let { runCatching { absoluteUrl(it) }.getOrNull() } ?: previous?.poster,
            duration, season ?: previous?.seasonNumber, episode ?: previous?.episodeNumber,
            viewItemId, row.intOrNull("show_id") ?: previous?.showId,
            if (watched) 0 else position, watched,
            row.stringOrNull("show_title") ?: previous?.showTitle,
            row.stringOrNull("subtitle") ?: previous?.displaySubtitle,
            backdrop?.let { runCatching { absoluteUrl(it) }.getOrNull() } ?: previous?.backdrop,
            previous?.isSpoiler == true || row.booleanOrNull("spoiler_enabled") == true,
            if (row.has("markers") && !row.isNull("markers")) PlaybackMarker.parse(row.opt("markers"), duration)
                else previous?.markers ?: emptyList(), previous?.folderKey, previous?.navigationPartId,
            thumbnail ?: previous?.thumbnail, playbackPart,
            mediaYear(row) ?: previous?.year, mediaGenres(row).ifEmpty { previous?.genres.orEmpty() },
            when {
                watched -> MediaWatchState.WATCHED
                !row.isNull("position_ms") && position > 0 -> MediaWatchState.IN_PROGRESS
                !row.isNull("position_ms") || row.watchedState() == false -> MediaWatchState.UNWATCHED
                else -> previous?.watchState
            }, row.numberOrNull("episode_count")?.takeIf { it >= 0 } ?: previous?.episodeCount,
            mediaAddedAt(row) ?: previous?.addedAtMs)
    }

    private fun JSONObject.watchedState(): Boolean? = when {
        !isNull("completed") -> optBoolean("completed")
        !isNull("watched") -> optBoolean("watched")
        optString("state") == "completed" -> true
        else -> null
    }

    internal fun progressFor(value: Any, itemId: Int): JSONObject? {
        val obj = value as? JSONObject
        obj?.optJSONObject(itemId.toString())?.let { return it }
        obj?.optJSONObject("progress")?.let { nested ->
            nested.optJSONObject(itemId.toString())?.let { return it }
        }
        if (obj?.optInt("metadata_item_id") == itemId) return obj
        return rows(value, "items", "progress").firstOrNull {
            it.optInt("metadata_item_id", it.optInt("item_id", it.optInt("id"))) == itemId
        }
    }

    private fun resolve(path: String): HttpUrl = origin.resolve(path)
        ?: throw ApiException("서버 주소가 올바르지 않습니다.")
    private fun sameOrigin(url: HttpUrl): Boolean = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private suspend fun request(path: String, body: JSONObject? = null, authenticated: Boolean = true, method: String = "POST", responseLimit: Long? = null,
        spoiler: Boolean = false, spoilerToken: String? = null, expectContinueResponse: Boolean = false,
        singleAttempt: Boolean = false, lane: ShyniRequestLane? = null): Any = withContext(Dispatchers.Default) {
        val url = resolve(path)
        if (!sameOrigin(url)) throw ApiException("다른 서버로 인증 정보를 전달할 수 없습니다.")
        val builder = Request.Builder().url(url).header("Accept", "application/json")
            .header("X-Device-Id", deviceId)
        if (authenticated) {
            val currentToken = token?.takeIf { it.isNotBlank() } ?: throw ApiException("로그인이 필요합니다.", 401)
            sessionHeaders(currentToken).forEach { (name, value) -> builder.header(name, value) }
        }
        if (spoiler) {
            val grant = spoilerToken ?: spoilerGrant()?.token
                ?: throw ApiException("스포일러 표시가 잠겼습니다. PIN으로 다시 열어 주세요.", 403)
            builder.header("X-Shyni-Spoiler", grant)
        }
        body?.let { builder.method(method, it.toString().toRequestBody("application/json; charset=utf-8".toMediaType())) }
        val call = requestLanes.client(url.encodedPath, body != null, method, lane, singleAttempt).newCall(builder.build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ApiException("서버 연결에 실패했습니다. 주소와 네트워크를 확인하세요.").apply { initCause(e) })
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val responseBody = it.body
                            val text = if (responseLimit == null) responseBody?.string().orEmpty() else {
                                if (responseBody != null && responseBody.contentLength() > responseLimit) throw ApiException("서버 응답이 너무 큽니다.")
                                val buffer = okio.Buffer()
                                val source = responseBody?.source()
                                if (source != null) while (source.read(buffer, 65_536) >= 0) {
                                    if (buffer.size > responseLimit) throw ApiException("서버 응답이 너무 큽니다.")
                                    if (!continuation.isActive) return
                                }
                                buffer.readUtf8().removePrefix("\uFEFF")
                            }
                            val json = runCatching { JSONTokener(text).nextValue() }.getOrNull()
                            if (!it.isSuccessful) throw ApiException(errorMessage(json, it.code), it.code)
                            if (expectContinueResponse && json == null) throw ContinueWatchingResponseException()
                            // Current Shyni web servers may issue an HttpOnly session
                            // instead of a JSON bearer. Keep that session scoped to
                            // this origin, encrypted by SessionStore like a bearer.
                            val cookieLogin = path in setOf("/auth/login", "/auth/login/totp") ||
                                (path == "/auth/tv-login/pairing/claim" && json is JSONObject && json.opt("status") == "approved")
                            if (!authenticated && cookieLogin && json is JSONObject &&
                                !json.optBoolean("require_totp") && json.stringOrNull("token") == null) {
                                val cookie = Cookie.parseAll(url, it.headers).filter { value -> value.matches(origin) }
                                    .joinToString("; ") { value -> "${value.name}=${value.value}" }
                                if (cookie.isNotBlank()) json.put("token", "cookie:$cookie")
                            }
                            json ?: if (it.code == 204 || text.isBlank()) JSONObject()
                                else throw ApiException("서버 응답을 읽을 수 없습니다.")
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    private fun errorMessage(json: Any?, status: Int): String {
        if (status in 300..399) return "서버가 다른 주소로 이동했습니다. 설정에서 최종 서버 주소를 입력하세요."
        val detail = (json as? JSONObject)?.opt("detail")
        return when (detail) {
            is String -> detail
            is JSONObject -> detail.stringOrNull("message") ?: detail.stringOrNull("reason_text") ?: "요청을 처리할 수 없습니다. ($status)"
            else -> when (status) { 401 -> "로그인이 만료되었거나 계정 정보가 올바르지 않습니다."; 403 -> "이 항목에 접근할 권한이 없습니다."; else -> "요청을 처리할 수 없습니다. ($status)" }
        }
    }

    private fun sessionHeaders(value: String): Map<String, String> {
        require(!value.contains('\r') && !value.contains('\n')) { "잘못된 로그인 세션입니다." }
        return if (value.startsWith("cookie:")) mapOf("Cookie" to value.removePrefix("cookie:"))
            else mapOf("Authorization" to "Bearer $value")
    }

    private fun Any.objectValue(): JSONObject = this as? JSONObject ?: throw ApiException("서버 응답 형식이 올바르지 않습니다.")
    private fun JSONObject.numberOrNull(key: String): Int? = if (isNull(key)) null else optInt(key).takeIf { it >= 0 }
    private fun rows(value: Any, vararg keys: String): List<JSONObject> = rowSequence(value, *keys).toList()

    private fun rowSequence(value: Any, vararg keys: String): Sequence<JSONObject> {
        val array = when (value) {
            is JSONArray -> value
            is JSONObject -> (keys.asSequence() + sequenceOf("items", "results"))
                .mapNotNull { value.optJSONArray(it) }.firstOrNull() ?: JSONArray()
            else -> JSONArray()
        }
        return (0 until array.length()).asSequence().mapNotNull { array.optJSONObject(it) }
    }

    companion object {
        const val PAGE_SIZE = 60
        const val RECENT_ITEMS_PER_LIBRARY = 20
    }
}
