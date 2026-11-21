package io.mirr.shynigsy.plex

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.mirr.shynigsy.data.SessionStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PlexAccountConnectionTest {
    @get:Rule val screen = createEmptyComposeRule()

    @Test fun claimedBrowserLoginReturnsAndOpensReachableServer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sessions = SessionStore(context)
        val grants = PlexAccountStore(context)
        assumeTrue("Use a clean test installation", sessions.loadLast() == null && grants.load() == null)
        MockWebServer().use { cloud ->
            cloud.start()
            val first = cloud.url("/offline").toString()
            val second = cloud.url("/working").toString()
            val requests = ConcurrentLinkedQueue<String>()
            val visitedTab = AtomicBoolean(false)
            val approved = AtomicBoolean(false)
            val firstTried = AtomicBoolean(false)
            cloud.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request.requestUrl?.encodedPath.orEmpty())
                    val json = when (request.requestUrl?.encodedPath) {
                        "/auth" -> {
                            visitedTab.set(true)
                            return MockResponse().setHeader("Content-Type", "text/html")
                                .setBody("<html><body>PLEXMINI sign-in fixture</body></html>")
                        }
                        "/api/v2/pins" -> """{"id":3,"code":"fixture-hosted-pin","expiresIn":90}"""
                        "/api/v2/pins/3" -> if (approved.get()) """{"authToken":"fixture-cloud"}""" else """{"authToken":null}"""
                        "/api/v2/resources" -> {
                            if (request.getHeader("X-Plex-Token") != "fixture-cloud") return MockResponse().setResponseCode(403)
                            """[{"name":"Fixture PMS","clientIdentifier":"fixture-machine","provides":"server","accessToken":"fixture-pms","connections":[{"local":true,"uri":"$first"},{"local":true,"uri":"$second"}]}]"""
                        }
                        "/offline/library/sections" -> {
                            firstTried.set(true)
                            return MockResponse().setResponseCode(503)
                        }
                        "/working/library/sections" -> {
                            if (request.getHeader("X-Plex-Token") != "fixture-pms") return MockResponse().setResponseCode(403)
                            """{"MediaContainer":{"Directory":[{"key":"1","type":"movie","title":"Auth test library"}]}}"""
                        }
                        else -> """{"MediaContainer":{}}"""
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(json)
                }
            }
            try {
                ActivityScenario.launch(PlexMainActivity::class.java).use { scenario ->
                    screen.waitUntil(12_000) {
                        screen.onAllNodes(hasText("아이디·비밀번호로 로그인") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    screen.waitForIdle()
                    scenario.onActivity { activity ->
                        val model = ViewModelProvider(activity)[PlexLinkModel::class.java]
                        check(!model.linking) { "Account recovery is still active" }
                        val base = cloud.url("/api/v2/")
                        model.start(PlexAccount("test-device", base, base, cloud.url("/auth")), true, "")
                    }
                    // A blocking latch here stalls the Compose test frame clock that opens the browser.
                    try {
                        screen.waitUntil(20_000) { visitedTab.get() }
                    } catch (failure: Throwable) {
                        throw AssertionError("Browser fixture was not opened. Requested paths: ${requests.toSet()}", failure)
                    }
                    approved.set(true)
                    screen.waitUntil(25_000) { sessions.loadLast()?.server == second }
                    var page: PlexPage.Home? = null
                    screen.waitUntil(12_000) {
                        instrumentation.runOnMainSync {
                            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<PlexMainActivity>().firstOrNull()
                            page = activity?.let { ViewModelProvider(it)[PlexBrowseModel::class.java].page as? PlexPage.Home }
                        }
                        page != null
                    }
                    assertTrue("The unreachable address was tried before failover", firstTried.get())
                    assertEquals("Auth test library", requireNotNull(page).libraries.single().title)
                    assertEquals("fixture-pms", sessions.loadLast()?.token)
                    assertEquals("fixture-machine", grants.load()?.userName)
                }
            } finally {
                sessions.clear(second)
                grants.clear()
            }
        }
    }
}
