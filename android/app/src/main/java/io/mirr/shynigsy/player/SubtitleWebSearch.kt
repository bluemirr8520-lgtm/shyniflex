package io.mirr.shynigsy.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.graphics.toArgb
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.isTelevision
import io.mirr.shynigsy.ui.CinemaAppearancePreferences
import io.mirr.shynigsy.ui.cinemaColorScheme
import io.mirr.shynigsy.ui.tvQrPlacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opening the dialog only edits local text. Only an explicit search action opens the browser. */
internal fun showSubtitleWebSearch(activity: Activity, spec: PlaybackSpec): AlertDialog =
    SubtitleWebSearchDialog(activity, defaultSubtitleWebSearchQuery(spec), spec.sourceItem?.isSpoiler == true).show()

private class SubtitleWebSearchDialog(
    private val activity: Activity,
    initialQuery: String,
    private val secure: Boolean,
) {
    private val television = activity.isTelevision()
    private val colors = CinemaAppearancePreferences(activity).read().let {
        cinemaColorScheme(androidx.compose.ui.graphics.Color(it.backgroundArgb), it.style)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var query = initialQuery
    private var shownAt = Long.MAX_VALUE
    private var generation = 0
    private val focusable = mutableListOf<View>()
    private var errorLabel: TextView? = null
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(12))
        setBackgroundColor(colors.surface.toArgb())
    }
    private val scroll = object : ScrollView(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = (activity.resources.displayMetrics.heightPixels - dp(48)).coerceAtLeast(dp(120))
            val offered = View.MeasureSpec.getSize(heightMeasureSpec)
            val height = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) limit
                else minOf(offered, limit)
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        }
    }.apply {
        tag = "subtitle-web-search-dialog"
        addView(content)
    }
    private val dialog = AlertDialog.Builder(activity).setView(scroll).create()

    init {
        dialog.setOnKeyListener { _, code, event ->
            val confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
            confirm && event.downTime <= shownAt
        }
        // The caller may own OnDismissListener to clear its dialog reference.
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { hideKeyboard(); generation++; scope.cancel() }
        })
    }

    fun show(): AlertDialog {
        showEditor()
        if (secure) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        shownAt = SystemClock.uptimeMillis()
        dialog.window?.apply {
            setLayout(minOf(dp(if (television) 480 else 420), activity.resources.displayMetrics.widthPixels - dp(24)),
                WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(colors.surface.toArgb()))
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        focusAction(if (television) "subtitle-search-qr" else "subtitle-search-browser")
        return dialog
    }

    private fun beginPage(title: String, note: String) {
        hideKeyboard()
        generation++
        shownAt = SystemClock.uptimeMillis()
        focusable.clear()
        content.removeAllViews()
        scroll.scrollTo(0, 0)
        content.addView(text(title, 17f).apply { setPadding(0, 0, 0, dp(10)) })
        content.addView(text(note, 13f).apply { setPadding(0, 0, 0, dp(12)) })
        errorLabel = text("", 13f).apply { visibility = View.GONE }
    }

    private fun showEditor() {
        beginPage("웹에서 자막 찾기", "검색어를 확인한 뒤 검색하세요. 검색어는 Google에 전달됩니다.\n" +
            if (television) "휴대폰으로 QR을 스캔해 검색할 수도 있습니다."
            else "다운로드한 자막은 재생 화면의 ‘자막 파일 불러오기’에서 선택하세요.")
        val editor = EditText(activity).apply {
            id = View.generateViewId()
            tag = "subtitle-search-query"
            setSingleLine(true)
            textSize = 15f
            setTextColor(colors.onSurface.toArgb())
            setHintTextColor(colors.onSurfaceVariant.toArgb())
            hint = "작품명·회차·한국어 자막"
            contentDescription = "자막 검색어"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(InputFilter.LengthFilter(SUBTITLE_SEARCH_MAX_LENGTH))
            setText(query)
            setSelection(text.length)
            setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_SEARCH) false
                else { readQuery(this)?.let(::openBrowser); true }
            }
        }
        focusable += editor
        content.addView(editor, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(errorLabel)
        if (television) action("휴대폰 검색 QR 표시", "subtitle-search-qr") {
            readQuery(editor)?.let(::showQr)
        }
        action("브라우저로 자막 검색", "subtitle-search-browser") { readQuery(editor)?.let(::openBrowser) }
        action("취소", "subtitle-search-close") { dialog.dismiss() }
        connectFocus()
    }

    private fun readQuery(editor: EditText): String? {
        query = normalizeSubtitleWebSearchQuery(editor.text.toString())
        val url = subtitleWebSearchUrl(query)
        if (url == null) {
            showError("검색어를 입력해 주세요.")
            editor.requestFocus()
        } else {
            editor.setText(query)
            editor.setSelection(query.length)
        }
        return url
    }

    private fun openBrowser(url: String) {
        try {
            hideKeyboard()
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            })
            dialog.dismiss()
        } catch (_: ActivityNotFoundException) {
            showError(if (television) "브라우저 앱이 없습니다. ‘휴대폰 검색 QR 표시’를 이용하세요."
                else "브라우저 앱이 없습니다. 브라우저를 설치한 뒤 다시 검색해 주세요.")
        } catch (_: SecurityException) {
            showError("브라우저를 열 수 없습니다. 기기의 앱 실행 설정을 확인해 주세요.")
        }
    }

    private fun showQr(url: String) {
        beginPage("휴대폰에서 자막 검색", "휴대폰 카메라로 QR을 스캔하세요. 자막 파일을 다운로드한 뒤\n" +
            "USB나 파일 관리자로 TV에 옮기고 ‘자막 파일 불러오기’에서 선택하세요.")
        content.addView(text(query, 13f).apply { maxLines = 3; setPadding(0, 0, 0, dp(8)) })
        val placeholder = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        val loading = text("QR 만드는 중…", 13f).apply { gravity = Gravity.CENTER }
        placeholder.addView(loading, LinearLayout.LayoutParams(dp(216), dp(216)))
        content.addView(placeholder, LinearLayout.LayoutParams(-1, dp(216)))
        content.addView(errorLabel)
        action("TV 브라우저로 검색", "subtitle-search-browser") { openBrowser(url) }
        action("검색어 수정", "subtitle-search-edit") {
            showEditor()
            focusAction("subtitle-search-query")
        }
        action("닫기", "subtitle-search-close") { dialog.dismiss() }
        connectFocus()
        focusAction("subtitle-search-edit")
        val page = generation
        scope.launch {
            val matrix = withContext(Dispatchers.Default) {
                runCatching {
                    QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 1, 1, mapOf(
                        EncodeHintType.CHARACTER_SET to "UTF-8",
                        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                        EncodeHintType.MARGIN to 4,
                    ))
                }.getOrNull()
            }
            if (generation != page || !dialog.isShowing) return@launch
            if (matrix == null) {
                loading.text = "QR을 표시할 수 없습니다."
                showError("검색어를 줄여 다시 시도하거나 TV 브라우저로 검색해 주세요.")
            } else {
                placeholder.removeAllViews()
                placeholder.addView(SubtitleSearchQrView(activity, matrix), LinearLayout.LayoutParams(dp(216), dp(216)))
            }
        }
    }

    private fun action(label: String, tagName: String, click: () -> Unit) {
        val button = Button(activity).apply {
            id = View.generateViewId(); tag = tagName
            text = label; textSize = 14f; isAllCaps = false; minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(colors.onSurface.toArgb())
            fun focused() = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(this@SubtitleWebSearchDialog.colors.surfaceVariant.toArgb())
                setStroke(dp(1), this@SubtitleWebSearchDialog.colors.primary.toArgb())
            }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused())
                addState(intArrayOf(android.R.attr.state_pressed), focused())
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            backgroundTintList = null; stateListAnimator = null; elevation = 0f
            setOnClickListener { click() }
        }
        focusable += button
        content.addView(button, LinearLayout.LayoutParams(-1, -2))
    }

    private fun connectFocus() {
        focusable.forEachIndexed { index, view ->
            view.nextFocusUpId = focusable[(index - 1).coerceAtLeast(0)].id
            view.nextFocusDownId = focusable[(index + 1).coerceAtMost(focusable.lastIndex)].id
        }
    }

    private fun focusAction(tagName: String) {
        content.post { if (dialog.isShowing) focusable.firstOrNull { it.tag == tagName }?.requestFocus() }
    }

    private fun showError(message: String) {
        errorLabel?.apply { text = message; visibility = View.VISIBLE; announceForAccessibility(message) }
    }

    private fun text(value: String, size: Float) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(colors.onSurface.toArgb())
    }

    private fun hideKeyboard() {
        dialog.currentFocus?.windowToken?.let { token ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}

/** Use the login QR's integer module placement so TV scaling cannot blur the code. */
private class SubtitleSearchQrView(context: Context, private val matrix: BitMatrix) : View(context) {
    private val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
    init {
        tag = "subtitle-search-qr-code"
        contentDescription = "휴대폰으로 스캔할 자막 검색 QR 코드"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val placement = tvQrPlacement(width, height, matrix.width) ?: return
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) {
            val left = placement.left + x * placement.modulePixels
            val top = placement.top + y * placement.modulePixels
            canvas.drawRect(left.toFloat(), top.toFloat(), (left + placement.modulePixels).toFloat(),
                (top + placement.modulePixels).toFloat(), paint)
        }
    }
}
