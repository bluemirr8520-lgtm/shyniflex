package io.mirr.shynigsy.plex

import android.content.Intent
import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.R
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlexClient
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.player.NativePlayerActivity
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PlexResolutionPlaybackTest {
    @Test fun naturalEofStartsTheNextPlexFileWithContinuousPlaybackEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug"))
        val sessions = SessionStore(context)
        assertNull("Use an isolated application with no signed-in account", sessions.loadLast())
        val prefs = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val savedContinuous = prefs.all["continuous_playback"] as? Boolean
        val savedEngine = prefs.getString("playback_engine", null)
        val clip = instrumentation.context.assets.open("media3/gesture-av.mp4").use { it.readBytes() }
        val nextFileOpened = AtomicBoolean(false)
        val token = "fixture-continuation-token"
        fun episode(id: Int): JSONObject {
            val part = JSONObject().put("id", 80 + id).put("key", "/library/parts/${80 + id}/clip.mp4")
            val media = JSONObject().put("container", "mp4").put("videoCodec", "h264").put("Part", JSONArray().put(part))
            return JSONObject().put("type", "episode").put("ratingKey", id).put("librarySectionID", 1)
                .put("index", id - 10).put("title", "Episode ${id - 10}").put("duration", 6000)
                .put("Media", JSONArray().put(media))
        }
        val first = episode(11).put("parentRatingKey", 20).put("grandparentRatingKey", 10)
        val second = episode(12)
        val season = JSONObject().put("type", "season").put("ratingKey", 20).put("index", 1).put("title", "Season 1")
        fun listing(vararg rows: JSONObject) = MockResponse().setBody(JSONObject().put("MediaContainer",
            JSONObject().put("Metadata", JSONArray(rows.toList())).put("size", rows.size)).toString())
        MockWebServer().use { pms ->
            pms.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = requireNotNull(request.requestUrl)
                    if (request.getHeader("X-Plex-Token") != token && url.queryParameter("X-Plex-Token") != token)
                        return MockResponse().setResponseCode(401)
                    return when (url.encodedPath) {
                        "/library/metadata/11" -> listing(first)
                        "/library/metadata/12" -> listing(second)
                        "/library/metadata/10/children" -> listing(season)
                        "/library/metadata/20/children" -> listing(first, second)
                        "/library/parts/91/clip.mp4" -> mediaResponse(clip, "video/mp4", request)
                        "/library/parts/92/clip.mp4" -> {
                            nextFileOpened.set(true)
                            mediaResponse(clip, "video/mp4", request)
                        }
                        else -> listing()
                    }
                }
            }
            pms.start(InetAddress.getByName("127.0.0.1"), 0)
            val server = pms.url("/").toString().trimEnd('/')
            val spec = runBlocking { PlexClient(server, token, "fixture-device").playback(MediaItem(11, 1, "First", "episode")) }
            try {
                sessions.save(server, token, "fixture")
                prefs.edit().putBoolean("continuous_playback", true).putString("playback_engine", "auto").commit()
                val intent = Intent(context, NativePlayerActivity::class.java).putExtra("server", server)
                    .putExtra("spec", spec.toJson()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                    var originalPlayer: Player? = null
                    waitForPlayer(scenario, "First Plex episode playing") { player ->
                        player.volume = 0f
                        (player.isPlaying && player.videoSize.width > 0 && player.currentPosition > 150).also {
                            if (it) originalPlayer = player
                        }
                    }
                    waitForPlayer(scenario, "Next Plex episode plays without a seek or button press") { player ->
                        player.volume = 0f
                        nextFileOpened.get() && player !== originalPlayer && player.isPlaying &&
                            player.videoSize.width > 0 && player.currentPosition > 150
                    }
                }
            } finally {
                sessions.clear(server)
                prefs.edit().apply {
                    if (savedContinuous == null) remove("continuous_playback") else putBoolean("continuous_playback", savedContinuous)
                    if (savedEngine == null) remove("playback_engine") else putString("playback_engine", savedEngine)
                }.commit()
            }
        }
    }

    @Test fun playerSelects720pSeeksAndReturnsToOriginalAtSamePosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug")) { "Use the isolated test application." }
        val sessions = SessionStore(context)
        assertNull("The test app must not contain a real session", sessions.loadLast())
        val assets = instrumentation.context.assets
        val original = assets.open("media3/synthetic-4k-hevc-main10.mp4").use { it.readBytes() }
        val converted = assets.open("media3/exo-embedded-subrip.mkv").use { it.readBytes() }
        val starts = LinkedBlockingQueue<HttpUrl>()
        val stops = LinkedBlockingQueue<String>()
        val token = "fixture-resolution-token"
        MockWebServer().use { pms ->
            pms.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = requireNotNull(request.requestUrl)
                    if (request.getHeader("X-Plex-Token") != token && url.queryParameter("X-Plex-Token") != token)
                        return MockResponse().setResponseCode(401)
                    return when (url.encodedPath) {
                        "/library/metadata/7" -> MockResponse().setBody(metadata)
                        "/library/parts/81/film.mp4" -> mediaResponse(original, "video/mp4", request)
                        "/video/:/transcode/universal/decision" -> MockResponse().setBody(
                            """{"MediaContainer":{"generalDecisionCode":1001,"transcodeDecisionCode":1001}}""")
                        "/video/:/transcode/universal/start.mkv" -> {
                            starts.add(url)
                            mediaResponse(converted, "video/x-matroska", request)
                        }
                        "/video/:/transcode/universal/stop" -> {
                            stops.add(requireNotNull(url.queryParameter("session")))
                            MockResponse()
                        }
                        else -> MockResponse().setBody("""{"MediaContainer":{}}""")
                    }
                }
            }
            pms.start(InetAddress.getByName("127.0.0.1"), 0)
            val server = pms.url("/").toString().trimEnd('/')
            val spec = runBlocking { PlexClient(server, token, "fixture-device").playback(MediaItem(7, 1, "Fixture", "movie")) }
            sessions.save(server, token, "fixture")
            val intent = Intent(context, NativePlayerActivity::class.java).putExtra("server", server).putExtra("spec", spec.toJson())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ActivityScenario.launch<NativePlayerActivity>(intent).use { scenario ->
                    var pausedAt = 0L
                    waitForPlayer(scenario, "original 4K frames") { player ->
                        player.volume = 0f
                        if (player.videoSize.width != 3840 || player.currentPosition < 900) false else {
                            player.pause()
                            pausedAt = player.currentPosition
                            true
                        }
                    }
                    chooseQuality(scenario, "720p")
                    val start = requireNotNull(starts.poll(25, TimeUnit.SECONDS)) { "Plex start request missing" }
                    assertEquals("1280x720", start.queryParameter("videoResolution"))
                    assertEquals("/library/metadata/7", start.queryParameter("path"))
                    assertEquals("0", start.queryParameter("directPlay"))
                    val offset = start.queryParameter("offset")!!.toDouble() * 1000
                    assertEquals(pausedAt.toDouble(), offset, 600.0)
                    waitForPlayer(scenario, "paused conversion decodes lower-resolution frames at original timeline position") {
                        it.videoSize.width in 1..1280 && !it.playWhenReady && abs(it.currentPosition - pausedAt) < 650
                    }
                    val seekTo = 5_000L
                    scenario.onActivity { it.findViewById<PlayerView>(R.id.media_player_view).player!!.seekTo(seekTo) }
                    waitForPlayer(scenario, "converted stream reopens at the selected offset") { player ->
                        starts.any { abs((it.queryParameter("offset")?.toDoubleOrNull() ?: -1.0) * 1000 - seekTo) < 5 } &&
                            player.videoSize.width in 1..1280 && abs(player.currentPosition - seekTo) < 650
                    }
                    assertTrue("Seeking keeps the active transcode session", stops.isEmpty())
                    chooseQuality(scenario, "원본")
                    waitForPlayer(scenario, "original 4K frames resume at converted stream position") {
                        it.videoSize.width == 3840 && !it.playWhenReady && abs(it.currentPosition - seekTo) < 650
                    }
                    assertEquals(start.queryParameter("session"), stops.poll(8, TimeUnit.SECONDS))
                }
            } finally {
                sessions.clear(server)
            }
        }
    }

    private fun chooseQuality(scenario: ActivityScenario<NativePlayerActivity>, label: String) {
        scenario.onActivity { it.findViewById<PlayerView>(R.id.media_player_view).showController() }
        onView(withId(R.id.player_conversion)).check(matches(isDisplayed())).perform(click())
        listOf("원본", "1080p", "720p", "480p").forEach {
            onView(withText(it)).inRoot(isDialog()).check(matches(isDisplayed()))
        }
        onView(withText(label)).inRoot(isDialog()).perform(click())
    }

    private fun waitForPlayer(scenario: ActivityScenario<NativePlayerActivity>, reason: String, check: (Player) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var state = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val active = activity.findViewById<PlayerView>(R.id.media_player_view).player
                if (active != null) {
                    state = "state=${active.playbackState} playing=${active.playWhenReady} pos=${active.currentPosition} width=${active.videoSize.width}"
                    ready = active.playbackState == Player.STATE_READY && check(active)
                }
            }
            if (ready) return
            SystemClock.sleep(60)
        }
        fail("$reason ($state)")
    }

    private fun mediaResponse(bytes: ByteArray, type: String, request: RecordedRequest): MockResponse {
        val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull()
        if (start != null && start !in bytes.indices) return MockResponse().setResponseCode(416)
        val response = MockResponse().setHeader("Content-Type", type).setHeader("Accept-Ranges", "bytes")
        if (start != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
        return response.setBody(Buffer().write(bytes, start ?: 0, bytes.size - (start ?: 0)))
    }

    private val metadata = """{"MediaContainer":{"Metadata":[{
        "ratingKey":"7","librarySectionID":"1","type":"movie","title":"Fixture","duration":11958,
        "Media":[{"videoCodec":"hevc","container":"mp4","Part":[{"id":81,"key":"/library/parts/81/film.mp4"}]}]
    }]}}"""
}
