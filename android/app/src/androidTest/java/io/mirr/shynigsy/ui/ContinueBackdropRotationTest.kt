package io.mirr.shynigsy.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production timer with synthetic keys and the host Activity's real lifecycle. */
@RunWith(AndroidJUnit4::class)
class ContinueBackdropRotationTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Before fun requireIsolatedSyntheticApplication() {
        assertEquals("io.mirr.shyniflex.spoilerqa",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test fun theTimerWrapsOverOnlyTheRealKeysWithoutDuplicateAdvances() {
        val selected = mutableStateOf("first")
        val callbacks = mutableListOf<String>()
        render(mutableStateOf(listOf("first", "second", "third")), selected,
            onAdvance = { callbacks.add(it) })
        advanceInterval()
        assertSelected("second")
        advanceInterval()
        assertSelected("third")
        advanceInterval()
        assertSelected("first")
        screen.runOnIdle { assertEquals(listOf("second", "third", "first"), callbacks) }
    }

    @Test fun interactionAndDisabledStateRestartTheFullInterval() {
        val selected = mutableStateOf("first")
        val enabled = mutableStateOf(true)
        val revision = mutableStateOf(0)
        render(mutableStateOf(listOf("first", "second")), selected, enabled, revision)
        advance(600)
        screen.runOnIdle { revision.value++ }
        advance(600)
        assertSelected("first")
        advance(600)
        assertSelected("second")

        screen.runOnIdle { enabled.value = false }
        advance(3_000)
        assertSelected("second")
        screen.runOnIdle { enabled.value = true }
        advance(600)
        assertSelected("second")
        advance(600)
        assertSelected("first")
    }

    @Test fun changingOrRemovingKeysCancelsThePendingDestination() {
        val keys = mutableStateOf(listOf("public", "private"))
        val selected = mutableStateOf("public")
        val callbacks = mutableListOf<String>()
        render(keys, selected, onAdvance = { callbacks.add(it) })
        advance(600)
        screen.runOnIdle { keys.value = listOf("public", "replacement") }
        advance(600)
        assertSelected("public")
        advance(600)
        assertSelected("replacement")

        screen.runOnIdle { keys.value = listOf("replacement") }
        advance(3_000)
        assertSelected("replacement")
        screen.runOnIdle { keys.value = emptyList() }
        advance(3_000)
        screen.runOnIdle {
            assertEquals("A removed private identity can never be emitted by the old timer",
                listOf("replacement"), callbacks)
        }
    }

    @Test fun pausingTheActivityStopsRotationAndResumeWaitsForANewInterval() {
        val selected = mutableStateOf("first")
        val callbacks = mutableListOf<String>()
        render(mutableStateOf(listOf("first", "second")), selected, onAdvance = { callbacks.add(it) })
        advance(600)
        try {
            screen.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            // A stopped renderer must not be required to draw a frame to revoke a running timer.
            screen.mainClock.advanceTimeBy(3_000)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals("first", selected.value)
                assertTrue("Background time does not emit a slide", callbacks.isEmpty())
            }
        } finally {
            screen.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        screen.waitForIdle()
        advance(600)
        assertSelected("first")
        advance(600)
        assertSelected("second")
        screen.runOnIdle { assertEquals(listOf("second"), callbacks) }
    }

    private fun render(
        keys: MutableState<List<String>>,
        selected: MutableState<String>,
        enabled: MutableState<Boolean> = mutableStateOf(true),
        revision: MutableState<Int> = mutableStateOf(0),
        onAdvance: (String) -> Unit = {},
    ) {
        screen.mainClock.autoAdvance = false
        screen.setContent {
            MaterialTheme(colorScheme = ShyniColors, typography = CompactTypography, shapes = CompactShapes) {
                Box(Modifier.fillMaxSize()) {
                    rememberContinueBackdropRotation(keys.value, selected.value, enabled.value, revision.value,
                        onAdvance = { selected.value = it; onAdvance(it) }, intervalMs = INTERVAL)
                    Text(selected.value, Modifier.testTag(SELECTED))
                }
            }
        }
        screen.waitForIdle()
        screen.runOnIdle { assertTrue("The synthetic Activity has window focus", screen.activity.window.decorView.hasWindowFocus()) }
    }

    private fun advance(milliseconds: Long) {
        screen.mainClock.advanceTimeBy(milliseconds)
        screen.waitForIdle()
    }

    private fun advanceInterval() = advance(INTERVAL + 200)

    private fun assertSelected(key: String) = screen.onNodeWithTag(SELECTED).assertTextEquals(key)

    private companion object {
        const val INTERVAL = 1_000L
        const val SELECTED = "rotation-test-selected"
    }
}
