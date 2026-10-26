package io.mirr.shynigsy.player

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.mirr.shynigsy.R

/** One TV window owns its focus and physical key gesture until explicitly dismissed. */
internal class TvSubtitlePositionDialog(
    private val context: Context,
    initialX: Int,
    initialY: Int,
    private val onPreview: (Int, Int) -> Unit,
    private val onCommit: (Int, Int) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private var position = TvSubtitlePosition(initialX, initialY)
    private var committed = position
    private var shownAtMs = Long.MAX_VALUE
    private var input = TvSubtitlePositionInput(shownAtMs)
    private val moves = mutableMapOf<View, TvSubtitleMove>()
    private val positionLabel = TextView(context).apply {
        tag = "tv-subtitle-position-status"
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
    }
    private val closeButton: Button
    val dialog: AlertDialog

    init {
        val content = object : LinearLayout(context) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus) finishGesture()
            }
        }.apply {
            tag = "tv-subtitle-position-dialog"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }
        content.addView(TextView(context).apply {
            text = "방향 버튼을 선택해 확인 키를 누르세요. 길게 누르면 계속 이동합니다."
            textSize = 13f
            gravity = Gravity.CENTER
        }, matchWrap())
        content.addView(positionLabel, matchWrap())
        fun direction(label: String, tagName: String, move: TvSubtitleMove): Button = button(label, tagName).also { button ->
            moves[button] = move
            button.setOnClickListener { applyMove(move); commitIfChanged() }
        }
        val up = direction("↑ 위로", "tv-subtitle-position-up", TvSubtitleMove.UP)
        val left = direction("← 왼쪽", "tv-subtitle-position-left", TvSubtitleMove.LEFT)
        val reset = direction("기본 위치", "tv-subtitle-position-reset", TvSubtitleMove.RESET)
        val right = direction("오른쪽 →", "tv-subtitle-position-right", TvSubtitleMove.RIGHT)
        val down = direction("↓ 아래로", "tv-subtitle-position-down", TvSubtitleMove.DOWN)
        closeButton = button("닫기", "tv-subtitle-position-close").apply { setOnClickListener { dismiss() } }
        fun row(vararg children: View?) {
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                children.forEach { child ->
                    addView(child ?: View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }, matchWrap())
        }
        row(null, up, null)
        row(left, reset, right)
        row(null, down, null)
        content.addView(closeButton, matchWrap())
        // Direction keys navigate the controls normally; only the focused control's confirm key moves subtitles.
        up.nextFocusUpId = up.id; up.nextFocusDownId = reset.id
        up.nextFocusLeftId = left.id; up.nextFocusRightId = right.id
        left.nextFocusUpId = up.id; left.nextFocusDownId = down.id
        left.nextFocusLeftId = left.id; left.nextFocusRightId = reset.id
        reset.nextFocusUpId = up.id; reset.nextFocusDownId = down.id
        reset.nextFocusLeftId = left.id; reset.nextFocusRightId = right.id
        right.nextFocusUpId = up.id; right.nextFocusDownId = down.id
        right.nextFocusLeftId = reset.id; right.nextFocusRightId = right.id
        down.nextFocusUpId = reset.id; down.nextFocusDownId = closeButton.id
        down.nextFocusLeftId = left.id; down.nextFocusRightId = right.id
        closeButton.nextFocusUpId = down.id; closeButton.nextFocusDownId = closeButton.id
        closeButton.nextFocusLeftId = closeButton.id; closeButton.nextFocusRightId = closeButton.id
        dialog = AlertDialog.Builder(context).setTitle("자막 위치")
            .setView(ScrollView(context).apply { isFillViewport = false; addView(content) }).create()
        dialog.setOnKeyListener { _, keyCode, event -> handleKey(keyCode, event) }
        dialog.setOnDismissListener { finishGesture(); onDismiss() }
        updateLabel()
    }

    fun show() {
        if (dialog.isShowing) return
        shownAtMs = SystemClock.uptimeMillis()
        input = TvSubtitlePositionInput(shownAtMs)
        dialog.show()
        dialog.window?.setLayout(minOf(dp(420), context.resources.displayMetrics.widthPixels - dp(32)),
            WindowManager.LayoutParams.WRAP_CONTENT)
        closeButton.post { if (dialog.isShowing) closeButton.requestFocus() }
    }

    fun dismiss() {
        finishGesture()
        dialog.dismiss()
    }

    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        val confirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!confirm) {
            if (event.action == KeyEvent.ACTION_DOWN) finishGesture()
            return false
        }
        // Consume confirm UP even if it belongs to the settings window that opened this dialog.
        if (event.downTime <= shownAtMs) return true
        val focused = dialog.currentFocus
        if (focused === closeButton) {
            // RESET is only a nonrepeating key-gesture sentinel here; closing never changes offsets.
            if (event.action == KeyEvent.ACTION_DOWN) {
                input.keyDown(keyCode, event.downTime, event.eventTime, event.repeatCount, TvSubtitleMove.RESET)
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (event.isCanceled) finishGesture()
                else if (input.keyUp(keyCode, event.downTime)) dismiss()
            }
            return true
        }
        val move = moves[focused] ?: return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            input.keyDown(keyCode, event.downTime, event.eventTime, event.repeatCount, move)?.let(::applyMove)
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (event.isCanceled) finishGesture()
            else if (input.keyUp(keyCode, event.downTime)) commitIfChanged()
        }
        return true
    }

    private fun button(label: String, tagName: String): Button = Button(context).apply {
        id = View.generateViewId(); tag = tagName
        text = label; textSize = 13f; isAllCaps = false
        minHeight = dp(48); minimumHeight = dp(48); minWidth = 0; minimumWidth = 0
        setPadding(dp(4), dp(6), dp(4), dp(6))
        background = context.getDrawable(R.drawable.tv_control_background)
        setOnFocusChangeListener { _, focused -> if (!focused) finishGesture() }
    }

    private fun applyMove(move: TvSubtitleMove) {
        val next = moveTvSubtitlePosition(position, move)
        if (next == position) return
        position = next
        updateLabel()
        onPreview(position.x, position.y)
    }

    private fun finishGesture() {
        input.cancel()
        commitIfChanged()
    }

    private fun commitIfChanged() {
        if (position == committed) return
        committed = position
        onCommit(position.x, position.y)
    }

    private fun updateLabel() {
        fun signed(value: Int) = if (value > 0) "+$value" else "$value"
        positionLabel.text = "가로 ${signed(position.x)}% · 세로 ${signed(position.y)}%"
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
