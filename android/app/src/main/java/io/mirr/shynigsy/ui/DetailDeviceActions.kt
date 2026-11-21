package io.mirr.shynigsy.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.mirr.shynigsy.data.MediaItem

@Composable
internal fun rememberDetailCastAction(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        val intent = Intent(Settings.ACTION_CAST_SETTINGS)
        if (intent.resolveActivity(context.packageManager) == null) null
        else ({
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "화면 전송 설정을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        })
    }
}

@Composable
internal fun DetailRatingDialog(item: MediaItem, scope: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("detail_ratings", Context.MODE_PRIVATE)
    }
    val key = "$scope/${compactMediaIdentity(item)}"
    val saved = remember(key) { preferences.getInt(key, 0) }
    var score by rememberSaveable(key) { mutableIntStateOf(saved) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("내 평점") },
        text = {
            Column {
                Row {
                    for (value in 1..5) {
                        IconButton(onClick = { score = value }, modifier = Modifier.weight(1f)
                            .semantics { selected = value == score }) {
                            Icon(if (value <= score) Icons.Rounded.Star else Icons.Outlined.StarOutline,
                                contentDescription = "${value}점", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Text("이 기기에 저장됩니다.", style = MaterialTheme.typography.bodySmall)
                if (saved > 0) TextButton(onClick = {
                    preferences.edit().remove(key).apply()
                    onDismiss()
                }) { Text("평점 지우기") }
            }
        },
        confirmButton = {
            TextButton(enabled = score in 1..5, onClick = {
                preferences.edit().putInt(key, score).apply()
                onDismiss()
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
