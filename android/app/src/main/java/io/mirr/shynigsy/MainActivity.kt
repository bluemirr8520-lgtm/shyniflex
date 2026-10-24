package io.mirr.shynigsy

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.bitmapFactoryMaxParallelism
import coil3.network.NetworkFetcher
import coil3.network.okhttp.asNetworkClient
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.PlaybackCompletionSignals
import io.mirr.shynigsy.ui.ShyniApp
import io.mirr.shynigsy.ui.CinemaAppearanceTheme
import io.mirr.shynigsy.ui.ArtworkNetworkClient
import okhttp3.OkHttpClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.mirr.shynigsy.updates.UpdateViewModel
import io.mirr.shynigsy.updates.UpdateDialog

class MainActivity : ComponentActivity() {
    private lateinit var model: AppViewModel
    private lateinit var updates: UpdateViewModel
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) installReadyUpdate()
        else updates.installError("이 앱의 설치 허용을 켠 뒤 설치를 다시 눌러 주세요.")
    }
    private var lastCompletionRevision = 0L
    private val playback = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        // Artwork cannot redirect authenticated image requests to another origin.
        SingletonImageLoader.setSafe { context ->
            val artworkHttp = OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false).build()
            val artworkNetwork = ArtworkNetworkClient(artworkHttp.asNetworkClient())
            ImageLoader.Builder(context)
                .bitmapFactoryMaxParallelism(2)
                .components {
                    add(NetworkFetcher.Factory(networkClient = { artworkNetwork }))
                }.build()
        }
        model = ViewModelProvider(this)[AppViewModel::class.java]
        updates = ViewModelProvider(this)[UpdateViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackCompletionSignals.changes.combine(model.state) { revision, state -> revision to state }
                    .collect { (revision, state) ->
                    // A late progress commit must not wait behind the recent catalog.
                    if (revision > lastCompletionRevision && state.loggedIn &&
                        (!state.busy || state.homeLoading) && !state.spoilerBusy) {
                        lastCompletionRevision = revision
                        model.refresh()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.map { it.spoilerUnlocked || it.spoilerPromptOpen }.distinctUntilChanged().collect { secure ->
                    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.decorView.postOnAnimation {
                        window.decorView.postOnAnimation {
                            val current = model.state.value
                            if (!current.spoilerUnlocked && !current.spoilerPromptOpen)
                                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }
            }
        }
        setContent {
            CinemaAppearanceTheme {
            val updateState by updates.state.collectAsState()
            ShyniApp(model, onUpdate = { updates.check(manual = true) }) { spec ->
                playback.launch(Intent(this, NativePlayerActivity::class.java)
                    .putExtra("spec", spec.toJson())
                    .putExtra("server", model.state.value.server))
            }
            UpdateDialog(updateState, onInstall = ::requestUpdateInstall,
                onCheck = { updates.check(manual = true) }, onDismiss = updates::dismiss)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::updates.isInitialized) updates.check()
    }

    private fun requestUpdateInstall() {
        if (!packageManager.canRequestPackageInstalls()) {
            try {
                installPermission.launch(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:$packageName")))
            } catch (_: Exception) { updates.installError("TV 설정에서 이 앱의 설치 허용을 켠 뒤 다시 시도해 주세요.") }
        } else installReadyUpdate()
    }

    private fun installReadyUpdate() {
        lifecycleScope.launch {
            val file = updates.verifiedFile() ?: return@launch
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.updates", file)
                val installer = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                updates.dismiss()
                startActivity(installer)
            } catch (_: Exception) { updates.installError("설치 화면을 열지 못했습니다. TV의 앱 설치 설정을 확인해 주세요.") }
        }
    }
}
