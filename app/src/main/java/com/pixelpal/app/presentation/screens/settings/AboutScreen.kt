package com.pixelpal.app.presentation.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.pixelpal.app.BuildConfig
import com.pixelpal.app.R
import com.pixelpal.app.animation.AnimationState
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.GroupDivider
import com.pixelpal.app.presentation.components.PetRenderer
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import com.pixelpal.app.presentation.components.SectionHeader
import com.pixelpal.app.presentation.components.SettingsGroup
import com.pixelpal.app.presentation.components.SettingsRow
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing

/**
 * About PixelPal — companion art, tagline, version (from BuildConfig — never
 * hardcoded), help & feedback, privacy note.
 *
 * The privacy note must stay truthful about cloud sync: local-first by default,
 * but signed-in accounts sync to their own Firebase Cloud Firestore documents.
 */
@Composable
fun AboutScreen(
    navController: NavController,
    petType: String = "cat"
) {
    val context = LocalContext.current

    PixelPalAtmosphere(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.lg)
        ) {
            AppTopBar(title = stringResource(R.string.about_title), onBack = { navController.popBackStack() })

            Column(
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            PetRenderer(
                petType = petType,
                animationState = AnimationState.WAVE,
                size = 140.dp
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.about_app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionHeader(title = stringResource(R.string.about_section_support))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.about_support_feedback_title),
                    description = stringResource(R.string.about_support_feedback_description),
                    icon = Icons.Default.Mail,
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:")
                            putExtra(
                                Intent.EXTRA_SUBJECT,
                                context.getString(R.string.about_feedback_email_subject)
                            )
                        }
                        context.startActivity(
                            Intent.createChooser(
                                intent,
                                context.getString(R.string.about_feedback_chooser_title)
                            )
                        )
                    }
                )
            }

            SectionHeader(title = stringResource(R.string.about_section_privacy))
            SettingsGroup {
                Surface(
                    shape = RoundedCornerShape(Radius.large),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Four honest paragraphs: local-first, what cloud sync does,
                    // guest mode, and the one feature that always stays on-device.
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        listOf(
                            R.string.about_privacy_local,
                            R.string.about_privacy_cloud,
                            R.string.about_privacy_guest,
                            R.string.about_privacy_agent
                        ).forEachIndexed { index, textRes ->
                            if (index > 0) {
                                Spacer(modifier = Modifier.height(Spacing.sm))
                            }
                            Text(
                                text = stringResource(textRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))
            Text(
                text = stringResource(R.string.about_footer_made_with),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
}
