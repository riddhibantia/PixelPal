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
 * from the top-right and a soft secondary glow from the bottom-left, eliminating
 * dead flat voids and providing lush glassmorphic depth.
 */
@Composable
fun PixelPalAtmosphere(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    // Soft atmospheric top-right violet glow
    val topGlowColor = if (isLight) {
        Color(0xFF8B6CF6).copy(alpha = 0.16f)
    } else {
        Color(0xFF8B6CF6).copy(alpha = 0.22f)
    }

    val topGlowBrush = Brush.radialGradient(
        colors = listOf(
            topGlowColor,
            topGlowColor.copy(alpha = 0.08f),
            Color.Transparent
        )
    )

    // Soft atmospheric bottom-left indigo ambient glow
    val bottomGlowColor = if (isLight) {
        Color(0xFF38BDF8).copy(alpha = 0.08f)
    } else {
        Color(0xFF6738EB).copy(alpha = 0.16f)
    }

    val bottomGlowBrush = Brush.radialGradient(
        colors = listOf(
            bottomGlowColor,
            bottomGlowColor.copy(alpha = 0.06f),
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
                .offset(x = 80.dp, y = (-40).dp)
                .size(380.dp)
                .background(brush = topGlowBrush, shape = CircleShape)
        )

        // Secondary subtle ambient glow at bottom-left
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-60).dp, y = 80.dp)
                .size(340.dp)
                .background(brush = bottomGlowBrush, shape = CircleShape)
        )

        // Content
        content()
    }
}
