package io.mirr.shynigsy.plex

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.NetworkFetcher
import coil3.network.okhttp.asNetworkClient
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.PlaybackCompletionSignals
import io.mirr.shynigsy.ui.CinemaAppearanceTheme
import io.mirr.shynigsy.ui.AppHeader
import io.mirr.shynigsy.ui.tvOnlyFocus
import io.mirr.shynigsy.updates.ActivityUpdateInstaller
import io.mirr.shynigsy.updates.UpdateDialog
import io.mirr.shynigsy.updates.UpdateViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class PlexMainActivity : ComponentActivity() {
    private lateinit var model: PlexBrowseModel
    private lateinit var updates: UpdateViewModel
    private lateinit var installFlow: ActivityUpdateInstaller
    private var preparingPlayback = false
    private val playbackInFlight = MutableStateFlow(false)
    private val player = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        playbackInFlight.value = false
        val payload = if (result.resultCode == RESULT_OK) result.data else null
        val returned = (payload?.getStringExtra("completedEpisode") ?: payload?.getStringExtra("playedItem"))
            ?.let { runCatching { MediaItem.fromJson(it) }.getOrNull() }
        model.playerReturned(returned)
    }
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        installFlow.permissionReturned()
    }
    private val updateInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updates.installerReturned()
    }

    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackInFlight.value = savedInstanceState?.getBoolean("update_playback_in_flight") ?: false
        enableEdgeToEdge()
        SingletonImageLoader.setSafe { context ->
            val network = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
            ImageLoader.Builder(context).components {
                add(NetworkFetcher.Factory(networkClient = { network.asNetworkClient() }))
            }.build()
        }
        model = ViewModelProvider(this)[PlexBrowseModel::class.java]
        val link = ViewModelProvider(this)[PlexLinkModel::class.java]
        updates = ViewModelProvider(this)[UpdateViewModel::class.java]
        installFlow = ActivityUpdateInstaller(this, updates, { installPermission.launch(it) }, { updateInstaller.launch(it) }) {
            preparingPlayback || playbackInFlight.value
        }
        updates.checkOnStartup()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val browsing = snapshotFlow { !model.busy && !link.linking }
                combine(updates.state, browsing, playbackInFlight, PlaybackCompletionSignals.progressWriters) { state, idle, playing, writers ->
                    state.automaticInstallPending && idle && !playing && writers == 0
                }.collect { ready -> if (ready) installFlow.request(automatic = true) }
            }
        }
        val name = applicationInfo.loadLabel(packageManager).toString()
        setContent {
            CinemaAppearanceTheme {
                val updateState by updates.state.collectAsState()
                val check = { updates.check(manual = true) }
                PlexBrowser(name, model, ::play, onUpdate = check)
                UpdateDialog(updateState, onInstall = { installFlow.request() }, onCheck = check, onDismiss = updates::dismiss)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("update_playback_in_flight", playbackInFlight.value)
        super.onSaveInstanceState(outState)
    }

    private fun play(item: MediaItem, fromStart: Boolean) {
        if (preparingPlayback || playbackInFlight.value) return
        lifecycleScope.launch {
            val playback = try {
                preparingPlayback = true
                model.playback(item, fromStart)
            } finally {
                preparingPlayback = false
            } ?: return@launch
            val (server, spec) = playback
            playbackInFlight.value = true
            try {
                player.launch(Intent(this@PlexMainActivity, NativePlayerActivity::class.java)
                    .putExtra("server", server).putExtra("spec", spec.toJson()))
            } catch (_: RuntimeException) {
                playbackInFlight.value = false
                Toast.makeText(this@PlexMainActivity, "재생 화면을 열지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
internal fun ConnectionScreen(appName: String, model: PlexBrowseModel, onUpdate: () -> Unit) {
    val switching = model.switchingServer
    var address by rememberSaveable(model.savedServer, switching) {
        mutableStateOf(if (switching) "" else model.savedServer)
    }
    BackHandler(enabled = switching) { model.cancelSwitch() }
    var token by remember { mutableStateOf("") }
    var showToken by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally) {
        AppHeader(loggedIn = false, title = appName)
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (switching) "Plex 서버 변경" else "Plex 서버 연결", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Plex 서버 주소") }, placeholder = { Text("http://192.168.1.10:32400") },
                supportingText = { Text("비워 두면 계정에 등록된 서버를 찾습니다.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            PlexAccountLink(model.deviceId, !model.busy, address, switching, model::connect)
            if (switching) {
                OutlinedButton(onClick = model::cancelSwitch, enabled = !model.busy, modifier = Modifier.tvOnlyFocus()) {
                    Text("기존 서버로 돌아가기")
                }
            }
            if (model.savedServer.isNotBlank()) {
                OutlinedButton(onClick = model::reconnect, enabled = !model.busy, modifier = Modifier.tvOnlyFocus()) {
                    Text("이전 서버에 다시 연결")
                }
            }
            OutlinedButton(onClick = { showToken = !showToken }, modifier = Modifier.tvOnlyFocus()) {
                Text(if (showToken) "토큰 입력 닫기" else "서버 주소와 토큰으로 연결")
            }
            if (showToken) {
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Plex 토큰 (X-Plex-Token)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Button(onClick = { model.connect(address, token) },
                    enabled = !model.busy && address.isNotBlank() && token.isNotBlank(),
                    modifier = Modifier.tvOnlyFocus()) { Text("서버에 연결") }
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onUpdate, modifier = Modifier.tvOnlyFocus()) { Text("업데이트 확인") }
        }
    }
}
