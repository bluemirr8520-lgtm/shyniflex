package io.mirr.shynigsy

import android.content.Context
import android.content.Intent
import android.view.FocusFinder
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.player.NativePlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The player can be much narrower than the fixed-width controls at large font scales. */
@RunWith(AndroidJUnit4::class)
class PlaybackControlsLayoutTest {
    @Test fun continuousPlaybackIsInitiallyOffAndSurvivesReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val hadPrevious = preferences.contains("continuous_playback")
        val previous = preferences.getBoolean("continuous_playback", false)
        val server = "https://continuous-playback-test.invalid"
        val store = SessionStore(context)
        val spec = PlaybackSpec("test", "$server/stream/1?ticket=test", itemId = 1,
            libraryId = 1, partId = 1, sessionId = "test", viewItemId = 1)
        val intent = Intent(context, NativePlayerActivity::class.java)
            .putExtra("server", server).putExtra("spec", spec.toJson())
        try {
            preferences.edit().remove("continuous_playback").commit()
            store.save(server, "test-token", "test")
            ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val button = activity.findViewById<Button>(R.id.player_continuous)
                    assertFalse(preferences.getBoolean("continuous_playback", false))
                    assertEquals("연속 재생 꺼짐", button.contentDescription.toString())
                    assertTrue(button.performClick())
                    assertTrue(preferences.getBoolean("continuous_playback", false))
                    assertEquals("연속 재생 켜짐", button.contentDescription.toString())
                }
            }
            ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("연속 재생 켜짐",
                        activity.findViewById<Button>(R.id.player_continuous).contentDescription.toString())
                }
            }
        } finally {
            store.clear(server)
            val editor = preferences.edit()
            if (hadPrevious) editor.putBoolean("continuous_playback", previous)
            else editor.remove("continuous_playback")
            editor.commit()
        }
    }

    @Test fun narrowPlayerLetsRemoteFocusReachTheRightmostControl() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controls = activity.layoutInflater.inflate(R.layout.shyniflix_controls, null)
                activity.setContentView(controls)
                val dp = activity.resources.displayMetrics.density
                val width = (460 * dp).toInt()
                val height = (240 * dp).toInt()
                controls.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                controls.layout(0, 0, width, height)

                val scroll = controls.findViewById<HorizontalScrollView>(R.id.player_control_scroller)
                scroll.isSmoothScrollingEnabled = false
                // Mirror the player's TV setup while testing on an Android phone.
                listOf(androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_ffwd,
                    R.id.player_continuous, R.id.player_speed, R.id.player_conversion,
                    R.id.player_subtitles, R.id.player_resize, R.id.player_settings).forEach { id ->
                    controls.findViewById<View>(id).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                }
                val first = controls.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                val last = controls.findViewById<View>(R.id.player_settings)
                assertTrue("The narrow viewport must actually require scrolling", scroll.getChildAt(0).width > scroll.width)
                assertTrue("Playback controls must be focusable", first.requestFocus())
                var current = first
                var count = 0
                while (current !== last && count++ < 16) {
                    val next = FocusFinder.getInstance().findNextFocus(scroll, current, View.FOCUS_RIGHT)
                    assertNotNull("Remote right must reach the remaining controls", next)
                    assertTrue("Remote focus must move", next!!.requestFocus())
                    current = next
                }
                assertEquals("Remote navigation must reach settings", last, current)
                assertTrue("Focusing controls beyond the viewport must scroll them into view", scroll.scrollX > 0)
            }
        }
    }
}
