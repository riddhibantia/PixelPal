package com.pixelpal.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = DarkPalette.Primary,
    onPrimary = DarkPalette.OnPrimary,
    primaryContainer = DarkPalette.PrimaryContainer,
    onPrimaryContainer = DarkPalette.OnPrimaryContainer,
    secondary = DarkPalette.Secondary,
    onSecondary = DarkPalette.OnSecondary,
    secondaryContainer = DarkPalette.SecondaryContainer,
    onSecondaryContainer = DarkPalette.OnSecondaryContainer,
    tertiary = DarkPalette.Secondary,
    onTertiary = DarkPalette.OnSecondary,
    tertiaryContainer = DarkPalette.SecondaryContainer,
    onTertiaryContainer = DarkPalette.OnSecondaryContainer,
    background = DarkPalette.Background,
    onBackground = DarkPalette.TextPrimary,
    surface = DarkPalette.Surface,
    onSurface = DarkPalette.TextPrimary,
    surfaceVariant = DarkPalette.SurfaceElevated,
    onSurfaceVariant = DarkPalette.TextSecondary,
    outline = DarkPalette.Outline,
    outlineVariant = DarkPalette.Divider,
    error = DarkPalette.Error,
    onError = DarkPalette.OnError
)

private val LightColorScheme = lightColorScheme(
    primary = LightPalette.Primary,
    onPrimary = LightPalette.OnPrimary,
    primaryContainer = LightPalette.PrimaryContainer,
    onPrimaryContainer = LightPalette.OnPrimaryContainer,
    secondary = LightPalette.Secondary,
    onSecondary = LightPalette.OnSecondary,
    secondaryContainer = LightPalette.SecondaryContainer,
    onSecondaryContainer = LightPalette.OnSecondaryContainer,
    tertiary = LightPalette.Secondary,
    onTertiary = LightPalette.OnSecondary,
    tertiaryContainer = LightPalette.SecondaryContainer,
    onTertiaryContainer = LightPalette.OnSecondaryContainer,
    background = LightPalette.Background,
    onBackground = LightPalette.TextPrimary,
    surface = LightPalette.Surface,
    onSurface = LightPalette.TextPrimary,
    surfaceVariant = LightPalette.SurfaceElevated,
    onSurfaceVariant = LightPalette.TextSecondary,
    outline = LightPalette.Outline,
    outlineVariant = LightPalette.Divider,
    error = LightPalette.Error,
    onError = LightPalette.OnError
)

/**
 * PixelPal Theme: Exactly two curated themes (Obsidian Dark and Paper Light).
 * No muddy dynamic wallpaper tinting. Pure, quiet, deliberate chrome.
 */
@Composable
fun PixelPalTheme(
    theme: String = "dark",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val colorScheme = when (theme.lowercase()) {
        "light" -> LightColorScheme
        "dark" -> DarkColorScheme
        else -> if (systemDark) DarkColorScheme else LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PixelPalTypography,
        shapes = PixelPalShapes,
        content = content
    )
}
