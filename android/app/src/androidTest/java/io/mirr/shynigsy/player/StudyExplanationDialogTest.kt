package io.mirr.shynigsy.player

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic English text in the isolated fixture app; no media or account access. */
@RunWith(AndroidJUnit4::class)
class StudyExplanationDialogTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var helper: StudyExplanationDialog
    private var originalTouchMode = true
    private var changedInputMode = false

    @Suppress("DEPRECATION")
    @Before fun launchIsolatedApplication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        assertTrue("Use an isolated debug or spoiler QA application only",
            packageName.endsWith(".debug") || packageName.endsWith(".spoilerqa"))
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity {
            it.setTheme(R.style.Theme_ShyniFlix)
            originalTouchMode = it.window.decorView.isInTouchMode
        }
        instrumentation.setInTouchMode(false)
        changedInputMode = true
    }

    @Suppress("DEPRECATION")
    @After fun closeFixture() {
        try {
            if (::scenario.isInitialized) {
                scenario.onActivity { if (::helper.isInitialized) helper.dismiss() }
                scenario.close()
            }
        } finally {
            if (changedInputMode) InstrumentationRegistry.getInstrumentation().setInTouchMode(originalTouchMode)
        }
    }

    @Test fun smallLandscapeLongResultScrollsWhileCloseStaysFullyVisible() {
        show(television = false, smallLandscape = true)
        scenario.onActivity { helper.showResult(longResult()) }
        idle()
        scenario.onActivity {
            val scroll = view("study-explanation-scroll") as ScrollView
            assertTrue("Long explanation scrolls inside the window", scroll.canScrollVertically(1))
            assertTrue("Result has a usable viewport", scroll.height >= (48 * scroll.resources.displayMetrics.density).toInt())
            assertCloseVisible()
            scroll.fullScroll(View.FOCUS_DOWN)
        }
        idle()
        scenario.onActivity {
            val unknown = view("study-explanation-unknown") as TextView
            assertTrue(unknown.text.contains("등록되지 않은 단어의 뜻은 추측하지 않습니다."))
            assertEquals(View.VISIBLE, view("study-explanation-truncated").visibility)
            assertCloseVisible()
            helper.dialog.cancel()
            assertFalse(helper.dialog.isShowing)
            helper.showResult(longResult())
            assertFalse("Cancellation must reject results even before onDismiss is delivered", helper.dialog.isShowing)
        }
        idle()
        scenario.onActivity {
            helper.showResult(longResult())
            assertFalse("An asynchronous result must not reopen an externally cancelled dialog", helper.dialog.isShowing)
        }
    }

    @Test fun televisionKeepsFocusWhenResultArrivesAndCanScrollThenCloseWithDpad() {
        show(television = true)
        awaitValue { view("study-explanation-scroll").hasFocus() }
        lateinit var originalScroll: View
        lateinit var originalSource: View
        scenario.onActivity {
            originalScroll = view("study-explanation-scroll")
            originalSource = view("study-explanation-source")
            helper.showResult(longResult())
        }
        idle()
        scenario.onActivity {
            assertSame(originalScroll, view("study-explanation-scroll"))
            assertSame(originalSource, view("study-explanation-source"))
            assertTrue("Completing analysis preserves TV focus", originalScroll.hasFocus())
        }
        var closeFocused = false
        repeat(100) {
            if (!closeFocused) {
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                scenario.onActivity { closeFocused = view("study-explanation-close").hasFocus() }
            }
        }
        scenario.onActivity {
            assertTrue("The remote can reach close after scrolling the complete result", closeFocused)
            assertTrue((view("study-explanation-scroll") as ScrollView).scrollY > 0)
            assertCloseVisible()
        }
        press(KeyEvent.KEYCODE_DPAD_UP)
        scenario.onActivity { assertTrue(view("study-explanation-scroll").hasFocus()) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        scenario.onActivity { assertTrue(view("study-explanation-close").hasFocus()) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        scenario.onActivity { assertFalse(helper.dialog.isShowing) }
    }

    private fun longResult() = StudyExplanation(
        sourceText = List(45) { "I have been looking forward to seeing you. This is sentence $it." }.joinToString("\n"),
        vocabulary = List(24) { StudyVocabulary("expression $it", "학습용 합성 어휘의 한국어 설명입니다.", "표현") },
        grammar = List(8) { StudyGrammar("현재완료 진행 $it", "과거부터 현재까지 계속되는 동작을 나타냅니다.", "I have been looking forward to seeing you.") },
        unknownWords = listOf("unlistedword"),
        truncated = true,
    )

    @Suppress("DEPRECATION")
    private fun show(television: Boolean, smallLandscape: Boolean = false) {
        scenario.onActivity { activity ->
            val configuration = Configuration(activity.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                    if (television) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
                if (smallLandscape) {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                    screenWidthDp = 640
                    screenHeightDp = 360
                }
            }
            val metrics = DisplayMetrics().apply {
                setTo(activity.resources.displayMetrics)
                if (smallLandscape) { widthPixels = (640 * density).toInt(); heightPixels = (360 * density).toInt() }
            }
            val fixtureResources = Resources(activity.assets, metrics, configuration)
            val context = object : ContextThemeWrapper(activity, R.style.Theme_ShyniFlix) {
                override fun getResources(): Resources = fixtureResources
            }
            helper = StudyExplanationDialog(context, "A–B 반복 · 영어 원문")
            helper.showLoading()
        }
        idle()
    }

    private fun assertCloseVisible() {
        val close = view("study-explanation-close")
        val visible = Rect()
        assertTrue(close.getGlobalVisibleRect(visible))
        assertEquals("The entire close button remains visible", close.height, visible.height())
    }

    private fun awaitValue(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 4_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        scenario.onActivity { assertTrue("Dialog state did not settle", condition()) }
    }

    private fun press(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        idle()
    }

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    private fun view(tag: String): View = requireNotNull(helper.dialog.window!!.decorView.findViewWithTag<View>(tag))
}
