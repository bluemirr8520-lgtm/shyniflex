package io.mirr.shynigsy.updates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.mirr.shynigsy.player.PlaybackCompletionSignals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The launchers belong to the Activity so their saved result keys survive app upgrades. */
internal class ActivityUpdateInstaller(
    private val activity: ComponentActivity,
    private val updates: UpdateViewModel,
    private val openPermission: (Intent) -> Unit,
    private val openInstaller: (Intent) -> Unit,
    private val playbackPending: () -> Boolean,
) {
    fun request(automatic: Boolean = false) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || playbackPending()) return
        val attempt = updates.beginInstall(automatic) ?: return
        if (activity.packageManager.canRequestPackageInstalls()) {
            installReadyUpdate(attempt)
            return
        }
        try {
            openPermission(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            updates.installerOpened(attempt)
        } catch (_: Exception) {
            updates.installError("기기 설정에서 이 앱의 설치 허용을 켠 뒤 다시 시도해 주세요.", attempt)
        }
    }

    fun permissionReturned() {
        val attempt = updates.activeInstallAttempt() ?: return
        if (activity.packageManager.canRequestPackageInstalls()) installReadyUpdate(attempt)
        else updates.installError("이 앱의 설치 허용을 켠 뒤 설치를 다시 눌러 주세요.", attempt)
    }

    private fun installReadyUpdate(attempt: Long) {
        if (!updates.isInstallCurrent(attempt)) return
        activity.lifecycleScope.launch {
            try {
                val file = updates.verifiedFile() ?: return@launch
                if (!updates.isInstallCurrent(attempt)) return@launch
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || playbackPending() ||
                    PlaybackCompletionSignals.progressWriters.value > 0) {
                    updates.deferInstall(attempt)
                    return@launch
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                openInstaller(intent)
                updates.installerOpened(attempt)
            } catch (cancel: CancellationException) {
                if (!activity.isFinishing) updates.deferInstall(attempt)
                throw cancel
            } catch (_: Exception) {
                updates.installError("설치 화면을 열지 못했습니다. 기기의 앱 설치 설정을 확인해 주세요.", attempt)
            }
        }
    }
}
