package io.mirr.shynigsy.plex

import android.content.Intent
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.NetworkFetcher
import coil3.network.okhttp.asNetworkClient
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.ui.CinemaAppearanceTheme
import io.mirr.shynigsy.ui.AppHeader
import io.mirr.shynigsy.ui.tvOnlyFocus
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class PlexMainActivity : ComponentActivity() {
    private lateinit var model: PlexBrowseModel
    private val player = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val payload = if (result.resultCode == RESULT_OK) result.data else null
        val returned = (payload?.getStringExtra("completedEpisode") ?: payload?.getStringExtra("playedItem"))
            ?.let { runCatching { MediaItem.fromJson(it) }.getOrNull() }
        model.playerReturned(returned)
    }

    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SingletonImageLoader.setSafe { context ->
            val network = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
            ImageLoader.Builder(context).components {
                add(NetworkFetcher.Factory(networkClient = { network.asNetworkClient() }))
            }.build()
        }
        model = ViewModelProvider(this)[PlexBrowseModel::class.java]
        val name = applicationInfo.loadLabel(packageManager).toString()
        setContent { CinemaAppearanceTheme { PlexBrowser(name, model, ::play) } }
    }

    private fun play(item: MediaItem, fromStart: Boolean) {
        lifecycleScope.launch {
            val (server, spec) = model.playback(item, fromStart) ?: return@launch
            player.launch(Intent(this@PlexMainActivity, NativePlayerActivity::class.java)
                .putExtra("server", server).putExtra("spec", spec.toJson()))
        }
    }
}

@Composable
internal fun ConnectionScreen(appName: String, model: PlexBrowseModel) {
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
        }
    }
}
