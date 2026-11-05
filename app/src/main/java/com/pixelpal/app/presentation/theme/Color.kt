package com.pixelpal.app.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * PixelPal semantic color palettes.
 *
 * Impeccable design system: Linear & Apple Notes caliber craft.
 * Exactly two themes: Obsidian Dark and Clean Paper Light.
 * Quiet chrome, neutral ramps, single disciplined Indigo accent.
 */

val PixelSuccess = Color(0xFF2EA043)
val PixelWarning = Color(0xFFD29922)

object DarkPalette {
    // Mockup-tuned: cards read as clearly elevated panels against the
    // obsidian canvas (channel spread kept ≤ 20 — pinned by DarkThemeTest).
    val Background = Color(0xFF131124)
    val Surface = Color(0xFF1D1A2E)
    val SurfaceElevated = Color(0xFF242134)

    val Primary = Color(0xFF8B6CF6)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFF2E2458)
    val OnPrimaryContainer = Color(0xFFEDE9FE)

    val Secondary = Color(0xFFA78BFA)
    val OnSecondary = Color(0xFF131124)
    val SecondaryContainer = Color(0xFF262044)
    val OnSecondaryContainer = Color(0xFFDDD6FE)

    val TextPrimary = Color(0xFFFAF9FE)
    val TextSecondary = Color(0xFFA6A0B8)

    val Outline = Color(0x338B6CF6)
    val Divider = Color(0x1F8B6CF6)

    val Error = Color(0xFFFF6B6B)
    val OnError = Color(0xFFFFFFFF)
}

object LightPalette {
    // Mockup-tuned: pale lilac canvas deep enough that WHITE cards float on
    // it (the wash fix), pure white surfaces carried by soft shadows.
    val Background = Color(0xFFEDE7F9)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceElevated = Color(0xFFFFFFFF)

    val Primary = Color(0xFF7B57FF)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFEDE8FE)
    val OnPrimaryContainer = Color(0xFF351A88)

    val Secondary = Color(0xFF9878F8)
    val OnSecondary = Color(0xFFFFFFFF)
    val SecondaryContainer = Color(0xFFF3EFFF)
    val OnSecondaryContainer = Color(0xFF452B94)

    val TextPrimary = Color(0xFF1B162E)
    val TextSecondary = Color(0xFF736D8A)

    val Outline = Color(0xFFD9CFF0)
    val Divider = Color(0xFFE9E3F7)

    val Error = Color(0xFFDC2626)
    val OnError = Color(0xFFFFFFFF)
}

/**
 * Solid brushes for the redesign. No radial glows — radiance reads as slop.
 */
object PixelPalGradients {
    val primaryCard = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = listOf(Color(0xFF8B6CF6), Color(0xFF6738EB))
    )
    val streakBar = androidx.compose.ui.graphics.Brush.verticalGradient(
        colors = listOf(Color(0xFFA78BFA), Color(0xFF6738EB))
    )
}
