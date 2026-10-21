package io.mirr.shynigsy.updates

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal const val UPDATE_REPOSITORY = "bluemirr8520-lgtm/shyniflex"
internal const val UPDATE_MANIFEST = "shyniflex-update.json"
internal const val MAX_UPDATE_BYTES = 300L * 1024 * 1024

internal data class UpdateAsset(val name: String, val url: String, val size: Long)
internal data class UpdateCandidate(val packageName: String, val versionCode: Long,
    val versionName: String, val sha256: String, val asset: UpdateAsset)

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
    return UpdateAsset(name, url.toString(), size)
}

internal fun updateCandidate(release: JSONObject, manifest: JSONObject, packageName: String, installed: Long): UpdateCandidate? {
    require(manifest.getInt("schema") == 1)
    val entry = manifest.getJSONObject("packages").optJSONObject(packageName) ?: return null
    val code = java.math.BigDecimal(entry.get("versionCode").toString()).longValueExact()
    require(code > 0)
    if (code <= installed) return null
    val name = entry.getString("versionName").also { require(it.isNotBlank() && it.length <= 64) }
    val hash = entry.getString("sha256").lowercase(java.util.Locale.ROOT)
    require(Regex("[0-9a-f]{64}").matches(hash))
    val asset = releaseAsset(release, entry.getString("asset"))
    require(asset.name.endsWith(".apk"))
    return UpdateCandidate(packageName, code, name, hash, asset)
}
