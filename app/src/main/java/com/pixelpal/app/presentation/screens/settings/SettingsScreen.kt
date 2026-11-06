package com.pixelpal.app.presentation.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Shield
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pixelpal.app.BuildConfig
import com.pixelpal.app.R
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.GroupDivider
import com.pixelpal.app.animation.AnimationState
import com.pixelpal.app.presentation.components.PetRenderer
import com.pixelpal.app.presentation.components.SectionHeader
import com.pixelpal.app.presentation.components.SettingsGroup
import com.pixelpal.app.presentation.components.SettingsRow
import com.pixelpal.app.presentation.components.ThemePickerSheet
import com.pixelpal.app.presentation.components.PixelPalBottomBar
import com.pixelpal.app.presentation.navigation.Screen
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing

/**
 * Settings = ME + APP behavior.
 * Profile / General / Companion / Privacy & Data / Support.
 * Companion customization lives in Customize — not here.
 */
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val userName by viewModel.userName.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()
    var showThemeSheet by remember { mutableStateOf(false) }

    fun openNotificationSettings() {
        val intent = Intent().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = Uri.parse("package:${context.packageName}")
            }
        }
        context.startActivity(intent)
    }

    fun openFeedbackEmail() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, "PixelPal Feedback")
        }
        context.startActivity(Intent.createChooser(intent, "Send feedback"))
    }

    PixelPalAtmosphere(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Spacing.lg)
            ) {
                AppTopBar(title = stringResource(R.string.settings_title))

                Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {

                    // ── PROFILE ──
                    GlassCard(
                        shape = RoundedCornerShape(Radius.large),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { navController.navigate(Screen.Profile.route) }
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // The pet IS the profile — seated on the violet tile
                            // every other leading icon uses.
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                        androidx.compose.foundation.shape.CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                val settingsCompanion by viewModel.companion.collectAsState()
                                PetRenderer(
                                    petType = settingsCompanion?.effectiveSpecies ?: "cat",
                                    animationState = AnimationState.IDLE,
                                    size = 40.dp
                                )
                            }
                            Spacer(modifier = Modifier.width(Spacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = userName.ifBlank { stringResource(R.string.settings_row_profile_title) },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.settings_row_profile_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // ── GENERAL ──
                    SectionHeader(title = stringResource(R.string.settings_section_general))
                    SettingsGroup {
                        SettingsRow(
                            title = stringResource(R.string.settings_row_notifications_title),
                            description = stringResource(R.string.settings_row_notifications_description),
                            icon = Icons.Default.NotificationsActive,
                            onClick = { openNotificationSettings() }
                        )
                        GroupDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_row_appearance_title),
                            value = currentTheme.replaceFirstChar { it.uppercase() },
                            icon = Icons.Default.Palette,
                            onClick = { showThemeSheet = true }
                        )
                    }

                    // ── COMPANION ──
                    SectionHeader(title = stringResource(R.string.settings_section_companion))
                    SettingsGroup {
                        SettingsRow(
                            title = stringResource(R.string.settings_row_overlay_title),
                            description = stringResource(R.string.settings_row_overlay_description),
                            icon = Icons.Default.Pets,
                            onClick = { navController.navigate(Screen.OverlaySettings.route) }
                        )
                        GroupDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_row_background_title),
                            description = stringResource(R.string.settings_row_background_description),
                            icon = Icons.Default.Bolt,
                            onClick = { navController.navigate(Screen.Permissions.route) }
                        )
                    }

                    // ── PRIVACY & DATA ──
                    SectionHeader(title = stringResource(R.string.settings_section_privacy))
                    SettingsGroup {
                        SettingsRow(
                            title = stringResource(R.string.settings_row_permissions_title),
                            description = stringResource(R.string.settings_row_permissions_description),
                            icon = Icons.Default.Shield,
                            onClick = { navController.navigate(Screen.Permissions.route) }
                        )
                    }

                    // ── SUPPORT ──
                    SectionHeader(title = stringResource(R.string.settings_section_support))
                    SettingsGroup {
                        SettingsRow(
                            title = stringResource(R.string.settings_row_help_title),
                            icon = Icons.AutoMirrored.Filled.HelpOutline,
                            onClick = { openFeedbackEmail() }
                        )
                        GroupDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_row_about_title),
                            value = stringResource(R.string.settings_version_suffix, BuildConfig.VERSION_NAME),
                            icon = Icons.Default.Info,
                            onClick = { navController.navigate(Screen.About.route) }
                        )
                    }
                }
            }

            PixelPalBottomBar(navController = navController, selected = Screen.Settings)
        }
    }

    if (showThemeSheet) {
        ThemePickerSheet(
            currentTheme = currentTheme,
            onSelectTheme = {
                viewModel.selectTheme(it)
                showThemeSheet = false
            },
            onDismiss = { showThemeSheet = false }
        )
    }
}
