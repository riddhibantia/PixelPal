package com.pixelpal.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixelpal.app.animation.AnimationState

/**
 * A "stage" for the pet — a soft circular seat that keeps white-bodied pets
 * readable on white cards and lavender canvas alike.
 *
 * On the page canvas it renders as a white/elevated circle with the card
 * shadow (an empty state's little pedestal); inside cards a tinted seat is
 * enough, which is what callers doing their own Box use.
 */
@Composable
fun PetStage(
    petType: String,
    animationState: AnimationState,
    petSize: Dp,
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val stageBackground = if (isLight) {
        androidx.compose.ui.graphics.Brush.radialGradient(
            colors = listOf(
                Color(0xFF8B6CF6).copy(alpha = 0.18f),
                Color(0xFF6738EB).copy(alpha = 0.08f),
                Color(0xFFEDE7F9)
            )
        )
    } else {
        androidx.compose.ui.graphics.Brush.radialGradient(
            colors = listOf(
                Color(0xFF8B6CF6).copy(alpha = 0.24f),
                Color(0xFF262044),
                Color(0xFF1D1A2E)
            )
        )
    }

    val stageBorder = androidx.compose.foundation.BorderStroke(
        width = 1.dp,
        color = Color(0xFF8B6CF6).copy(alpha = if (isLight) 0.25f else 0.35f)
    )

    Box(
        modifier = modifier
            .size(petSize + padding * 2)
            .shadow(
                elevation = 12.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color(0xFF8B6CF6).copy(alpha = 0.25f)
            )
            .border(stageBorder, CircleShape)
            .background(stageBackground, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        LottiePetView(
            petType = petType,
            animationState = animationState,
            size = petSize
        )
    }
}
