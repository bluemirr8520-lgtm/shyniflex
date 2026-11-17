package io.mirr.shynigsy.updates

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A fake source keeps every launch/installer policy check off GitHub and Android's installer. */
@RunWith(AndroidJUnit4::class)
class StartupUpdateFlowTest {
    @Test fun oneLaunchChecksOnceAndAnUpToDateResultStaysQuiet() = fixture(noUpdate = true) {
        main { model.checkOnStartup(); model.checkOnStartup(); model.check() }
        awaitPhase(UpdatePhase.CURRENT)
        assertEquals(1, source.checks.get())
        assertEquals(0, source.downloads.get())
        assertFalse(model.state.value.visible)
        assertFalse(model.state.value.automaticInstallPending)
        main { model.checkOnStartup() }
        assertEquals(1, source.checks.get())
    }

    @Test fun startupDownloadsInBackgroundAndConsumesOneInstallerAttempt() = fixture(holdDownload = true) {
        main { model.checkOnStartup() }
        awaitPhase(UpdatePhase.DOWNLOADING)
        assertFalse(model.state.value.visible)
        source.downloadGate.complete(Unit)
        awaitPhase(UpdatePhase.READY)
        assertFalse(model.state.value.visible)
        assertTrue(model.state.value.automaticInstallPending)
        var attempt = 0L
        main {
            attempt = requireNotNull(model.beginInstall(automatic = true))
            assertNull(model.beginInstall(automatic = true))
        }
        assertFalse(model.state.value.automaticInstallPending)
        assertEquals(source.file, runBlocking { model.verifiedFile() })
        assertEquals(1, source.verifications.get())
        main { model.installerOpened(attempt); model.installerReturned(); model.checkOnStartup() }
        assertEquals(UpdatePhase.READY, model.state.value.phase)
        assertFalse(model.state.value.visible)
        assertFalse(model.state.value.automaticInstallPending)
        main { assertNull(model.beginInstall(automatic = true)) }
        assertEquals(1, source.checks.get())
        assertEquals(1, source.downloads.get())
    }

    @Test fun dismissingADownloadStopsAutomaticInstallationAndRetainsManualInstall() = fixture(holdDownload = true) {
        main { model.checkOnStartup() }
        awaitPhase(UpdatePhase.DOWNLOADING)
        main { model.dismiss() }
        source.downloadGate.complete(Unit)
        awaitPhase(UpdatePhase.READY)
        assertFalse(model.state.value.visible)
        assertFalse(model.state.value.automaticInstallPending)
        main {
            assertNull(model.beginInstall(automatic = true))
            model.check(manual = true)
            assertTrue(model.state.value.visible)
            assertNotNull(model.beginInstall(automatic = false))
        }
        assertEquals(1, source.downloads.get())
    }

    @Test fun dismissingReadyOrVerificationCannotRearmTheAutomaticPrompt() = fixture {
        main { model.checkOnStartup() }
        awaitPhase(UpdatePhase.READY)
        main {
            model.dismiss()
            assertNull(model.beginInstall(automatic = true))
            model.check(manual = true)
            val attempt = requireNotNull(model.beginInstall(automatic = false))
            model.dismiss()
            model.deferInstall(attempt)
            assertFalse(model.isInstallCurrent(attempt))
            assertFalse(model.state.value.automaticInstallPending)
            assertFalse(model.state.value.visible)
        }
    }

    @Test fun aManualCheckKeepsTheInstallButtonInsteadOfAutomaticallyOpeningAndroid() = fixture {
        main { model.check(manual = true) }
        awaitPhase(UpdatePhase.READY)
        assertTrue(model.state.value.visible)
        assertFalse(model.state.value.automaticInstallPending)
        main {
            assertNull(model.beginInstall(automatic = true))
            assertNotNull(model.beginInstall(automatic = false))
        }
    }

    @Test fun manualCheckDuringStartupTakesOverVisibilityAndInstallControl() = fixture(holdCheck = true) {
        main { model.checkOnStartup(); model.check(manual = true) }
        assertTrue(model.state.value.visible)
        source.checkGate.complete(Unit)
        awaitPhase(UpdatePhase.READY)
        assertEquals(1, source.checks.get())
        assertTrue(model.state.value.visible)
        assertFalse(model.state.value.automaticInstallPending)
    }

    @Test fun failedStartupIsQuietButManualFailureIsVisibleAndTheNextLaunchRetries() {
        fixture(failCheck = true) {
            main { model.checkOnStartup() }
            awaitPhase(UpdatePhase.ERROR)
            assertFalse(model.state.value.visible)
            main { model.checkOnStartup() }
            assertEquals(1, source.checks.get())
            main { model.check(manual = true) }
            await { source.checks.get() == 2 && model.state.value.phase == UpdatePhase.ERROR }
            assertTrue(model.state.value.visible)
            assertFalse(model.state.value.automaticInstallPending)
        }
        // No old last_attempt/last_success timestamp may suppress a fresh launch.
        fixture(noUpdate = true) {
            main { model.checkOnStartup() }
            awaitPhase(UpdatePhase.CURRENT)
            assertEquals(1, source.checks.get())
        }
    }

    @Test fun preparationMayResumeOnceButAnInstallerOrPermissionCancellationDoesNotLoop() = fixture {
        main { model.checkOnStartup() }
        awaitPhase(UpdatePhase.READY)
        main {
            val first = requireNotNull(model.beginInstall(automatic = true))
            model.deferInstall(first)
            assertTrue(model.state.value.automaticInstallPending)
            val second = requireNotNull(model.beginInstall(automatic = true))
            assertTrue(second > first)
            model.deferInstall(first)
            assertTrue(model.isInstallCurrent(second))
            model.installerOpened(second)
            model.installError("설치 허용이 필요합니다", second)
            assertEquals(UpdatePhase.READY, model.state.value.phase)
            assertTrue(model.state.value.visible)
            assertFalse(model.state.value.automaticInstallPending)
            assertNull(model.beginInstall(automatic = true))
            model.dismiss()
            model.checkOnStartup()
            assertFalse(model.state.value.visible)
        }
    }

    @Test fun finalArchiveVerificationFailureBlocksInstallationAndDiscardsTheFile() = fixture {
        main { model.checkOnStartup() }
        awaitPhase(UpdatePhase.READY)
        main { assertNotNull(model.beginInstall(automatic = true)) }
        source.failVerification = true
        assertNull(runBlocking { model.verifiedFile() })
        assertEquals(UpdatePhase.ERROR, model.state.value.phase)
        assertTrue(model.state.value.visible)
        assertFalse(model.state.value.automaticInstallPending)
        assertFalse(source.file.exists())
        main { assertNull(model.beginInstall(automatic = true)) }
    }

    private fun fixture(noUpdate: Boolean = false, holdCheck: Boolean = false, holdDownload: Boolean = false,
        failCheck: Boolean = false, block: Fixture.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Update fixtures must not use the signed account app", "io.mirr.shyniflex.spoilerqa", context.packageName)
        val application = object : Application() { init { attachBaseContext(context.applicationContext) } }
        val source = FakeUpdateSource(context, noUpdate, holdCheck, holdDownload, failCheck)
        val store = ViewModelStore()
        lateinit var model: UpdateViewModel
        main {
            model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    modelClass.cast(UpdateViewModel(application, source))!!
            })[UpdateViewModel::class.java]
        }
        try { Fixture(model, source).block() }
        finally { main { store.clear() }; source.file.delete() }
    }

    private class Fixture(val model: UpdateViewModel, val source: FakeUpdateSource) {
        fun awaitPhase(phase: UpdatePhase) = await { model.state.value.phase == phase }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                Thread.sleep(10)
            }
            assertTrue("Timed out awaiting update state: ${model.state.value.phase}", condition())
        }
    }

    private class FakeUpdateSource(context: Context, private val noUpdate: Boolean, holdCheck: Boolean,
        holdDownload: Boolean, private val failCheck: Boolean) : UpdateSource {
        val checks = AtomicInteger()
        val downloads = AtomicInteger()
        val verifications = AtomicInteger()
        val checkGate = CompletableDeferred<Unit>().apply { if (!holdCheck) complete(Unit) }
        val downloadGate = CompletableDeferred<Unit>().apply { if (!holdDownload) complete(Unit) }
        val file = File(context.cacheDir, "update-policy-${UUID.randomUUID()}.fixture")
        @Volatile var failVerification = false
        private val candidate = UpdateCandidate(context.packageName, installedVersion(context) + 1L,
            "synthetic", "0".repeat(64), UpdateAsset("fixture.apk", "https://example.invalid/fixture.apk", 7))
        override suspend fun latest(installed: Long): UpdateCandidate? {
            checks.incrementAndGet()
            checkGate.await()
            if (failCheck) throw IOException("Synthetic offline response")
            return if (noUpdate) null else candidate
        }
        override suspend fun download(candidate: UpdateCandidate, progress: (Int) -> Unit): File {
            downloads.incrementAndGet()
            progress(50)
            downloadGate.await()
            file.writeText("fixture")
            progress(100)
            return file
        }
        override fun verify(file: File, candidate: UpdateCandidate) {
            verifications.incrementAndGet()
            if (failVerification || !file.isFile) throw IOException("Synthetic archive verification failure")
        }
    }

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
