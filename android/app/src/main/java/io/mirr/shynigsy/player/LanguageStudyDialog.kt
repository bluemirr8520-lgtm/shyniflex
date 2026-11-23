package io.mirr.shynigsy.player

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.graphics.toArgb
import io.mirr.shynigsy.ui.CinemaAppearancePreferences
import io.mirr.shynigsy.ui.cinemaColorScheme

internal data class LanguageStudySnapshot(
    val status: String,
    val sentenceText: String,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canRepeatSentence: Boolean,
    val repeatLabel: String,
    val canMarkEnd: Boolean,
    val secondaryLabel: String,
    val speedLabel: String,
    val canExplain: Boolean = false,
)

/** Keeps one scroll/focus tree while the playing sentence and repeat state change. */
internal class LanguageStudyDialog(
    private val context: Context,
    private val snapshot: () -> LanguageStudySnapshot,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onRepeatSentence: () -> Unit,
    private val onMarkStart: () -> Unit,
    private val onMarkEnd: () -> Unit,
    private val onClearRepeat: () -> Unit,
    private val onSecondarySubtitles: () -> Unit,
    private val onSpeed: () -> Unit,
    private val onExplain: () -> Unit = {},
) {
    // Window attachment can replace a wrapped Context's display metrics (notably on Samsung).
    // Keep the viewport that opened this menu, while still respecting smaller parent measures.
    private val contentHeightLimit = (context.resources.displayMetrics.heightPixels - dp(140)).coerceAtLeast(dp(100))
    private val windowWidth = minOf(dp(360), (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(1))
    private val television = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    private val colors = CinemaAppearancePreferences(context).read().let {
        cinemaColorScheme(androidx.compose.ui.graphics.Color(it.backgroundArgb), it.style)
    }
    private val handler = Handler(Looper.getMainLooper())
    private var shownAt = Long.MAX_VALUE
    private var dismissed = false
    private var previousSnapshot: LanguageStudySnapshot? = null
    private val focusRows = mutableListOf<Button>()
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
    private val scroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val offered = View.MeasureSpec.getSize(heightMeasureSpec)
            val height = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) contentHeightLimit
                else minOf(offered, contentHeightLimit)
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        }
    }.apply {
        tag = "study-dialog"
        isFocusable = false
        isSmoothScrollingEnabled = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setBackgroundColor(colors.surface.toArgb())
        addView(content, FrameLayout.LayoutParams(-1, -2))
    }
    private val status = text("", 12f, true, "study-status", 3)
    private val sentence = text("", 14f, false, "study-sentence", 3)
    private val repeat = text("", 12f, true, "study-repeat-state", 2)
    private val previous: Button
    private val next: Button
    private val sentenceRepeat: Button
    private val markEnd: Button
    private val secondary: Button
    private val speed: Button
    private val explain: Button
    val dialog: AlertDialog = AlertDialog.Builder(context).setView(scroll).create()
    private val refresh = object : Runnable {
        override fun run() {
            if (dismissed || !dialog.isShowing) return
            refreshSnapshot()
            handler.postDelayed(this, 500)
        }
    }

    init {
        content.addView(text("어학 학습", 16f, false, "study-title", 1), matchWrap())
        content.addView(status, matchWrap())
        content.addView(sentence, matchWrap())
        content.addView(repeat, matchWrap())
        previous = action("이전 문장", "study-previous", onPrevious)
        next = action("다음 문장", "study-next", onNext)
        sentenceRepeat = action("문장 반복", "study-repeat-sentence", onRepeatSentence)
        action("A 시작점 지정", "study-mark-a", onMarkStart)
        markEnd = action("B 끝점 지정", "study-mark-b", onMarkEnd)
        action("반복 해제", "study-clear-repeat", onClearRepeat)
        explain = action("어휘·문법", "study-explain") {
            dismiss()
            onExplain()
        }
        secondary = action("원문·번역 자막", "study-secondary") {
            dismiss()
            onSecondarySubtitles()
        }
        speed = action("배속", "study-speed") {
            dismiss()
            onSpeed()
        }
        action("닫기", "study-close", ::dismiss)
        dialog.setOnKeyListener { _, code, event ->
            val confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
            // A confirm release that opened the menu must not immediately activate its first row.
            confirm && event.downTime <= shownAt
        }
        dialog.setOnDismissListener {
            dismissed = true
            handler.removeCallbacks(refresh)
        }
    }

    fun show() {
        if (dismissed || dialog.isShowing) return
        refreshSnapshot()
        dialog.show()
        shownAt = SystemClock.uptimeMillis()
        dialog.window?.apply {
            setGravity(if (television) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM)
            setLayout(windowWidth, WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        if (television) focusRows.firstOrNull { it.isEnabled }?.requestFocus()
        handler.postDelayed(refresh, 500)
    }

    fun dismiss() {
        dismissed = true
        handler.removeCallbacks(refresh)
        dialog.dismiss()
    }

    private fun refreshSnapshot() {
        if (dismissed) return
        val value = snapshot()
        if (value == previousSnapshot) return
        val focusedIndex = focusRows.indexOfFirst { it.hasFocus() }
        previousSnapshot = value
        setTextIfChanged(status, value.status)
        status.visibility = if (value.status.isBlank()) View.GONE else View.VISIBLE
        setTextIfChanged(sentence, value.sentenceText.ifBlank { "현재 표시할 문장이 없습니다." })
        setTextIfChanged(repeat, "반복: ${value.repeatLabel.ifBlank { "꺼짐" }}")
        previous.isEnabled = value.canPrevious
        next.isEnabled = value.canNext
        sentenceRepeat.isEnabled = value.canRepeatSentence
        markEnd.isEnabled = value.canMarkEnd
        explain.isEnabled = value.canExplain
        setTextIfChanged(secondary, "원문·번역 자막 · ${value.secondaryLabel.ifBlank { "꺼짐" }}")
        setTextIfChanged(speed, "배속 · ${value.speedLabel}")
        focusRows.forEach { row -> row.alpha = if (row.isEnabled) 1f else .4f }
        val enabled = focusRows.filter { it.isEnabled }
        enabled.forEachIndexed { index, row ->
            row.nextFocusUpId = enabled.getOrNull(index - 1)?.id ?: row.id
            row.nextFocusDownId = enabled.getOrNull(index + 1)?.id ?: row.id
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = row.id
        }
        if (television && focusedIndex >= 0 && !focusRows[focusedIndex].isEnabled) {
            (focusRows.drop(focusedIndex + 1).firstOrNull { it.isEnabled }
                ?: focusRows.take(focusedIndex).lastOrNull { it.isEnabled })?.requestFocus()
        }
    }

    private fun action(label: String, tagName: String, onClick: () -> Unit): Button = Button(context).apply {
        id = View.generateViewId()
        tag = tagName
        text = label
        textSize = 13f
        isAllCaps = false
        isFocusable = true
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(colors.onSurface.toArgb())
        highlight(this)
        setOnClickListener {
            if (!dismissed) {
                onClick()
                refreshSnapshot()
            }
        }
        content.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
        focusRows.add(this)
    }

    private fun text(value: String, size: Float, secondary: Boolean, tagName: String, lines: Int) = TextView(context).apply {
        text = value
        textSize = size
        tag = tagName
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (secondary) colors.onSurfaceVariant.toArgb() else colors.onSurface.toArgb())
        setPadding(dp(4), dp(3), dp(4), dp(7))
    }

    private fun setTextIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    private fun highlight(view: View) {
        fun focused() = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(this@LanguageStudyDialog.colors.surfaceVariant.toArgb())
            setStroke(dp(1), this@LanguageStudyDialog.colors.primary.toArgb())
        }
        view.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), ColorDrawable(Color.TRANSPARENT))
            addState(intArrayOf(android.R.attr.state_pressed), focused())
            addState(intArrayOf(android.R.attr.state_focused), focused())
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        view.backgroundTintList = null
        view.stateListAnimator = null
        view.elevation = 0f
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
