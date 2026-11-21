package io.mirr.shynigsy.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlexMiniUpdatesTest {
    private fun refs(vararg names: String) = JSONArray().apply {
        names.forEach { put(JSONObject().put("ref", "refs/tags/$it")) }
    }

    private fun release(tag: String, manifest: String = PLEXMINI_UPDATE_MANIFEST): JSONObject {
        fun asset(name: String) = JSONObject().put("name", name).put("size", 80)
            .put("browser_download_url", "https://github.com/$UPDATE_REPOSITORY/releases/download/$tag/$name")
        return JSONObject().put("tag_name", tag).put("assets", JSONArray().put(asset(manifest))
            .put(asset("PLEXMINI-${tag.removePrefix(PLEXMINI_TAG_PREFIX)}.apk")))
    }

    private fun manifest(version: String, code: Int = 5, packageName: String = PLEXMINI_PACKAGE) =
        JSONObject().put("schema", 1).put("packages", JSONObject().put(packageName, JSONObject()
            .put("versionCode", code).put("versionName", version).put("asset", "PLEXMINI-$version.apk")
            .put("sha256", "a".repeat(64))))

    @Test fun acceptsOnlyOurExactPackageFamilyAndNumericallyOrderedStableTags() {
        assertTrue(usesPlexMiniUpdates(PLEXMINI_PACKAGE))
        assertTrue(usesPlexMiniUpdates("$PLEXMINI_PACKAGE.debug"))
        assertFalse(usesPlexMiniUpdates("io.mirr.shyniflex.renewed"))
        assertFalse(usesPlexMiniUpdates("io.mirr.shyniflex.plexmini"))
        val tags = refs("plexmini-v1.20.1", "plexmini-v1.2.11", "plexmini-v1.2.2", "plexmini-v2.0.0",
            "plexmini-v2.0.0", "plexmini-v3.0.0-rc1", "plexmini-v01.9.0", "plexmini-v3.0.0/branch",
            "webos-v3.0.1", "v1.0.103-renewed").put(JSONObject().put("ref", "refs/heads/plexmini-v8.0.0"))
        assertEquals(listOf("plexmini-v2.0.0", "plexmini-v1.20.1", "plexmini-v1.2.11", "plexmini-v1.2.2"),
            plexMiniReleaseTags(tags))
    }

    @Test fun otherProductsAndManyInterveningReleasesCannotDisplacePlexMini() {
        val tags = refs(*(1..30).map { "v2.0.$it-renewed" }.toTypedArray(),
            "plexmini-v1.0.3", "plexmini-v1.0.4")
        for (installed in listOf(4L, 5L, 6L)) {
            val requests = mutableListOf<String>()
            val candidate = latestPlexMiniUpdate(tags, PLEXMINI_PACKAGE, installed, emptyList(), {
                requests += it
                release(it)
            }, { asset ->
                requests += asset.name
                manifest("1.0.4")
            })
            assertEquals(listOf("plexmini-v1.0.4", PLEXMINI_UPDATE_MANIFEST), requests)
            if (installed >= 5) assertNull(candidate) else {
                assertEquals("PLEXMINI-1.0.4.apk", candidate?.asset?.name)
                assertEquals(PLEXMINI_PACKAGE, candidate?.packageName)
            }
        }
    }

    @Test fun skipsUnpublishedPrereleaseAndLegacyTagsWithoutThePlexManifest() {
        val tags = refs("plexmini-v1.0.4", "plexmini-v1.0.3", "plexmini-v1.0.2", "plexmini-v1.0.1")
        val requests = mutableListOf<String>()
        val actual = latestPlexMiniUpdate(tags, PLEXMINI_PACKAGE, 1, emptyList(), { tag ->
            requests += tag
            when (tag) {
                "plexmini-v1.0.4" -> null
                "plexmini-v1.0.3" -> release(tag).put("prerelease", true)
                "plexmini-v1.0.2" -> release(tag, UPDATE_MANIFEST)
                else -> release(tag)
            }
        }, { manifest("1.0.1", code = 2) })
        assertEquals(4, requests.size)
        assertEquals(2L, actual?.versionCode)
    }

    @Test fun requiresMatchingTagPackageAndReleaseAssets() {
        val tag = "plexmini-v1.0.4"
        fun candidate(release: JSONObject, manifest: JSONObject, id: String = PLEXMINI_PACKAGE) =
            latestPlexMiniUpdate(refs(tag), id, 1, emptyList(), { release }, { manifest })
        assertNull(candidate(release(tag), manifest("1.0.4", packageName = "io.mirr.shyniflex.renewed")))
        assertNull(candidate(release(tag), manifest("1.0.4"), "$PLEXMINI_PACKAGE.debug"))
        val foreign = release(tag).apply {
            getJSONArray("assets").getJSONObject(1).put("browser_download_url",
                "https://github.com/other/repo/releases/download/$tag/PLEXMINI-1.0.4.apk")
        }
        val badHash = manifest("1.0.4").apply { getJSONObject("packages").getJSONObject(PLEXMINI_PACKAGE)
            .put("sha256", "invalid") }
        assertTrue(runCatching { candidate(foreign, manifest("1.0.4")) }.isFailure)
        assertTrue(runCatching { candidate(release("plexmini-v1.0.5"), manifest("1.0.4")) }.isFailure)
        assertTrue(runCatching { candidate(release(tag), manifest("1.0.3")) }.isFailure)
        assertTrue(runCatching { candidate(release(tag), badHash) }.isFailure)
    }
}
