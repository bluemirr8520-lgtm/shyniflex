package io.mirr.shynigsy.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import io.mirr.shynigsy.BuildConfig
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.player.subtitles.LocalSubtitleException
import io.mirr.shynigsy.player.subtitles.LocalSubtitleStore
import io.mirr.shynigsy.player.subtitles.LocalSubtitleWrites
import io.mirr.shynigsy.player.subtitles.OpenSubtitlesAccount
import io.mirr.shynigsy.player.subtitles.OpenSubtitlesAccountStore
import io.mirr.shynigsy.player.subtitles.OpenSubtitlesClient
import io.mirr.shynigsy.player.subtitles.OpenSubtitlesException
import io.mirr.shynigsy.player.subtitles.OpenSubtitlesFile
import io.mirr.shynigsy.player.subtitles.OpenSubtitlesQuery
import io.mirr.shynigsy.player.subtitles.OpenSubtitlesResult
import io.mirr.shynigsy.player.subtitles.OpenSubtitlesSearchPage
import io.mirr.shynigsy.player.subtitles.validateOwner
import io.mirr.shynigsy.ui.CinemaAppearancePreferences
import io.mirr.shynigsy.ui.cinemaColorScheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Independent of playback: leaving the player does not grant or extend spoiler access. */
class OnlineSubtitleActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OpenSubtitlesClient()
    private val accountStore by lazy { OpenSubtitlesAccountStore(applicationContext) }
    private val colors by lazy {
        CinemaAppearancePreferences(this).read().let {
            cinemaColorScheme(androidx.compose.ui.graphics.Color(it.backgroundArgb), it.style)
        }
    }
    private var task: Job? = null
    private var account: OpenSubtitlesAccount? = null
    private lateinit var ownerKey: String
    private lateinit var panel: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private val controls = mutableListOf<View>()
    private var queryText = ""
    private var language = "ko"
    private var queryEditor: EditText? = null
    private var secretEditors = emptyList<EditText>()
    private var currentPage: OpenSubtitlesSearchPage? = null
    private var lastQuery: OpenSubtitlesQuery? = null
    private var spoiler = false
    private var busy = false
    private val applicationApiKey get() = BuildConfig.OPENSUBTITLES_API_KEY.trim()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        ownerKey = intent.getStringExtra(SubtitleImportActivity.EXTRA_OWNER_KEY).orEmpty()
        if (runCatching { validateOwner(ownerKey) }.isFailure) { finish(); return }
        spoiler = intent.getBooleanExtra(EXTRA_SPOILER, false)
        if (spoiler) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        queryText = normalizeSubtitleWebSearchQuery(savedInstanceState?.getString(STATE_QUERY)
            ?: intent.getStringExtra(EXTRA_QUERY).orEmpty())
        language = savedInstanceState?.getString(STATE_LANGUAGE)?.takeIf { it in LANGUAGES } ?: "ko"
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(colors.background.toArgb())
        }
        scroll = ScrollView(this).apply {
            tag = "online-subtitle-screen"
            isFillViewport = true
            setBackgroundColor(colors.background.toArgb())
            addView(panel, FrameLayout.LayoutParams(-1, -2))
        }
        setContentView(scroll)
        if (applicationApiKey.isEmpty()) { showUnavailable(); return }
        beginPage("앱에서 자막 찾기")
        note("설정을 확인하고 있습니다…")
        finishPage()
        runTask("설정 확인 중…") {
            account = withContext(Dispatchers.IO) { accountStore.load() }
                ?.takeIf { it.apiKey == applicationApiKey } ?: OpenSubtitlesAccount(applicationApiKey)
            showSearch()
        }
    }

    private fun showUnavailable() {
        beginPage("앱에서 자막 찾기")
        note("앱 내 자막 검색은 서비스 연동 준비 중입니다. 로컬 파일과 웹 검색을 이용해 주세요.")
        action("닫기", "online-subtitle-close") { finish() }
        panel.post { controls.firstOrNull()?.requestFocus() }
    }

    private fun beginPage(title: String, secure: Boolean = false) {
        hideKeyboard()
        secretEditors.forEach { it.text.clear() }
        secretEditors = emptyList()
        queryEditor = null
        controls.clear()
        panel.removeAllViews()
        scroll.scrollTo(0, 0)
        if (spoiler || secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        panel.addView(text(title, 21f).apply { setPadding(0, 0, 0, dp(16)) })
        status = text("", 14f).apply { visibility = View.GONE; tag = "online-subtitle-status" }
        progress = ProgressBar(this).apply { visibility = View.GONE; contentDescription = "자막 작업 중" }
    }

    private fun showSearch() {
        beginPage("앱에서 자막 찾기")
        note("OpenSubtitles에서 검색합니다. 검색 버튼을 눌렀을 때만 작품명과 검색 조건이 전송됩니다.")
        note(if (account?.token.isNullOrBlank()) "계정 없이 검색할 수 있습니다. 계정이 있으면 로그인할 수 있습니다."
            else "계정 로그인됨")
        account?.allowedDownloads?.let { note("다운로드 가능 횟수: $it") }
        queryEditor = editor("작품명", queryText, "online-subtitle-query", SUBTITLE_SEARCH_MAX_LENGTH).also {
            it.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            it.setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_SEARCH) false else { search(); true }
            }
        }
        val details = listOfNotNull(year()?.let { "${it}년" }, season()?.let { "시즌 $it" }, episode()?.let { "${it}화" })
        if (details.isNotEmpty()) note(details.joinToString(" · "))
        action("언어: ${languageLabel(language)} · 눌러 변경", "online-subtitle-language") { button ->
            language = LANGUAGES[(LANGUAGES.indexOf(language) + 1) % LANGUAGES.size]
            button.text = "언어: ${languageLabel(language)} · 눌러 변경"
        }
        action("자막 검색", "online-subtitle-search") { search() }
        action("OpenSubtitles 계정", "online-subtitle-settings") {
            rememberQuery(); showSettings()
        }
        currentPage?.let { page ->
            note("검색 결과 ${page.totalCount}개 · ${page.page}/${page.totalPages.coerceAtLeast(1)}페이지")
            if (page.items.isEmpty()) note("검색 결과가 없습니다. 제목이나 언어를 바꿔 다시 검색해 주세요.")
            page.items.forEach { result ->
                action(resultDescription(result), "online-subtitle-result-${result.id}") { showResult(result, result.files.firstOrNull()) }
            }
            if (page.page > 1) action("이전 결과", "online-subtitle-previous") { searchPage(page.page - 1) }
            if (page.hasNextPage) action("다음 결과", "online-subtitle-next") { searchPage(page.page + 1) }
        }
        finishPage("online-subtitle-search")
    }

    private fun search() {
        if (busy) return
        rememberQuery()
        if (queryText.isBlank()) { showStatus("검색할 작품명을 입력해 주세요."); queryEditor?.requestFocus(); return }
        if (account == null) { showUnavailable(); return }
        val request = OpenSubtitlesQuery(queryText, language, season(), episode(), year())
        performSearch(request)
    }

    private fun searchPage(page: Int) {
        val request = lastQuery ?: return
        performSearch(request.copy(page = page))
    }

    private fun performSearch(request: OpenSubtitlesQuery) {
        val saved = account ?: return
        runTask("자막을 찾고 있습니다…") {
            val result = client.search(saved, request)
            lastQuery = request
            currentPage = result
            queryText = request.query
            language = request.language
            showSearch()
            panel.post { controls.firstOrNull { it.tag.toString().startsWith("online-subtitle-result-") }?.requestFocus() }
        }
    }

    private fun showResult(result: OpenSubtitlesResult, selected: OpenSubtitlesFile?) {
        beginPage("다운로드할 자막 확인")
        note(resultDescription(result))
        note("파일과 릴리스를 확인하세요. 아래 다운로드 버튼을 누르면 제공 서비스의 다운로드 횟수가 사용될 수 있습니다.")
        if (result.files.isEmpty()) note("다운로드할 파일이 없습니다. 다른 검색 결과를 선택해 주세요.")
        result.files.forEach { file ->
            val prefix = if (file.fileId == selected?.fileId) "선택됨 · " else "파일 선택 · "
            action(prefix + displayText(file.fileName).ifBlank { "자막 파일" }, "online-subtitle-file-${file.fileId}") {
                showResult(result, file)
            }
        }
        if (selected != null) action("선택한 자막 다운로드", "online-subtitle-download") { download(result, selected) }
        action("검색 결과로", "online-subtitle-back-results") { showSearch() }
        finishPage("online-subtitle-back-results")
    }

    private fun download(result: OpenSubtitlesResult, file: OpenSubtitlesFile) {
        val saved = account ?: return
        runTask("선택한 자막을 내려받고 있습니다…") {
            val downloaded = client.downloadBytes(saved, file.fileId)
            val label = downloaded.fileName.ifBlank { file.fileName }.ifBlank { result.title + ".srt" }
            val record = withContext(Dispatchers.IO) {
                LocalSubtitleWrites.awaitPending()
                LocalSubtitleStore(applicationContext).importBytes(ownerKey, label, downloaded.bytes)
            }
            setResult(Activity.RESULT_OK, Intent()
                .putExtra(SubtitleImportActivity.EXTRA_OWNER_KEY, ownerKey)
                .putExtra(SubtitleImportActivity.EXTRA_SUBTITLE_ID, record.id))
            finish()
        }
    }

    private fun showSettings() {
        beginPage("OpenSubtitles 계정", secure = true)
        note("로그인은 선택 사항입니다. 로그인 비밀번호는 저장하지 않습니다.")
        val userName = editor("OpenSubtitles 계정 이름", account?.userName.orEmpty(), "online-subtitle-user", 200)
        val password = editor("비밀번호", "", "online-subtitle-password", 1024, secret = true)
        secretEditors = listOf(password)
        action("계정 로그인", "online-subtitle-login") {
            val name = userName.text.toString().trim()
            val secret = password.text.toString()
            if (name.isEmpty() || secret.isEmpty()) { showStatus("계정 이름과 비밀번호를 입력해 주세요."); return@action }
            password.text.clear()
            runTask("OpenSubtitles에 로그인하고 있습니다…") {
                val signedIn = client.login(applicationApiKey, name, secret)
                withContext(Dispatchers.IO) { accountStore.save(signedIn) }
                account = signedIn
                showSearch()
                showStatus("로그인했습니다. 검색 버튼을 눌러 자막을 찾으세요.")
            }
        }
        if (!account?.token.isNullOrBlank()) action("로그아웃", "online-subtitle-clear-account") {
            runTask("로그인 정보를 지우고 있습니다…") {
                withContext(Dispatchers.IO) { accountStore.clear() }
                account = OpenSubtitlesAccount(applicationApiKey)
                currentPage = null
                showSearch()
                showStatus("로그아웃했습니다.")
            }
        }
        action("검색 화면으로", "online-subtitle-back-search") { showSearch() }
        finishPage("online-subtitle-user")
    }

    private fun runTask(message: String, operation: suspend () -> Unit) {
        if (busy || isFinishing || isDestroyed) return
        hideKeyboard()
        busy = true
        updateBusy()
        showStatus(message)
        task = scope.launch {
            try {
                operation()
            } catch (_: CancellationException) {
                if (!isFinishing && !isDestroyed) showStatus("작업을 취소했습니다. 원하는 작업을 다시 선택해 주세요.")
            } catch (error: OpenSubtitlesException) {
                val suffix = when {
                    error.loginRequired -> " 설정에서 계정 로그인을 확인해 주세요."
                    error.quotaExceeded -> " 다운로드 가능 횟수가 다시 생긴 뒤 시도해 주세요."
                    else -> ""
                }
                showStatus((error.message ?: "자막 서비스에 연결하지 못했습니다.") + suffix)
            } catch (error: LocalSubtitleException) {
                showStatus(error.message ?: "내려받은 자막을 저장하지 못했습니다.")
            } catch (_: Exception) {
                showStatus("작업을 완료하지 못했습니다. 네트워크와 설정을 확인한 뒤 다시 시도해 주세요.")
            } finally {
                busy = false
                if (!isFinishing && !isDestroyed) updateBusy()
            }
        }
    }

    private fun finishPage(focusTag: String? = null) {
        panel.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        panel.addView(status, LinearLayout.LayoutParams(-1, -2))
        action("진행 중인 작업 취소", "online-subtitle-cancel-task") { task?.cancel() }
        action("닫기", "online-subtitle-close") { task?.cancel(); finish() }
        updateBusy()
        if (focusTag != null) panel.post {
            controls.firstOrNull { it.tag == focusTag && it.isEnabled }?.requestFocus()
        }
    }

    private fun updateBusy() {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        controls.forEach { control ->
            val tag = control.tag
            control.isEnabled = !busy || tag == "online-subtitle-cancel-task" || tag == "online-subtitle-close"
            if (tag == "online-subtitle-cancel-task") control.visibility = if (busy) View.VISIBLE else View.GONE
        }
        val navigable = controls.filter { it.isEnabled && it.visibility == View.VISIBLE }
        navigable.forEachIndexed { index, view ->
            view.nextFocusUpId = navigable[(index - 1).coerceAtLeast(0)].id
            view.nextFocusDownId = navigable[(index + 1).coerceAtMost(navigable.lastIndex)].id
        }
        if (busy) navigable.firstOrNull()?.requestFocus()
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
        status.announceForAccessibility(message)
    }

    private fun editor(hintText: String, value: String, tagName: String, maxLength: Int, secret: Boolean = false): EditText {
        note(hintText)
        return EditText(this).apply {
            id = View.generateViewId(); tag = tagName
            setSingleLine(true); textSize = 16f
            setTextColor(colors.onSurface.toArgb())
            setHintTextColor(colors.onSurfaceVariant.toArgb())
            hint = hintText; contentDescription = hintText
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setText(value); setSelection(text.length)
            controls += this
            panel.addView(this, LinearLayout.LayoutParams(-1, dp(52)))
        }
    }

    private fun action(label: String, tagName: String, clicked: (Button) -> Unit) {
        val button = Button(this).apply {
            id = View.generateViewId(); tag = tagName
            text = label; textSize = 15f; isAllCaps = false
            setTextColor(colors.onSurface.toArgb())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(48); maxLines = 5
            fun selected() = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(this@OnlineSubtitleActivity.colors.surfaceVariant.toArgb())
                setStroke(dp(1), this@OnlineSubtitleActivity.colors.primary.toArgb())
            }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), selected())
                addState(intArrayOf(android.R.attr.state_pressed), selected())
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            backgroundTintList = null; stateListAnimator = null; elevation = 0f
            setOnClickListener { clicked(this) }
        }
        controls += button
        panel.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun note(value: String) { panel.addView(text(value, 14f).apply { setPadding(0, dp(4), 0, dp(8)) }) }
    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(colors.onSurface.toArgb())
    }
    private fun rememberQuery() { queryEditor?.let { queryText = normalizeSubtitleWebSearchQuery(it.text.toString()) } }
    private fun year() = intent.getIntExtra(EXTRA_YEAR, -1).takeIf { it in 1800..2200 }
    private fun season() = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it in 0..999 }
    private fun episode() = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it in 0..9999 }
    private fun hideKeyboard() {
        currentFocus?.windowToken?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(it, 0) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        rememberQuery()
        outState.putString(STATE_QUERY, queryText)
        outState.putString(STATE_LANGUAGE, language)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        task?.cancel()
        secretEditors.forEach { it.text.clear() }
        super.onStop()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val EXTRA_QUERY = "io.mirr.shynigsy.subtitle.QUERY"
        const val EXTRA_YEAR = "io.mirr.shynigsy.subtitle.YEAR"
        const val EXTRA_SEASON = "io.mirr.shynigsy.subtitle.SEASON"
        const val EXTRA_EPISODE = "io.mirr.shynigsy.subtitle.EPISODE"
        const val EXTRA_SPOILER = "io.mirr.shynigsy.subtitle.SPOILER"
        private const val STATE_QUERY = "online_query"
        private const val STATE_LANGUAGE = "online_language"
        private val LANGUAGES = listOf("ko", "en", "all")

        internal fun createIntent(context: Context, ownerKey: String, spec: PlaybackSpec): Intent {
            validateOwner(ownerKey)
            return Intent(context, OnlineSubtitleActivity::class.java)
                .putExtra(SubtitleImportActivity.EXTRA_OWNER_KEY, ownerKey)
                .putExtra(EXTRA_QUERY, normalizeSubtitleWebSearchQuery(spec.controllerTitle))
                .putExtra(EXTRA_YEAR, spec.sourceItem?.year ?: -1)
                .putExtra(EXTRA_SEASON, spec.sourceItem?.seasonNumber ?: -1)
                .putExtra(EXTRA_EPISODE, spec.sourceItem?.episodeNumber ?: -1)
                .putExtra(EXTRA_SPOILER, spec.sourceItem?.isSpoiler == true)
        }

        private fun languageLabel(value: String) = when (value) { "ko" -> "한국어"; "en" -> "영어"; "all" -> "전체"; else -> displayText(value) }
        private fun displayText(value: String) = normalizeSubtitleWebSearchQuery(value)
        private fun resultDescription(result: OpenSubtitlesResult): String = listOfNotNull(
            displayText(result.title).ifBlank { "제목 없음" },
            displayText(result.release).takeIf { it.isNotBlank() },
            listOfNotNull(languageLabel(result.language), result.rating?.let { "평점 ${String.format(Locale.KOREAN, "%.1f", it)}" },
                "다운로드 ${result.downloadCount}회").joinToString(" · "),
        ).joinToString("\n")
    }
}
