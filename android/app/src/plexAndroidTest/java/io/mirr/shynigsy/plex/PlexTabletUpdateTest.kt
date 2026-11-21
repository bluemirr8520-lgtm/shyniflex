package io.mirr.shynigsy.plex

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mirr.shynigsy.data.MediaItem
import io.mirr.shynigsy.data.SessionStore
import io.mirr.shynigsy.ui.CinemaAppearanceTheme
import io.mirr.shynigsy.updates.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlexTabletUpdateTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Test fun portraitTabletCanCheckFromLoginHomeAndDetail() = checkViewport(800, 1280, tablet = true)
    @Test fun landscapeTabletCanCheckFromLoginHomeAndDetail() = checkViewport(1280, 800, tablet = true)
    @Test fun landscapePhoneKeepsExistingUpdateMenu() = checkViewport(800, 360, tablet = false)

    private fun checkViewport(width: Int, height: Int, tablet: Boolean) {
        val app = screen.activity.application
        check(app.packageName in setOf("io.mirr.shyniflex.plex.debug", "io.mirr.shyniflex.plex.renewed.debug")) {
            "Only run inside an isolated PLEXMINI test app"
        }
        val sessions = SessionStore(app)
        assertNull(sessions.loadLast())
        assertNull(PlexAccountStore(app).load())
        val checks = AtomicInteger()
        val updates = UpdateViewModel(app, object : UpdateSource {
            override suspend fun latest(installed: Long): UpdateCandidate? { checks.incrementAndGet(); return null }
            override suspend fun download(candidate: UpdateCandidate, progress: (Int) -> Unit): File =
                error("No update is available in this fixture")
            override fun verify(file: File, candidate: UpdateCandidate) = error("No installer expected")
        })
        val browser = screen.runOnUiThread { ViewModelProvider(screen.activity)[PlexBrowseModel::class.java] }
        var density = 0f
        screen.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp))) {
                val config = Configuration(LocalConfiguration.current).apply {
                    smallestScreenWidthDp = min(width, height)
                    screenWidthDp = width
                    screenHeightDp = height
                    orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                CompositionLocalProvider(LocalConfiguration provides config) {
                    density = LocalDensity.current.density
                    CinemaAppearanceTheme {
                        Box(Modifier.fillMaxSize().testTag("fixture-viewport")) {
                            PlexBrowser("PLEXMINI", browser, { _, _ -> error("No playback expected") },
                                onUpdate = { updates.check(manual = true) })
                            val state by updates.state.collectAsState()
                            UpdateDialog(state, onInstall = { error("No installer expected") },
                                onCheck = { updates.check(manual = true) }, onDismiss = updates::dismiss)
                        }
                    }
                }
            }
        }
        val viewport = screen.onNodeWithTag("fixture-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(width.toFloat(), viewport.width / density, 2f)
        assertEquals(height.toFloat(), viewport.height / density, 2f)
        fun checkButton() {
            val button = screen.onNodeWithTag("plex-tablet-update").assertIsDisplayed().assertHasClickAction()
                .assertTextContains("업데이트 확인")
            val node = button.fetchSemanticsNode()
            val bounds = node.boundsInRoot
            assertEquals(node.size.height.toFloat(), bounds.height, 1f)
            assertEquals(node.size.width.toFloat(), bounds.width, 1f)
            assertTrue("Whole touch target fits the viewport", viewport.contains(bounds.topLeft) &&
                bounds.right <= viewport.right && bounds.bottom <= viewport.bottom && bounds.height / density >= 47f)
            val before = checks.get()
            button.performTouchInput { click() }
            confirmUpdate(before + 1, checks)
        }

        screen.waitUntil(10_000) { !browser.busy }
        if (tablet) checkButton() else screen.onNodeWithTag("plex-tablet-update").assertDoesNotExist()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val data = when (request.requestUrl?.encodedPath) {
                        "/library/sections" -> """"Directory": [{"key":"1","type":"movie","title":"Films"}]"""
                        "/library/metadata/77" -> """"Metadata": [{"ratingKey":"77","type":"movie","title":"Fixture film","librarySectionID":"1"}]"""
                        else -> """"Metadata": []"""
                    }
                    return MockResponse().setBody("""{"MediaContainer":{$data}}""")
                }
            }
            server.start()
            val address = server.url("/").toString().trimEnd('/')
            try {
                screen.runOnIdle { browser.connect(address, "test-only-token") }
                screen.waitUntil(15_000) { browser.page is PlexPage.Home && !browser.busy }
                screen.onNodeWithTag("cinema-mobile-home").assertIsDisplayed()
                if (tablet) checkButton() else {
                    screen.onNodeWithTag("plex-tablet-update").assertDoesNotExist()
                    screen.onNodeWithTag("cinema-mobile-more").assertIsDisplayed().performTouchInput { click() }
                    screen.onNodeWithTag("mobile-more-actions").performScrollToNode(hasTestTag("mobile-more-update"))
                    screen.onNodeWithTag("mobile-more-update").assertIsDisplayed().performTouchInput { click() }
                    confirmUpdate(1, checks)
                }
                screen.runOnIdle { browser.openItem(MediaItem(77, 1, "Fixture film", "movie")) }
                screen.waitUntil(10_000) { browser.page is PlexPage.Detail && !browser.busy }
                screen.onNodeWithTag("compact-detail").assertExists()
                if (tablet) checkButton() else screen.onNodeWithTag("plex-tablet-update").assertDoesNotExist()
            } finally {
                sessions.clear(address)
            }
        }
    }

    private fun confirmUpdate(expectedChecks: Int, checks: AtomicInteger) {
        screen.waitUntil(10_000) { checks.get() == expectedChecks &&
            screen.onAllNodesWithText("최신 버전을 사용 중입니다").fetchSemanticsNodes().isNotEmpty() }
        screen.onNodeWithText("최신 버전을 사용 중입니다").assertIsDisplayed()
        screen.onNodeWithText("닫기").performTouchInput { click() }
        screen.onNodeWithText("최신 버전을 사용 중입니다").assertDoesNotExist()
    }
}
