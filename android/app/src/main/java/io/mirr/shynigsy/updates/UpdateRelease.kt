package io.mirr.shynigsy.updates

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

internal const val UPDATE_REPOSITORY = "bluemirr8520-lgtm/shyniflex"
internal const val UPDATE_MANIFEST = "shyniflex-update.json"
internal const val MAX_UPDATE_BYTES = 300L * 1024 * 1024

internal data class UpdateAsset(val name: String, val url: String, val size: Long, val apiUrl: String? = null)
internal data class UpdateCandidate(val packageName: String, val versionCode: Long,
    val versionName: String, val sha256: String, val asset: UpdateAsset, val incompleteScan: Boolean = false)

/** Release list order is not a version guarantee. If a later scan fails, offer
 * an already found update but mark the scan incomplete; it may not be the
 * highest version. A failure before finding a candidate remains an error. */
internal fun selectNewestUpdateCandidate(releases: JSONArray, inspect: (JSONObject) -> UpdateCandidate?): UpdateCandidate? {
    var best: UpdateCandidate? = null
    var failure: Exception? = null
    var incomplete = false
    for (index in 0 until releases.length()) {
        try {
            val candidate = inspect(releases.getJSONObject(index))
            if (candidate != null && (best == null || candidate.versionCode > best.versionCode)) best = candidate
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (best == null && failure == null) failure = error
            if (best != null) incomplete = true
        }
    }
    if (failure != null) throw failure
    return best?.copy(incompleteScan = incomplete)
}

/** Only assets attached to this repository's published release can supply an update. */
internal fun releaseAsset(release: JSONObject, name: String): UpdateAsset {
    require(!release.optBoolean("draft") && !release.optBoolean("prerelease"))
    require(Regex("[A-Za-z0-9._-]+").matches(name))
    val assets = release.getJSONArray("assets")
    val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
    require(matches.size == 1)
    val row = matches.single()
    val url = row.getString("browser_download_url").toHttpUrlOrNull() ?: error("Invalid asset URL")
    require(url.scheme == "https" && url.host == "github.com" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    val path = url.pathSegments
    require(path.size == 6 && path.take(4) == UPDATE_REPOSITORY.split('/') + listOf("releases", "download") &&
        path[4] == release.getString("tag_name") && path[5] == name)
    val size = row.getLong("size")
    require(size in 1..MAX_UPDATE_BYTES)
    // Construct the API endpoint from the ID returned in this release's own
    // asset list; never follow an arbitrary API URL supplied by a manifest.
    val assetId = if (!row.has("id") || row.isNull("id")) null else
        java.math.BigDecimal(row.get("id").toString()).longValueExact().also { require(it > 0) }
    val apiUrl = assetId?.let { "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/assets/$it" }
    if (apiUrl != null && row.has("url")) require(row.getString("url") == apiUrl)
    return UpdateAsset(name, url.toString(), size, apiUrl)
}

internal fun updateCandidate(release: JSONObject, manifest: JSONObject, packageName: String, installed: Long,
    supportedAbis: List<String> = emptyList()): UpdateCandidate? {
    require(manifest.getInt("schema") == 1)
    val entry = manifest.getJSONObject("packages").optJSONObject(packageName) ?: return null
    val code = java.math.BigDecimal(entry.get("versionCode").toString()).longValueExact()
    require(code > 0)
    if (code <= installed) return null
    val name = entry.getString("versionName").also { require(it.isNotBlank() && it.length <= 64) }
    fun payload(value: JSONObject): Pair<UpdateAsset, String> {
        val hash = value.getString("sha256").lowercase(java.util.Locale.ROOT)
        require(Regex("[0-9a-f]{64}").matches(hash))
        val asset = releaseAsset(release, value.getString("asset"))
        require(asset.name.endsWith(".apk"))
        return asset to hash
    }
    // Keep a valid universal entry for previously shipped schema-1 clients.
    val universal = payload(entry)
    val variants = if (entry.isNull("variants")) null else entry.getJSONObject("variants")
    val abi = supportedAbis.firstOrNull { it.isNotBlank() && variants?.has(it) == true }
    // Missing device variants fall back to universal. A present matching variant
    // must pass the same release-asset checks; malformed entries fail closed.
    val (asset, hash) = if (abi == null) universal else payload(requireNotNull(variants).getJSONObject(abi))
    return UpdateCandidate(packageName, code, name, hash, asset)
}
