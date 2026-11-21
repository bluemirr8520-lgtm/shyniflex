package io.mirr.shynigsy.plex

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.media3.ui.PlayerView
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.lifecycle.ViewModelProvider
import io.mirr.shynigsy.R
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.PlaybackCompletionSignals
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PlexBrowsePlaybackTest {
    @get:Rule val screen = createEmptyComposeRule()

    @Test fun completedNextEpisodeRefreshesTheReturnedItemAndItsSeason() = browseAndReturn("completedEpisode")

    @Test fun partiallyPlayedNextEpisodeRefreshesTheReturnedItemAndItsSeason() = browseAndReturn("playedItem")

    @Test fun authenticatedOriginalAdvancesAndReturnsThroughNativeBack() = browseAndReturn(null)

    private fun browseAndReturn(resultKey: String?) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = SessionStore(instrumentation.targetContext)
        assumeTrue("Use a clean Plex test installation", store.loadLast() == null)
        val media = instrumentation.context.assets.open("media3/gesture-av.mp4").use { it.readBytes() }
        val openedPart = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val refreshedSeason = CountDownLatch(1)
        val fixtureToken = "local-test-token"
        val episode = """{"ratingKey":"31","key":"/library/metadata/31","librarySectionID":"1","type":"episode","title":"Test episode",
            "parentIndex":1,"index":1,"grandparentRatingKey":"11","grandparentTitle":"Test series",
            "duration":6000,"Media":[{"container":"mp4","videoCodec":"h264","Part":[{"id":41,"key":"/library/parts/41/file.mp4"}]}]}"""
        val season = """{"ratingKey":"21","librarySectionID":"1","type":"season","title":"First season","index":1,"parentRatingKey":"11"}"""
        val show = """{"ratingKey":"11","librarySectionID":"1","type":"show","title":"Test series"}"""
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl
                    val partCredential = url?.encodedPath == "/library/parts/41/file.mp4" &&
                        url.queryParameter("X-Plex-Token") == fixtureToken
                    if (request.getHeader("X-Plex-Token") != fixtureToken && !partCredential)
                        return MockResponse().setResponseCode(401)
                    val watchState = when {
                        !returned.get() -> ""
                        resultKey == "completedEpisode" -> ""","viewCount":1"""
                        else -> ""","viewOffset":2000"""
                    }
                    val second = """{"ratingKey":"32","librarySectionID":"1","type":"episode","title":"Second episode",
                        "parentIndex":1,"index":2,"grandparentRatingKey":"11","grandparentTitle":"Test series","duration":6000$watchState}"""
                    val container = when (request.requestUrl?.encodedPath) {
                        "/library/sections" -> """{"Directory":[{"key":"1","type":"show","title":"Fixture TV"}]}"""
                        "/hubs/search" -> """{"Hub":[{"Metadata":[$episode]}]}"""
                        "/library/sections/1/all", "/library/metadata/11" -> """{"Metadata":[$show]}"""
                        "/library/metadata/11/children", "/library/metadata/21" -> """{"Metadata":[$season]}"""
                        "/library/metadata/31" -> """{"Metadata":[$episode]}"""
                        "/library/metadata/32" -> """{"Metadata":[$second]}"""
                        "/library/metadata/21/children" -> {
                            if (returned.get() && PlaybackCompletionSignals.progressWriters.value == 0) refreshedSeason.countDown()
                            """{"Metadata":[$episode,$second]}"""
                        }
                        "/library/parts/41/file.mp4" -> {
                            openedPart.countDown()
                            val range = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull()
                            if (range != null && range !in media.indices) return MockResponse().setResponseCode(416)
                            val response = MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                            if (range != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $range-${media.lastIndex}/${media.size}")
                            val start = range ?: 0
                            return response.setBody(Buffer().write(media, start, media.size - start))
                        }
                        else -> "{}"
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"MediaContainer":$container}""")
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val address = server.url("/").toString().trimEnd('/')
            try {
                ActivityScenario.launch(PlexMainActivity::class.java).use { scenario ->
                    screen.onNodeWithText("Plex 서버 주소").performScrollTo().performTextInput(address)
                    click("서버 주소와 토큰으로 연결")
                    screen.onNodeWithText("Plex 토큰 (X-Plex-Token)").performScrollTo().performTextInput(fixtureToken)
                    click("서버에 연결")
                    clickTag("cinema-mobile-search")
                    screen.onNodeWithTag("global-search-input").performTextInput("Test")
                    clickTag("global-search-submit")
                    click("Test episode")
                    screen.onNodeWithContentDescription("뒤로").performClick()
                    screen.onNode(hasText("Test episode") and hasClickAction()).assertExists()
                    screen.onNodeWithContentDescription("뒤로").performClick()
                    clickTag("cinema-mobile-libraries")
                    listOf("Fixture TV", "Test series").forEach(::click)
                    var detailState = ""
                    try {
                        screen.waitUntil(10_000) {
                            var loaded = false
                            scenario.onActivity { activity ->
                                val model = ViewModelProvider(activity)[PlexBrowseModel::class.java]
                                val page = model.page as? PlexPage.Detail
                                detailState = "type=${page?.item?.type} seasons=${page?.seasons?.size} episodes=${page?.children?.size} busy=${model.busy} error=${model.error}"
                                loaded = page?.children?.isNotEmpty() == true && !model.busy
                            }
                            loaded
                        }
                    } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                        throw AssertionError("Season detail did not load ($detailState)", failure)
                    }
                    screen.onNodeWithTag("compact-detail").performScrollToNode(hasText("Test episode") and hasClickAction())
                    click("Test episode")
                    clickTag("compact-detail-play")
                    assertTrue("Native player requests the authenticated Plex media part", openedPart.await(30, TimeUnit.SECONDS))
                    if (resultKey == null) {
                        var observed = false
                        val deadline = SystemClock.elapsedRealtime() + 25_000
                        while (!observed && SystemClock.elapsedRealtime() < deadline) {
                            instrumentation.runOnMainSync {
                                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                    .filterIsInstance<NativePlayerActivity>().firstOrNull() ?: return@runOnMainSync
                                val active = activity.findViewById<PlayerView>(R.id.media_player_view)?.player ?: return@runOnMainSync
                                active.volume = 0f
                                if (active.isPlaying && active.currentPosition >= 1000 && active.videoSize.width > 0) {
                                    Log.i("PlexPlaybackFixture", "engine=${active.javaClass.simpleName} positionMs=${active.currentPosition} video=${active.videoSize.width}x${active.videoSize.height}")
                                    observed = true
                                    returned.set(true)
                                    activity.onBackPressedDispatcher.onBackPressed()
                                }
                            }
                            if (!observed) SystemClock.sleep(80)
                        }
                        assertTrue("Authenticated MP4 decodes video and advances past one second", observed)
                    } else instrumentation.runOnMainSync {
                        val player = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<NativePlayerActivity>().single()
                        assertFalse(player.intent.getStringExtra("spec").orEmpty().contains(fixtureToken))
                        returned.set(true)
                        // Simulate the result emitted by the player after moving from E1 to E2.
                        val last = MediaItem(32, 1, "Second episode", "episode", showId = 11, seasonNumber = 1, episodeNumber = 2)
                        player.setResult(Activity.RESULT_OK, Intent().putExtra(resultKey, last.toJson()))
                        player.finish()
                    }
                    val expected = when (resultKey) {
                        "completedEpisode" -> "다시 재생"
                        "playedItem" -> "이어보기"
                        else -> "재생"
                    }
                    screen.waitUntil(15_000) {
                        screen.onAllNodes(hasText(expected)).fetchSemanticsNodes().isNotEmpty()
                    }
                    screen.onNodeWithTag("compact-detail-title").assertTextContains(if (resultKey == null) "Test episode" else "Second episode")
                    assertTrue("Season reloaded after queued progress writes", refreshedSeason.await(5, TimeUnit.SECONDS))
                    screen.onNodeWithContentDescription("뒤로").performClick()
                    screen.onNodeWithTag("compact-detail").performScrollToNode(hasText("Second episode") and hasClickAction())
                    screen.onNode(hasText("Second episode") and hasClickAction()).assertExists()
                    if (resultKey == null) {
                        screen.onNodeWithContentDescription("뒤로").performClick()
                        clickTag("cinema-mobile-more")
                        click("서버 연결")
                        screen.onNodeWithText("Plex 서버 주소").assertExists()
                        click("이전 서버에 다시 연결")
                        clickTag("cinema-mobile-libraries")
                        screen.onNodeWithText("Fixture TV").assertExists()
                    }
                }
            } finally { store.clear(address) }
        }
    }

    private fun click(text: String) {
        val match = hasText(text) and hasClickAction() and isEnabled()
        screen.waitUntil(10_000) { screen.onAllNodes(match).fetchSemanticsNodes().isNotEmpty() }
        screen.onNode(match).performScrollTo().performClick()
    }

    private fun clickTag(tag: String) {
        val match = hasTestTag(tag) and isEnabled()
        screen.waitUntil(10_000) { screen.onAllNodes(match).fetchSemanticsNodes().isNotEmpty() }
        screen.onNode(match).performClick()
    }
}
