package io.mirr.shynigsy.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

/** Display data only. Fresh playback preparation still resolves the file and its authorization. */
internal data class HomeSnapshot(
    val savedAtMs: Long,
    val libraries: List<Library>,
    val recentItems: List<MediaItem>,
    val continueItems: List<MediaItem>,
    val recentLibraryStatus: Map<Int, RecentLibraryStatus>,
)

internal const val HOME_SNAPSHOT_MAX_BYTES = 2 * 1024 * 1024
internal const val HOME_SNAPSHOT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
private const val MAX_LIBRARIES = 128
private const val MAX_RECENT_PER_LIBRARY = 20
private const val MAX_CONTINUE = 30
private val snapshotScopePattern = Regex("[a-f0-9]{64}")

/** A remembered session can reuse only its own exact HTTPS origin and token. */
internal fun homeSnapshotScope(server: String, token: String?): String? {
    if (token.isNullOrBlank() || token.length > 16_384 || token.any(Char::isISOControl)) return null
    val origin = server.trim().toHttpUrlOrNull()?.takeIf {
        it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() &&
            it.encodedPath == "/" && it.query == null && it.fragment == null
    } ?: return null
    return MessageDigest.getInstance("SHA-256")
        .digest((origin.toString() + "\u0000" + token).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Both writing and reading apply the same public-only limits and field allowlist. */
internal fun normalizeHomeSnapshot(snapshot: HomeSnapshot): HomeSnapshot {
    val privateIds = snapshot.libraries.filter { it.isSpoiler }.map { it.id }.toSet()
    val conflictingIds = snapshot.libraries.groupBy { it.id }.filterValues { rows ->
        rows.map { it.type }.distinct().size > 1
    }.keys
    val libraries = snapshot.libraries.asSequence().filter {
        it.id > 0 && !it.isSpoiler && it.id !in privateIds && it.id !in conflictingIds &&
            it.type in setOf("movie", "tv") && displayText(it.title, 256).isNotBlank()
    }.distinctBy { it.id }.take(MAX_LIBRARIES)
        .map { it.copy(title = displayText(it.title, 256), isSpoiler = false) }.toList()
    val byId = libraries.associateBy { it.id }
    fun allowed(item: MediaItem): Boolean {
        val library = byId[item.libraryId] ?: return false
        return !item.isSpoiler && item.id > 0 && displayText(item.title, 256).isNotBlank() &&
            (if (library.type == "movie") item.type == "movie"
                else item.type in setOf("show", "tv", "season", "episode"))
    }
    fun unique(items: List<MediaItem>) = items.asSequence().filter(::allowed)
        .distinctBy { listOf(it.libraryId, it.id, it.type, it.viewItemId) }
    val recent = unique(snapshot.recentItems).groupBy { it.libraryId }.let { groups ->
        libraries.flatMap { groups[it.id].orEmpty().take(MAX_RECENT_PER_LIBRARY).map(::displayItem) }
    }
    val continuing = unique(snapshot.continueItems).filter {
        it.playable && !it.watched && it.watchState != MediaWatchState.WATCHED && it.resumeMs > 0
    }
        .take(MAX_CONTINUE).map(::displayItem).toList()
    return snapshot.copy(libraries = libraries, recentItems = recent, continueItems = continuing,
        recentLibraryStatus = snapshot.recentLibraryStatus.filterKeys { it in byId })
}

private fun displayText(value: String, maxLength: Int): String = value.filterNot {
    it.isISOControl() && it != '\n' && it != '\t'
}.trim().take(maxLength)

private fun optionalText(value: String?, maxLength: Int): String? = value?.let { displayText(it, maxLength) }
    ?.takeIf { it.isNotBlank() }

/** Keep the original URL bytes so previously cached Coil artwork has the same key. */
internal fun homeSnapshotArtworkUrl(value: String?): String? {
    if (value.isNullOrBlank() || value.length > 2048 || value.any(Char::isISOControl)) return null
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
    val forbiddenSegments = setOf("playback", "stream", "streams", "transcode", "transcoding", "download", "ticket", "tickets")
    if (url.pathSegments.any { it.lowercase(Locale.ROOT) in forbiddenSegments } ||
        Regex("(?i)\\.(?:m3u8|mpd|mp4|mkv|avi|mov|ts|m2ts|webm)$").containsMatchIn(url.encodedPath)) return null
    for (name in url.queryParameterNames) {
        val values = url.queryParameterValues(name)
        if (values.size != 1) return null
        val parameter = values.single() ?: return null
        val allowed = when (name.lowercase(Locale.ROOT)) {
            "w", "h", "width", "height", "size" -> parameter.toIntOrNull()?.let { it in 1..16_384 } == true
            "q", "quality" -> parameter.toIntOrNull()?.let { it in 1..100 } == true
            "format", "fm" -> parameter.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "avif")
            "fit" -> parameter.lowercase(Locale.ROOT) in setOf("cover", "contain", "inside", "outside")
            else -> false
        }
        if (!allowed) return null
    }
    return value
}

private fun displayItem(item: MediaItem): MediaItem = item.copy(
    title = displayText(item.title, 256), summary = displayText(item.summary, 600),
    poster = homeSnapshotArtworkUrl(item.poster), backdrop = homeSnapshotArtworkUrl(item.backdrop),
    thumbnail = homeSnapshotArtworkUrl(item.thumbnail), showTitle = optionalText(item.showTitle, 256),
    displaySubtitle = optionalText(item.displaySubtitle, 256),
    durationMs = item.durationMs.coerceAtLeast(0), resumeMs = if (item.watched) 0 else item.resumeMs.coerceAtLeast(0),
    seasonNumber = item.seasonNumber?.takeIf { it >= 0 }, episodeNumber = item.episodeNumber?.takeIf { it >= 0 },
    viewItemId = item.viewItemId?.takeIf { it > 0 }, showId = item.showId?.takeIf { it > 0 },
    year = item.year?.takeIf { it in 1..9999 }, episodeCount = item.episodeCount?.takeIf { it >= 0 },
    addedAtMs = item.addedAtMs?.takeIf { it >= 0 },
    genres = item.genres.mapNotNull { optionalText(it, 60) }.distinct().take(12),
    productionCountries = item.productionCountries.mapNotNull(::normalizedProductionCountry).distinct().take(16),
    originalLanguage = item.originalLanguage?.let { mediaOriginalLanguage(JSONObject().put("originalLanguage", it)) },
    isSpoiler = false, markers = emptyList(), folderKey = null, navigationPartId = null, playbackPartId = null,
)

internal fun encodeHomeSnapshot(scope: String, snapshot: HomeSnapshot): ByteArray? = runCatching {
    if (!snapshotScopePattern.matches(scope) || snapshot.savedAtMs <= 0) return null
    val clean = normalizeHomeSnapshot(snapshot)
    val value = JSONObject().put("schema", 1).put("scope", scope).put("savedAtMs", clean.savedAtMs)
        .put("libraries", JSONArray(clean.libraries.map { library ->
            JSONObject().put("id", library.id).put("title", library.title).put("type", library.type)
                .put("isSpoiler", false)
        }))
        .put("recentLibraryStatus", JSONObject().apply {
            clean.recentLibraryStatus.forEach { (id, status) -> put(id.toString(), status.name) }
        })
    // Bound the assembled JSON too, rather than first allocating a many-megabyte string.
    var remaining = HOME_SNAPSHOT_MAX_BYTES - value.toString().toByteArray(Charsets.UTF_8).size - 128
    fun boundedRows(items: List<MediaItem>): JSONArray? {
        val rows = JSONArray()
        for (item in items) {
            val row = encodeItem(item)
            remaining -= row.toString().toByteArray(Charsets.UTF_8).size + 1
            if (remaining < 0) return null
            rows.put(row)
        }
        return rows
    }
    value.put("recentItems", boundedRows(clean.recentItems) ?: return null)
        .put("continueItems", boundedRows(clean.continueItems) ?: return null)
    value.toString().toByteArray(Charsets.UTF_8).takeIf { it.size <= HOME_SNAPSHOT_MAX_BYTES }
}.getOrNull()

private fun encodeItem(item: MediaItem): JSONObject = JSONObject().apply {
    put("id", item.id); put("libraryId", item.libraryId); put("title", item.title); put("type", item.type)
    put("summary", item.summary); put("poster", item.poster); put("backdrop", item.backdrop); put("thumbnail", item.thumbnail)
    put("durationMs", item.durationMs); put("resumeMs", item.resumeMs); put("watched", item.watched)
    put("viewItemId", item.viewItemId); put("showId", item.showId)
    put("seasonNumber", item.seasonNumber); put("episodeNumber", item.episodeNumber)
    put("showTitle", item.showTitle); put("displaySubtitle", item.displaySubtitle); put("year", item.year)
    put("genres", JSONArray(item.genres)); put("watchState", item.watchState?.name)
    put("episodeCount", item.episodeCount); put("addedAtMs", item.addedAtMs); put("isSpoiler", false)
    put("productionCountries", JSONArray(item.productionCountries)); put("originalLanguage", item.originalLanguage)
}

internal fun decodeHomeSnapshot(scope: String, bytes: ByteArray, nowMs: Long): HomeSnapshot? = runCatching {
    if (!snapshotScopePattern.matches(scope) || bytes.isEmpty() || bytes.size > HOME_SNAPSHOT_MAX_BYTES || nowMs <= 0) return null
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    val value = JSONObject(text)
    if (value.exactLong("schema") != 1L || value.opt("scope") != scope) return null
    val savedAt = value.exactLong("savedAtMs") ?: return null
    if (savedAt <= 0 || savedAt > nowMs || nowMs - savedAt > HOME_SNAPSHOT_MAX_AGE_MS) return null
    val libraries = value.requiredRows("libraries", MAX_LIBRARIES).map { row ->
        Library(row.requiredPositiveInt("id"), row.requiredText("title"), row.requiredText("type"),
            row.requiredBoolean("isSpoiler"))
    }
    val recent = value.requiredRows("recentItems", MAX_LIBRARIES * MAX_RECENT_PER_LIBRARY).map(::decodeItem)
    val continuing = value.requiredRows("continueItems", MAX_CONTINUE).map(::decodeItem)
    val statuses = value.getJSONObject("recentLibraryStatus")
    require(statuses.length() <= MAX_LIBRARIES)
    val statusMap = statuses.keys().asSequence().associate { key ->
        val id = key.toIntOrNull()?.takeIf { it > 0 } ?: error("Invalid cached library")
        id to RecentLibraryStatus.valueOf(statuses.requiredText(key))
    }
    normalizeHomeSnapshot(HomeSnapshot(savedAt, libraries, recent, continuing, statusMap))
}.getOrNull()

private fun JSONObject.requiredRows(key: String, maximum: Int): List<JSONObject> {
    val rows = getJSONArray(key)
    require(rows.length() <= maximum)
    return (0 until rows.length()).map { rows.getJSONObject(it) }
}

private fun JSONObject.exactLong(key: String): Long? = (opt(key) as? Number)?.let {
    runCatching { java.math.BigDecimal(it.toString()).longValueExact() }.getOrNull()
}

private fun JSONObject.requiredPositiveInt(key: String): Int = exactLong(key)
    ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt() ?: error("Invalid cached identity")

private fun JSONObject.requiredText(key: String): String = opt(key) as? String ?: error("Invalid cached text")

private fun JSONObject.requiredBoolean(key: String): Boolean = opt(key) as? Boolean ?: error("Invalid cached flag")

private fun decodeItem(row: JSONObject): MediaItem {
    row.requiredPositiveInt("id"); row.requiredPositiveInt("libraryId")
    row.requiredText("title"); row.requiredText("type"); row.requiredBoolean("watched"); row.requiredBoolean("isSpoiler")
    require(row.exactLong("durationMs") != null && row.exactLong("resumeMs") != null)
    for (key in listOf("viewItemId", "showId", "seasonNumber", "episodeNumber", "year", "episodeCount")) {
        if (row.has(key) && !row.isNull(key)) require(row.exactLong(key)?.let { it in 0L..Int.MAX_VALUE.toLong() } == true)
    }
    if (row.has("addedAtMs") && !row.isNull("addedAtMs")) require(row.exactLong("addedAtMs") != null)
    for (key in listOf("summary", "poster", "backdrop", "thumbnail", "showTitle", "displaySubtitle", "watchState")) {
        if (row.has(key) && !row.isNull(key)) row.requiredText(key)
    }
    if (row.has("watchState") && !row.isNull("watchState")) MediaWatchState.valueOf(row.requiredText("watchState"))
    val genres = row.getJSONArray("genres")
    require(genres.length() <= 12 && (0 until genres.length()).all { genres.opt(it) is String })
    if (row.has("productionCountries")) {
        val countries = row.getJSONArray("productionCountries")
        require(countries.length() <= 16 && (0 until countries.length()).all { countries.opt(it) is String })
    }
    if (row.has("originalLanguage") && !row.isNull("originalLanguage")) row.requiredText("originalLanguage")
    // A record cannot inject a file hint or markers even if a newer model gains extra fields.
    return MediaItem.fromJson(row).copy(markers = emptyList(), folderKey = null, navigationPartId = null, playbackPartId = null)
}
