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
import io.mirr.shynigsy.ui.ArtworkBrowsingState
import okhttp3.OkHttpClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.mirr.shynigsy.updates.UpdateViewModel
import io.mirr.shynigsy.updates.UpdateDialog
import io.mirr.shynigsy.data.parseTvLoginLink
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec

class MainActivity : ComponentActivity() {
    private lateinit var model: AppViewModel
    private lateinit var updates: UpdateViewModel
    private val playbackOrientation by lazy { PlaybackReturnOrientation(this) }
    private val artworkBrowsingOwner = Any()
    private val playbackInFlight = MutableStateFlow(false)
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val attempt = if (::updates.isInitialized) updates.activeInstallAttempt() else null
        if (attempt != null) {
            if (packageManager.canRequestPackageInstalls()) installReadyUpdate(attempt)
            else updates.installError("이 앱의 설치 허용을 켠 뒤 설치를 다시 눌러 주세요.", attempt)
        }
    }
    private var lastCompletionRevision = 0L
    private val playback = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        playbackInFlight.value = false
        playbackOrientation.playbackReturned()
        val completedEpisode = if (result.resultCode == RESULT_OK)
            result.data?.getStringExtra("completedEpisode")?.let { runCatching { MediaItem.fromJson(it) }.getOrNull() }
        else null
        if (completedEpisode != null) model.showCompletedEpisodeList(completedEpisode) else model.refresh()
    }
    // Keep the existing permission/playback registry keys stable across app updates.
    private val updateInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (::updates.isInitialized) updates.installerReturned()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackInFlight.value = savedInstanceState?.getBoolean("update_playback_in_flight", false) ?: false
        playbackOrientation.restore(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        // Artwork cannot redirect authenticated image requests to another origin.
        SingletonImageLoader.setSafe { context ->
            val artworkHttp = OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false).build()
            val artworkNetwork = ArtworkNetworkClient(artworkHttp.asNetworkClient(), maxConcurrentRequests = 4,
                isBrowsing = ArtworkBrowsingState.process::isBrowsing)
            ImageLoader.Builder(context)
                .bitmapFactoryMaxParallelism(2)
                .components {
                    add(NetworkFetcher.Factory(networkClient = { artworkNetwork }))
                }.build()
        }
        model = ViewModelProvider(this)[AppViewModel::class.java]
        updates = ViewModelProvider(this)[UpdateViewModel::class.java]
        updates.checkOnStartup()
        consumeTvLoginIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(updates.state, model.state, playbackInFlight, PlaybackCompletionSignals.progressWriters) { update, app, playing, writers ->
                    update.automaticInstallPending && writers == 0 && !playing && app.playback == null &&
                        (!app.busy || app.homeLoading) && !app.spoilerBusy
                }.collect { ready -> if (ready) requestUpdateInstall(automatic = true) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackCompletionSignals.confirmations.collect { model.acceptPlaybackConfirmations(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackCompletionSignals.presentation.collect { model.acceptPlaybackCompletionFailures(it) }
            }
        }
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
                launchPlayback(spec)
            }
            UpdateDialog(updateState, onInstall = { requestUpdateInstall() },
                onCheck = { updates.check(manual = true) }, onDismiss = updates::dismiss)
            }
        }
    }

    private fun launchPlayback(spec: PlaybackSpec) {
        // Freeze the list's current direction before the landscape player covers it.
        // Returning then reveals the correct direction without a second rotation.
        playbackOrientation.beforePlayback()
        playbackInFlight.value = true
        try {
            playback.launch(Intent(this, NativePlayerActivity::class.java)
                .putExtra("spec", spec.toJson())
                .putExtra("server", model.state.value.server))
        } catch (_: RuntimeException) {
            playbackInFlight.value = false
            playbackOrientation.launchFailed()
            android.widget.Toast.makeText(this, "재생 화면을 열지 못했습니다. 다시 시도해 주세요.",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        ArtworkBrowsingState.process.started(artworkBrowsingOwner)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::model.isInitialized) consumeTvLoginIntent(intent)
    }

    private fun consumeTvLoginIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_VIEW) return
        val link = incoming.dataString?.let(::parseTvLoginLink)
        // A recreation or a later launcher visit must not replay an approval request.
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        link?.let(model::receiveTvLoginLink)
    }

    override fun onStop() {
        playbackOrientation.onStop(isChangingConfigurations)
        if (::model.isInitialized) model.cancelTvQrLogin()
        ArtworkBrowsingState.process.stopped(artworkBrowsingOwner)
        super.onStop()
    }

    override fun onDestroy() {
        playbackOrientation.onDestroy()
        ArtworkBrowsingState.process.stopped(artworkBrowsingOwner)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        playbackOrientation.onResume()
    }

    override fun onPause() {
        playbackOrientation.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("update_playback_in_flight", playbackInFlight.value)
        playbackOrientation.save(outState)
        super.onSaveInstanceState(outState)
    }

    private fun requestUpdateInstall(automatic: Boolean = false) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || playbackInFlight.value ||
            model.state.value.playback != null) return
        val attempt = updates.beginInstall(automatic) ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            try {
                installPermission.launch(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:$packageName")))
                updates.installerOpened(attempt)
            } catch (_: Exception) { updates.installError("기기 설정에서 이 앱의 설치 허용을 켠 뒤 다시 시도해 주세요.", attempt) }
        } else installReadyUpdate(attempt)
    }

    private fun installReadyUpdate(attempt: Long) {
        if (!updates.isInstallCurrent(attempt)) return
        lifecycleScope.launch {
            try {
                val file = updates.verifiedFile() ?: return@launch
                if (!updates.isInstallCurrent(attempt)) return@launch
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || playbackInFlight.value ||
                    PlaybackCompletionSignals.progressWriters.value > 0 ||
                    model.state.value.playback != null) {
                    updates.deferInstall(attempt)
                    return@launch
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.updates", file)
                val installer = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                updateInstaller.launch(installer)
                updates.installerOpened(attempt)
            } catch (cancel: CancellationException) {
                // A rotated/backgrounded preparation has not opened an installer yet.
                if (!isFinishing) updates.deferInstall(attempt)
                throw cancel
            } catch (_: Exception) {
                updates.installError("설치 화면을 열지 못했습니다. 기기의 앱 설치 설정을 확인해 주세요.", attempt)
            }
        }
    }
}
