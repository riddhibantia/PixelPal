package com.pixelpal.app.presentation.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pixelpal.app.R
import com.pixelpal.app.presentation.theme.AutumnPalette
import com.pixelpal.app.presentation.theme.DarkPalette
import com.pixelpal.app.presentation.theme.LightPalette
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Sizing
import com.pixelpal.app.presentation.theme.SpringPalette
import com.pixelpal.app.presentation.theme.Spacing

/**
 * Display metadata for each selectable theme — swatches come from the centralized
 * palettes. [name] is a resolved (already-localized) string so this stays a plain,
 * non-composable registry; [descriptionRes] and [id] are stable platform values
 * (readable from ViewModels / PreferencesManager.kt).
 */
@Immutable
data class ThemeOption(
    val id: String,
    val name: String,
    val background: Color,
    val surface: Color,
    val accent: Color,
    /** Optional supporting line shown in the full picker sheet only (null = none). */
    val descriptionRes: Int? = null
)

/** Id of the adaptive theme — [com.pixelpal.app.presentation.theme.PixelPalTheme] matches this exactly. */
const val SYSTEM_THEME_ID = "system"

/**
 * Every selectable theme, with display names resolved from resources at call time.
 *
 * "dark"/"light"/"spring"/"autumn" are fixed palettes; [SYSTEM_THEME_ID] adapts
 * to the device (wallpaper dynamic color on Android 12+, otherwise PixelPal's
 * own dark/light palette following the system dark-mode setting — see Theme.kt).
 */
@Composable
fun themeOptions(): List<ThemeOption> {
    val systemOption = if (supportsDynamicColor()) {
        ThemeOption(
            id = SYSTEM_THEME_ID,
            name = stringResource(R.string.theme_option_system_name),
            background = DarkPalette.Surface,
            surface = DarkPalette.Primary,
            accent = LightPalette.Primary,
            descriptionRes = R.string.theme_option_system_description
        )
    } else {
        // Pre-Android 12: no wallpaper colors, so preview the palette "system"
        // actually resolves to right now (system dark -> Dark, else Light).
        // (Palettes are objects, not a common supertype — branch explicitly.)
        val systemIsDark = isSystemInDarkTheme()
        ThemeOption(
            id = SYSTEM_THEME_ID,
            name = stringResource(R.string.theme_option_system_name),
            background = if (systemIsDark) DarkPalette.Background else LightPalette.Background,
            surface = if (systemIsDark) DarkPalette.Surface else LightPalette.Surface,
            accent = if (systemIsDark) DarkPalette.Primary else LightPalette.Primary
        )
    }
    return listOf(
        systemOption,
        ThemeOption("dark", stringResource(R.string.theme_option_dark_name), DarkPalette.Background, DarkPalette.Surface, DarkPalette.Primary),
        ThemeOption("light", stringResource(R.string.theme_option_light_name), LightPalette.Background, LightPalette.Surface, LightPalette.Primary),
        ThemeOption("spring", stringResource(R.string.theme_option_spring_name), SpringPalette.Background, SpringPalette.Surface, SpringPalette.Primary),
        ThemeOption("autumn", stringResource(R.string.theme_option_autumn_name), AutumnPalette.Background, AutumnPalette.Surface, AutumnPalette.Primary)
    )
}

/** True on Android 12+ where "system" renders wallpaper-derived dynamic color. */
private fun supportsDynamicColor(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The single theme selector for the whole app.
 * Source of truth is PreferencesManager.currentTheme; every surface that
 * changes themes calls [onSelectTheme] with the same ids.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePickerSheet(
    currentTheme: String,
    onSelectTheme: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = Radius.xlarge, topEnd = Radius.xlarge)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.theme_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.md))

            val options = themeOptions()
            options.forEach { option ->
                val selected = currentTheme.equals(option.id, ignoreCase = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = option.name) { onSelectTheme(option.id) }
                        .padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Palette preview: background / surface / accent swatches
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(option.background, RoundedCornerShape(Radius.small))
                            .border(
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                RoundedCornerShape(Radius.small)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(option.surface, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(option.accent, CircleShape)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        option.descriptionRes?.let { descriptionRes ->
                            Text(
                                text = stringResource(descriptionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.theme_selected_description),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Sizing.icon)
                        )
                    } else {
                        Box(modifier = Modifier.size(Sizing.icon)) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(20.dp)
                                    .border(
                                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                                        CircleShape
                                    )
                            )
                        }
                    }
                }
                if (option.id != options.last().id) {
                    GroupDivider()
                }
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
        }
    }
}


/**
 * Compact theme card for the Customize screen: palette swatch preview +
 * name + selected checkmark + accent border.
 */
@Composable
fun ThemeCard(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Resolve the a11y label outside the non-composable `semantics {}` lambda.
    val cardDescription = if (selected) {
        stringResource(R.string.theme_card_description_selected, option.name)
    } else {
        stringResource(R.string.theme_card_description, option.name)
    }
    Column(
        modifier = modifier
            .clickable(onClickLabel = option.name) { onClick() }
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(Radius.large)
            )
            .border(
                BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                RoundedCornerShape(Radius.large)
            )
            .padding(Spacing.md)
            .semantics { contentDescription = cardDescription },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Stacked palette preview
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(option.background, CircleShape)
                    .border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(option.surface, CircleShape)
                    .border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(option.accent, CircleShape)
                    .border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface), CircleShape)
            )
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = option.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (selected) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
