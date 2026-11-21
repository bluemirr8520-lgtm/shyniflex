package io.mirr.shynigsy.plex

import android.app.Application
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.mirr.shynigsy.ui.tvOnlyFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val AUTH_TAB_REQUEST = 4815

internal class PlexLinkModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val grants = PlexAccountStore(application)
    private val closeTab = Channel<Unit>(Channel.CONFLATED)
    val claimed = closeTab.receiveAsFlow()
    var linking by mutableStateOf(false)
        private set
    var pin by mutableStateOf<LinkCode?>(null)
        private set
    var signInUrl by mutableStateOf<String?>(null)
        private set
    var openBrowser by mutableStateOf(false)
        private set
    var servers by mutableStateOf(emptyList<PlexServerResource>())
        private set
    var autoServer by mutableStateOf<PlexServerResource?>(null)
        private set
    var hasAccount by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
    private var job: Job? = null
    private var restored = false
    private var choosingServer = false

    fun browserOpened() { openBrowser = false }
    fun tabStarted() { saved["hostingLoginTab"] = true }
    fun connecting() { autoServer = null }

    fun leave() {
        job?.cancel()
        job = null
        clearPin()
        saved.remove<Boolean>("hostingLoginTab")
        servers = emptyList()
        autoServer = null
        hasAccount = false
        restored = false
        choosingServer = false
        message = ""
        linking = false
    }

    fun forget() {
        leave()
        restored = true
        runLink { withContext(Dispatchers.IO) { grants.clear() } }
    }

    fun restore(deviceId: String, preferred: String, chooseServer: Boolean = false) {
        if (restored) return
        choosingServer = chooseServer
        restored = true
        val account = PlexAccount(deviceId)
        val pending = saved.get<Bundle>("pendingPin")
        runLink {
            val grant = withContext(Dispatchers.IO) { grants.load() }
            if (grant != null) {
                hasAccount = true
                discover(account, grant.token, preferred, grant.userName)
            } else if (pending != null) {
                authorize(account, pending.getBoolean("browser"), preferred, pending)
            }
        }
    }

    fun start(account: PlexAccount, browser: Boolean, preferred: String) {
        if (linking) return
        restored = true
        runLink { authorize(account, browser, preferred, null) }
    }

    fun retry(deviceId: String, preferred: String) {
        restored = false
        restore(deviceId, preferred, choosingServer)
    }

    private fun runLink(action: suspend () -> Unit) {
        if (linking) return
        linking = true
        message = ""
        job = viewModelScope.launch {
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: PlexAccountException) {
                if (failure.status == 401 || failure.status == 403) {
                    withContext(Dispatchers.IO) { grants.clear() }
                    hasAccount = false
                }
                message = failure.message.orEmpty()
            } catch (_: IOException) {
                message = if (hasAccount) "서버 목록을 받지 못했습니다. 연결을 확인한 뒤 다시 불러오세요."
                          else "Plex 계정에 연결하지 못했습니다. 네트워크를 확인해 주세요."
            } catch (_: Exception) {
                message = "Plex 로그인 정보를 처리하지 못했습니다. 다시 시도해 주세요."
            } finally {
                if (currentCoroutineContext().isActive) {
                    clearPin()
                    linking = false
                }
            }
        }
    }

    private fun clearPin() {
        pin = null
        signInUrl = null
        openBrowser = false
        saved.remove<Bundle>("pendingPin")
    }

    private suspend fun authorize(account: PlexAccount, browser: Boolean, preferred: String, pending: Bundle?) {
        val code = if (pending == null) account.createCode(strong = browser)
            else LinkCode(pending.getLong("id"), requireNotNull(pending.getString("code")), pending.getLong("ttl"))
        val deadline = pending?.getLong("deadline") ?: (SystemClock.elapsedRealtime() + code.lifetimeSeconds * 1000L)
        pin = code
        saved["pendingPin"] = Bundle().apply {
            putLong("id", code.id)
            putString("code", code.code)
            putLong("ttl", code.lifetimeSeconds)
            putLong("deadline", deadline)
            putBoolean("browser", browser)
        }
        if (browser && SystemClock.elapsedRealtime() < deadline) {
            signInUrl = account.signInUrl(code)
            openBrowser = pending == null
        }
        while (SystemClock.elapsedRealtime() < deadline) {
            val token = try {
                account.tokenFor(code)
            } catch (e: PlexAccountException) {
                if (e.status !in 500..599) throw e
                null
            } catch (_: IOException) {
                // Moving between Wi-Fi and mobile data during the browser handoff is recoverable.
                null
            }
            if (token != null) {
                withContext(Dispatchers.IO) { grants.save(token) }
                hasAccount = true
                clearPin()
                if (saved.remove<Boolean>("hostingLoginTab") == true) closeTab.trySend(Unit)
                discover(account, token, preferred, null)
                return
            }
            delay(1000)
        }
        message = "연결 코드가 만료되었습니다. 새로 로그인해 주세요."
    }

    private suspend fun discover(account: PlexAccount, token: String, preferred: String, previousId: String?) {
        servers = account.servers(token, preferred)
        autoServer = if (choosingServer) null else servers.singleOrNull { it.matches(preferred) }
            ?: servers.firstOrNull { it.id == previousId } ?: servers.singleOrNull()
        message = when {
            servers.isEmpty() -> "이 계정에서 연결할 수 있는 Plex 서버를 찾지 못했습니다."
            !choosingServer && preferred.isNotBlank() && autoServer == null -> "입력한 주소와 일치하는 서버가 없습니다. 계정의 서버를 선택해 주세요."
            else -> ""
        }
    }
}

@Composable
internal fun PlexAccountLink(deviceId: String, enabled: Boolean, preferredServer: String,
                             choosingServer: Boolean,
                             onConnect: (PlexServerResource, String) -> Unit) {
    val activity = LocalActivity.current
    val link: PlexLinkModel = viewModel()
    val openPlex: (Uri) -> Unit = { uri ->
        try {
            // A Custom Tab runs in our task. Finishing this specific result request returns to PLEXMINI.
            val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                .putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
            activity?.let { it.startActivityForResult(intent, AUTH_TAB_REQUEST); link.tabStarted() }
        } catch (_: ActivityNotFoundException) {
            link.message = "브라우저를 열 수 없습니다. TV 연결 코드를 사용해 주세요."
        }
    }
    LaunchedEffect(link, enabled, choosingServer) {
        if (enabled) link.restore(deviceId, preferredServer, choosingServer)
    }
    LaunchedEffect(link.openBrowser) {
        if (link.openBrowser) {
            val url = link.signInUrl
            link.browserOpened()
            if (url != null) openPlex(Uri.parse(url))
        }
    }
    // Collect even while this Activity is underneath the login tab.
    LaunchedEffect(link, activity) {
        link.claimed.collect {
            if (activity == null) return@collect
            activity.finishActivity(AUTH_TAB_REQUEST)
            // Samsung Internet finishes its result-bearing launcher and creates a separate tab above us.
            // Return to the existing instance in our own visible task even when that launcher is already gone.
            val manager = activity.getSystemService(ActivityManager::class.java)
            val visibleTask = if (Build.VERSION.SDK_INT >= 32) {
                manager?.appTasks?.any { task ->
                    val info = task.taskInfo
                    info.taskId == activity.taskId && info.isVisible
                } == true
            } else true
            if (visibleTask && !activity.hasWindowFocus()) {
                activity.startActivity(Intent(activity, activity.javaClass).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
    }
    LaunchedEffect(link.autoServer, enabled) {
        val server = link.autoServer
        if (server != null && enabled && !choosingServer) {
            link.connecting()
            onConnect(server, preferredServer)
        }
    }
    DisposableEffect(link, activity) {
        onDispose { if (activity?.isChangingConfigurations != true) link.leave() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val code = link.pin?.code
        when {
            link.servers.isNotEmpty() -> {
                Text(if (enabled) "연결할 서버 선택" else "서버에 연결하는 중…", style = MaterialTheme.typography.titleMedium)
                link.servers.forEach { server ->
                    val duplicateName = link.servers.count { it.title == server.title } > 1
                    OutlinedButton(enabled = enabled, modifier = Modifier.fillMaxWidth().tvOnlyFocus(),
                        onClick = { link.connecting(); onConnect(server, preferredServer) }) {
                        Text(if (duplicateName) "${server.title} · ${Uri.parse(server.urls.first()).host}" else server.title)
                    }
                }
            }
            link.linking -> {
                val url = link.signInUrl
                if (url != null) {
                    Text("Plex 로그인을 기다리는 중…")
                    TextButton(modifier = Modifier.tvOnlyFocus(), onClick = { openPlex(Uri.parse(url)) }) { Text("로그인 화면 다시 열기") }
                } else if (code == null) {
                    Text(if (link.hasAccount) "Plex 서버를 찾는 중…" else "Plex 계정에 연결하는 중…")
                } else {
                    Text("휴대폰이나 PC에서 plex.tv/link를 열고 코드를 입력하세요.")
                    Text(code, style = MaterialTheme.typography.headlineLarge)
                    TextButton(modifier = Modifier.tvOnlyFocus(), onClick = {
                        openPlex(Uri.parse("https://plex.tv/link/").buildUpon().appendQueryParameter("pin", code).build())
                    }) { Text("이 기기에서 Plex 열기") }
                }
                if (!choosingServer) TextButton(onClick = link::forget, modifier = Modifier.tvOnlyFocus()) { Text("취소") }
            }
            link.hasAccount -> {
                Button(enabled = enabled, modifier = Modifier.tvOnlyFocus(),
                    onClick = { link.retry(deviceId, preferredServer) }) { Text("서버 다시 찾기") }
            }
            else -> {
                Button(enabled = enabled, modifier = Modifier.tvOnlyFocus(), onClick = {
                    link.start(PlexAccount(deviceId), browser = true, preferredServer)
                }) { Text(if (preferredServer.isBlank()) "아이디·비밀번호로 로그인" else "아이디·비밀번호로 서버 연결") }
                TextButton(enabled = enabled, modifier = Modifier.tvOnlyFocus(), onClick = {
                    link.start(PlexAccount(deviceId), browser = false, preferredServer)
                }) { Text("TV 연결 코드 사용") }
            }
        }
        if (link.hasAccount && !link.linking && !choosingServer) {
            TextButton(enabled = enabled, onClick = link::forget, modifier = Modifier.tvOnlyFocus()) { Text("다른 계정 사용") }
        }
        if (link.message.isNotEmpty()) Text(link.message)
    }
}
