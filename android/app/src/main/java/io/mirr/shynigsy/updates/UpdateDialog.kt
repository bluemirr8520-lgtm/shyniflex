package io.mirr.shynigsy.updates

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.mirr.shynigsy.isTelevision
import io.mirr.shynigsy.ui.tvOnlyFocus

@Composable
internal fun UpdateDialog(state: UpdateState, onInstall: () -> Unit, onCheck: () -> Unit, onDismiss: () -> Unit) {
    if (!state.visible) return
    val television = LocalContext.current.isTelevision()
    val cancel = remember { FocusRequester() }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 360.dp).fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("업데이트", style = MaterialTheme.typography.titleLarge)
                state.version?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                if (state.phase == UpdatePhase.CHECKING) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.phase == UpdatePhase.DOWNLOADING) {
                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${state.percent}%", style = MaterialTheme.typography.labelMedium)
                }
                if (state.phase == UpdatePhase.READY) TextButton(onInstall, Modifier.fillMaxWidth().tvOnlyFocus()) { Text("설치") }
                if (state.phase in setOf(UpdatePhase.CURRENT, UpdatePhase.ERROR))
                    TextButton(onCheck, Modifier.fillMaxWidth().tvOnlyFocus()) { Text("다시 확인") }
                TextButton(onDismiss, Modifier.fillMaxWidth().focusRequester(cancel).tvOnlyFocus()) {
                    Text(if (state.phase == UpdatePhase.READY) "나중에" else "닫기")
                }
            }
        }
        LaunchedEffect(Unit) { if (television) cancel.requestFocus() }
    }
    }
}
