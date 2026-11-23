package io.mirr.shynigsy.player

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.graphics.toArgb
import io.mirr.shynigsy.ui.CinemaAppearancePreferences
import io.mirr.shynigsy.ui.cinemaColorScheme

/** One view tree per analysis; playback polling never replaces its text, scroll, or focus. */
internal class StudyExplanationDialog(private val context: Context, sourceLabel: String) {
    // Capture the opening viewport before Android attaches the dialog's themed window.
    private val windowWidth = minOf(dp(520), (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(1))
    private val windowHeight = minOf(dp(680), (context.resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(dp(160)))
    private val television = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    private val colors = CinemaAppearancePreferences(context).read().let {
        cinemaColorScheme(androidx.compose.ui.graphics.Color(it.backgroundArgb), it.style)
    }
    private var dismissed = false
    private var shownAt = Long.MAX_VALUE
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(8))
    }
    private val scroll = ScrollView(context).apply {
        id = View.generateViewId()
        tag = "study-explanation-scroll"
        isFocusable = television
        isFocusableInTouchMode = television
        isSmoothScrollingEnabled = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(content, FrameLayout.LayoutParams(-1, -2))
        if (television) highlight(this)
    }
    private val status = text("", 14f, false, "study-explanation-status")
    private val sourceTitle = text("원문", 14f, false, "study-explanation-source-title")
    private val source = text("", 15f, false, "study-explanation-source")
    private val vocabularyTitle = text("어휘·숙어", 14f, false, "study-explanation-vocabulary-title")
    private val vocabulary = text("", 14f, false, "study-explanation-vocabulary")
    private val grammarTitle = text("문법·표현 패턴", 14f, false, "study-explanation-grammar-title")
    private val grammar = text("", 14f, false, "study-explanation-grammar")
    private val unknownTitle = text("사전에 등록되지 않은 단어", 14f, false, "study-explanation-unknown-title")
    private val unknown = text("", 13f, true, "study-explanation-unknown")
    private val limit = text("구간이 길어 일부 문장이나 설명만 표시했습니다. 짧게 나누면 더 확인할 수 있습니다.",
        12f, true, "study-explanation-truncated")
    private val resultViews = listOf(sourceTitle, source, vocabularyTitle, vocabulary, grammarTitle, grammar,
        unknownTitle, unknown, limit)
    private val close = Button(context).apply {
        id = View.generateViewId()
        tag = "study-explanation-close"
        text = "닫기"
        textSize = 14f
        isAllCaps = false
        isFocusable = true
        minHeight = dp(48)
        minimumHeight = dp(48)
        setTextColor(colors.onSurface.toArgb())
        highlight(this)
        setOnClickListener { dismiss() }
    }
    private val layout = LinearLayout(context).apply {
        tag = "study-explanation-dialog"
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(8))
        setBackgroundColor(colors.surface.toArgb())
        addView(text("어휘·문법", 18f, false, "study-explanation-title"), matchWrap())
        addView(text(sourceLabel, 12f, true, "study-explanation-source-label").apply { maxLines = 2 }, matchWrap())
        addView(text("기기 내 영어 사전과 문법 규칙의 기본 설명입니다. 문맥에 따라 뜻과 쓰임이 달라질 수 있습니다.",
            12f, true, "study-explanation-note"), matchWrap())
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(close, LinearLayout.LayoutParams(-1, dp(48)))
    }
    val dialog: AlertDialog = AlertDialog.Builder(context).setView(layout).create()
    var onDismiss: (() -> Unit)? = null

    init {
        content.addView(status, matchWrap())
        resultViews.forEach { content.addView(it, matchWrap()); it.visibility = View.GONE }
        scroll.nextFocusDownId = close.id
        scroll.nextFocusUpId = scroll.id
        close.nextFocusUpId = scroll.id
        close.nextFocusDownId = close.id
        close.nextFocusLeftId = close.id
        close.nextFocusRightId = close.id
        scroll.setOnKeyListener { _, code, event ->
            if (!television || event.action != KeyEvent.ACTION_DOWN) false
            else when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (scroll.canScrollVertically(1)) scroll.scrollBy(0, maxOf(dp(48), scroll.height * 2 / 3))
                    else close.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    scroll.scrollBy(0, -maxOf(dp(48), scroll.height * 2 / 3))
                    true
                }
                else -> false
            }
        }
        dialog.setOnKeyListener { _, code, event ->
            val confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
            confirm && event.downTime <= shownAt
        }
        dialog.setOnDismissListener {
            dismissed = true
            onDismiss?.invoke()
        }
    }

    fun showLoading() {
        if (!canUpdate()) return
        status.text = "반복 구간의 어휘와 문법을 살펴보는 중…"
        status.visibility = View.VISIBLE
        resultViews.forEach { it.visibility = View.GONE }
        show()
    }

    fun showResult(result: StudyExplanation) {
        if (!canUpdate()) return
        source.text = result.sourceText
        vocabulary.text = result.vocabulary.joinToString("\n\n") {
            "${it.expression}${if (it.partOfSpeech.isBlank()) "" else " · ${it.partOfSpeech}"}\n${it.meaning}"
        }.ifBlank { "이 구간에서 사전에 등록된 어휘·숙어를 찾지 못했습니다." }
        grammar.text = result.grammar.joinToString("\n\n") {
            "${it.title}\n${it.explanation}\n원문 예: ${it.example}"
        }.ifBlank { "이 구간에서 기본 문법 규칙과 일치하는 패턴을 찾지 못했습니다." }
        unknown.text = result.unknownWords.joinToString(", ") + "\n등록되지 않은 단어의 뜻은 추측하지 않습니다."
        status.visibility = View.GONE
        resultViews.forEach { it.visibility = View.VISIBLE }
        unknownTitle.visibility = if (result.unknownWords.isEmpty()) View.GONE else View.VISIBLE
        unknown.visibility = unknownTitle.visibility
        limit.visibility = if (result.truncated) View.VISIBLE else View.GONE
        show()
    }

    fun showMessage(text: String) {
        if (!canUpdate()) return
        status.text = text
        status.visibility = View.VISIBLE
        resultViews.forEach { it.visibility = View.GONE }
        show()
    }

    fun dismiss() {
        dismissed = true
        dialog.dismiss()
    }

    // Android posts onDismiss asynchronously. Also reject results in the interval between an
    // external cancel/back action closing the window and our dismissal listener being delivered.
    private fun canUpdate() = !dismissed && (shownAt == Long.MAX_VALUE || dialog.isShowing)

    private fun show() {
        if (dialog.isShowing) return
        dialog.show()
        shownAt = SystemClock.uptimeMillis()
        dialog.window?.apply {
            setGravity(if (television) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM)
            setLayout(windowWidth, windowHeight)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        if (television) scroll.requestFocus()
    }

    private fun text(value: String, size: Float, secondary: Boolean, tagName: String) = TextView(context).apply {
        text = value
        textSize = size
        tag = tagName
        setTextColor(if (secondary) colors.onSurfaceVariant.toArgb() else colors.onSurface.toArgb())
        setPadding(dp(4), dp(4), dp(4), dp(8))
    }

    private fun highlight(view: View) {
        fun focused() = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(this@StudyExplanationDialog.colors.surfaceVariant.toArgb())
            setStroke(dp(1), this@StudyExplanationDialog.colors.primary.toArgb())
        }
        view.background = StateListDrawable().apply {
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
