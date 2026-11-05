package com.pixelpal.app.presentation.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pixelpal.app.presentation.theme.PixelPalGradients
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing

/**
 * Frosted-glass segmented control — replaces default Material 3
 * [androidx.compose.material3.SegmentedButton] rows with the quiet-chrome
 * pill language used across the redesign.
 *
 * The track is a calm surface with a hairline outline; the selected segment
 * is the single bright element — the primary gradient pill.
 */
@Composable
fun GlassSegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackShape = RoundedCornerShape(Radius.pill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.surface, trackShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, trackShape)
            .padding(Spacing.xs)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val scale by animateFloatAsState(
                targetValue = if (selected) 1f else 0.97f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "segmentScale$index"
            )
            val segmentShape = RoundedCornerShape(Radius.pill)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .scale(scale)
                    .clip(segmentShape)
                    .then(
                        if (selected) {
                            Modifier.background(PixelPalGradients.primaryCard, segmentShape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelected(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
