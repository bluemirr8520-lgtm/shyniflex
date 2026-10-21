package io.mirr.shynigsy

import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.updates.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateTrustDeviceTest {
    @Test fun installedSignerAndPackageAreRequiredForAnUpdateArchive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.applicationInfo.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val candidate = UpdateCandidate(context.packageName, installedVersion(context), "test", hash,
            UpdateAsset("app.apk", "https://github.com/example", archive.length()))
        verifyUpdateArchive(context, archive, candidate)
        assertTrue(runCatching { verifyUpdateArchive(context, archive, candidate.copy(packageName = "other.package")) }.isFailure)
        assertTrue(runCatching { verifyUpdateArchive(context, archive, candidate.copy(versionCode = candidate.versionCode + 1)) }.isFailure)
        assertTrue(runCatching { verifyUpdateArchive(context, archive, candidate.copy(sha256 = "0".repeat(64))) }.isFailure)
        val updates = File(context.cacheDir, "updates").apply { mkdirs() }
        val shared = File(updates, "provider-test.apk").apply { writeText("fixture") }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", shared)
            assertEquals("content", uri.scheme)
        } finally { shared.delete() }
    }
}
