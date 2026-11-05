package com.pixelpal.app.presentation.theme

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Guards the Linear-inspired obsidian dark theme: surfaces and text must stay
 * quiet, neutral, and low-saturation.
 */
class DarkThemeTest {

    private fun maxChannelSpread(color: androidx.compose.ui.graphics.Color): Float {
        val r = color.red * 255
        val g = color.green * 255
        val b = color.blue * 255
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    private fun luminance(color: androidx.compose.ui.graphics.Color): Float =
        0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

    @Test
    fun darkSurfaces_maintainNeutralObsidianAtmosphere() {
        listOf(DarkPalette.Background, DarkPalette.Surface, DarkPalette.SurfaceElevated).forEach {
            assertTrue(
                maxChannelSpread(it) <= 20f,
                "Dark surface $it has too strong a color tint (spread ${maxChannelSpread(it)})"
            )
        }
    }

    @Test
    fun darkText_isLegibleAndBalanced() {
        assertTrue(maxChannelSpread(DarkPalette.TextPrimary) <= 10f, "Primary text must stay near pure crisp white")
        assertTrue(maxChannelSpread(DarkPalette.TextSecondary) <= 25f, "Secondary text must maintain quiet neutral slate tone")
    }

    @Test
    fun darkSurfaces_elevateUpwards() {
        assertTrue(
            luminance(DarkPalette.Surface) > luminance(DarkPalette.Background),
            "Surface must be lighter than background"
        )
        assertTrue(
            luminance(DarkPalette.SurfaceElevated) > luminance(DarkPalette.Surface),
            "Elevated surface must be lighter than surface"
        )
    }

    @Test
    fun darkPrimary_staysDisciplinedIndigoAccent() {
        // Linear Indigo brand accent
        assertTrue(DarkPalette.Primary.blue > 0.75f, "Primary should stay a clear indigo/blue")
        assertTrue(DarkPalette.Primary.red > 0.30f, "Primary should have balanced indigo warmth")
    }
}
