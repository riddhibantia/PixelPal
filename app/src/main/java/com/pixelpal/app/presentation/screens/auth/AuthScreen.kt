package com.pixelpal.app.presentation.screens.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixelpal.app.R
import com.pixelpal.app.animation.AnimationState
import com.pixelpal.app.data.remote.firebase.AuthState
import com.pixelpal.app.presentation.components.AppTextField
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.LottiePetView
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.SecondaryButton
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing
import com.pixelpal.app.util.Constants

@Composable
private fun GoogleG() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(color = Color(0xFF4285F4), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "G",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isDataReady by viewModel.isDataReady.collectAsState()
    val syncError by viewModel.syncError.collectAsState()
    var showResetDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    // Navigate only after auth AND the post-login cloud restore finished —
    // never on auth alone, or Home renders an empty Room mid-restore.
    // A sync failure stays on this screen with a retry option instead of
    // silently showing an empty app.
    LaunchedEffect(authState, isDataReady, syncError) {
        if (authState is AuthState.Authenticated && isDataReady && syncError == null) {
            onAuthSuccess()
        }
    }

    Scaffold { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Companion Mascot with Lottie
                LottiePetView(
                    petType = Constants.DEFAULT_PET_TYPE,
                    animationState = if (uiState.isLoading) AnimationState.THINKING else AnimationState.HAPPY,
                    size = 120.dp
                )

                Spacer(modifier = Modifier.height(Spacing.md))

                Text(
                    text = if (uiState.isSignUpMode) stringResource(R.string.auth_signup_title)
                    else stringResource(R.string.auth_login_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = stringResource(R.string.auth_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                )

                Spacer(modifier = Modifier.height(Spacing.md))

                // User Name field — how the Pixel Pet should address the user
                AppTextField(
                    value = uiState.userName,
                    onValueChange = viewModel::onUserNameChanged,
                    label = stringResource(R.string.auth_user_name_label),
                    placeholder = stringResource(R.string.auth_user_name_placeholder),
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                Spacer(modifier = Modifier.height(Spacing.md))

                // Google Sign-In first — the one-tap path.
                val activityContext = LocalContext.current
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.medium),
                    onClick = { if (!uiState.isLoading) viewModel.signInWithGoogle(activityContext) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GoogleG()
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            text = stringResource(R.string.auth_action_google),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                Text(
                    text = stringResource(R.string.auth_or_email),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Email field
                AppTextField(
                    value = uiState.email,
                    onValueChange = viewModel::onEmailChanged,
                    label = stringResource(R.string.auth_email_label),
                    placeholder = stringResource(R.string.auth_email_placeholder),
                    keyboardType = KeyboardType.Email,
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Mail,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Password field
                AppTextField(
                    value = uiState.password,
                    onValueChange = viewModel::onPasswordChanged,
                    label = stringResource(R.string.auth_password_label),
                    placeholder = stringResource(R.string.auth_password_placeholder),
                    keyboardType = KeyboardType.Password,
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                if (uiState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = uiState.errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                if (uiState.successMessage != null) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = uiState.successMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }

                // Firestore/application-init failure AFTER successful auth —
                // kept visually distinct from auth errors above.
                if (syncError != null) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = syncError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            Spacing.sm,
                            Alignment.CenterHorizontally
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = viewModel::retrySync) {
                            Text(text = stringResource(R.string.auth_action_retry_sync))
                        }
                        TextButton(onClick = onAuthSuccess) {
                            Text(text = stringResource(R.string.auth_action_continue_offline))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                if (uiState.isLoading || isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isSyncing) {
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.auth_status_syncing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // Primary Action Button
                    PrimaryButton(
                        text = if (uiState.isSignUpMode) stringResource(R.string.auth_action_sign_up)
                        else stringResource(R.string.auth_action_log_in),
                        onClick = viewModel::submitEmailAuth
                    )

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    // Guest Mode Button
                    SecondaryButton(
                        text = stringResource(R.string.auth_action_guest),
                        onClick = viewModel::signInAsGuest
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = viewModel::toggleAuthMode) {
                        Text(
                            text = if (uiState.isSignUpMode) stringResource(R.string.auth_toggle_to_login)
                            else stringResource(R.string.auth_toggle_to_signup),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (!uiState.isSignUpMode) {
                        TextButton(onClick = { showResetDialog = true }) {
                            Text(
                                text = stringResource(R.string.auth_action_forgot_password),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        var resetEmail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(uiState.email) }
        var resetSent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var resetError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
        var isSending by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isSending) showResetDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.auth_reset_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (resetSent) {
                        Text(
                            text = stringResource(R.string.auth_reset_dialog_success),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.auth_reset_dialog_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppTextField(
                            value = resetEmail,
                            onValueChange = { resetEmail = it; resetError = null },
                            label = stringResource(R.string.auth_email_label),
                            placeholder = stringResource(R.string.auth_email_placeholder),
                            keyboardType = KeyboardType.Email
                        )
                        Text(
                            text = stringResource(R.string.auth_reset_dialog_spam_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (resetError != null) {
                            Text(
                                text = resetError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (resetSent) {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text("OK")
                    }
                } else {
                    PrimaryButton(
                        text = stringResource(R.string.auth_reset_dialog_send),
                        onClick = {
                            isSending = true
                            resetError = null
                            viewModel.sendPasswordReset(resetEmail) { success, msg ->
                                isSending = false
                                if (success) {
                                    resetSent = true
                                } else {
                                    resetError = msg
                                }
                            }
                        },
                        enabled = !isSending && resetEmail.isNotBlank()
                    )
                }
            },
            dismissButton = {
                if (!resetSent) {
                    TextButton(
                        onClick = { showResetDialog = false },
                        enabled = !isSending
                    ) {
                        Text(stringResource(R.string.common_dialog_cancel))
                    }
                }
            }
        )
    }
}
