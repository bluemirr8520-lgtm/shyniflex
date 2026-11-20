package io.mirr.shynigsy.player

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real dialog focus and persistence in the isolated QA app, without media or an account. */
@RunWith(AndroidJUnit4::class)
class PictureSettingsDialogTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var helper: PictureSettingsDialog
    private lateinit var preferences: SharedPreferences
    private lateinit var store: PicturePresetStore
    private var current = PictureSettings()
    private val applied = mutableListOf<PictureSettings>()
    private var originalTouchMode = true
    private var changedInputMode = false

    @Suppress("DEPRECATION")
    @Before fun launchIsolatedApplication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Only the isolated QA application may show picture fixtures", "io.mirr.shyniflex.spoilerqa", context.packageName)
        preferences = context.getSharedPreferences("picture-dialog-fixture", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = PicturePresetStore(preferences)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity {
            it.setTheme(io.mirr.shynigsy.R.style.Theme_ShyniFlix)
            originalTouchMode = it.window.decorView.isInTouchMode
        }
        // This fixture explicitly creates the TV dialog on a phone. A phone's touch mode
        // prevents its remote-only rows from receiving focus and consumes the first DPAD
        // event merely to leave touch mode. Establish the real remote input mode first.
        instrumentation.setInTouchMode(false)
        changedInputMode = true
        idle()
    }

    @Suppress("DEPRECATION")
    @After fun closeFixture() {
        try {
            if (::scenario.isInitialized) {
                scenario.onActivity { if (::helper.isInitialized) helper.dismiss() }
                scenario.close()
            }
        } finally {
            if (::preferences.isInitialized) preferences.edit().clear().commit()
            if (changedInputMode) InstrumentationRegistry.getInstrumentation().setInTouchMode(originalTouchMode)
        }
    }

    @Test fun remoteAdjustmentsPreserveFocusAndSupportNoteWhileDownNavigates() {
        show()
        val originalWindow = helper.dialog.window
        val originalRow = onMainView("picture-brightness")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        scenario.onActivity {
            assertEquals(PictureSettings(1, 0, 0), current)
            assertEquals(current, store.readCurrent())
            assertSame(originalWindow, helper.dialog.window)
            assertSame(originalRow, view("picture-brightness"))
            assertTrue(originalRow.hasFocus())
            assertEquals("적용 값 1", (view("picture-support-note") as TextView).text.toString())
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        scenario.onActivity { assertTrue(view("picture-contrast").hasFocus()) }
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        scenario.onActivity { assertEquals(PictureSettings(1, -1, 0), current) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        scenario.onActivity { assertTrue(view("picture-saturation").hasFocus()) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        scenario.onActivity { assertTrue(view("picture-reset").hasFocus()) }
    }

    @Test fun staleOpeningConfirmAndPresetFocusNeverApplyAnImageChange() {
        val oldDown = SystemClock.uptimeMillis() - 100
        show()
        click("picture-presets")
        val first = builtInPicturePresets()[1]
        scenario.onActivity {
            assertTrue(view("picture-builtin-${first.id}").requestFocus())
            for ((action, repeat) in listOf(KeyEvent.ACTION_DOWN to 2, KeyEvent.ACTION_UP to 0)) {
                assertTrue(helper.dialog.dispatchKeyEvent(KeyEvent(oldDown, SystemClock.uptimeMillis(), action,
                    KeyEvent.KEYCODE_DPAD_CENTER, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD)))
            }
            assertTrue(applied.isEmpty())
            assertTrue(helper.dialog.isShowing)
        }
        click("picture-builtin-${first.id}")
        scenario.onActivity { assertEquals(listOf(first.settings), applied) }
    }

    @Test fun lgModeFocusDoesNotApplyAndEveryModeKeepsItsNameEvenWhenNeutral() {
        val saved = store.save("보관할 프리셋", PictureSettings(9, 3, -8))
        show()
        click("picture-presets")
        scenario.onActivity {
            assertEquals("화면 모드", (view("picture-lg-section") as TextView).text.toString())
            assertTrue((view("picture-lg-note") as TextView).text.contains("밝기·대비·채도를 조절하는"))
            assertTrue(view("picture-lg-lg_game").requestFocus())
            assertTrue(applied.isEmpty())
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        scenario.onActivity {
            assertTrue(view("picture-lg-lg_bright_room").hasFocus())
            assertTrue(applied.isEmpty())
        }
        for (preset in lgInspiredPicturePresets()) {
            click("picture-lg-${preset.id}")
            scenario.onActivity {
                assertEquals(preset.settings, current)
                assertEquals(preset, PicturePresetStore(preferences).currentPreset())
                assertEquals("현재 프리셋 · ${preset.name}", (view("picture-selected-preset") as TextView).text.toString())
            }
            click("picture-presets")
        }
        click("picture-back")
        scenario.onActivity { helper.dismiss() }
        show()
        scenario.onActivity {
            assertEquals("현재 프리셋 · 필름메이커 느낌", (view("picture-selected-preset") as TextView).text.toString())
            assertEquals(listOf(saved), store.list())
        }
        click("picture-reset")
        scenario.onActivity {
            assertEquals(PictureSettings(), current)
            assertEquals("builtin_original", store.currentPreset()?.id)
            assertEquals("현재 프리셋 · 원본", (view("picture-selected-preset") as TextView).text.toString())
            assertEquals(listOf(saved), store.list())
        }
    }

    @Test fun adjustingAnAppliedLgModeChangesTheNameToCustomWithoutChangingSavedPresets() {
        show()
        click("picture-presets")
        click("picture-lg-lg_vivid")
        scenario.onActivity { assertTrue(view("picture-brightness").requestFocus()) }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        scenario.onActivity {
            assertEquals(lgInspiredPicturePresets().first { it.id == "lg_vivid" }.settings.copy(brightness = 4), current)
            assertNull(store.currentPreset())
            assertEquals("현재 프리셋 · 사용자 조절", (view("picture-selected-preset") as TextView).text.toString())
        }
    }

    @Test fun invalidAndDuplicateNamesKeepTheEditorAndNeverOverwriteTheExistingPreset() {
        val existing = store.save("기존 설정", PictureSettings(8, 9, 10))
        current = PictureSettings(3, 4, 5)
        show()
        click("picture-save-current")
        val originalWindow = helper.dialog.window
        val editor = onMainView("picture-preset-name") as EditText
        scenario.onActivity {
            assertEquals(0, helper.dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            editor.setText(" ")
        }
        click("picture-name-save")
        scenario.onActivity {
            assertSame(editor, view("picture-preset-name"))
            assertSame(originalWindow, helper.dialog.window)
            assertEquals(View.VISIBLE, view("picture-error").visibility)
            editor.setText("기존 설정")
        }
        click("picture-name-save")
        scenario.onActivity {
            assertSame(editor, view("picture-preset-name"))
            assertEquals(listOf(existing), store.list())
            editor.setText("새 설정")
        }
        click("picture-name-save")
        scenario.onActivity {
            assertEquals(listOf("기존 설정", "새 설정"), store.list().map { it.name })
            assertEquals(current, store.list().last().settings)
            assertEquals(listOf(existing, store.list().last()), PicturePresetStore(preferences).list())
            assertTrue(applied.isEmpty())
        }
    }

    @Test fun savedPresetActionsRequireExplicitConfirmationAndDismissNeverSavesTypedText() {
        val saved = store.save("내 영상", PictureSettings(7, 8, 9))
        current = PictureSettings(1, 2, 3)
        show()
        click("picture-presets")
        click("picture-custom-${saved.id}")
        scenario.onActivity { assertTrue(applied.isEmpty()) }
        click("picture-overwrite")
        click("picture-cancel")
        scenario.onActivity { assertEquals(saved, store.list().single()) }
        click("picture-custom-${saved.id}")
        click("picture-overwrite")
        click("picture-confirm")
        scenario.onActivity { assertEquals(current, store.list().single().settings) }
        click("picture-custom-${saved.id}")
        click("picture-delete")
        click("picture-cancel")
        scenario.onActivity { assertEquals(1, store.list().size) }
        click("picture-custom-${saved.id}")
        click("picture-delete")
        click("picture-confirm")
        scenario.onActivity { assertTrue(store.list().isEmpty()) }
        click("picture-save-current")
        scenario.onActivity {
            (view("picture-preset-name") as EditText).setText("저장하지 않을 이름")
            helper.dismiss()
            assertFalse(helper.dialog.isShowing)
            assertTrue(store.list().isEmpty())
        }
    }

    private fun show() {
        scenario.onActivity { activity ->
            helper = PictureSettingsDialog(activity, true, store, { current }, { value ->
                current = value; applied += value; store.writeCurrent(value)
            }, supportNote = { "적용 값 ${current.brightness}" })
            helper.show()
        }
        idle()
        awaitInitialRemoteFocus()
    }

    private fun awaitInitialRemoteFocus() {
        val deadline = SystemClock.elapsedRealtime() + 4_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity {
                val decor = helper.dialog.window!!.decorView
                ready = helper.dialog.isShowing && decor.hasWindowFocus() && !decor.isInTouchMode &&
                    view("picture-brightness").hasFocus()
            }
            if (ready) return
            Thread.sleep(25)
        }
        scenario.onActivity {
            val decor = helper.dialog.window!!.decorView
            assertFalse("The TV fixture must enter remote input mode before dispatching keys", decor.isInTouchMode)
            assertTrue("The actual dialog window must own input focus", decor.hasWindowFocus())
            assertTrue("The production dialog must focus brightness without a synthetic key or forced focus",
                view("picture-brightness").hasFocus())
        }
    }

    private fun click(tag: String) {
        scenario.onActivity { assertTrue(view(tag).performClick()) }
        idle()
    }

    private fun press(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        idle()
    }

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    private fun view(tag: String): View = requireNotNull(helper.dialog.window!!.decorView.findViewWithTag<View>(tag))
    private fun onMainView(tag: String): View {
        var result: View? = null
        scenario.onActivity { result = view(tag) }
        return requireNotNull(result)
    }
}
