package com.pixelpal.app.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixelpal.app.presentation.theme.Radius

/**
 * Clean, quiet instrument card for PixelPal.
 *
 * Grounded in Linear / Apple Notes craft: solid calm surface, quiet hairline
 * border, subtle neutral depth. Per the approved mockup, the card treatment
 * is theme-aware:
 *  - **Light**: pure white surface carried by a soft diffuse shadow — no
 *    border.
 *  - **Dark**: surface lifted one step with a faint violet hairline — no
 *    shadow.
 *
 * Explicit arguments always win: pass [borderStroke] to force a border
 * (e.g. the error edge on a waiting agent card) or `null` to force none;
 * pass [elevation] to force a shadow level in either theme.
 */
private val ThemeDefaultBorder = BorderStroke(0.dp, Color.Transparent)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.large),
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    backgroundBrush: Brush? = null,
    borderStroke: BorderStroke? = ThemeDefaultBorder,
    elevation: Dp = Dp.Unspecified,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val effectiveBorder = when {
        borderStroke !== ThemeDefaultBorder -> borderStroke
        isLight -> null
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }
    val effectiveElevation = when {
        elevation != Dp.Unspecified -> elevation
        isLight -> 8.dp
        else -> 0.dp
    }

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(
                if (effectiveElevation > 0.dp) {
                    Modifier.shadow(
                        elevation = effectiveElevation,
                        shape = shape,
                        ambientColor = Color.Black.copy(alpha = 0.08f),
                        spotColor = Color(0xFF4A3B78).copy(alpha = 0.22f)
                    )
                } else Modifier
            )
            .clip(shape)
            .then(
                if (effectiveBorder != null) {
                    Modifier.border(effectiveBorder, shape)
                } else Modifier
            )
            .then(
                if (backgroundBrush != null) {
                    Modifier.background(backgroundBrush, shape)
                } else {
                    Modifier.background(backgroundColor, shape)
                }
            )
            .then(clickableModifier),
        content = content
    )
}
