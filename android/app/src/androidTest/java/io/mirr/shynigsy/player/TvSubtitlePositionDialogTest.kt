package io.mirr.shynigsy.player

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual dialog/window key dispatch with synthetic callbacks; no player, account, or network is created. */
@RunWith(AndroidJUnit4::class)
class TvSubtitlePositionDialogTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var helper: TvSubtitlePositionDialog
    private val previews = mutableListOf<Pair<Int, Int>>()
    private val commits = mutableListOf<Pair<Int, Int>>()
    private var dismissals = 0

    @Before fun launchOnlyTheIsolatedSyntheticApplication() {
        assertEquals("Only the isolated QA application may show subtitle position fixtures",
            "io.mirr.shyniflex.spoilerqa", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After fun closeSyntheticWindows() {
        if (::scenario.isInitialized) {
            scenario.onActivity { if (::helper.isInitialized) helper.dismiss() }
            scenario.close()
        }
    }

    @Test fun openingConfirmAndOrphanReleaseNeverMoveOrCloseTheDialog() {
        val beforeOpening = SystemClock.uptimeMillis() - 100
        show(12, 8)
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, beforeOpening, beforeOpening + 500, repeat = 2)
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, beforeOpening, beforeOpening + 550)
        focus(UP)
        val orphan = freshTime()
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, orphan, orphan + 80, repeat = 1)
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, orphan, orphan + 100)
        scenario.onActivity {
            assertTrue(helper.dialog.isShowing)
            assertTrue(view(UP).hasFocus())
            assertTrue(previews.isEmpty()); assertTrue(commits.isEmpty()); assertEquals(0, dismissals)
        }
    }

    @Test fun freshAndHeldConfirmKeepOneWindowAndCommitOnlyTheReleasedPosition() {
        show(5, 10)
        focus(UP)
        val originalWindow = helper.dialog.window
        val originalView = viewOnMain(UP)
        val downAt = freshTime()
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, downAt, downAt)
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, downAt, downAt + 20, repeat = 1)
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, downAt, downAt + 80, repeat = 2)
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, downAt, downAt + 160, repeat = 3)
        scenario.onActivity {
            assertEquals(listOf(5 to 9, 5 to 8, 5 to 7), previews)
            assertTrue("Preview must not persist on every held-key event", commits.isEmpty())
        }
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, downAt, downAt + 200)
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, downAt, downAt + 240)
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, downAt, downAt + 260)
        scenario.onActivity {
            assertEquals(listOf(5 to 7), commits)
            assertEquals(3, previews.size)
            assertSame(originalWindow, helper.dialog.window)
            assertSame(originalView, view(UP))
            assertTrue(helper.dialog.isShowing && view(UP).hasFocus())
            assertEquals("가로 +5% · 세로 +7%", (view(STATUS) as TextView).text.toString())
        }
    }

    @Test fun focusAndWindowLossCancelHeldInputWhilePositionRemainsBounded() {
        show(79, -79)
        focus(RIGHT)
        val first = freshTime()
        send(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, first, first)
        send(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, first, first + 80, repeat = 1)
        focus(UP)
        send(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, first, first + 100)
        val second = first + 400
        send(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, second, second)
        scenario.onActivity { helper.dialog.window!!.decorView.dispatchWindowFocusChanged(false) }
        scenario.onActivity { helper.dialog.window!!.decorView.dispatchWindowFocusChanged(true) }
        focus(UP)
        send(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, second, second + 80, repeat = 1)
        send(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, second, second + 120)
        focus(DOWN)
        val third = second + 400
        send(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, third, third)
        send(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, third, third + 100)
        scenario.onActivity {
            assertEquals(listOf(80 to -79, 80 to -80, 80 to -79), previews)
            assertEquals(previews, commits)
            assertTrue(previews.all { (x, y) -> x in -80..80 && y in -80..80 })
            assertTrue(helper.dialog.isShowing)
        }
    }

    @Test fun directionalNavigationReachesResetAndCloseWithoutRepeatingTheirActions() {
        show(12, -7)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertFocused(DOWN)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertFocused(RESET)
        val resetAt = freshTime()
        send(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN, resetAt, resetAt)
        send(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN, resetAt, resetAt + 80, repeat = 1)
        send(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN, resetAt, resetAt + 160, repeat = 2)
        send(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_UP, resetAt, resetAt + 200)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertFocused(UP)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused(RIGHT)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocused(DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocused(CLOSE)
        val canceledClose = resetAt + 800
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, canceledClose, canceledClose)
        scenario.onActivity { helper.dialog.window!!.decorView.dispatchWindowFocusChanged(false) }
        scenario.onActivity { helper.dialog.window!!.decorView.dispatchWindowFocusChanged(true) }
        focus(CLOSE)
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, canceledClose, canceledClose + 80)
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, canceledClose, canceledClose + 120)
        scenario.onActivity { assertTrue("A canceled Close press cannot rearm on a duplicate DOWN", helper.dialog.isShowing) }
        val closeAt = resetAt + 1600
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, closeAt, closeAt)
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, closeAt, closeAt + 100, repeat = 1)
        scenario.onActivity { assertTrue("Close requires its matching release", helper.dialog.isShowing) }
        send(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, closeAt, closeAt + 160)
        scenario.onActivity {
            assertEquals(listOf(0 to 0), previews)
            assertEquals(listOf(0 to 0), commits)
            assertEquals(1, dismissals)
            assertTrue(!helper.dialog.isShowing)
        }
    }

    private fun show(x: Int, y: Int) {
        scenario.onActivity { activity ->
            helper = TvSubtitlePositionDialog(activity, x, y,
                onPreview = { nextX, nextY -> previews += nextX to nextY },
                onCommit = { nextX, nextY -> commits += nextX to nextY },
                onDismiss = { dismissals++ })
            helper.show()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFocused(CLOSE)
    }

    private fun focus(tag: String) {
        scenario.onActivity { assertTrue(view(tag).requestFocus()) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFocused(tag)
    }

    private fun assertFocused(tag: String) = scenario.onActivity { assertTrue("$tag must retain real View focus", view(tag).hasFocus()) }

    private fun press(key: Int) {
        // Normal focus navigation is performed by ViewRoot, so enter through the real input pipeline.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    private fun send(key: Int, action: Int, downAt: Long, eventAt: Long, repeat: Int = 0) {
        scenario.onActivity {
            assertTrue("The actual dialog must handle this remote key",
                helper.dialog.dispatchKeyEvent(KeyEvent(downAt, eventAt, action, key, repeat, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD)))
        }
    }

    private fun view(tag: String): View = requireNotNull(helper.dialog.window!!.decorView.findViewWithTag<View>(tag))
    private fun viewOnMain(tag: String): View {
        var found: View? = null
        scenario.onActivity { found = view(tag) }
        return requireNotNull(found)
    }
    private fun freshTime() = SystemClock.uptimeMillis() + 500

    private companion object {
        const val UP = "tv-subtitle-position-up"
        const val DOWN = "tv-subtitle-position-down"
        const val RIGHT = "tv-subtitle-position-right"
        const val RESET = "tv-subtitle-position-reset"
        const val CLOSE = "tv-subtitle-position-close"
        const val STATUS = "tv-subtitle-position-status"
    }
}
