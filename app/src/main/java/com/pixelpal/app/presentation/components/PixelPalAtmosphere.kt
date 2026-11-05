package com.pixelpal.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Universal ambient backdrop for PixelPal screens.
 *
 * Imbues both Dark and Light themes with an organic, atmospheric violet glow
 * from the top-right (matching the approved design mockup), eliminating dead
 * flat voids and providing glassmorphic depth.
 */
@Composable
fun PixelPalAtmosphere(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val glowColor = if (isLight) {
        Color(0xFF8B6CF6).copy(alpha = 0.16f)
    } else {
        Color(0xFF8B6CF6).copy(alpha = 0.14f)
    }

    val glowBrush = Brush.radialGradient(
        colors = listOf(
            glowColor,
            glowColor.copy(alpha = 0.06f),
            Color.Transparent
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Primary ambient atmospheric glow at top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 60.dp, y = (-40).dp)
                .size(320.dp)
                .background(brush = glowBrush, shape = CircleShape)
        )

        // Content
        content()
    }
}
