package io.mirr.shynigsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import io.mirr.shynigsy.TvQrLoginPhase
import io.mirr.shynigsy.TvQrLoginState

/** TV form only; a QR is created by an explicit remote action after a server is entered. */
@Composable
internal fun TvQrLoginScreen(
    initialServer: String,
    initialUser: String,
    busy: Boolean,
    error: String?,
    qrState: TvQrLoginState,
    onStartQr: (String) -> Unit,
    onCancelQr: () -> Unit,
    onLogin: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var server by rememberSaveable(initialServer) { mutableStateOf(initialServer) }
    var user by rememberSaveable(initialUser) { mutableStateOf(initialUser) }
    var qrMode by rememberSaveable { mutableStateOf(true) }
    // Passwords never enter saved instance state, callbacks retained by a ViewModel, or the QR.
    var password by remember(initialServer) { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val cancel by rememberUpdatedState(onCancelQr)
    val focus = LocalFocusManager.current
    val serverFocus = remember { FocusRequester() }
    var serverPlaced by remember { mutableStateOf(false) }
    var placedInitialFocus by remember { mutableStateOf(false) }
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { password = ""; cancel() }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); cancel() }
    }
    LaunchedEffect(busy, serverPlaced) {
        if (!busy && !placedInitialFocus && serverPlaced) {
            withFrameNanos { }
            serverFocus.requestFocus()
            placedInitialFocus = true
        }
    }
    fun chooseMode(wantedQr: Boolean) {
        if (qrMode != wantedQr) {
            cancel()
            password = ""
            passwordVisible = false
            qrMode = wantedQr
        }
    }
    fun startQr() {
        if (!busy && server.isNotBlank() && qrState.phase != TvQrLoginPhase.CREATING) {
            password = ""
            focus.clearFocus()
            onStartQr(server.trim())
        }
    }
    fun submitLogin() {
        if (!busy && server.isNotBlank() && user.isNotBlank() && password.isNotEmpty()) {
            cancel()
            focus.clearFocus()
            onLogin(server.trim(), user.trim(), password)
        }
    }

    val heading: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Shyni에 연결하세요", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactMenuAction(onClick = { chooseMode(true) }, selected = qrMode,
                    modifier = Modifier.testTag("tv-login-mode-qr")) { Text("QR로 로그인") }
                CompactMenuAction(onClick = { chooseMode(false) }, selected = !qrMode,
                    modifier = Modifier.testTag("tv-login-mode-manual")) { Text("직접 입력") }
            }
        }
    }
    val serverField: @Composable () -> Unit = {
        DisposableEffect(Unit) { onDispose { serverPlaced = false } }
        OutlinedTextField(server, {
            if (it != server) { cancel(); password = ""; server = it }
        }, label = { Text("서버 주소") }, enabled = !busy, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(serverFocus).tvOnlyFocus()
                .onGloballyPositioned { serverPlaced = true }.testTag("login_server"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri,
                imeAction = if (qrMode) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onDone = { if (qrMode) startQr() }))
    }
    val userField: @Composable () -> Unit = {
        OutlinedTextField(user, { user = it }, label = { Text("아이디") }, enabled = !busy, singleLine = true,
            modifier = Modifier.fillMaxWidth().tvOnlyFocus().testTag("tv-login-user"),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
    }
    val passwordField: @Composable () -> Unit = {
        OutlinedTextField(password, { password = it }, label = { Text("비밀번호") }, enabled = !busy, singleLine = true,
            modifier = Modifier.fillMaxWidth().tvOnlyFocus().testTag("tv-login-password"),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.tvOnlyFocus()) {
                    Icon(if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        if (passwordVisible) "비밀번호 숨기기" else "비밀번호 표시")
                }
            }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitLogin() }))
    }
    val manualSubmit: @Composable () -> Unit = {
        Button(onClick = { submitLogin() }, enabled = !busy && server.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).tvOnlyFocus().testTag("tv-login-manual-submit")) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("로그인")
        }
    }
    val qrActions: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("서버 주소를 입력한 뒤 휴대폰으로 연결하세요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { startQr() },
                enabled = !busy && server.isNotBlank() && qrState.phase != TvQrLoginPhase.CREATING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).tvOnlyFocus().testTag("tv-login-qr-start")) {
                Text(if (qrState.phase == TvQrLoginPhase.IDLE) "QR 코드 만들기" else "새 QR 코드 만들기")
            }
            if (qrState.phase == TvQrLoginPhase.WAITING || qrState.phase == TvQrLoginPhase.CREATING) {
                CompactMenuAction(onClick = { cancel() }, modifier = Modifier.testTag("tv-login-qr-cancel")) { Text("QR 로그인 취소") }
            }
        }
    }
    val errorMessage: @Composable () -> Unit = {
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("tv-login-form-error"))
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag("tv-qr-login-screen")) {
        val fontScale = LocalDensity.current.fontScale
        // Include the header/mode row and the two-line instructions when fitting a short TV window.
        val availableQrSize = maxHeight - 138.dp - 80.dp * fontScale
        val wide = maxWidth >= 760.dp && maxHeight >= 360.dp && fontScale <= 1.35f && availableQrSize >= 128.dp
        val wideQrSize = availableQrSize.coerceIn(128.dp, 216.dp)
        if (wide) Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            heading()
            if (qrMode) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    serverField(); qrActions(); errorMessage()
                }
                TvQrLoginPresentation(qrState, Modifier.weight(1f).verticalScroll(rememberScrollState()), wideQrSize)
            } else Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                serverField(); userField(); passwordField(); errorMessage(); manualSubmit()
            }
        } else LazyColumn(Modifier.fillMaxSize().testTag("tv-login-scroll"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "heading") { heading() }
            item(key = "server") { serverField() }
            if (qrMode) {
                item(key = "qr-actions") { qrActions() }
                item(key = "qr-code") { TvQrLoginPresentation(qrState, Modifier.fillMaxWidth()) }
                item(key = "qr-error") { errorMessage() }
            } else {
                item(key = "username") { userField() }
                item(key = "password") { passwordField() }
                item(key = "manual-error") { errorMessage() }
                item(key = "submit") { manualSubmit() }
            }
        }
    }
}

@Composable
private fun TvQrLoginPresentation(state: TvQrLoginState, modifier: Modifier = Modifier, qrSize: Dp = 216.dp) {
    Column(modifier.testTag("tv-login-qr-panel"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state.phase) {
            TvQrLoginPhase.WAITING -> {
                state.qrUrl?.let { TvLoginQrCode(it, Modifier.size(qrSize)) }
                Text("휴대폰 shyniflex 앱으로 QR 코드를 열어\n로그인한 뒤 TV 연결을 승인하세요.",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text("남은 시간 ${state.remainingSeconds.coerceAtLeast(0) / 60}분 ${state.remainingSeconds.coerceAtLeast(0) % 60}초",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("tv-login-qr-countdown"))
            }
            TvQrLoginPhase.CREATING -> {
                Box(Modifier.size(qrSize), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
                }
                Text("QR 코드를 준비하고 있습니다.", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("tv-login-qr-creating"))
            }
            else -> {
                Box(Modifier.size(qrSize).background(MaterialTheme.colorScheme.surfaceVariant).padding(18.dp),
                    contentAlignment = Alignment.Center) {
                    Text(when (state.phase) {
                        TvQrLoginPhase.EXPIRED -> "QR 코드가 만료되었습니다.\n새로 만들어 주세요."
                        TvQrLoginPhase.ERROR -> state.message ?: "연결하지 못했습니다.\n새로 만들거나 직접 입력해 주세요."
                        else -> "서버 주소를 입력한 뒤\nQR 코드를 만들어 주세요."
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("tv-login-qr-status"))
                }
            }
        }
    }
}
