package io.mirr.shynigsy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CinemaHeroScrimTest {
    @Test fun titleProtectionKeepsTheOriginalHorizontalAndVerticalFades() {
        assertEquals(.35f, cinemaScrimAlphaAt(0f, 0f, wide = true), .0001f)
        assertEquals(.35f, cinemaScrimAlphaAt(.65f, 0f, wide = true), .0001f)
        assertEquals(.175f, cinemaScrimAlphaAt(.825f, 0f, wide = true), .0001f)
        assertEquals(0f, cinemaScrimAlphaAt(1f, 0f, wide = true), .0001f)
        assertEquals(.175f, cinemaScrimAlphaAt(0f, .5f, wide = true), .0001f)
        assertEquals(0f, cinemaScrimAlphaAt(0f, 1f, wide = true), .0001f)
        assertEquals(.175f, cinemaScrimAlphaAt(1f, .5f, wide = false), .0001f)
    }

    @Test fun bandApproximationStaysWithinHalfABandOfTheSmoothFade() {
        val maxError = CINEMA_SCRIM_MAX_ALPHA / (2 * CINEMA_SCRIM_FADE_STEPS) + .00001f
        for (band in 0 until CINEMA_SCRIM_FADE_STEPS) {
            val midpoint = cinemaScrimBandMidpoint(band)
            for (sample in 0..10) {
                val y = (band + sample / 10f) / CINEMA_SCRIM_FADE_STEPS
                for (x in listOf(0f, .65f, .8f, 1f)) {
                    assertTrue(abs(cinemaScrimAlphaAt(x, midpoint, true) - cinemaScrimAlphaAt(x, y, true)) <= maxError)
                }
            }
        }
    }
}
