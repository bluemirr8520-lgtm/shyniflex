package io.mirr.shynigsy.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.graphics.toArgb
import io.mirr.shynigsy.ui.CinemaAppearancePreferences
import io.mirr.shynigsy.ui.cinemaColorScheme

/** One window owns editing, preset management and remote focus; focus never applies a preset. */
internal class PictureSettingsDialog(
    private val context: Context,
    private val television: Boolean,
    private val store: PicturePresetStore,
    private val current: () -> PictureSettings,
    private val onApply: (PictureSettings) -> Unit,
    private val supportNote: () -> String? = { null },
) {
    private val colors = CinemaAppearancePreferences(context).read().let {
        cinemaColorScheme(androidx.compose.ui.graphics.Color(it.backgroundArgb), it.style)
    }
    private var value = current().normalized()
    private var pageShownAt = Long.MAX_VALUE
    private var generation = 0
    private var dismissed = false
    private var supportLabel: TextView? = null
    private var selectedPresetLabel: TextView? = null
    private val focusRows = mutableListOf<View>()
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private val scroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = (context.resources.displayMetrics.heightPixels - dp(40)).coerceAtLeast(dp(120))
            val offered = View.MeasureSpec.getSize(heightMeasureSpec)
            val height = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) limit
                else minOf(offered, limit)
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        }
    }.apply {
        tag = "picture-settings-panel"
        setBackgroundColor(colors.surface.toArgb())
        addView(content, FrameLayout.LayoutParams(-1, -2))
    }
    val dialog: AlertDialog = AlertDialog.Builder(context).setView(scroll).create()

    init {
        dialog.setOnKeyListener { _, code, event ->
            // The opening settings button may still have a confirm release/repeat in flight.
            val confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
            confirm && event.downTime <= pageShownAt
        }
        dialog.setOnDismissListener { dismissed = true; generation++ }
    }

    fun show() {
        if (dialog.isShowing || dismissed) return
        showControls()
        dialog.show()
        pageShownAt = SystemClock.uptimeMillis()
        dialog.window?.apply {
            setGravity(if (television) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM)
            setLayout(minOf(dp(360), context.resources.displayMetrics.widthPixels - dp(24)),
                WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            // The first page has no editor. Keep the same window IME-capable for the later name page.
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        focus("picture-brightness")
    }

    fun dismiss() {
        hideKeyboard()
        dismissed = true
        generation++
        dialog.dismiss()
    }

    private fun beginPage(title: String, note: String? = null) {
        hideKeyboard()
        generation++
        pageShownAt = SystemClock.uptimeMillis()
        content.removeAllViews()
        focusRows.clear()
        supportLabel = null
        selectedPresetLabel = null
        scroll.scrollTo(0, 0)
        content.addView(text(title, 16f).apply {
            tag = "picture-page-title"
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }, matchWrap())
        note?.let { content.addView(text(it, 12f, secondary = true).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        }, matchWrap()) }
    }

    private fun showControls(focusTag: String = "picture-brightness") {
        value = current().normalized()
        beginPage("영상 화면 설정", "기기 밝기가 아닌 영상의 밝기·색을 조절합니다." +
            if (television) "\n좌우로 조절 · 위아래로 이동" else "\n설정은 기기에 자동 저장합니다.")
        supportLabel = text("", 12f, secondary = true).apply {
            tag = "picture-support-note"; setPadding(dp(4), 0, dp(4), dp(8))
            content.addView(this, matchWrap())
        }
        refreshSupportNote()
        selectedPresetLabel = text("", 12f, secondary = true).apply {
            tag = "picture-selected-preset"; setPadding(dp(4), 0, dp(4), dp(8))
            content.addView(this, matchWrap())
        }
        refreshSelectedPreset()
        adjustment("밝기", "picture-brightness", -50..50, { it.brightness }) { next, amount -> next.copy(brightness = amount) }
        adjustment("대비 (농도)", "picture-contrast", -50..50, { it.contrast }) { next, amount -> next.copy(contrast = amount) }
        adjustment("채도", "picture-saturation", -100..100, { it.saturation }) { next, amount -> next.copy(saturation = amount) }
        action("원본으로 되돌리기", "picture-reset") {
            apply(PictureSettings(), "builtin_original"); showControls("picture-reset")
        }
        action("프리셋 불러오기·관리", "picture-presets") { showPresets() }
        action("현재 설정 저장", "picture-save-current") { showNameEditor(null, value) }
        action("닫기", "picture-close") { dismiss() }
        finishPage(focusTag)
    }

    private fun adjustment(label: String, tagName: String, range: IntRange,
        read: (PictureSettings) -> Int, change: (PictureSettings, Int) -> PictureSettings) {
        val valueText = text(signed(read(value)), 14f).apply { gravity = Gravity.CENTER; tag = "$tagName-value" }
        val row = LinearLayout(context).apply {
            id = View.generateViewId(); tag = tagName
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = television
            setPadding(dp(4), 0, 0, 0)
            highlight(this)
        }
        fun updateDescription() { row.contentDescription = "$label, ${signed(read(value))}, 좌우로 조절" }
        fun changeBy(delta: Int) {
            val next = (read(value) + delta).coerceIn(range)
            if (next == read(value)) return
            apply(change(value, next))
            valueText.text = signed(read(value))
            updateDescription()
        }
        row.addView(text(label, 14f), LinearLayout.LayoutParams(0, -2, 1f))
        val minus = button("−", "$tagName-minus") { changeBy(-1) }.apply {
            contentDescription = "$label 낮추기"; isFocusable = !television
        }
        val plus = button("+", "$tagName-plus") { changeBy(1) }.apply {
            contentDescription = "$label 높이기"; isFocusable = !television
        }
        row.addView(minus, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(valueText, LinearLayout.LayoutParams(dp(40), -2))
        row.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)))
        if (television) {
            row.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            row.setOnKeyListener { _, code, event ->
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) false
                else {
                    if (event.action == KeyEvent.ACTION_DOWN) changeBy(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    true
                }
            }
            focusRows.add(row)
        } else { focusRows.add(minus); focusRows.add(plus) }
        updateDescription()
        content.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun showPresets(focusTag: String? = null) {
        beginPage("영상 프리셋", "밝기 · 대비 · 채도를 함께 적용합니다.")
        builtInPicturePresets().forEach { preset ->
            action("${preset.name}  ·  ${summary(preset.settings)}", "picture-builtin-${preset.id}") {
                apply(preset.settings, preset.id); showControls("picture-presets")
            }
        }
        content.addView(text("LG TV 참고", 13f, secondary = true).apply {
            tag = "picture-lg-section"; setPadding(dp(4), dp(12), dp(4), dp(4))
        }, matchWrap())
        content.addView(text("LG 공식 수치가 아닌 밝기·대비·채도 근사값입니다. TV 화면 모드·게임 지연·필름메이커 인증 기능은 바뀌지 않습니다.",
            12f, secondary = true).apply {
            tag = "picture-lg-note"; setPadding(dp(4), 0, dp(4), dp(8))
        }, matchWrap())
        lgInspiredPicturePresets().forEach { preset ->
            action("${preset.name}  ·  ${summary(preset.settings)}", "picture-lg-${preset.id}") {
                apply(preset.settings, preset.id); showControls("picture-presets")
            }
        }
        content.addView(text("내 프리셋", 13f, secondary = true).apply {
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }, matchWrap())
        val saved = store.list()
        if (saved.isEmpty()) content.addView(text("저장한 프리셋이 없습니다.", 12f, secondary = true).apply {
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }, matchWrap())
        saved.forEach { preset -> action(preset.name, "picture-custom-${preset.id}") { showSavedPreset(preset) } }
        action("현재 설정 저장", "picture-save-current") { showNameEditor(null, value) }
        action("화면 설정으로", "picture-back") { showControls("picture-presets") }
        finishPage(focusTag)
    }

    private fun showSavedPreset(preset: PicturePreset) {
        beginPage(preset.name, summary(preset.settings))
        action("적용", "picture-apply") { apply(preset.settings, preset.id); showControls("picture-presets") }
        action("현재 설정으로 덮어쓰기", "picture-overwrite") {
            showConfirmation("프리셋 덮어쓰기", "‘${preset.name}’을 현재 영상 설정으로 바꿀까요?", "덮어쓰기") {
                store.save(preset.name, value, preset.id)
                showPresets("picture-custom-${preset.id}")
            }
        }
        action("이름 변경", "picture-rename") { showNameEditor(preset, preset.settings) }
        action("삭제", "picture-delete") {
            showConfirmation("프리셋 삭제", "‘${preset.name}’을 삭제할까요?", "삭제") {
                store.delete(preset.id)
                showPresets()
            }
        }
        action("프리셋 목록으로", "picture-back") { showPresets("picture-custom-${preset.id}") }
        finishPage("picture-apply")
    }

    private fun showConfirmation(title: String, message: String, accept: String, operation: () -> Unit) {
        beginPage(title, message)
        val error = errorText()
        action(accept, "picture-confirm") {
            try { operation() } catch (failure: IllegalArgumentException) { showError(error, failure) }
        }
        action("취소", "picture-cancel") { showPresets() }
        finishPage("picture-cancel")
    }

    private fun showNameEditor(existing: PicturePreset?, snapshot: PictureSettings) {
        beginPage(if (existing == null) "현재 영상 설정 저장" else "프리셋 이름 변경", "이름은 30자까지 입력할 수 있습니다.")
        val input = EditText(context).apply {
            id = View.generateViewId(); tag = "picture-preset-name"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(30))
            textSize = 14f; setTextColor(colors.onSurface.toArgb()); setHintTextColor(colors.onSurfaceVariant.toArgb())
            hint = "프리셋 이름"
            setText(existing?.name ?: "사용자 설정 ${store.list().size + 1}")
            selectAll()
        }
        content.addView(input, LinearLayout.LayoutParams(-1, dp(48)))
        focusRows.add(input)
        val error = errorText()
        fun save() {
            try {
                val saved = store.save(input.text.toString(), snapshot, existing?.id)
                showPresets("picture-custom-${saved.id}")
            } catch (failure: IllegalArgumentException) { showError(error, failure) }
        }
        action("저장", "picture-name-save") { save() }
        action("취소", "picture-cancel") { showPresets() }
        input.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_DONE && event == null) { save(); true } else false
        }
        finishPage("picture-preset-name")
        val page = generation
        if (!television) input.post {
            if (!dismissed && dialog.isShowing && generation == page) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun apply(settings: PictureSettings, presetId: String? = null) {
        value = settings.normalized()
        store.clearSelectedPreset()
        onApply(value)
        // onApply owns saving and engine handoff. Remember the identity only after it accepted these values.
        if (presetId != null && current().normalized() == value) store.selectAppliedPreset(presetId, value)
        refreshSupportNote()
        refreshSelectedPreset()
    }

    private fun refreshSelectedPreset() {
        selectedPresetLabel?.text = "현재 프리셋 · ${store.currentPreset(value)?.name ?: "사용자 조절"}"
    }

    private fun refreshSupportNote() {
        val note = supportNote()?.takeIf { it.isNotBlank() }
        supportLabel?.apply { text = note.orEmpty(); visibility = if (note == null) View.GONE else View.VISIBLE }
    }

    private fun action(label: String, tagName: String, onClick: () -> Unit) {
        val view = button(label, tagName, onClick).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        content.addView(view, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        focusRows.add(view)
    }

    private fun button(label: String, tagName: String, onClick: () -> Unit) = Button(context).apply {
        id = View.generateViewId(); tag = tagName; text = label
        textSize = 13f; isAllCaps = false; setTextColor(colors.onSurface.toArgb())
        minHeight = dp(44); minimumHeight = dp(44); minWidth = 0; minimumWidth = 0
        setPadding(0, 0, 0, 0)
        highlight(this)
        setOnClickListener { if (!dismissed) onClick() }
    }

    private fun text(value: String, size: Float, secondary: Boolean = false) = TextView(context).apply {
        text = value; textSize = size
        setTextColor(if (secondary) colors.onSurfaceVariant.toArgb() else colors.onSurface.toArgb())
    }

    private fun errorText(): TextView = text("", 12f).apply {
        tag = "picture-error"; visibility = View.GONE
        setTextColor(colors.error.toArgb()); setPadding(dp(4), dp(4), dp(4), dp(6))
        content.addView(this, matchWrap())
    }

    private fun showError(view: TextView, failure: IllegalArgumentException) {
        view.text = failure.message ?: "프리셋을 저장하지 못했습니다. 이름을 확인해 주세요."
        view.visibility = View.VISIBLE
    }

    private fun finishPage(preferredTag: String? = null) {
        if (television) focusRows.forEachIndexed { index, view ->
            view.nextFocusUpId = focusRows.getOrNull(index - 1)?.id ?: view.id
            view.nextFocusDownId = focusRows.getOrNull(index + 1)?.id ?: view.id
            if (view !is EditText) { view.nextFocusLeftId = view.id; view.nextFocusRightId = view.id }
        }
        focus(preferredTag)
    }

    private fun focus(tagName: String?) {
        val target = tagName?.let { content.findViewWithTag<View>(it) }?.takeIf { it.isFocusable }
            ?: focusRows.firstOrNull() ?: return
        val page = generation
        target.post {
            if (!dismissed && dialog.isShowing && generation == page) target.requestFocus()
        }
    }

    private fun hideKeyboard() {
        dialog.currentFocus?.windowToken?.let { token ->
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun highlight(view: View) {
        fun selected() = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(this@PictureSettingsDialog.colors.surfaceVariant.toArgb())
            setStroke(dp(1), this@PictureSettingsDialog.colors.primary.toArgb())
        }
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), selected())
            addState(intArrayOf(android.R.attr.state_focused), selected())
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        view.backgroundTintList = null; view.stateListAnimator = null; view.elevation = 0f
    }

    private fun summary(settings: PictureSettings) =
        "밝기 ${signed(settings.brightness)} · 대비 ${signed(settings.contrast)} · 채도 ${signed(settings.saturation)}"
    private fun signed(value: Int) = if (value > 0) "+$value" else "$value"
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
}
