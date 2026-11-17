package io.mirr.shynigsy.updates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, READY, INSTALLING, CURRENT, ERROR }
internal data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val visible: Boolean = false,
    val percent: Int = 0, val version: String? = null, val message: String = "",
    val automaticInstallPending: Boolean = false, val installAttempt: Long = 0L)

internal class UpdateViewModel @JvmOverloads constructor(application: Application,
    private val service: UpdateSource = GitHubUpdates(application)) : AndroidViewModel(application) {
    private val current = installedVersion(application)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var ready: Pair<UpdateCandidate, File>? = null
    private var startupChecked = false
    private var automaticInstallAllowed = false
    private var installSequence = 0L

    /** One check for this app launch; Activity recreation and installer returns reuse this ViewModel. */
    fun checkOnStartup() {
        if (startupChecked) return
        startupChecked = true
        checkForUpdate(manual = false, installAutomatically = true)
    }

    fun check(manual: Boolean = false) {
        if (!manual) { checkOnStartup(); return }
        checkForUpdate(manual = true, installAutomatically = false)
    }

    private fun checkForUpdate(manual: Boolean, installAutomatically: Boolean) {
        if (manual) automaticInstallAllowed = false
        if (mutable.value.phase == UpdatePhase.INSTALLING) return
        if (ready?.second?.isFile == true) {
            if (manual) mutable.update { it.copy(phase = UpdatePhase.READY, visible = true, automaticInstallPending = false) }
            return
        }
        if (job?.isActive == true) { if (manual) mutable.update { it.copy(visible = true, automaticInstallPending = false) }; return }
        automaticInstallAllowed = installAutomatically
        mutable.value = UpdateState(UpdatePhase.CHECKING, manual, message = "새 버전을 확인하고 있습니다")
        job = viewModelScope.launch {
            try {
                val candidate = service.latest(current)
                if (candidate == null) {
                    mutable.update { it.copy(phase = UpdatePhase.CURRENT, message = "최신 버전을 사용 중입니다") }
                } else {
                    mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, version = candidate.versionName,
                        message = if (candidate.incompleteScan) "일부 릴리스를 확인하지 못했습니다. 찾은 새 버전을 내려받고 있습니다."
                            else "새 버전을 내려받고 있습니다") }
                    val file = service.download(candidate) { percent -> mutable.update { it.copy(percent = percent) } }
                    ready = candidate to file
                    mutable.value = UpdateState(UpdatePhase.READY, mutable.value.visible, 100, candidate.versionName,
                        if (candidate.incompleteScan) "일부 릴리스를 확인하지 못했습니다. 찾은 새 버전을 설치할 수 있습니다."
                        else "새 버전이 준비되었습니다. 설치하면 계정과 설정이 유지됩니다.",
                        automaticInstallPending = automaticInstallAllowed)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                mutable.update { it.copy(phase = UpdatePhase.ERROR,
                    message = "업데이트를 확인하지 못했습니다. 연결을 확인하고 다시 시도해 주세요.") }
            }
        }
    }

    fun dismiss() {
        automaticInstallAllowed = false
        mutable.update { it.copy(visible = false, automaticInstallPending = false,
            phase = if (it.phase == UpdatePhase.INSTALLING) UpdatePhase.READY else it.phase) }
    }

    /** Consume before opening Android settings/installer so returning or cancelling cannot loop. */
    fun beginInstall(automatic: Boolean): Long? {
        val snapshot = mutable.value
        if (snapshot.phase != UpdatePhase.READY || ready?.second?.isFile != true ||
            (automatic && !snapshot.automaticInstallPending)) return null
        automaticInstallAllowed = false
        val attempt = ++installSequence
        mutable.value = snapshot.copy(phase = UpdatePhase.INSTALLING, visible = true,
            automaticInstallPending = false, installAttempt = attempt, message = "설치 파일을 확인하고 있습니다")
        return attempt
    }

    fun activeInstallAttempt(): Long? = mutable.value.takeIf { it.phase == UpdatePhase.INSTALLING }?.installAttempt
    fun isInstallCurrent(attempt: Long): Boolean = activeInstallAttempt() == attempt
    fun installerOpened(attempt: Long) { if (isInstallCurrent(attempt)) mutable.update { it.copy(visible = false) } }

    /** Only preparation may be deferred. Once an Android screen has opened, its result owns the attempt. */
    fun deferInstall(attempt: Long) {
        if (isInstallCurrent(attempt)) mutable.update { it.copy(phase = UpdatePhase.READY, visible = false,
            automaticInstallPending = true) }
    }

    fun installerReturned() {
        if (mutable.value.phase == UpdatePhase.INSTALLING) mutable.update { it.copy(phase = UpdatePhase.READY,
            visible = false, automaticInstallPending = false,
            message = "설치하지 않았다면 업데이트 메뉴에서 다시 설치할 수 있습니다.") }
    }

    fun installError(message: String, attempt: Long? = null) {
        if (attempt != null && !isInstallCurrent(attempt)) return
        mutable.update { it.copy(phase = if (ready?.second?.isFile == true) UpdatePhase.READY else UpdatePhase.ERROR,
            visible = true, automaticInstallPending = false, message = message) }
    }

    suspend fun verifiedFile(): File? = withContext(Dispatchers.IO) {
        val (candidate, file) = ready ?: return@withContext null
        try { service.verify(file, candidate); file }
        catch (_: Exception) {
            ready = null; file.delete()
            mutable.value = UpdateState(UpdatePhase.ERROR, true, message = "설치 파일을 다시 받아야 합니다. 다시 확인해 주세요.")
            null
        }
    }
}
