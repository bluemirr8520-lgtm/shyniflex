package io.mirr.shynigsy.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private val packageName = "io.mirr.shyniflex.debug"
    private fun release(url: String = "https://github.com/$UPDATE_REPOSITORY/releases/download/tv-1.0.15/app.apk") =
        JSONObject().put("tag_name", "tv-1.0.15").put("assets", JSONArray().put(JSONObject()
            .put("name", "app.apk").put("size", 100).put("browser_download_url", url)))
    private fun manifest(code: Any = 16, hash: String = "a".repeat(64)) = JSONObject().put("schema", 1)
        .put("packages", JSONObject().put(packageName, JSONObject().put("versionCode", code)
            .put("versionName", "1.0.15-test").put("sha256", hash).put("asset", "app.apk")))

    private fun addAsset(release: JSONObject, name: String, url: String =
        "https://github.com/$UPDATE_REPOSITORY/releases/download/tv-1.0.15/$name", size: Long = 80): JSONObject = release.apply {
        getJSONArray("assets").put(JSONObject().put("name", name).put("size", size).put("browser_download_url", url))
    }

    private fun addVariants(manifest: JSONObject, variants: Any): JSONObject = manifest.apply {
        getJSONObject("packages").getJSONObject(packageName).put("variants", variants)
    }

    private fun variant(asset: String, hash: String = "b".repeat(64)) = JSONObject().put("asset", asset).put("sha256", hash)

    @Test fun selectsOnlyNewerVersionsOfTheInstalledPackage() {
        assertEquals(16L, updateCandidate(release(), manifest(), packageName, 15)!!.versionCode)
        assertNull(updateCandidate(release(), manifest(), packageName, 16))
        assertNull(updateCandidate(release(), manifest(), packageName, 17))
        assertNull(updateCandidate(release(), manifest(), "io.mirr.shyniflex", 15))
    }

    @Test fun refusesForeignInsecureAndMismatchedAssetUrls() {
        for (url in listOf("http://github.com/$UPDATE_REPOSITORY/releases/download/tv-1.0.15/app.apk",
            "https://github.com/other/repo/releases/download/tv-1.0.15/app.apk",
            "https://github.com.evil.invalid/$UPDATE_REPOSITORY/releases/download/tv-1.0.15/app.apk",
            "https://github.com/$UPDATE_REPOSITORY/releases/download/other/app.apk",
            "https://github.com/$UPDATE_REPOSITORY/releases/download/tv-1.0.15/app.apk?redirect=other")) {
            assertTrue(url, runCatching { updateCandidate(release(url), manifest(), packageName, 15) }.isFailure)
        }
    }

    @Test fun refusesBadChecksumsFractionalVersionsAndUnpublishedReleases() {
        assertTrue(runCatching { updateCandidate(release(), manifest(hash = "bad"), packageName, 15) }.isFailure)
        assertTrue(runCatching { updateCandidate(release(), manifest(code = 16.5), packageName, 15) }.isFailure)
        assertTrue(runCatching { updateCandidate(release().put("draft", true), manifest(), packageName, 15) }.isFailure)
        assertTrue(runCatching { updateCandidate(release().put("prerelease", true), manifest(), packageName, 15) }.isFailure)
    }

    @Test fun choosesDeviceAbiPriorityRatherThanManifestOrdering() {
        val release = addAsset(addAsset(release(), "armv7.apk"), "arm64.apk")
        val manifest = addVariants(manifest(), JSONObject()
            .put("armeabi-v7a", variant("armv7.apk", "c".repeat(64)))
            .put("arm64-v8a", variant("arm64.apk", "B".repeat(64))))
        val candidate = updateCandidate(release, manifest, packageName, 15, listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("arm64.apk", candidate.asset.name)
        assertEquals("b".repeat(64), candidate.sha256)
        assertEquals(80L, candidate.asset.size)
        assertEquals(16L, candidate.versionCode)
    }

    @Test fun usesTheFirstAvailableSupportedAbiAndIgnoresUnrelatedVariants() {
        val release = addAsset(addAsset(release(), "armv7.apk"), "arm64.apk")
        val manifest = addVariants(manifest(), JSONObject()
            .put("arm64-v8a", variant("arm64.apk"))
            .put("armeabi-v7a", variant("armv7.apk", "c".repeat(64)))
            .put("unsupported-abi", "not a device candidate"))
        val candidate = updateCandidate(release, manifest, packageName, 15, listOf("x86", "armeabi-v7a", "arm64-v8a"))!!
        assertEquals("armv7.apk", candidate.asset.name)
        assertEquals("c".repeat(64), candidate.sha256)
    }

    @Test fun absentUnknownAndLegacyAbiListsRetainUniversalFallback() {
        val release = addAsset(release(), "arm64.apk")
        val data = addVariants(manifest(), JSONObject().put("arm64-v8a", variant("arm64.apk")))
        for (abis in listOf(emptyList(), listOf("mips"), listOf("x86_64", "x86"))) {
            val candidate = updateCandidate(release, data, packageName, 15, abis)!!
            assertEquals("app.apk", candidate.asset.name)
            assertEquals("a".repeat(64), candidate.sha256)
        }
        assertEquals("app.apk", updateCandidate(release, manifest(), packageName, 15, listOf("arm64-v8a"))!!.asset.name)
        assertEquals("app.apk", updateCandidate(release, addVariants(manifest(), JSONObject.NULL), packageName, 15, listOf("arm64-v8a"))!!.asset.name)
    }

    @Test fun matchingMalformedVariantCannotSilentlyDowngradeToUniversalOrAnotherAbi() {
        val release = addAsset(addAsset(release(), "arm64.apk"), "armv7.apk")
        for (invalid in listOf<Any>(JSONObject.NULL, "wrong type", JSONObject(), variant("arm64.apk", "invalid"),
            variant("arm64.apk", "0".repeat(63)), variant("not-attached.apk"), variant("../arm64.apk"))) {
            val manifest = addVariants(manifest(), JSONObject().put("arm64-v8a", invalid)
                .put("armeabi-v7a", variant("armv7.apk")))
            assertTrue(runCatching { updateCandidate(release, manifest, packageName, 15,
                listOf("arm64-v8a", "armeabi-v7a")) }.isFailure)
        }
        assertTrue(runCatching { updateCandidate(release, addVariants(manifest(), JSONArray()),
            packageName, 15, listOf("arm64-v8a")) }.isFailure)
    }

    @Test fun selectedVariantAssetsMustBelongToTheSamePublishedRelease() {
        val name = "arm64.apk"
        val manifest = addVariants(manifest(), JSONObject().put("arm64-v8a", variant(name)))
        for (url in listOf("http://github.com/$UPDATE_REPOSITORY/releases/download/tv-1.0.15/$name",
            "https://github.com/foreign/repo/releases/download/tv-1.0.15/$name",
            "https://github.com/$UPDATE_REPOSITORY/releases/download/wrong-tag/$name",
            "https://github.com/$UPDATE_REPOSITORY/releases/download/tv-1.0.15/$name?redirect=elsewhere")) {
            assertTrue(runCatching { updateCandidate(addAsset(release(), name, url), manifest,
                packageName, 15, listOf("arm64-v8a")) }.isFailure)
        }
        assertTrue(runCatching { updateCandidate(addAsset(release().put("draft", true), name), manifest,
            packageName, 15, listOf("arm64-v8a")) }.isFailure)
        assertTrue(runCatching { updateCandidate(addAsset(release().put("prerelease", true), name), manifest,
            packageName, 15, listOf("arm64-v8a")) }.isFailure)
        assertTrue(runCatching { updateCandidate(addAsset(release(), name, size = 0), manifest,
            packageName, 15, listOf("arm64-v8a")) }.isFailure)
    }

    @Test fun variantsStillRequireAValidUniversalAndCannotChangePackageOrUpgradeRules() {
        val release = addAsset(release(), "arm64.apk")
        val data = addVariants(manifest(), JSONObject().put("arm64-v8a", variant("arm64.apk")))
        assertNull(updateCandidate(release, data, "io.mirr.shyniflex", 15, listOf("arm64-v8a")))
        assertNull(updateCandidate(release, data, packageName, 16, listOf("arm64-v8a")))
        assertTrue(runCatching { updateCandidate(release,
            addVariants(manifest(hash = "invalid"), JSONObject().put("arm64-v8a", variant("arm64.apk"))),
            packageName, 15, listOf("arm64-v8a")) }.isFailure)
    }

    @Test fun duplicateAssetsAndNonApkVariantsRemainTrustFailures() {
        val duplicate = addAsset(addAsset(release(), "arm64.apk"), "arm64.apk")
        val manifest = addVariants(manifest(), JSONObject().put("arm64-v8a", variant("arm64.apk")))
        assertTrue(runCatching { updateCandidate(duplicate, manifest, packageName, 15, listOf("arm64-v8a")) }.isFailure)
        assertTrue(runCatching { updateCandidate(addAsset(release(), "source.zip"),
            addVariants(manifest(), JSONObject().put("arm64-v8a", variant("source.zip"))),
            packageName, 15, listOf("arm64-v8a")) }.isFailure)
    }
}
