package io.mirr.shynigsy.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CinematicContinueFadeTest {
    @Test fun artworkFadesContinuouslyFromClearTopToOpaqueBottom() {
        val stops = cinematicContinueFadeStops()
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(0f, stops.first().second, 0f)
        assertEquals(1f, stops.last().first, 0f)
        assertEquals(1f, stops.last().second, 0f)
        assertTrue(stops.zipWithNext().all { (a, b) ->
            b.first > a.first && b.second > a.second && b.first <= 1f && b.second <= 1f
        })
        // Normalized positions scale with both small phone and large TV sections.
        for (height in listOf(180f, 500f, 1400f)) {
            val locations = stops.map { it.first * height }
            assertTrue(locations.zipWithNext().all { (a, b) -> b > a })
            assertEquals(height, locations.last(), 0f)
        }
    }

    @Test fun fadeUsesTheUsersChosenBackgroundColor() {
        for (background in listOf(Color(0xFF102030), Color(0xFFF5E5D5))) {
            val stops = cinematicContinueFadeColors(background)
            assertTrue(stops.all { (_, color) ->
                color.red == background.red && color.green == background.green && color.blue == background.blue
            })
            assertEquals(0f, stops.first().second.alpha, 0.001f)
            assertEquals(1f, stops.last().second.alpha, 0.001f)
        }
    }
}
