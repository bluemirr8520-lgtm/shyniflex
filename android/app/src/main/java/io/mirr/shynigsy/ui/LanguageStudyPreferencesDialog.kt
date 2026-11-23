package io.mirr.shynigsy.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Home settings share the playback preferences, so the next player uses them immediately. */
@Composable
internal fun LanguageStudyPreferencesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
    }
    var autoOpen by remember { mutableStateOf(settings.getBoolean("language_study_auto_open", false)) }
    var secondaryTop by remember { mutableStateOf(settings.getBoolean("study_secondary_top", true)) }
    var speed by remember {
        mutableStateOf(settings.getFloat("playback_speed", 1f).takeIf { it.isFinite() && it > 0f } ?: 1f)
    }
    var speedMenuOpen by remember { mutableStateOf(false) }
    val limit = LocalConfiguration.current.screenHeightDp.dp * .60f
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("language-study-preferences"),
        title = { Text("어학 학습") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = limit).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("문장·A–B 반복과 원문·번역 자막 선택은 영상 재생 중 재생 설정 → 어학 학습에서 사용할 수 있습니다. ‘어휘·문법’에서는 반복 구간이나 현재 문장의 영어 표현을 한국어로 설명합니다. 기기 내 기본 사전·문법 규칙을 사용하며 문맥에 따라 뜻이 다를 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StudyPreferenceToggle("재생 시작 시 어학 메뉴 자동 열기", autoOpen, "study-preference-auto-open") {
                    autoOpen = it
                    settings.edit().putBoolean("language_study_auto_open", it).apply()
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("기본 배속", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Column {
                        TextButton(onClick = { speedMenuOpen = true }, modifier = Modifier.testTag("study-preference-speed")) {
                            Text(studySpeedLabel(speed))
                        }
                        DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                            listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { choice ->
                                DropdownMenuItem(text = { Text(studySpeedLabel(choice)) }, onClick = {
                                    speed = choice
                                    settings.edit().putFloat("playback_speed", choice).apply()
                                    speedMenuOpen = false
                                })
                            }
                        }
                    }
                }
                StudyPreferenceToggle("번역 자막을 화면 위쪽에 표시", secondaryTop, "study-preference-secondary-top") {
                    secondaryTop = it
                    settings.edit().putBoolean("study_secondary_top", it).apply()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("study-preference-close")) { Text("닫기") } },
    )
}

@Composable
private fun StudyPreferenceToggle(label: String, checked: Boolean, tag: String, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun studySpeedLabel(speed: Float) = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"
