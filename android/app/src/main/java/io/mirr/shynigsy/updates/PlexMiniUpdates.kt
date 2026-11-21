package io.mirr.shynigsy.updates

import org.json.JSONArray
import org.json.JSONObject

internal const val PLEXMINI_PACKAGE = "io.mirr.shyniflex.plex"
internal const val PLEXMINI_TAG_PREFIX = "plexmini-v"
internal const val PLEXMINI_UPDATE_MANIFEST = "plexmini-update.json"

internal fun usesPlexMiniUpdates(packageName: String): Boolean =
    packageName == PLEXMINI_PACKAGE || packageName.startsWith("$PLEXMINI_PACKAGE.")

private val plexMiniRef = Regex("""refs/tags/plexmini-v(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})""")

internal fun plexMiniReleaseTags(refs: JSONArray): List<String> =
    (0 until refs.length()).mapNotNull { index ->
        plexMiniRef.matchEntire(refs.getJSONObject(index).getString("ref"))
    }.sortedWith(compareByDescending<MatchResult> { it.groupValues[1].toInt() }
        .thenByDescending { it.groupValues[2].toInt() }.thenByDescending { it.groupValues[3].toInt() })
        .map { it.value.removePrefix("refs/tags/") }.distinct()

/** Each public PLEXMINI version has its own tag. Stop as soon as that app's newest published manifest is found. */
internal fun latestPlexMiniUpdate(
    refs: JSONArray,
    packageName: String,
    installed: Long,
    abis: List<String>,
    readRelease: (String) -> JSONObject?,
    readManifest: (UpdateAsset) -> JSONObject,
): UpdateCandidate? {
    require(usesPlexMiniUpdates(packageName))
    for (tag in plexMiniReleaseTags(refs)) {
        val release = readRelease(tag) ?: continue
        require(release.getString("tag_name") == tag)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
        val manifestAsset = releaseManifest(release, PLEXMINI_UPDATE_MANIFEST) ?: continue
        val manifest = readManifest(manifestAsset)
        require(manifest.getInt("schema") == 1)
        val entry = manifest.getJSONObject("packages").optJSONObject(packageName) ?: continue
        val version = tag.removePrefix(PLEXMINI_TAG_PREFIX)
        val manifestVersion = entry.getString("versionName")
        require(manifestVersion == version || (packageName != PLEXMINI_PACKAGE && manifestVersion == "$version-test"))
        require(entry.getString("asset") == "PLEXMINI-$version.apk")
        return updateCandidate(release, manifest, packageName, installed, abis)
    }
    return null
}
