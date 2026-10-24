package io.mirr.shynigsy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import java.util.Locale

private val appearancePresets = listOf(
    "극장" to DEFAULT_CINEMA_BACKGROUND,
    "남색" to 0xFF101C2C.toInt(),
    "차콜" to 0xFF282828.toInt(),
    "숲" to 0xFF10251F.toInt(),
    "와인" to 0xFF2C1820.toInt(),
    "밝게" to 0xFFF8FAFB.toInt(),
)

private fun colorHex(argb: Int) = String.format(Locale.ROOT, "%06X", argb and 0xFFFFFF)

@Composable
internal fun CinemaAppearanceSettings(initialBackground: Int, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var hex by rememberSaveable(initialBackground) { mutableStateOf(colorHex(initialBackground)) }
    val parsed = parseHomeBackgroundColor(hex)
    val preview = parsed ?: initialBackground
    val previewColors = cinemaColorScheme(Color(preview))
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .60f).dp.coerceAtLeast(180.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("화면 색상") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                .testTag("cinema-appearance-settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("배경색을 고르면 글자와 아이콘 색이 자동으로 맞춰집니다.")
                appearancePresets.chunked(3).forEach { presets ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        presets.forEach { (label, color) ->
                            val chosen = parsed == color
                            CompactMenuAction(onClick = { hex = colorHex(color) }, selected = chosen,
                                modifier = Modifier.weight(1f).semantics { selected = chosen },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(30.dp).background(Color(color), CircleShape)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                                        if (chosen) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp),
                                            tint = cinemaColorScheme(Color(color)).onBackground)
                                    }
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                Surface(color = Color(preview), contentColor = previewColors.onBackground,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().testTag("cinema-color-preview")) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Palette, null, tint = previewColors.primary)
                        Column {
                            Text("미리보기", color = previewColors.onBackground)
                            Text("글자 · 아이콘 자동 대비", color = previewColors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(value = hex, onValueChange = { hex = it.take(16) }, singleLine = true,
                    label = { Text("배경색 (#RRGGBB)") }, isError = parsed == null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    supportingText = { if (parsed == null) Text("6자리 색상 코드를 입력해 주세요.") },
                    modifier = Modifier.fillMaxWidth().testTag("cinema-background-hex"))
                listOf("빨강" to 16, "초록" to 8, "파랑" to 0).forEach { (label, shift) ->
                    val channel = (preview ushr shift) and 0xFF
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Slider(value = channel.toFloat(), onValueChange = { value ->
                            val next = (preview and (0xFF shl shift).inv()) or (value.roundToInt().coerceIn(0, 255) shl shift)
                            hex = colorHex(next)
                        }, valueRange = 0f..255f, steps = 254,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { contentDescription = "$label $channel" })
                        Text(channel.toString(), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = { CompactMenuAction(onClick = { parsed?.let(onApply) }, enabled = parsed != null,
            modifier = Modifier.testTag("cinema-appearance-apply")) { Text("적용") } },
        dismissButton = { CompactMenuAction(onClick = onDismiss) { Text("취소") } },
    )
}
