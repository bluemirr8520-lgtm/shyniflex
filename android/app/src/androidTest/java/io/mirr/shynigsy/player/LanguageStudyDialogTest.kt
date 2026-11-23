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
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic state and callbacks only, in an isolated app; never accesses an account or video. */
@RunWith(AndroidJUnit4::class)
class LanguageStudyDialogTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var helper: LanguageStudyDialog
    private var originalTouchMode = true
    private var changedInputMode = false
    private var state = LanguageStudySnapshot("외부 자막 시간표 사용", "합성 원문입니다.", false, true, false,
        "꺼짐", false, "꺼짐", "1×")
    private val reads = AtomicInteger()
    private var previousCalls = 0
    private var nextCalls = 0
    private var repeatCalls = 0
    private var endCalls = 0
    private var secondaryCalls = 0
    private var secondaryWasDismissed = false
    private var explanationCalls = 0
    private var explanationWasDismissed = false

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

    @Test fun smallLandscapeDialogScrollsToItsLastControlWithoutClippingIt() {
        show(television = false, smallLandscape = true)
        scenario.onActivity {
            val scroll = view("study-dialog") as ScrollView
            val density = scroll.resources.displayMetrics.density
            assertTrue("Short landscape viewport stays bounded: height=${scroll.height}, density=$density, " +
                "resourceHeight=${scroll.resources.displayMetrics.heightPixels}", scroll.height <= (220 * density).toInt())
            assertTrue("The long menu must scroll instead of extending off screen", scroll.getChildAt(0).height > scroll.height)
            scroll.fullScroll(View.FOCUS_DOWN)
        }
        idle()
        scenario.onActivity {
            val close = view("study-close")
            val visible = Rect()
            assertTrue("Close must be reachable by scrolling", close.getGlobalVisibleRect(visible))
            assertEquals("The complete last row stays within the short window", close.height, visible.height())
            assertTrue(close.performClick())
            assertFalse(helper.dialog.isShowing)
        }
    }

    @Test fun televisionSkipsDisabledRowsAndTracksNewlyEnabledRepeatEnd() {
        show(television = true)
        awaitFocus("study-next")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        scenario.onActivity {
            assertTrue("Disabled sentence-repeat row must be skipped", view("study-mark-a").hasFocus())
            assertFalse(view("study-mark-b").isEnabled)
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        scenario.onActivity {
            assertTrue(view("study-mark-a").hasFocus())
            assertTrue(view("study-mark-b").isEnabled)
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        scenario.onActivity { assertTrue(view("study-mark-b").hasFocus()) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        scenario.onActivity {
            assertEquals(1, endCalls)
            assertFalse(view("study-mark-b").isEnabled)
            assertTrue("Focus moves out of a row disabled by the callback", view("study-clear-repeat").hasFocus())
            assertEquals("반복: A–B 반복", (view("study-repeat-state") as TextView).text.toString())
            assertEquals(0, previousCalls)
            assertEquals(0, repeatCalls)
        }
        press(KeyEvent.KEYCODE_DPAD_UP)
        scenario.onActivity { assertTrue(view("study-mark-a").hasFocus()) }
    }

    @Test fun snapshotRefreshUpdatesControlsAndDismissStopsPollingBeforeOpeningSubtitleMenu() {
        show(television = false)
        scenario.onActivity {
            assertFalse(view("study-previous").isEnabled)
            assertTrue(view("study-next").performClick())
            assertEquals(1, nextCalls)
            state = state.copy(sentenceText = "다음 합성 문장", canPrevious = true, canRepeatSentence = true,
                secondaryLabel = "번역 자막", speedLabel = "0.75×")
        }
        awaitValue { view("study-previous").isEnabled }
        scenario.onActivity {
            assertEquals("다음 합성 문장", (view("study-sentence") as TextView).text.toString())
            assertTrue(view("study-repeat-sentence").isEnabled)
            assertEquals("원문·번역 자막 · 번역 자막", (view("study-secondary") as TextView).text.toString())
            assertEquals("배속 · 0.75×", (view("study-speed") as TextView).text.toString())
            assertTrue(view("study-secondary").performClick())
            assertEquals(1, secondaryCalls)
            assertTrue("The child menu opens only after this window closes", secondaryWasDismissed)
            assertFalse(helper.dialog.isShowing)
        }
        val readsAfterDismiss = reads.get()
        SystemClock.sleep(650)
        idle()
        assertEquals("Dismiss removes the scheduled 500 ms refresh", readsAfterDismiss, reads.get())
    }

    @Test fun explanationActionStartsDisabledAndClosesMenuBeforeOpeningExplanation() {
        show(television = false)
        scenario.onActivity {
            assertFalse("Existing snapshots default to unavailable explanation", view("study-explain").isEnabled)
            state = state.copy(canExplain = true)
        }
        awaitValue { view("study-explain").isEnabled }
        scenario.onActivity {
            assertTrue(view("study-explain").performClick())
            assertEquals(1, explanationCalls)
            assertTrue("Explanation must open after the polling menu closes", explanationWasDismissed)
            assertFalse(helper.dialog.isShowing)
        }
    }

    @Suppress("DEPRECATION")
    private fun show(television: Boolean, smallLandscape: Boolean = false) {
        scenario.onActivity { activity ->
            val fixtureConfiguration = Configuration(activity.resources.configuration)
            val fixtureMetrics = DisplayMetrics().apply { setTo(activity.resources.displayMetrics) }
            // Keep the Activity's real window token while giving the production dialog a small viewport.
            // Resources' deprecated constructor may adopt the display's real dimensions; the
            // explicit getters make this synthetic viewport stable before the dialog is created.
            val fixtureResources = object : Resources(activity.assets, fixtureMetrics, fixtureConfiguration) {
                override fun getDisplayMetrics(): DisplayMetrics = fixtureMetrics
                override fun getConfiguration(): Configuration = fixtureConfiguration
            }
            fixtureConfiguration.uiMode = (fixtureConfiguration.uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                if (television) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
            if (smallLandscape) {
                fixtureConfiguration.orientation = Configuration.ORIENTATION_LANDSCAPE
                fixtureConfiguration.screenWidthDp = 640
                fixtureConfiguration.screenHeightDp = 360
                fixtureMetrics.widthPixels = (640 * fixtureMetrics.density).toInt()
                fixtureMetrics.heightPixels = (360 * fixtureMetrics.density).toInt()
            }
            val context = object : ContextThemeWrapper(activity, R.style.Theme_ShyniFlix) {
                override fun getResources(): Resources = fixtureResources
            }
            if (smallLandscape) assertEquals("The fixture must supply the short viewport before creating the dialog",
                (360 * fixtureMetrics.density).toInt(), context.resources.displayMetrics.heightPixels)
            helper = LanguageStudyDialog(context,
                snapshot = { reads.incrementAndGet(); state },
                onPrevious = { previousCalls++ },
                onNext = { nextCalls++ },
                onRepeatSentence = { repeatCalls++ },
                onMarkStart = { state = state.copy(canMarkEnd = true, repeatLabel = "A 시작점 지정됨") },
                onMarkEnd = { endCalls++; state = state.copy(canMarkEnd = false, repeatLabel = "A–B 반복") },
                onClearRepeat = { state = state.copy(repeatLabel = "꺼짐", canMarkEnd = false) },
                onSecondarySubtitles = { secondaryCalls++; secondaryWasDismissed = !helper.dialog.isShowing },
                onSpeed = {},
                onExplain = { explanationCalls++; explanationWasDismissed = !helper.dialog.isShowing })
            helper.show()
        }
        idle()
    }

    private fun awaitFocus(tag: String) = awaitValue {
        helper.dialog.window!!.decorView.hasWindowFocus() && view(tag).hasFocus()
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
