package com.pixelpal.app.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Token
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pixelpal.app.R
import com.pixelpal.app.presentation.navigation.Screen
import com.pixelpal.app.presentation.theme.PixelPalGradients

private data class NavDestination(
    val screen: Screen,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val labelRes: Int
)

private val destinations = listOf(
    NavDestination(Screen.Home, Icons.Filled.Home, Icons.Outlined.Home, R.string.dock_tab_home),
    NavDestination(Screen.Tasks, Icons.AutoMirrored.Filled.FormatListBulleted, Icons.AutoMirrored.Outlined.FormatListBulleted, R.string.dock_tab_tasks),
    NavDestination(Screen.ConnectedAgents, Icons.Filled.Token, Icons.Outlined.Token, R.string.dock_tab_agent),
    NavDestination(Screen.Reminders, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarToday, R.string.dock_tab_reminders),
    NavDestination(Screen.Settings, Icons.Filled.Person, Icons.Outlined.Person, R.string.dock_tab_settings)
)

/**
 * Floating frosted glass navigation pill with individual active tab indicators.
 */
@Composable
fun PixelPalBottomBar(
    navController: NavController,
    selected: Screen,
    modifier: Modifier = Modifier
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val dockBackground = if (isLight) {
        Color.White.copy(alpha = 0.96f)
    } else {
        Color(0xFF19162C).copy(alpha = 0.94f)
    }

    val dockBorderColor = Color(0xFF8B6CF6).copy(alpha = if (isLight) 0.18f else 0.25f)
    val inactiveIconColor = if (isLight) Color(0xFF6B6382) else Color(0xFFA6A0B8)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = if (isLight) Color(0xFF2C2250).copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.35f),
                    spotColor = if (isLight) Color(0xFF6738EB).copy(alpha = 0.20f) else Color(0xFF8B6CF6).copy(alpha = 0.30f)
                )
                .clip(CircleShape)
                .background(dockBackground)
                .border(
                    BorderStroke(1.dp, dockBorderColor),
                    CircleShape
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            destinations.forEach { dest ->
                val isSelected = selected == dest.screen
                val label = stringResource(dest.labelRes)
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.05f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "pillScale"
                )
                val iconTint by animateColorAsState(
                    targetValue = if (isSelected) Color.White else inactiveIconColor,
                    label = "pillIcon"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = label
                        ) {
                            if (!isSelected) {
                                if (dest.screen == Screen.Home) {
                                    val popped = navController.popBackStack(Screen.Home.route, inclusive = false)
                                    if (!popped) {
                                        navController.navigate(Screen.Home.route) {
                                            popUpTo(Screen.Home.route) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                } else {
                                    navController.navigate(dest.screen.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 54.dp, height = 44.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .shadow(
                                            elevation = 6.dp,
                                            shape = RoundedCornerShape(22.dp),
                                            ambientColor = Color.Transparent,
                                            spotColor = Color(0xFF6738EB).copy(alpha = 0.45f)
                                        )
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(PixelPalGradients.primaryCard)
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected) dest.selectedIcon else dest.unselectedIcon,
                            contentDescription = label,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
