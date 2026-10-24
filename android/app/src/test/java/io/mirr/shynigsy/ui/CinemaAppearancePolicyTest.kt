package io.mirr.shynigsy.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class CinemaAppearancePolicyTest {
    @Test fun blackWhiteAndMiddleGrayKeepBodyAndButtonTextReadable() {
        listOf(Color.Black, Color.White, Color(0xFF777777), Color(0xFF3B3B3B)).forEach { background ->
            assertReadable(cinemaColorScheme(background), "neutral $background")
        }
    }

    @Test fun saturatedUserBackgroundsKeepBodyAndButtonTextReadable() {
        listOf(Color.Red, Color.Green, Color.Blue, Color.Cyan, Color.Magenta, Color.Yellow).forEach { background ->
            assertReadable(cinemaColorScheme(background), "saturated $background")
        }
    }

    @Test fun sixDigitColorsAcceptAnOptionalHashAndAlwaysBecomeOpaque() {
        assertEquals(0xFFFFFFFF.toInt(), parseHomeBackgroundColor("FFFFFF"))
        assertEquals(0xFF000000.toInt(), parseHomeBackgroundColor("#000000"))
        assertEquals(0xFFA0B1C2.toInt(), parseHomeBackgroundColor("  #a0B1c2  "))
    }

    @Test fun malformedOrAlphaColorsCannotChangeTheBackground() {
        listOf("", "#", "FFF", "#12345", "1234567", "FF123456", "#GG0000", "0x123456", "12 456", "-12345")
            .forEach { input -> assertNull("Invalid color must be rejected: $input", parseHomeBackgroundColor(input)) }
    }

    private fun assertReadable(scheme: ColorScheme, label: String) {
        listOf(
            Triple("body", scheme.onBackground, scheme.background),
            Triple("surface", scheme.onSurface, scheme.surface),
            Triple("secondary text", scheme.onSurfaceVariant, scheme.background),
            Triple("secondary surface text", scheme.onSurfaceVariant, scheme.surfaceVariant),
            Triple("button", scheme.onPrimary, scheme.primary),
            Triple("accent on background", scheme.primary, scheme.background),
            Triple("accent on surface", scheme.primary, scheme.surface),
            Triple("accent on panel", scheme.primary, scheme.surfaceContainer),
        ).forEach { (role, foreground, background) ->
            val ratio = contrast(foreground, background)
            assertTrue("$label $role contrast must be at least 4.5:1, was $ratio", ratio >= 4.5f)
        }
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val first = foreground.compositeOver(background).luminance()
        val second = background.luminance()
        return (max(first, second) + .05f) / (min(first, second) + .05f)
    }
}
