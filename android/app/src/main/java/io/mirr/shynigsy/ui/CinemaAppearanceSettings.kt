package io.mirr.shynigsy.ui

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mirr.shynigsy.isTelevision
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
internal fun CinemaAppearanceSettings(initialBackground: Int, onDismiss: () -> Unit, onApply: (Int) -> Unit,
    television: Boolean = LocalContext.current.isTelevision()) {
    val styleController = LocalCinemaStyleController.current
    val initialStyle = styleController.style
    var selectedStyleKey by rememberSaveable { mutableStateOf(initialStyle.key) }
    val selectedStyle = CinemaStyle.fromKey(selectedStyleKey)
    var drafts by rememberSaveable { mutableStateOf(HashMap(CinemaStyle.entries.associate {
        it.key to colorHex(if (it == initialStyle) initialBackground else styleController.background(it))
    })) }
    var hex by rememberSaveable(initialBackground) { mutableStateOf(colorHex(initialBackground)) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorValue by rememberSaveable { mutableStateOf("") }
    var restoreEditorFocus by remember { mutableStateOf(false) }
    val presetFocus = remember { List(appearancePresets.size) { FocusRequester() } }
    val styleFocus = remember { List(CinemaStyle.entries.size) { FocusRequester() } }
    val resetFocus = remember { FocusRequester() }
    val hexFocus = remember { FocusRequester() }
    val redFocus = remember { FocusRequester() }
    val greenFocus = remember { FocusRequester() }
    val blueFocus = remember { FocusRequester() }
    val applyFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val parsed = parseHomeBackgroundColor(hex)
    val preview = parsed ?: if (selectedStyle == initialStyle) initialBackground else styleController.background(selectedStyle)
    val previewColors = cinemaColorScheme(Color(preview), selectedStyle)
    val screenHeight = LocalConfiguration.current.screenHeightDp
    // IME insets may update screenHeightDp; keep the dialog's layout budget stable until it closes.
    val maxHeight = remember { (screenHeight * .60f).dp.coerceAtLeast(180.dp) }
    LaunchedEffect(television) {
        if (television) {
            withFrameNanos { }
            styleFocus[selectedStyle.ordinal].requestFocus()
        }
    }
    LaunchedEffect(editorOpen, restoreEditorFocus) {
        if (!editorOpen && restoreEditorFocus) {
            withFrameNanos { }
            hexFocus.requestFocus()
            restoreEditorFocus = false
        }
    }
    fun chooseStyle(style: CinemaStyle) {
        drafts = HashMap(drafts).apply { put(selectedStyleKey, hex) }
        selectedStyleKey = style.key
        hex = drafts[style.key] ?: colorHex(styleController.background(style))
    }
    fun changeChannel(shift: Int, value: Int) {
        val current = parseHomeBackgroundColor(hex) ?: preview
        hex = colorHex((current and (0xFF shl shift).inv()) or (value.coerceIn(0, 255) shl shift))
    }
    fun adjustChannel(shift: Int, delta: Int) {
        val current = parseHomeBackgroundColor(hex) ?: preview
        changeChannel(shift, ((current ushr shift) and 0xFF) + delta)
    }
    fun closeEditor(commit: Boolean) {
        if (commit) parseHomeBackgroundColor(editorValue)?.let { hex = colorHex(it) }
        editorOpen = false
        restoreEditorFocus = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("화면 스타일 · 색상") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                .testTag("cinema-appearance-settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("스타일별 색상을 기억합니다. 적용을 누르면 저장되고, 취소하면 변경하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall)
                CinemaStyle.entries.forEachIndexed { index, style ->
                    val chosen = selectedStyle == style
                    val color = if (chosen) preview else parseHomeBackgroundColor(drafts[style.key].orEmpty())
                        ?: styleController.background(style)
                    CompactMenuAction(onClick = { chooseStyle(style) }, selected = chosen,
                        modifier = Modifier.fillMaxWidth().focusRequester(styleFocus[index])
                            .focusProperties {
                                if (television) {
                                    up = if (index == 0) cancelFocus else styleFocus[index - 1]
                                    down = if (index == CinemaStyle.entries.lastIndex) resetFocus else styleFocus[index + 1]
                                }
                            }.testTag("cinema-style-${style.key}").semantics { selected = chosen },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
                        CinemaStylePreview(style, color)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(style.label, style = MaterialTheme.typography.labelLarge)
                            Text(style.description, style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (chosen) CinemaIcon(CinemaGlyph.Check, "선택됨", Modifier.size(18.dp))
                    }
                }
                CompactMenuAction(onClick = { hex = colorHex(selectedStyle.defaultBackgroundArgb) },
                    modifier = Modifier.fillMaxWidth().focusRequester(resetFocus)
                        .focusProperties { if (television) { up = styleFocus.last(); down = presetFocus.first() } }
                        .testTag("cinema-style-reset")) { Text("${selectedStyle.label} 기본색 복원") }
                Text("배경색에 맞춰 글자와 아이콘 대비가 자동으로 조정됩니다.", style = MaterialTheme.typography.bodySmall)
                appearancePresets.chunked(3).forEachIndexed { row, presets ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        presets.forEachIndexed { column, (label, color) ->
                            val chosen = parsed == color
                            val index = row * 3 + column
                            CompactMenuAction(onClick = { hex = colorHex(color) }, selected = chosen,
                                modifier = Modifier.weight(1f).focusRequester(presetFocus[index])
                                    .focusProperties {
                                        if (television) {
                                            if (row == 0) up = resetFocus
                                            if (row == 1) down = hexFocus
                                        }
                                    }.semantics { selected = chosen },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(30.dp).background(Color(color), CircleShape)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                                        if (chosen) CinemaIcon(CinemaGlyph.Check, null, Modifier.size(18.dp),
                                            tint = cinemaColorScheme(Color(color), selectedStyle).onBackground)
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
                        Icon(remember(selectedStyle) { cinemaIconVector(CinemaGlyph.Palette, selectedStyle) },
                            null, tint = previewColors.primary)
                        Column {
                            Text("${selectedStyle.label} 미리보기", color = previewColors.onBackground)
                            Text("글자 · 아이콘 자동 대비", color = previewColors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (television) CompactMenuAction(onClick = {
                    editorValue = hex
                    editorOpen = true
                }, modifier = Modifier.fillMaxWidth().focusRequester(hexFocus)
                    .focusProperties { up = presetFocus.last(); down = redFocus }
                    .testTag("cinema-background-edit")) { Text("색상 코드 입력 · #${colorHex(preview)}") }
                else CinemaHexField(hex, { hex = it }, Modifier.fillMaxWidth().focusRequester(hexFocus)) { }
                listOf(Triple("빨강", 16, "red"), Triple("초록", 8, "green"), Triple("파랑", 0, "blue"))
                    .forEachIndexed { index, (label, shift, tag) ->
                        val channel = (preview ushr shift) and 0xFF
                        if (television) {
                            val focus = listOf(redFocus, greenFocus, blueFocus)[index]
                            TvColorChannel(label, tag, channel, focus,
                                above = listOf(hexFocus, redFocus, greenFocus)[index],
                                below = listOf(greenFocus, blueFocus, applyFocus)[index],
                                onAdjust = { adjustChannel(shift, it) }, onValue = { changeChannel(shift, it) },
                                onConfirm = { applyFocus.requestFocus() })
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                                Slider(value = channel.toFloat(), onValueChange = { value ->
                                    changeChannel(shift, value.roundToInt())
                                }, valueRange = 0f..255f, steps = 254,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                        .semantics { contentDescription = "$label $channel" })
                                Text(channel.toString(), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
            }
        },
        confirmButton = { CompactMenuAction(onClick = {
            parsed?.let { color ->
                val colors = CinemaStyle.entries.associateWith { style ->
                    if (style == selectedStyle) color else parseHomeBackgroundColor(drafts[style.key].orEmpty())
                        ?: styleController.background(style)
                }
                styleController.apply(selectedStyle, colors)
                onApply(color)
            }
        }, enabled = parsed != null,
            modifier = Modifier.focusRequester(applyFocus).focusProperties {
                if (television) { up = blueFocus; down = cancelFocus; left = cancelFocus; right = cancelFocus }
            }.testTag("cinema-appearance-apply")) { Text("적용") } },
        dismissButton = { CompactMenuAction(onClick = onDismiss,
            modifier = Modifier.focusRequester(cancelFocus).focusProperties {
                if (television) { up = applyFocus; down = styleFocus.first(); left = applyFocus; right = applyFocus }
            }.testTag("cinema-appearance-cancel")) { Text("취소") } },
    )
    if (editorOpen) {
        val editorCancel = remember { FocusRequester() }
        val editorInput = remember { FocusRequester() }
        val editorDone = remember { FocusRequester() }
        val valid = parseHomeBackgroundColor(editorValue) != null
        AlertDialog(onDismissRequest = { closeEditor(false) }, title = { Text("색상 코드 입력") },
            text = {
                val focusManager = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    editorInput.requestFocus()
                    keyboard?.show()
                }
                CinemaHexField(editorValue, { editorValue = it }, Modifier.fillMaxWidth().focusRequester(editorInput)
                    .focusProperties { up = editorCancel; down = if (valid) editorDone else editorCancel },
                    onDone = {
                        focusManager.clearFocus()
                        if (valid) closeEditor(true) else editorCancel.requestFocus()
                    })
            },
            confirmButton = {
                val keyboard = LocalSoftwareKeyboardController.current
                CompactMenuAction(onClick = { keyboard?.hide(); closeEditor(true) }, enabled = valid,
                    modifier = Modifier.focusRequester(editorDone).focusProperties { up = editorInput; left = editorCancel }
                        .testTag("cinema-hex-done")) { Text("확인") }
            },
            dismissButton = {
                val keyboard = LocalSoftwareKeyboardController.current
                CompactMenuAction(onClick = { keyboard?.hide(); closeEditor(false) },
                    modifier = Modifier.focusRequester(editorCancel).focusProperties { up = editorInput; right = editorDone }
                        .testTag("cinema-hex-cancel")) { Text("취소") }
            })
    }
}

/** A compact layout preview uses the real palette, without loading any library artwork. */
@Composable
private fun CinemaStylePreview(style: CinemaStyle, background: Int) {
    val colors = cinemaColorScheme(Color(background), style)
    Canvas(Modifier.size(width = 70.dp, height = 46.dp).border(1.dp, colors.outlineVariant)) {
        drawRect(colors.background)
        val inset = 5.dp.toPx()
        val heroHeight = size.height * when (style) { CinemaStyle.CINEMA -> .48f; CinemaStyle.OTT -> .28f; CinemaStyle.LOUNGE -> .37f }
        drawRect(colors.primary.copy(alpha = if (style == CinemaStyle.LOUNGE) .18f else .25f),
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - inset * 2, heroHeight))
        drawRect(colors.primary, topLeft = androidx.compose.ui.geometry.Offset(inset * 1.6f, inset * 1.6f),
            size = androidx.compose.ui.geometry.Size(size.width * .28f, 2.dp.toPx()))
        val count = when (style) { CinemaStyle.CINEMA -> 3; CinemaStyle.OTT -> 4; CinemaStyle.LOUNGE -> 2 }
        val gap = 3.dp.toPx()
        val width = (size.width - inset * 2 - gap * (count - 1)) / count
        repeat(count) { index ->
            drawRect(if (index == 0) colors.primary else colors.onSurfaceVariant.copy(alpha = .35f),
                topLeft = androidx.compose.ui.geometry.Offset(inset + (width + gap) * index, heroHeight + inset + gap),
                size = androidx.compose.ui.geometry.Size(width, (size.height - heroHeight - inset * 2 - gap).coerceAtLeast(0f)))
        }
    }
}

@Composable
private fun CinemaHexField(value: String, onValue: (String) -> Unit, modifier: Modifier, onDone: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val valid = parseHomeBackgroundColor(value) != null
    OutlinedTextField(value = value, onValueChange = { onValue(it.take(16)) }, singleLine = true,
        label = { Text("배경색 (#RRGGBB)") }, isError = !valid,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus(); onDone() }),
        supportingText = { Text(if (valid) " " else "6자리 색상 코드를 입력해 주세요.",
            minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.testTag("cinema-background-hex"))
}

@Composable
private fun TvColorChannel(label: String, tag: String, value: Int, focus: FocusRequester,
    above: FocusRequester, below: FocusRequester, onAdjust: (Int) -> Unit,
    onValue: (Int) -> Unit, onConfirm: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(color = if (focused) colors.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent.keyCode
                val down = event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) onAdjust(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) onAdjust(1); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { if (down) above.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) below.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        // Move focus only on release, and consume it before Apply can see this confirm.
                        if (!down && event.nativeKeyEvent.action == KeyEvent.ACTION_UP) onConfirm()
                        true
                    }
                    else -> false
                }
            }.focusProperties { up = above; down = below; left = FocusRequester.Cancel; right = FocusRequester.Cancel }
            .focusable().testTag("cinema-rgb-$tag").semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = value.toString()
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..255f, 254)
                setProgress { onValue(it.roundToInt()); true }
                onClick(label = "적용으로 이동") { onConfirm(); true }
            }) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = if (focused) colors.onPrimaryContainer else colors.onSurface)
            Text("‹", color = if (focused) colors.onPrimaryContainer else colors.onSurfaceVariant)
            LinearProgressIndicator(progress = { value / 255f }, modifier = Modifier.weight(1f),
                color = if (focused) colors.onPrimaryContainer else colors.primary,
                trackColor = colors.outlineVariant)
            Text("›", color = if (focused) colors.onPrimaryContainer else colors.onSurfaceVariant)
            Text(value.toString(), style = MaterialTheme.typography.labelMedium,
                color = if (focused) colors.onPrimaryContainer else colors.onSurface)
        }
    }
}
