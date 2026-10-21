package io.mirr.shynigsy

import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.updates.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in end-to-end check of the APK actually published on this repository. Never installs it. */
class PublishedUpdateDeviceTest {
    @Test fun publishedApkDownloadsAndMatchesTheInstalledSigner() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_update") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = GitHubUpdates(context)
        val current = installedVersion(context)
        val candidate = requireNotNull(service.latest(current - 1))
        assertEquals("Publish the matching build before running this live check", current, candidate.versionCode)
        val file = service.download(candidate) { }
        verifyUpdateArchive(context, file, candidate)
        assertEquals(candidate.asset.size, file.length())
        assertNull("The installed version must not offer itself as a newer update", service.latest(current))
    }
}
