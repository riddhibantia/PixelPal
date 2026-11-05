package com.pixelpal.app.presentation.screens.companions

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pixelpal.app.R
import com.pixelpal.app.domain.model.AgentConnection
import com.pixelpal.app.domain.model.Task
import com.pixelpal.app.presentation.components.AppTextField
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.BaseCompanionAvatar
import com.pixelpal.app.presentation.components.ConfirmationDialog
import com.pixelpal.app.presentation.components.DestructiveButton
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.GlassChip
import com.pixelpal.app.presentation.components.GroupDivider
import com.pixelpal.app.presentation.components.LoadingState
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import com.pixelpal.app.presentation.components.PixelPalSnackbarHost
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.SecondaryButton
import com.pixelpal.app.presentation.components.SectionHeader
import com.pixelpal.app.presentation.components.SnackbarEvent
import com.pixelpal.app.presentation.navigation.Screen
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

/** Collapsed manual provider setup embedded in the Agent tab. */
@Composable
fun CustomProviderPanel(
    scannedUrl: String? = null,
    onScannedConsumed: () -> Unit = {},
    onScanQr: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    viewModel: CompanionWorkspaceViewModel = hiltViewModel()
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.uiState.collectAsState()
    val checking by viewModel.checkingAgent.collectAsState()
    val feedback by viewModel.commandFeedback.collectAsState()
    val geminiKey by viewModel.geminiKeyOverride.collectAsState()
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.snackbarEvents.collect { event ->
            val host = snackbarHostState ?: return@collect
            val result = host.showSnackbar(
                message = event.message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed && event is SnackbarEvent.AgentDisconnected) {
                viewModel.undoDisconnect(event.connection)
            }
        }
    }

    TextButton(onClick = { expanded = !expanded }) {
        Column {
            Text(
                text = if (expanded) stringResource(R.string.agent_provider_hide_toggle) else stringResource(R.string.agent_provider_show_toggle),
                fontWeight = FontWeight.SemiBold
            )
            if (!expanded) {
                Text(
                    text = stringResource(R.string.agent_provider_collapsed_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (expanded) {
        val voicePrompt = stringResource(R.string.agent_provider_voice_prompt)
        val voiceLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let(viewModel::sendAgentCommand)
        }
        val voiceCommand = {
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, voicePrompt)
            }
            try {
                voiceLauncher.launch(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                viewModel.reportVoiceUnavailable()
            }
        }
        AgentConnectionSection(
            connection = state.agentConnection,
            checking = checking,
            feedbackMessage = feedback,
            geminiKeyOverride = geminiKey,
            scannedUrl = scannedUrl,
            onScannedConsumed = onScannedConsumed,
            onScanQr = onScanQr,
            onSave = viewModel::saveAgentConnection,
            onCheckNow = viewModel::refreshAgentStatus,
            onDisconnect = viewModel::disconnectAgent,
            onSendCommand = viewModel::sendAgentCommand,
            onVoiceCommand = voiceCommand,
            onSaveGeminiKey = viewModel::saveGeminiKey
        )
    }
}

@Composable
private fun AgentConnectionSection(
    connection: AgentConnection?,
    checking: Boolean,
    feedbackMessage: String?,
    geminiKeyOverride: String,
    scannedUrl: String? = null,
    onScannedConsumed: () -> Unit = {},
    onScanQr: () -> Unit = {},
    onSave: (AgentConnection) -> Unit,
    onCheckNow: () -> Unit,
    onDisconnect: () -> Unit,
    onSendCommand: (String) -> Unit,
    onVoiceCommand: () -> Unit,
    onSaveGeminiKey: (String) -> Unit
) {
    SectionHeader(title = stringResource(R.string.agent_provider_title))

    val currentProvider = com.pixelpal.app.domain.model.AgentProviders.normalize(connection?.provider ?: "")
    var endpoint by rememberSaveable(connection?.companionId) {
        mutableStateOf(connection?.endpointUrl ?: "")
    }
    var commandEndpoint by rememberSaveable(connection?.companionId) {
        mutableStateOf(connection?.commandUrl ?: "")
    }
    var agentName by rememberSaveable(connection?.companionId) {
        mutableStateOf(connection?.agentName ?: "")
    }
    var provider by rememberSaveable(connection?.companionId, currentProvider) {
        mutableStateOf(currentProvider)
    }
    var intervalMinutes by rememberSaveable(connection?.companionId) {
        mutableStateOf(connection?.pollingIntervalMinutes ?: com.pixelpal.app.util.Constants.DEFAULT_AGENT_POLL_INTERVAL_MIN)
    }
    var geminiKeyInput by rememberSaveable(connection?.companionId, geminiKeyOverride) {
        mutableStateOf(geminiKeyOverride)
    }
    var commandText by rememberSaveable { mutableStateOf("") }
    var pollingEnabled by rememberSaveable(connection?.companionId) {
        mutableStateOf(connection?.pollingEnabled ?: false)
    }
    var showDisconnectConfirm by rememberSaveable { mutableStateOf(false) }

    val isGemini = provider == com.pixelpal.app.domain.model.AgentProviders.GEMINI
    val isWebSocket = provider == com.pixelpal.app.domain.model.AgentProviders.WEBSOCKET
    // Gemini is configured without any endpoint; others need their URL.
    val isConfigured = if (isGemini) true else endpoint.trim().isNotBlank()
    // Check Now acts on the SAVED connection, not the draft form.
    val canCheck = !checking && (connection?.isGemini == true || connection?.endpointUrl?.isNotBlank() == true)

    Text(
        text = stringResource(R.string.agent_provider_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(Spacing.sm))

    // QR pairing fills the endpoint draft (once) when returning from the scanner.
    androidx.compose.runtime.LaunchedEffect(scannedUrl) {
        if (!scannedUrl.isNullOrBlank()) {
            endpoint = scannedUrl
            onScannedConsumed()
        }
    }

    if (showDisconnectConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.agent_provider_disconnect_title),
            message = stringResource(R.string.agent_provider_disconnect_message),
            confirmLabel = stringResource(R.string.agent_provider_action_disconnect),
            dismissLabel = stringResource(R.string.agent_action_cancel),
            destructive = true,
            onConfirm = {
                showDisconnectConfirm = false
                onDisconnect()
            },
            onDismiss = { showDisconnectConfirm = false }
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            // Status line
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = statusColor(connection),
                            shape = RoundedCornerShape(5.dp)
                        )
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connectionStatusText(connection),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    connection?.currentTask?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            connection?.progress?.let { progress ->
                Spacer(modifier = Modifier.height(Spacing.sm))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.agent_provider_status_progress,
                        progress,
                        connection.currentStatus.displayName
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            connection?.lastCheckedAt?.let {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.agent_provider_last_update, timeFormat.format(Date(it))),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            Text(
                text = stringResource(R.string.agent_provider_section_provider),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                com.pixelpal.app.domain.model.AgentProviders.ALL.forEach { option ->
                    GlassChip(
                        label = com.pixelpal.app.domain.model.AgentProviders.displayName(option),
                        selected = provider == option,
                        onClick = { provider = option }
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))
            AppTextField(
                value = agentName,
                onValueChange = { agentName = it },
                label = stringResource(R.string.agent_provider_label_agent_name),
                placeholder = stringResource(R.string.agent_provider_hint_agent_name)
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            if (isGemini) {
                AppTextField(
                    value = geminiKeyInput,
                    onValueChange = {
                        geminiKeyInput = it
                        onSaveGeminiKey(it)
                    },
                    label = stringResource(R.string.agent_provider_label_gemini_key),
                    placeholder = stringResource(R.string.agent_provider_hint_gemini_key),
                    supportingText = if (geminiKeyOverride.isBlank()) {
                        stringResource(R.string.agent_provider_key_missing_support)
                    } else {
                        stringResource(R.string.agent_provider_key_saved_support)
                    }
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.agent_provider_gemini_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                AppTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = if (isWebSocket) stringResource(R.string.agent_provider_label_ws_url) else stringResource(R.string.agent_provider_label_http_url),
                    placeholder = if (isWebSocket) "wss://example.com/agent" else "https://example.com/status",
                    supportingText = if (isWebSocket) {
                        stringResource(R.string.agent_provider_ws_support)
                    } else {
                        stringResource(R.string.agent_provider_http_support)
                    }
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                SecondaryButton(
                    text = stringResource(R.string.agent_provider_action_scan_qr),
                    onClick = onScanQr
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Spacer(modifier = Modifier.height(Spacing.sm))
                AppTextField(
                    value = commandEndpoint,
                    onValueChange = { commandEndpoint = it },
                    label = stringResource(R.string.agent_provider_label_command_url),
                    placeholder = stringResource(R.string.agent_provider_hint_command_url)
                )
            }
            Spacer(modifier = Modifier.height(Spacing.sm))

            Text(
                text = stringResource(R.string.agent_provider_label_check_interval),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                com.pixelpal.app.util.Constants.AGENT_POLL_INTERVAL_OPTIONS_MIN.forEach { option ->
                    GlassChip(
                        label = if (option >= 60) stringResource(R.string.agent_provider_interval_hours, option / 60) else stringResource(R.string.agent_provider_interval_minutes, option),
                        selected = intervalMinutes == option,
                        onClick = { intervalMinutes = option }
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.agent_provider_label_polling), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.agent_provider_polling_subtitle, intervalMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = pollingEnabled,
                    onCheckedChange = { pollingEnabled = it }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            PrimaryButton(
                text = if (connection?.endpointUrl.isNullOrBlank() && !isGemini) stringResource(R.string.agent_action_connect) else stringResource(R.string.agent_provider_action_save),
                onClick = {
                    val companionId = connection?.companionId ?: return@PrimaryButton
                    onSave(
                        (connection ?: AgentConnection(companionId = companionId)).copy(
                            agentName = agentName.trim(),
                            provider = provider,
                            endpointUrl = if (isGemini) "" else endpoint.trim(),
                            commandUrl = if (isGemini) null else commandEndpoint.trim().takeIf { it.isNotEmpty() },
                            pollingEnabled = pollingEnabled,
                            pollingIntervalMinutes = intervalMinutes
                        )
                    )
                },
                enabled = !checking && isConfigured
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            SecondaryButton(
                text = if (checking) stringResource(R.string.agent_provider_checking) else stringResource(R.string.agent_provider_action_check_now),
                onClick = onCheckNow,
                enabled = canCheck
            )

            // ── Talk to your agent: typed or spoken commands ──
            // Always visible: when unconfigured it explains what to do first.
            Spacer(modifier = Modifier.height(Spacing.md))
            GroupDivider()
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.agent_provider_command_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            if (isConfigured) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppTextField(
                        value = commandText,
                        onValueChange = { commandText = it },
                        label = stringResource(R.string.agent_provider_command_hint_label),
                        placeholder = stringResource(R.string.agent_provider_command_hint_example),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onVoiceCommand) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = stringResource(R.string.agent_provider_voice_content_description),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = {
                            onSendCommand(commandText)
                            commandText = ""
                        },
                        enabled = commandText.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.agent_provider_send_content_description),
                            tint = if (commandText.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                feedbackMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("Couldn't")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            } else {
                Text(
                    text = if (isGemini) {
                        stringResource(R.string.agent_provider_command_cta_gemini)
                    } else {
                        stringResource(R.string.agent_provider_command_cta_bridge)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isConfigured) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                DestructiveButton(
                    text = stringResource(R.string.agent_provider_action_disconnect),
                    onClick = { showDisconnectConfirm = true }
                )
            }

            connection?.errorMessage?.let {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun statusColor(connection: AgentConnection?): androidx.compose.ui.graphics.Color =
    when {
        connection == null -> MaterialTheme.colorScheme.onSurfaceVariant
        connection.connectionStatus == com.pixelpal.app.domain.model.ConnectionStatus.ERROR ->
            MaterialTheme.colorScheme.error
        connection.isConnected &&
            connection.currentStatus in setOf(
                com.pixelpal.app.domain.model.AgentState.WORKING,
                com.pixelpal.app.domain.model.AgentState.ONLINE,
                com.pixelpal.app.domain.model.AgentState.IDLE
            ) -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun connectionStatusText(connection: AgentConnection?): String =
    when {
        connection == null -> stringResource(R.string.agent_not_connected)
        connection.isGemini && connection.connectionStatus == com.pixelpal.app.domain.model.ConnectionStatus.ERROR ->
            stringResource(R.string.agent_provider_status_gemini_error)
        connection.isGemini && connection.currentStatus == com.pixelpal.app.domain.model.AgentState.DISCONNECTED ->
            stringResource(R.string.agent_provider_status_gemini_unconfigured)
        connection.isGemini -> stringResource(R.string.agent_provider_status_gemini, connection.currentStatus.displayName)
        connection.endpointUrl.isBlank() -> stringResource(R.string.agent_not_connected)
        connection.connectionStatus == com.pixelpal.app.domain.model.ConnectionStatus.ERROR ->
            stringResource(R.string.agent_provider_status_error)
        else -> stringResource(R.string.agent_provider_status_connected, connection.currentStatus.displayName)
    }
