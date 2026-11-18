package io.mirr.shynigsy.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.mirr.shynigsy.player.subtitles.LocalSubtitleException
import io.mirr.shynigsy.player.subtitles.LocalSubtitleStore
import io.mirr.shynigsy.player.subtitles.LocalSubtitleWrites
import io.mirr.shynigsy.player.subtitles.validateOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Persists the result independently if the private playback Activity is locked/closed. */
class SubtitleImportActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var importJob: Job? = null
    private lateinit var ownerKey: String
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button
    private var pendingUri: Uri? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { finish(); return@registerForActivityResult }
        pendingUri = uri
        importUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        ownerKey = intent.getStringExtra(EXTRA_OWNER_KEY).orEmpty()
        if (runCatching { validateOwner(ownerKey) }.isFailure) { finish(); return }
        val padding = (24 * resources.displayMetrics.density).toInt()
        message = TextView(this).apply { textSize = 17f; text = "자막 파일을 선택해 주세요." }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        retry = Button(this).apply { text = "다시 선택"; visibility = View.GONE; setOnClickListener { launchPicker() } }
        val cancel = Button(this).apply { text = "취소"; setOnClickListener { importJob?.cancel(); finish() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(padding, padding, padding, padding)
            addView(message); addView(progress); addView(retry); addView(cancel)
        })
        @Suppress("DEPRECATION")
        pendingUri = savedInstanceState?.getParcelable(STATE_URI)
        val pending = pendingUri
        if (pending != null) importUri(pending) else if (savedInstanceState == null) launchPicker()
    }

    private fun launchPicker() {
        if (isFinishing || isDestroyed || importJob?.isActive == true) return
        pendingUri = null
        retry.visibility = View.GONE
        message.text = "자막 파일을 선택해 주세요."
        try { picker.launch(arrayOf("*/*")) }
        catch (_: ActivityNotFoundException) { showFailure("이 기기에 파일 선택 앱이 없습니다.") }
        catch (_: SecurityException) { showFailure("파일 선택 앱을 열 권한이 없습니다.") }
    }

    private fun importUri(uri: Uri) {
        if (importJob?.isActive == true || isFinishing || isDestroyed) return
        message.text = "자막 파일을 확인하고 있습니다…"
        progress.visibility = View.VISIBLE
        retry.visibility = View.GONE
        importJob = scope.launch {
            try {
                val record = withContext(Dispatchers.IO) {
                    LocalSubtitleWrites.awaitPending()
                    LocalSubtitleStore(applicationContext).importSubtitle(ownerKey, uri)
                }
                if (!isFinishing && !isDestroyed) {
                    pendingUri = null
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OWNER_KEY, ownerKey).putExtra(EXTRA_SUBTITLE_ID, record.id))
                    finish()
                }
            } catch (_: CancellationException) {
                // A canceled import must never replace the prior subtitle.
            } catch (error: LocalSubtitleException) {
                pendingUri = null
                if (!isFinishing && !isDestroyed) showFailure(error.message ?: "자막을 불러오지 못했습니다.")
            } catch (_: Exception) {
                pendingUri = null
                if (!isFinishing && !isDestroyed) showFailure("자막을 불러오지 못했습니다. 다른 파일을 선택해 주세요.")
            }
        }
    }

    private fun showFailure(text: String) {
        progress.visibility = View.GONE
        message.text = text
        retry.visibility = View.VISIBLE
        retry.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingUri?.let { outState.putParcelable(STATE_URI, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OWNER_KEY = "io.mirr.shynigsy.subtitle.OWNER_KEY"
        const val EXTRA_SUBTITLE_ID = "io.mirr.shynigsy.subtitle.ID"
        private const val STATE_URI = "import_uri"
    }
}
