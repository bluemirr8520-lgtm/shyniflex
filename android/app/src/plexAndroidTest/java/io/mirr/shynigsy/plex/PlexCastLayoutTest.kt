package io.mirr.shynigsy.plex

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mirr.shynigsy.DetailDiscoveryState
import io.mirr.shynigsy.data.CastMember
import io.mirr.shynigsy.data.Library
import io.mirr.shynigsy.data.MediaDetails
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.ui.CompactDetailContent
import io.mirr.shynigsy.ui.compactMediaIdentity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min

/** Local callbacks and synthetic images only; no account or server is used. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlexCastLayoutTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()
    private val show = MediaItem(100, 7, "합성 시리즈", "show", summary = "출연진 화면 배치 검사")
    private val episode = MediaItem(101, 7, "첫 회차", "episode", showId = 100, episodeNumber = 1)
    private val actors = (0..12).map { CastMember("합성 배우 $it", "배역 $it", ppkey = "fixture-person-$it") }
    private lateinit var inputModeManager: InputModeManager

    @Test fun phoneShowsAllCastInLeftAlignedRowsAndKeepsActorClick() {
        var selected: CastMember? = null
        render(420, 900, onActor = { selected = it })
        assertWrappedCast()
        screen.onNodeWithTag("detail-cast-12").performScrollTo().assertIsDisplayed().performClick()
        screen.runOnIdle { assertEquals(actors.last(), selected) }
    }

    @Test fun tabletKeepsCastLeftOfEpisodeListAndWrapsWithoutHorizontalScrolling() {
        render(1280, 800)
        assertWrappedCast()
        screen.onNodeWithTag("detail-cast-12").performScrollTo().assertIsDisplayed()
        val overview = screen.onNodeWithTag("detail-overview-pane").fetchSemanticsNode().boundsInRoot
        val actions = screen.onNodeWithTag("detail-actions-pane").fetchSemanticsNode().boundsInRoot
        val actor = screen.onNodeWithTag("detail-cast-12").fetchSemanticsNode().boundsInRoot
        assertTrue("Cast stays inside the left overview pane", actor.left >= overview.left && actor.right <= overview.right)
        assertTrue("Cast stays left of episode actions", actor.right < actions.left)
        screen.onNodeWithTag("compact-episode-${compactMediaIdentity(episode)}").assertIsDisplayed()
    }

    @Test fun televisionRemoteCanMoveDownCastRowsAndSelectAnActor() {
        var selected: CastMember? = null
        render(1280, 720, television = true, onActor = { selected = it })
        assertWrappedCast()
        screen.onNodeWithTag("detail-cast-0").performScrollTo()
        screen.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        screen.onNodeWithTag("detail-cast-0").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        screen.waitForIdle()
        val focused = actors.indices.firstOrNull { index ->
            screen.onAllNodes(hasTestTag("detail-cast-$index") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull("D-pad down reaches another cast row", focused)
        assertTrue("D-pad down leaves the first actor", focused!! > 0)
        val first = screen.onNodeWithTag("detail-cast-0").fetchSemanticsNode().positionInRoot
        val next = screen.onNodeWithTag("detail-cast-$focused").fetchSemanticsNode().positionInRoot
        assertTrue("D-pad down moves to a lower row", next.y > first.y)
        screen.onNodeWithTag("detail-cast-$focused").assertIsDisplayed()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        screen.runOnIdle { assertEquals(actors[focused], selected) }
    }

    private fun assertWrappedCast() {
        screen.onNodeWithTag("detail-cast-0").performScrollTo().assertIsDisplayed()
        val cast = screen.onNodeWithTag("detail-cast").fetchSemanticsNode()
        assertFalse("Cast no longer requires horizontal scrolling", cast.config.contains(SemanticsProperties.HorizontalScrollAxisRange))
        val first = screen.onNodeWithTag("detail-cast-0").fetchSemanticsNode()
        val last = screen.onNodeWithTag("detail-cast-12").fetchSemanticsNode()
        assertEquals("Rows align to the left of the available area", cast.positionInRoot.x, first.positionInRoot.x, 1f)
        assertTrue("Cast wraps into multiple rows", last.positionInRoot.y > first.positionInRoot.y)
        for (index in actors.indices) {
            val actor = screen.onNodeWithTag("detail-cast-$index").fetchSemanticsNode()
            assertTrue("Actor $index stays within the row width", actor.positionInRoot.x >= cast.positionInRoot.x - 1f &&
                actor.positionInRoot.x + actor.size.width <= cast.positionInRoot.x + cast.size.width + 1f)
        }
    }

    @Suppress("DEPRECATION")
    private fun render(width: Int, height: Int, television: Boolean = false, onActor: (CastMember) -> Unit = {}) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName in setOf("io.mirr.shyniflex.plex.debug", "io.mirr.shyniflex.plex.renewed.debug"))
        instrumentation.setInTouchMode(!television)
        screen.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp))) {
                val config = Configuration(LocalConfiguration.current).apply {
                    smallestScreenWidthDp = min(width, height)
                    screenWidthDp = width
                    screenHeightDp = height
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                        if (television) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
                    orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                val context = LocalContext.current.createConfigurationContext(config)
                inputModeManager = LocalInputModeManager.current
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Surface(Modifier.fillMaxSize().testTag("cast-fixture-viewport")) {
                            CompactDetailContent(MediaDetails(show, children = listOf(episode), castMembers = actors),
                                versions = emptyList(), selectedPartId = null, busy = false, error = null, spoilerUnlocked = false,
                                onPlay = { _, _ -> error("No playback expected") }, onOpenItem = {}, onSelectSeason = {},
                                onSelectVersion = {}, onWatchOptions = {}, onWatchedChange = { _, _ -> },
                                artwork = { _, modifier -> Box(modifier.background(Color.DarkGray)) },
                                portrait = { _, modifier -> Box(modifier.background(Color.Gray)) },
                                discovery = DetailDiscoveryState(show), libraries = listOf(Library(7, "합성 시리즈", "tv")),
                                onSelectActor = onActor)
                        }
                    }
                }
            }
        }
        screen.waitForIdle()
    }
}
