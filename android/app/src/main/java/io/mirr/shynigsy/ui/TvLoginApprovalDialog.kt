package io.mirr.shynigsy.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mirr.shynigsy.TvLoginApprovalState
import io.mirr.shynigsy.data.sameTvLoginServer
import kotlinx.coroutines.delay

/** The user deliberately approves only with an account belonging to this exact server origin. */
@Composable
internal fun TvLoginApprovalDialog(
    state: TvLoginApprovalState,
    accountServer: String,
    userName: String,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val expired = tvApprovalExpired(state.expiresAtMs)
    val sameServer = sameTvLoginServer(accountServer, state.link.server)
    val mayApprove = sameServer && !state.busy && !state.approved && !expired
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("tv-login-approval"),
        title = { Text("TV 연결 승인") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("tv-login-approval-details"),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("연결할 서버", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.link.server, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("tv-login-approval-server"))
                Text("로그인 계정: ${userName.ifBlank { "사용자" }}", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("tv-login-approval-account"))
                Text(when {
                    state.approved -> "TV 연결을 승인했습니다. TV 화면에서 로그인을 확인하세요."
                    !sameServer -> "현재 로그인한 서버와 TV의 서버가 다릅니다. TV와 같은 서버에 로그인한 계정에서 승인하세요."
                    expired -> "연결 요청이 만료되었습니다. TV에서 새 QR 코드를 만들어 주세요."
                    state.busy -> "TV 연결을 승인하고 있습니다."
                    else -> "이 계정으로 TV에 로그인하려면 아래 버튼을 눌러 승인하세요."
                }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("tv-login-approval-status"))
                if (!sameServer && !state.approved) Text("현재 서버: $accountServer",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.takeIf { !state.approved }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("tv-login-approval-error"))
                }
            }
        },
        confirmButton = {
            if (state.approved) TextButton(onClick = onDismiss, modifier = Modifier.testTag("tv-login-approval-close")) {
                Text("닫기")
            } else TextButton(onClick = { if (mayApprove) onApprove() }, enabled = mayApprove,
                modifier = Modifier.testTag("tv-login-approve")) { Text("TV 연결 승인") }
        },
        dismissButton = {
            if (!state.approved) TextButton(onClick = onDismiss, modifier = Modifier.testTag("tv-login-approval-cancel")) {
                Text("취소")
            }
        })
}

/** A scanned link is a pending request, never an instruction to change account or auto-approve. */
@Composable
internal fun TvLoginPendingHint(state: TvLoginApprovalState, onCancel: () -> Unit) {
    val expired = tvApprovalExpired(state.expiresAtMs)
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("tv-login-pending-hint")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("TV 로그인 연결", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(state.link.server, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("tv-login-pending-server"))
                Text(if (expired) "요청이 만료되었습니다. TV에서 새 QR 코드를 만들어 주세요."
                    else "로그인 후 TV 연결을 승인하세요.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("tv-login-pending-cancel")) { Text("취소") }
        }
    }
}

@Composable
private fun tvApprovalExpired(deadline: Long): Boolean {
    val expired by produceState(SystemClock.elapsedRealtime() >= deadline, deadline) {
        value = SystemClock.elapsedRealtime() >= deadline
        while (!value) {
            delay((deadline - SystemClock.elapsedRealtime()).coerceIn(1L, 1_000L))
            value = SystemClock.elapsedRealtime() >= deadline
        }
    }
    return expired
}
