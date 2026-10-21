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

internal enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, READY, CURRENT, ERROR }
internal data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val visible: Boolean = false,
    val percent: Int = 0, val version: String? = null, val message: String = "")

internal class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("app_updates", Application.MODE_PRIVATE)
    private val service = GitHubUpdates(application)
    private val current = installedVersion(application)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var ready: Pair<UpdateCandidate, File>? = null

    fun check(manual: Boolean = false) {
        if (ready?.second?.isFile == true) { if (manual) mutable.update { it.copy(visible = true) }; return }
        if (job?.isActive == true) { if (manual) mutable.update { it.copy(visible = true) }; return }
        val now = System.currentTimeMillis()
        if (!manual && ((now - preferences.getLong("last_success", 0)) in 0 until 6 * 60 * 60_000L ||
                (now - preferences.getLong("last_attempt", 0)) in 0 until 15 * 60_000L)) return
        preferences.edit().putLong("last_attempt", now).apply()
        mutable.value = UpdateState(UpdatePhase.CHECKING, manual, message = "새 버전을 확인하고 있습니다")
        job = viewModelScope.launch {
            try {
                val candidate = service.latest(current)
                if (candidate == null) {
                    preferences.edit().putLong("last_success", System.currentTimeMillis()).apply()
                    mutable.update { it.copy(phase = UpdatePhase.CURRENT, message = "최신 버전을 사용 중입니다") }
                } else {
                    mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, version = candidate.versionName,
                        message = "새 버전을 내려받고 있습니다") }
                    val file = service.download(candidate) { percent -> mutable.update { it.copy(percent = percent) } }
                    ready = candidate to file
                    preferences.edit().putLong("last_success", System.currentTimeMillis()).apply()
                    mutable.value = UpdateState(UpdatePhase.READY, true, 100, candidate.versionName,
                        "새 버전이 준비되었습니다. 설치하면 계정과 설정이 유지됩니다.")
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                mutable.update { it.copy(phase = UpdatePhase.ERROR,
                    message = "업데이트를 확인하지 못했습니다. 연결을 확인하고 다시 시도해 주세요.") }
            }
        }
    }

    fun dismiss() { mutable.update { it.copy(visible = false) } }
    fun installError(message: String) { mutable.update { it.copy(visible = true, message = message) } }

    suspend fun verifiedFile(): File? = withContext(Dispatchers.IO) {
        val (candidate, file) = ready ?: return@withContext null
        try { verifyUpdateArchive(getApplication(), file, candidate); file }
        catch (_: Exception) {
            ready = null; file.delete()
            mutable.value = UpdateState(UpdatePhase.ERROR, true, message = "설치 파일을 다시 받아야 합니다. 다시 확인해 주세요.")
            null
        }
    }
}
