package io.mirr.shynigsy.player

import android.content.Intent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.BuildConfig
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.player.subtitles.OpenSubtitlesAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Local UI contract only. No real API key/account or provider request is needed. */
@RunWith(AndroidJUnit4::class)
class OnlineSubtitleActivityTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun isolateProviderState() {
        check(context.packageName.endsWith(".spoilerqa")) { "Use only the isolated QA application" }
        runBlocking { withContext(Dispatchers.IO) { OpenSubtitlesAccountStore(context).clear() } }
    }

    @After fun clearQaAccount() {
        if (context.packageName.endsWith(".spoilerqa"))
            runBlocking { withContext(Dispatchers.IO) { OpenSubtitlesAccountStore(context).clear() } }
    }

    @Test fun intentContainsOnlySearchMetadataAndOpaqueOwner() {
        val intent = OnlineSubtitleActivity.createIntent(context, OWNER, fixture())
        val extras = intent.extras!!
        assertEquals(6, extras.keySet().size)
        assertEquals(OWNER, extras.getString(SubtitleImportActivity.EXTRA_OWNER_KEY))
        assertEquals("합성 작품", extras.getString(OnlineSubtitleActivity.EXTRA_QUERY))
        assertEquals(2026, extras.getInt(OnlineSubtitleActivity.EXTRA_YEAR))
        assertEquals(2, extras.getInt(OnlineSubtitleActivity.EXTRA_SEASON))
        assertEquals(6, extras.getInt(OnlineSubtitleActivity.EXTRA_EPISODE))
        assertTrue(extras.getBoolean(OnlineSubtitleActivity.EXTRA_SPOILER))
        assertFalse(extras.keySet().any { key -> extras.get(key).toString().contains("private-secret") })
    }

    @Test fun noApplicationKeyExplainsAlternativesWithoutAskingUserForAKey() {
        assumeTrue(BuildConfig.OPENSUBTITLES_API_KEY.isBlank())
        ActivityScenario.launch<OnlineSubtitleActivity>(OnlineSubtitleActivity.createIntent(context, OWNER, fixture())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertNull(view(activity, "online-subtitle-query"))
                assertNull(view(activity, "online-subtitle-api-key"))
                assertTrue(view(activity, "online-subtitle-close") is Button)
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertTrue(allText(root).contains("서비스 연동 준비 중"))
            }
        }
    }

    @Test fun settingsMasksPasswordAndEmptySearchDoesNotSendARequest() {
        assumeTrue(BuildConfig.OPENSUBTITLES_API_KEY.isNotBlank())
        ActivityScenario.launch<OnlineSubtitleActivity>(OnlineSubtitleActivity.createIntent(context, OWNER, fixture())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            await(scenario) { view(it, "online-subtitle-settings")?.isEnabled == true }
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                (view(activity, "online-subtitle-settings") as Button).performClick()
                val password = view(activity, "online-subtitle-password") as EditText
                assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, password.inputType and InputType.TYPE_MASK_VARIATION)
                assertFalse(password.isSaveEnabled)
                assertNull(view(activity, "online-subtitle-api-key"))
                (view(activity, "online-subtitle-login") as Button).performClick()
                assertEquals("계정 이름과 비밀번호를 입력해 주세요.", (view(activity, "online-subtitle-status") as TextView).text.toString())
                (view(activity, "online-subtitle-back-search") as Button).performClick()
            }
            await(scenario) { view(it, "online-subtitle-search")?.isEnabled == true }
            scenario.onActivity { activity ->
                val language = view(activity, "online-subtitle-language") as Button
                language.performClick(); language.performClick()
                assertTrue(language.text.contains("전체"))
                (view(activity, "online-subtitle-query") as EditText).setText(" \n ")
                (view(activity, "online-subtitle-search") as Button).performClick()
                assertEquals("검색할 작품명을 입력해 주세요.", (view(activity, "online-subtitle-status") as TextView).text.toString())
            }
        }
    }

    private fun await(scenario: ActivityScenario<OnlineSubtitleActivity>, predicate: (OnlineSubtitleActivity) -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(40)
        }
        throw AssertionError("Local subtitle UI did not become ready")
    }

    private fun view(activity: OnlineSubtitleActivity, tag: String): View? =
        activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag(tag)

    private fun allText(view: View): String = when (view) {
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { allText(view.getChildAt(it)) }
        is TextView -> view.text.toString()
        else -> ""
    }

    private fun fixture() = PlaybackSpec(
        "에피소드", "https://private-secret.invalid/play?token=private-secret",
        headers = mapOf("Authorization" to "private-secret"), itemId = 1, libraryId = 2, partId = 3,
        sourceItem = MediaItem(1, 2, "에피소드", "episode", showTitle = "합성 작품", year = 2026,
            seasonNumber = 2, episodeNumber = 6, isSpoiler = true),
    )

    companion object { private val OWNER = "a".repeat(64) }
}
