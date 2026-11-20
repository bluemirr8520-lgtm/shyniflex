package io.mirr.shynigsy

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.PlaybackSpec
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.data.SubtitleTrack
import io.mirr.shynigsy.player.NativePlayerActivity
import io.mirr.shynigsy.player.PlaybackEnginePreference
import io.mirr.shynigsy.player.mpv.MpvPlayer
import io.mirr.shynigsy.player.subtitles.LocalSubtitleStore
import io.mirr.shynigsy.player.subtitles.LocalSubtitleWrites
import io.mirr.shynigsy.player.subtitles.PlexSubtitleOverlayView
import io.mirr.shynigsy.player.subtitles.localSubtitleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production playback paths with synthetic media and account data in the isolated QA package. */
@RunWith(AndroidJUnit4::class)
class KoreanSubtitlePlaybackTest {
    @Test fun koreanMovieSavedLocalStartsOffAndManualChoiceSurvivesEngineSwitch() =
        fixture(PlaybackEnginePreference.MPV) { f ->
            val spec = f.spec(korean = true)
            f.seed(spec)
            f.launch(spec).use { scenario ->
                awaitReady(scenario, PlaybackEnginePreference.MPV, waitForLocal = true)
                scenario.onActivity(::assertOff)
                select(scenario) { it.startsWith("불러온 자막 ·") }
                awaitCue(scenario, FIRST)
                scenario.onActivity { field<Player>(it, "player").seekTo(4_000) }
                awaitCue(scenario, SECOND)
                switchEngine(scenario, PlaybackEnginePreference.EXO)
                awaitReady(scenario, PlaybackEnginePreference.EXO)
                awaitCue(scenario, SECOND)
                // Configuration recreation is still this playback, so the explicit choice survives.
                scenario.recreate()
                awaitReady(scenario, PlaybackEnginePreference.EXO)
                awaitCue(scenario, SECOND)
            }
        }

    @Test fun koreanEpisodeServerSubtitleStaysOffUntilManualSelectionInExo() =
        fixture(PlaybackEnginePreference.EXO) { f ->
            val spec = f.spec(korean = true, episode = true, serverSubtitle = true)
            f.launch(spec).use { scenario ->
                awaitReady(scenario, PlaybackEnginePreference.EXO)
                scenario.onActivity(::assertOff)
                assertEquals("Default off must not download the subtitle body", 0, f.server.subtitleRequests.get())
                select(scenario) { it.contains("서버 외부") }
                awaitCue(scenario, FIRST)
                assertTrue(f.server.subtitleRequests.get() > 0)
                scenario.onActivity { field<Player>(it, "player").seekTo(4_000) }
                awaitCue(scenario, SECOND)
                switchEngine(scenario, PlaybackEnginePreference.MPV)
                awaitReady(scenario, PlaybackEnginePreference.MPV)
                awaitCue(scenario, SECOND)
            }
        }

    @Test fun nextKoreanEpisodeResetsManualChoiceButKeepsStoredFileAvailable() =
        fixture(PlaybackEnginePreference.MPV) { f ->
            val first = f.spec(korean = true, episode = true)
            val next = f.spec(korean = true, episode = true, part = 78)
            f.seed(first); f.seed(next)
            f.launch(first).use { scenario ->
                awaitReady(scenario, PlaybackEnginePreference.MPV, waitForLocal = true)
                select(scenario) { it.startsWith("불러온 자막 ·") }
                awaitCue(scenario, FIRST)
                scenario.onActivity { activity ->
                    // This is the same replacement path used after the next-episode ticket resolves.
                    val restoreType = NativePlayerActivity::class.java.declaredClasses.single { it.simpleName == "ConversionRestore" }
                    val ctor = restoreType.declaredConstructors.single { it.parameterCount == 11 }.apply { isAccessible = true }
                    val previous = ctor.newInstance(first, 0L, false, null, null, null, null, 6_000L,
                        true, true, field<String?>(activity, "localSubtitleActiveId"))
                    NativePlayerActivity::class.java.getDeclaredMethod("applyConversionPlayback", PlaybackSpec::class.java, restoreType)
                        .apply { isAccessible = true }.invoke(activity, next, previous)
                }
                await(scenario, "The next episode must replace the original") {
                    field<PlaybackSpec>(it, "spec").partId == 78 && field<Any?>(it, "localSubtitleRecord") != null
                }
                awaitReady(scenario, PlaybackEnginePreference.MPV, waitForLocal = true, minimumPosition = 0)
                scenario.onActivity(::assertOff)
                select(scenario) { it.startsWith("불러온 자막 ·") }
                awaitCue(scenario, FIRST)
            }
        }

    @Test fun foreignMovieWithKoreanTitleKeepsAutomaticLocalSubtitle() =
        fixture(PlaybackEnginePreference.EXO) { f ->
            val spec = f.spec(korean = false)
            f.seed(spec)
            f.launch(spec).use { scenario ->
                awaitReady(scenario, PlaybackEnginePreference.EXO, waitForLocal = true)
                awaitCue(scenario, FIRST)
            }
        }

    @Test fun koreanNativeEmbeddedSubtitleRemainsOffThenCanBeSelected() =
        fixture(PlaybackEnginePreference.MPV, "media3/native-subtitle.mkv") { f ->
            val spec = f.spec(korean = true).copy(subtitles = listOf(SubtitleTrack(
                7, "한국어", "${f.server.origin}/must-not-extract", "application/x-subrip",
                source = "embedded", language = "kor", streamIndex = 2, format = "subrip",
            )), selectedSubtitleId = 7, sourceContainer = "mkv")
            f.launch(spec).use { scenario ->
                // The fixture's only cue spans 1.021–4.021s; pause inside its actual interval.
                awaitReady(scenario, PlaybackEnginePreference.MPV, minimumPosition = 1_500)
                scenario.onActivity { activity ->
                    assertOff(activity)
                    assertFalse(field<Player>(activity, "player").currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected })
                }
                select(scenario) { it.contains("서버 내장") }
                awaitCue(scenario, "한글 내장 자막 확인")
            }
        }

    @Test fun homeEpisodeLateKoreanOriginDisablesEmbeddedMpvWithoutRestart() =
        verifyDeferredEmbeddedOrigin(PlaybackEnginePreference.MPV)

    @Test fun homeEpisodeLateKoreanOriginDisablesEmbeddedExoWithoutRestart() =
        verifyDeferredEmbeddedOrigin(PlaybackEnginePreference.EXO)

    private fun verifyDeferredEmbeddedOrigin(engine: PlaybackEnginePreference) =
        fixture(engine, "media3/exo-embedded-subrip.mkv") { f ->
            val spec = f.unknownEmbeddedEpisode()
            f.holdOrigin(spec)
            f.launch(spec).use { scenario ->
                awaitReady(scenario, engine, minimumPosition = 1_000)
                assertTrue("Origin lookup must run after playback starts", f.server.originRequested.await(3, TimeUnit.SECONDS))
                var original: Player? = null
                var position = 0L
                scenario.onActivity {
                    original = field(it, "player")
                    position = original!!.currentPosition
                    assertTrue("Unknown origin preserves the previous subtitle default", field(it, "subtitlesEnabled"))
                    assertEquals("Playback must not wait for the origin response", 1L, f.server.originRelease.count)
                }
                f.server.originRelease.countDown()
                await(scenario, "Late Korean origin must disable automatic embedded captions") {
                    field<PlaybackSpec>(it, "spec").sourceItem?.productionCountries == listOf("KR") &&
                        !field<Boolean>(it, "subtitlesEnabled")
                }
                scenario.onActivity {
                    assertOff(it)
                    assertSame("Origin metadata must not recreate the decoder", original, field<Player>(it, "player"))
                    assertTrue("Origin metadata must not seek the paused video",
                        field<Player>(it, "player").currentPosition in (position - 250)..(position + 250))
                    assertFalse(field<Player>(it, "player").playWhenReady)
                }
            }
        }

    @Test fun manualEmbeddedSelectionSurvivesLateKoreanOrigin() =
        fixture(PlaybackEnginePreference.MPV, "media3/exo-embedded-subrip.mkv") { f ->
            val spec = f.unknownEmbeddedEpisode()
            f.holdOrigin(spec)
            f.launch(spec).use { scenario ->
                awaitReady(scenario, PlaybackEnginePreference.MPV, minimumPosition = 1_000)
                assertTrue(f.server.originRequested.await(3, TimeUnit.SECONDS))
                select(scenario) { it.contains("서버 내장") }
                awaitCue(scenario, "Embedded SubRip cue")
                f.server.originRelease.countDown()
                await(scenario, "Late origin must preserve the explicit subtitle choice") {
                    field<PlaybackSpec>(it, "spec").sourceItem?.productionCountries == listOf("KR") &&
                        field<Boolean>(it, "subtitleManuallySelected")
                }
                awaitCue(scenario, "Embedded SubRip cue")
                scenario.onActivity {
                    assertTrue(field<Player>(it, "player").currentTracks.groups.any { group -> group.type == C.TRACK_TYPE_TEXT && group.isSelected })
                }
            }
        }

    @Test fun automaticallyRestoredLocalSubtitleDoesNotOverrideLateKoreanOrigin() =
        fixture(PlaybackEnginePreference.EXO) { f ->
            val known = f.spec(korean = true)
            val spec = known.copy(sourceItem = known.sourceItem!!.copy(productionCountries = emptyList(), originalLanguage = null))
            f.holdOrigin(spec)
            f.seed(spec)
            f.launch(spec).use { scenario ->
                awaitReady(scenario, PlaybackEnginePreference.EXO, waitForLocal = true)
                awaitCue(scenario, FIRST)
                assertTrue(f.server.originRequested.await(3, TimeUnit.SECONDS))
                f.server.originRelease.countDown()
                await(scenario, "An automatic saved-file restore is not a manual subtitle choice") {
                    field<PlaybackSpec>(it, "spec").sourceItem?.productionCountries == listOf("KR") &&
                        !field<Boolean>(it, "subtitlesEnabled") && field<Any?>(it, "localSubtitleRecord") != null
                }
                scenario.onActivity(::assertOff)
                select(scenario) { it.startsWith("불러온 자막 ·") }
                awaitCue(scenario, FIRST)
            }
        }

    @Test fun lateKoreanOriginKeepsTheSavedSubtitleMenuWhileItsInitialReadIsPending() =
        fixture(PlaybackEnginePreference.MPV) { f ->
            val known = f.spec(korean = true)
            val spec = known.copy(sourceItem = known.sourceItem!!.copy(productionCountries = emptyList(), originalLanguage = null))
            f.holdOrigin(spec)
            val record = f.seed(spec)
            val writesStarted = CountDownLatch(1)
            val writesRelease = CountDownLatch(1)
            val owner = localSubtitleOwner(f.server.origin, TOKEN, spec)!!
            LocalSubtitleWrites.select(LocalSubtitleStore(f.context), owner, record.id, true) {
                writesStarted.countDown()
                writesRelease.await(12, TimeUnit.SECONDS)
            }
            try {
                assertTrue("The synthetic write must delay the first local record read", writesStarted.await(3, TimeUnit.SECONDS))
                f.launch(spec).use { scenario ->
                    awaitReady(scenario, PlaybackEnginePreference.MPV)
                    assertTrue(f.server.originRequested.await(3, TimeUnit.SECONDS))
                    scenario.onActivity { assertNull(field<Any?>(it, "localSubtitleRecord")) }
                    f.server.originRelease.countDown()
                    await(scenario, "Korean origin must apply while the local record lookup is pending") {
                        field<PlaybackSpec>(it, "spec").sourceItem?.productionCountries == listOf("KR") &&
                            !field<Boolean>(it, "subtitlesEnabled")
                    }
                    writesRelease.countDown()
                    await(scenario, "Turning automatic captions off must retain the saved-file menu entry") {
                        field<Any?>(it, "localSubtitleRecord") != null
                    }
                    scenario.onActivity(::assertOff)
                    select(scenario) { it.startsWith("불러온 자막 ·") }
                    awaitCue(scenario, FIRST)
                }
            } finally { writesRelease.countDown() }
        }

    @Test fun delayedOriginFromPreviousEpisodeCannotDisableTheNewForeignEpisode() =
        fixture(PlaybackEnginePreference.MPV, "media3/exo-embedded-subrip.mkv") { f ->
            val first = f.unknownEmbeddedEpisode()
            val next = f.unknownEmbeddedEpisode(part = 78).let { it.copy(sourceItem = it.sourceItem!!.copy(
                showId = 901, productionCountries = listOf("US"), originalLanguage = "en")) }
            f.holdOrigin(first)
            f.launch(first).use { scenario ->
                awaitReady(scenario, PlaybackEnginePreference.MPV, minimumPosition = 1_000)
                assertTrue(f.server.originRequested.await(3, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    val restoreType = NativePlayerActivity::class.java.declaredClasses.single { it.simpleName == "ConversionRestore" }
                    val ctor = restoreType.declaredConstructors.single { it.parameterCount == 11 }.apply { isAccessible = true }
                    val previous = ctor.newInstance(first, 0L, true, null, null, null, null, 20_000L, true, false, null)
                    NativePlayerActivity::class.java.getDeclaredMethod("applyConversionPlayback", PlaybackSpec::class.java, restoreType)
                        .apply { isAccessible = true }.invoke(activity, next, previous)
                }
                await(scenario, "The next source must replace the pending origin request") {
                    field<PlaybackSpec>(it, "spec").partId == 78
                }
                f.server.originRelease.countDown()
                assertTrue("Old origin response must complete or be cancelled", f.server.originCompleted.await(3, TimeUnit.SECONDS))
                awaitReady(scenario, PlaybackEnginePreference.MPV, minimumPosition = 1_000)
                awaitCue(scenario, "Embedded SubRip cue")
                scenario.onActivity {
                    assertEquals(listOf("US"), field<PlaybackSpec>(it, "spec").sourceItem!!.productionCountries)
                    assertTrue(field(it, "subtitlesEnabled"))
                }
            }
        }

    @Test fun missingHomeShowIdRetriesOriginAfterVerifiedPartInfoArrives() =
        fixture(PlaybackEnginePreference.MPV, "media3/exo-embedded-subrip.mkv") { f ->
            val knownParent = f.unknownEmbeddedEpisode()
            val spec = knownParent.copy(sourceItem = knownParent.sourceItem!!.copy(showId = null))
            f.holdOrigin(knownParent)
            f.server.mediaInfoPath = "/libraries/${spec.libraryId}/media_info/${spec.partId}"
            f.server.mediaInfoBody = JSONObject()
                .put("item", JSONObject().put("id", spec.itemId).put("show_id", 900))
                .put("parts", JSONArray().put(JSONObject().put("id", spec.partId).put("metadata_item_id", spec.itemId)))
                .put("markers", JSONArray()).toString()
            f.launch(spec).use { scenario ->
                awaitReady(scenario, PlaybackEnginePreference.MPV, minimumPosition = 1_000)
                assertTrue(f.server.mediaInfoRequested.await(3, TimeUnit.SECONDS))
                await(scenario, "The first origin attempt must finish without guessing the missing show ID") {
                    field<String?>(it, "subtitleOriginAttemptedKey") != null
                }
                assertEquals("A missing parent ID must not request an arbitrary show", 1L, f.server.originRequested.count)
                f.server.mediaInfoRelease.countDown()
                assertTrue("Verified part metadata must allow a bounded origin retry", f.server.originRequested.await(4, TimeUnit.SECONDS))
                f.server.originRelease.countDown()
                await(scenario, "The verified parent must supply the Korean subtitle default") {
                    field<PlaybackSpec>(it, "spec").sourceItem?.productionCountries == listOf("KR") &&
                        !field<Boolean>(it, "subtitlesEnabled")
                }
                scenario.onActivity(::assertOff)
            }
        }

    private fun fixture(engine: PlaybackEnginePreference, asset: String = "media3/gesture-av.mp4", block: (Harness) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Only the isolated QA app may use synthetic account data", context.packageName.endsWith(".spoilerqa"))
        assertFalse("Unlock the device before testing", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
        val settings = context.getSharedPreferences("gsy_settings", Context.MODE_PRIVATE)
        val keys = listOf("playback_engine", "playback_speed", "resize_mode", "resize_scale", "picture_settings_v1")
        val old = keys.associateWith { settings.all[it] }
        val encrypted = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
        val oldLast = encrypted.getString("last_server", null)
        val sessions = SessionStore(context)
        val bytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
        NativeLocalSubtitlePlaybackTest.Fixture(bytes, CAPTIONS.toByteArray()).use { server ->
            val harness = Harness(context, server)
            try {
                settings.edit().putString("playback_engine", engine.key).putFloat("playback_speed", 1f)
                    .putInt("resize_mode", 0).putFloat("resize_scale", 1f).remove("picture_settings_v1").commit()
                sessions.save(server.origin, TOKEN, "fixture")
                block(harness)
                server.assertHealthy()
            } finally {
                harness.clear()
                sessions.clear(server.origin)
                encrypted.edit().apply { if (oldLast == null) remove("last_server") else putString("last_server", oldLast) }.commit()
                settings.edit().apply { old.forEach { (key, value) -> when (value) {
                    null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
                    is Float -> putFloat(key, value); is String -> putString(key, value); is Long -> putLong(key, value)
                } } }.commit()
            }
        }
    }

    private class Harness(val context: Context, val server: NativeLocalSubtitlePlaybackTest.Fixture) {
        private val store = LocalSubtitleStore(context)
        private val owners = mutableSetOf<String>()
        fun spec(korean: Boolean, episode: Boolean = false, serverSubtitle: Boolean = false, part: Int = 77): PlaybackSpec {
            if (serverSubtitle) server.subtitleRows = JSONArray().put(JSONObject()
                .put("id", 9).put("label", "한국어").put("source", "external").put("lang_code", "kor")
                .put("format", "subrip").put("playable", true)).toString()
            val item = MediaItem(part + 100, 5, "한글로 표시된 합성 작품", if (episode) "episode" else "movie",
                durationMs = 6_000, viewItemId = part + 200, playbackPartId = part,
                showId = if (episode) 900 else null, showTitle = if (episode) "합성 시리즈" else null,
                seasonNumber = if (episode) 1 else null, episodeNumber = if (episode) part - 76 else null,
                productionCountries = listOf(if (korean) "KR" else "US"), originalLanguage = if (korean) "ko" else "en")
            return PlaybackSpec(item.title, "${server.origin}/stream/$part?ticket=synthetic", itemId = item.id,
                libraryId = item.libraryId, partId = part, viewItemId = item.viewItemId, sourceItem = item, durationMs = 6_000,
                subtitles = if (serverSubtitle) listOf(SubtitleTrack(9, "한국어", "${server.origin}/external-subtitle.srt",
                    mimeType = "application/x-subrip", source = "external", language = "kor", format = "subrip")) else emptyList(),
                selectedSubtitleId = if (serverSubtitle) 9 else null,
                sessionId = "synthetic-korean-session", progressToken = "synthetic-korean-context")
        }
        fun unknownEmbeddedEpisode(part: Int = 77): PlaybackSpec {
            val base = spec(korean = true, episode = true, part = part)
            val embedded = SubtitleTrack(7, "Synthetic embedded", "${server.origin}/must-not-extract", "application/x-subrip",
                source = "embedded", language = "eng", streamIndex = 2, format = "subrip")
            server.subtitleRows = JSONArray().put(JSONObject().put("id", 7).put("source", "embedded")
                .put("label", "Synthetic embedded").put("lang_code", "eng").put("stream_index", 2).put("format", "subrip")).toString()
            return base.copy(durationMs = 20_000, subtitles = listOf(embedded), selectedSubtitleId = 7, sourceContainer = "mkv",
                sourceItem = base.sourceItem!!.copy(durationMs = 20_000, productionCountries = emptyList(), originalLanguage = null))
        }
        fun holdOrigin(spec: PlaybackSpec) {
            val item = spec.sourceItem!!
            val episode = item.type == "episode"
            val detailId = if (episode) item.showId!! else item.id
            server.originDetailPath = "/libraries/${item.libraryId}/${if (episode) "shows" else "movies"}/$detailId"
            server.originDetailBody = JSONObject().put("id", detailId).put("type", if (episode) "show" else "movie")
                .put("library_view_id", item.libraryId).put("view_item_id", item.viewItemId)
                .put("title", "합성 상세 작품").put("extra", JSONObject().put("country", JSONArray().put("KR"))).toString()
        }
        fun seed(spec: PlaybackSpec) = runBlocking { withContext(Dispatchers.IO) {
            val owner = localSubtitleOwner(server.origin, TOKEN, spec)!!; owners += owner
            LocalSubtitleWrites.awaitPending(); store.importBytes(owner, "synthetic-korean.srt", CAPTIONS.toByteArray())
        } }
        fun clear() = runBlocking { withContext(Dispatchers.IO) { LocalSubtitleWrites.awaitPending(); owners.forEach { store.remove(it) } } }
        fun launch(spec: PlaybackSpec): ActivityScenario<NativePlayerActivity> = ActivityScenario.launch<NativePlayerActivity>(
            Intent(context, NativePlayerActivity::class.java).putExtra("server", server.origin).putExtra("spec", spec.toJson())
        ).also { scenario -> scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    }

    private fun awaitReady(scenario: ActivityScenario<NativePlayerActivity>, engine: PlaybackEnginePreference,
        waitForLocal: Boolean = false, minimumPosition: Long = 500) = await(scenario, "The requested engine must become ready") { activity ->
        val player = field<Player?>(activity, "player")
        player?.volume = 0f
        val matches = if (engine == PlaybackEnginePreference.MPV) player is MpvPlayer else player is ExoPlayer
        val ready = matches && player != null && player.playbackState == Player.STATE_READY && player.currentPosition >= minimumPosition &&
            !field<Boolean>(activity, "mpvProbePending") && (!waitForLocal || field<Any?>(activity, "localSubtitleRecord") != null)
        if (ready) player?.pause()
        ready
    }

    private fun assertOff(activity: NativePlayerActivity) {
        assertFalse(field<Boolean>(activity, "subtitlesEnabled"))
        assertNull(field<String?>(activity, "localSubtitleActiveId"))
        assertNull(field<Int?>(activity, "externalSubtitleId"))
        val overlay = field<PlexSubtitleOverlayView>(activity, "subtitleOverlay")
        assertTrue("No subtitle may be visible before a manual choice", overlay.visibility != View.VISIBLE || overlay.subtitleText.isBlank())
        assertTrue(field<Player>(activity, "player").trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }

    private fun select(scenario: ActivityScenario<NativePlayerActivity>, match: (String) -> Boolean) {
        scenario.onActivity { activity ->
            field<PlayerView>(activity, "playerView").showController()
            NativePlayerActivity::class.java.getDeclaredMethod("showTracks", Int::class.javaPrimitiveType!!)
                .apply { isAccessible = true }.invoke(activity, C.TRACK_TYPE_TEXT)
            val dialog = field<AlertDialog>(activity, "subtitleDialog")
            val list = dialog.listView
            val labels = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
            val index = labels.indexOfFirst(match).takeIf { it >= 0 }
                ?: throw AssertionError("Expected subtitle menu choice was missing: $labels")
            list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index))
        }
    }

    private fun switchEngine(scenario: ActivityScenario<NativePlayerActivity>, engine: PlaybackEnginePreference) {
        scenario.onActivity { NativePlayerActivity::class.java.getDeclaredMethod("applyPlaybackEnginePreference", PlaybackEnginePreference::class.java)
            .apply { isAccessible = true }.invoke(it, engine) }
    }

    private fun awaitCue(scenario: ActivityScenario<NativePlayerActivity>, expected: String) = await(scenario, "Manual subtitle cue must render: $expected") {
        val overlay = field<PlexSubtitleOverlayView>(it, "subtitleOverlay")
        field<Boolean>(it, "subtitlesEnabled") && overlay.visibility == View.VISIBLE && overlay.subtitleText == expected
    }

    private fun await(scenario: ActivityScenario<NativePlayerActivity>, description: String, predicate: (NativePlayerActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var diagnostics = "No activity"
        while (SystemClock.elapsedRealtime() < deadline) {
            if (scenario.state == Lifecycle.State.DESTROYED) fail("$description; Activity finished")
            var matched = false
            scenario.onActivity { activity ->
                matched = predicate(activity)
                val player = field<Player?>(activity, "player")
                diagnostics = "engine=${player?.javaClass?.simpleName}, state=${player?.playbackState}, pos=${player?.currentPosition}, " +
                    "enabled=${field<Boolean>(activity, "subtitlesEnabled")}, cue=${field<PlexSubtitleOverlayView>(activity, "subtitleOverlay").subtitleText}"
            }
            if (matched) return
            Thread.sleep(40)
        }
        fail("$description; $diagnostics")
    }

    @Suppress("UNCHECKED_CAST") private fun <T> field(activity: NativePlayerActivity, name: String): T =
        NativePlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    companion object {
        private const val TOKEN = "synthetic-korean-subtitle-token"
        private const val FIRST = "Synthetic manually enabled caption"
        private const val SECOND = "Synthetic caption after seek"
        private val CAPTIONS = "1\n00:00:00,000 --> 00:00:03,000\n$FIRST\n\n2\n00:00:03,000 --> 00:00:06,000\n$SECOND\n"
    }
}
