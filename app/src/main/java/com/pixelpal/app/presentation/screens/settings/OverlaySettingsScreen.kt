package com.pixelpal.app.presentation.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pixelpal.app.R
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.GroupDivider
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import com.pixelpal.app.presentation.components.SectionHeader
import com.pixelpal.app.presentation.components.SettingsGroup
import com.pixelpal.app.presentation.components.SettingsRow
import com.pixelpal.app.presentation.components.ToggleSettingsRow
import com.pixelpal.app.presentation.theme.Spacing
import com.pixelpal.app.util.PermissionHelper

/**
 * Screen Companion Overlay settings — ONE overlay for THE companion.
 */
@Composable
fun OverlaySettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val overlayEnabled by viewModel.overlayEnabled.collectAsState()

    PixelPalAtmosphere(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.lg)
        ) {
            AppTopBar(title = stringResource(R.string.settings_overlay_title), onBack = { navController.popBackStack() })

        Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {

            SectionHeader(title = stringResource(R.string.settings_overlay_section_overlay))
            SettingsGroup {
                ToggleSettingsRow(
                    title = stringResource(R.string.settings_overlay_toggle_title),
                    description = stringResource(R.string.settings_overlay_toggle_description),
                    icon = Icons.Default.Pets,
                    checked = overlayEnabled,
                    onCheckedChange = { viewModel.toggleOverlay() }
                )
            }

            SectionHeader(title = stringResource(R.string.settings_overlay_section_position))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_overlay_reset_title),
                    description = stringResource(R.string.settings_overlay_reset_description),
                    icon = Icons.Default.ScreenRotation,
                    onClick = { viewModel.resetOverlayPosition() }
                )
            }

            SectionHeader(title = stringResource(R.string.settings_overlay_section_background))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_overlay_battery_title),
                    description = stringResource(R.string.settings_overlay_battery_description),
                    icon = Icons.Default.BatteryChargingFull,
                    onClick = {
                        (navController.context as? android.app.Activity)?.let {
                            PermissionHelper.requestIgnoreBatteryOptimizations(it)
                        }
                    }
                )
            }
        }
    }
}
}