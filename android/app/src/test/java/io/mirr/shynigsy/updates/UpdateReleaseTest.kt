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
}
