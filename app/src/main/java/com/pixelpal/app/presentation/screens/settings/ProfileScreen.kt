package com.pixelpal.app.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pixelpal.app.R
import com.pixelpal.app.presentation.components.AppTextField
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.ConfirmationDialog
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.PixelAvatar
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.SectionHeader
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing

private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

/**
 * Edit Profile — warm, personal space. Local profile only (no auth system):
 * name, email and a cozy deterministic avatar.
 */
@Composable
fun ProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var avatarSeed by remember { mutableStateOf("pixelpal") }
    var originalName by remember { mutableStateOf("") }
    var originalEmail by remember { mutableStateOf("") }
    var originalSeed by remember { mutableStateOf("pixelpal") }
    var loaded by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var attemptedSave by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // Load stored profile once, directly from DataStore (avoids StateFlow's
    // initial-value race where the form would start blank).
    LaunchedEffect(Unit) {
        if (!loaded) {
            originalName = viewModel.currentUserName()
            originalEmail = viewModel.currentUserEmail()
            originalSeed = viewModel.currentAvatarSeed()
            name = originalName
            email = originalEmail
            avatarSeed = originalSeed
            loaded = true
        }
    }

    val currentName = name
    val currentEmail = email
    val currentSeed = avatarSeed

    val nameError = attemptedSave && currentName.isBlank()
    val emailError = attemptedSave && currentEmail.isNotEmpty() && !EMAIL_REGEX.matches(currentEmail)
    val hasChanges = loaded && (
        currentName != originalName ||
            currentEmail != originalEmail ||
            currentSeed != originalSeed
        )

    fun attemptBack() {
        if (hasChanges) showDiscardDialog = true else navController.popBackStack()
    }

    PixelPalAtmosphere(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = stringResource(R.string.settings_profile_title), onBack = { attemptBack() })

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(bottom = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Warm avatar header
                PixelAvatar(seed = currentSeed, size = 96.dp)
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = currentName.ifBlank { stringResource(R.string.settings_profile_name_placeholder) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_profile_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.clickable {
                        avatarSeed = viewModel.nextAvatarSeed(currentSeed)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.settings_profile_avatar_content_description),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Change Avatar",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                SectionHeader(title = stringResource(R.string.settings_profile_section_personal))

                GlassCard(
                    shape = RoundedCornerShape(Radius.large),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        AppTextField(
                            value = currentName,
                            onValueChange = { name = it },
                            label = stringResource(R.string.settings_profile_name_label),
                            placeholder = stringResource(R.string.settings_profile_name_placeholder),
                            isError = nameError,
                            supportingText = if (nameError) stringResource(R.string.settings_profile_name_error) else null
                        )
                        AppTextField(
                            value = currentEmail,
                            onValueChange = { email = it },
                            label = stringResource(R.string.settings_profile_email_label),
                            placeholder = stringResource(R.string.settings_profile_email_placeholder),
                            keyboardType = KeyboardType.Email,
                            isError = emailError,
                            supportingText = when {
                                emailError -> stringResource(R.string.settings_profile_email_error)
                                else -> null
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.lg))

                SectionHeader(title = stringResource(R.string.settings_profile_section_sync))

                GlassCard(
                    shape = RoundedCornerShape(Radius.large),
                    modifier = Modifier.fillMaxWidth()
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    val isSyncing by viewModel.isSyncing.collectAsState()
                    val syncError by viewModel.lastSyncError.collectAsState()
                    val accountText = when {
                        !viewModel.isUserLoggedIn -> stringResource(R.string.settings_profile_sync_not_connected)
                        viewModel.isAnonymousUser -> stringResource(R.string.settings_profile_sync_guest)
                        else -> stringResource(
                            R.string.settings_profile_sync_connected,
                            viewModel.currentUserEmail
                                ?: stringResource(R.string.settings_profile_sync_authenticated)
                        )
                    }

                    Text(
                        text = accountText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = stringResource(R.string.settings_profile_sync_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Last-sync status: failures were previously only logged,
                    // so a broken sync looked identical to a healthy one.
                    SyncStatusRow(
                        loggedIn = viewModel.isUserLoggedIn,
                        isSyncing = isSyncing,
                        syncError = syncError
                    )

                    Spacer(modifier = Modifier.height(Spacing.xs))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        com.pixelpal.app.presentation.components.SecondaryButton(
                            text = if (isSyncing) stringResource(R.string.settings_profile_action_syncing)
                            else stringResource(R.string.settings_profile_action_sync_now),
                            enabled = !isSyncing && viewModel.isUserLoggedIn,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.triggerCloudSync() }
                        )

                        com.pixelpal.app.presentation.components.SecondaryButton(
                            text = if (viewModel.isUserLoggedIn && !viewModel.isAnonymousUser)
                                stringResource(R.string.settings_profile_action_account)
                            else stringResource(R.string.settings_profile_action_log_in),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                navController.navigate(com.pixelpal.app.presentation.navigation.Screen.Auth.route)
                            }
                        )
                    }

                    if (viewModel.isUserLoggedIn) {
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        com.pixelpal.app.presentation.components.DestructiveButton(
                            text = stringResource(R.string.settings_profile_action_sign_out),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                viewModel.signOut()
                                navController.navigate(com.pixelpal.app.presentation.navigation.Screen.Auth.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            PrimaryButton(
                text = stringResource(R.string.settings_profile_action_save),
                enabled = !nameError && !emailError,
                onClick = {
                    attemptedSave = true
                    if (currentName.isNotBlank() &&
                        (currentEmail.isEmpty() || EMAIL_REGEX.matches(currentEmail))
                    ) {
                        focusManager.clearFocus()
                        viewModel.saveProfile(currentName, currentEmail, currentSeed)
                        Toast.makeText(context, "Profile saved!", Toast.LENGTH_SHORT).show()
                        navController.popBackStack()
                    }
                }
            )
        }
    }
}

    if (showDiscardDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_profile_discard_title),
            message = stringResource(R.string.settings_profile_discard_message),
            confirmLabel = stringResource(R.string.settings_profile_discard_confirm),
            destructive = true,
            onConfirm = {
                showDiscardDialog = false
                navController.popBackStack()
            },
            onDismiss = { showDiscardDialog = false }
        )
    }
}

/**
 * Small inline status line for the Cloud Sync card. Shows the last full-sync
 * failure (with the raw reason) so sync breakage is visible to the user
 * instead of only appearing in logcat.
 */
@Composable
private fun SyncStatusRow(
    loggedIn: Boolean,
    isSyncing: Boolean,
    syncError: String?
) {
    val (text, color) = when {
        !loggedIn -> stringResource(R.string.settings_sync_status_off) to
            MaterialTheme.colorScheme.onSurfaceVariant
        isSyncing -> stringResource(R.string.settings_sync_status_syncing) to
            MaterialTheme.colorScheme.onSurfaceVariant
        syncError != null -> stringResource(R.string.settings_sync_status_failed, syncError) to
            MaterialTheme.colorScheme.error
        else -> stringResource(R.string.settings_sync_status_ok) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = when {
                syncError != null && loggedIn -> Icons.Default.ErrorOutline
                else -> Icons.Default.Sync
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.height(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}
