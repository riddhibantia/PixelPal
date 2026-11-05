package com.pixelpal.app.presentation.screens.agents

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pixelpal.app.domain.model.AgentState
import com.pixelpal.app.animation.AnimationState
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.theme.Spacing
import com.pixelpal.app.presentation.components.DestructiveButton
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.LottiePetView
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.SecondaryButton
import androidx.navigation.NavController
import com.pixelpal.app.presentation.components.SectionHeader
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import com.pixelpal.app.presentation.components.IconTile
import androidx.compose.material.icons.filled.SmartToy
import com.pixelpal.app.presentation.navigation.Screen

/**
 * Connected Agents — the multi-agent home for the bridge.
 *
 * Design intent: the old workspace screen was a form with a URL field, which
 * does not scale to five agents. This screen leads with WHAT IS HAPPENING
 * (one row per agent, live state, and an inline action when an agent is
 * blocked on you) and hides pairing behind a single "Pair bridge" action.
 */
@Composable
fun ConnectedAgentsScreen(
    onBack: () -> Unit,
    onScanQr: () -> Unit,
    onOpenManualSetup: () -> Unit,
    /** Pairing payload from a scanned QR code, consumed once. */
    scannedUri: String? = null,
    onScannedConsumed: () -> Unit = {},
    navController: NavController? = null,
    viewModel: ConnectedAgentsViewModel = hiltViewModel()
) {
    LaunchedEffect(scannedUri) {
        if (!scannedUri.isNullOrBlank()) {
            viewModel.applyPairing(scannedUri)
            onScannedConsumed()
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Refresh on every resume. Cheap (loopback) and means the list is never
    // stale after switching back from the terminal or another app.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(
            androidx.lifecycle.Lifecycle.State.RESUMED
        ) {
            viewModel.refresh()
        }
    }

    androidx.compose.material3.Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (navController != null) {
                com.pixelpal.app.presentation.components.PixelPalBottomBar(
                    navController = navController,
                    selected = Screen.ConnectedAgents
                )
            }
        }
    ) { padding ->
        PixelPalAtmosphere(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                AppTopBar(
                    title = "Connected Agents",
                    onBack = if (navController != null) null else onBack,
                    subtitle = when {
                        state.isEmpty -> null
                        else -> buildString {
                            if (state.workingCount > 0) append("${state.workingCount} working")
                            if (state.waitingCount > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("${state.waitingCount} waiting on you")
                            }
                            if (isEmpty()) append("${state.rows.size} idle")
                        }
                    }
                )

                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (state.isPaired) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline
                                    )
                            )
                        }
                        Text(
                            text = when {
                                state.bridgeUrl.isNotBlank() -> state.bridgeUrl
                                state.isPaired -> "Bridge Paired"
                                else -> "Not connected"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = if (state.bridgeUrl.isNotBlank()) FontFamily.Monospace else FontFamily.Default
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = viewModel::refresh,
                            enabled = state.bridgeUrl.isNotBlank(),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Refresh agents",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

        state.lastError?.let { error ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        when {
            !state.isPaired -> PairingState(
                onPair = viewModel::applyPairing,
                onScanQr = onScanQr,
                onOpenManualSetup = onOpenManualSetup
            )

            state.isEmpty -> NoAgentsState()

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = 100.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.rows, key = { it.id }) { row ->
                    AgentRowCard(
                        row = row,
                        onApprove = { viewModel.approve(row) },
                        onDeny = { viewModel.deny(row) }
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(text = "Pair another bridge", onClick = onScanQr, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::unpair) { Text("Unpair") }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
}
}







@Composable
private fun PairingState(
    onPair: (String) -> Unit,
    onScanQr: () -> Unit,
    onOpenManualSetup: () -> Unit
) {
    var pasted by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LottiePetView(
            petType = "cat",
            animationState = AnimationState.WAVE,
            size = 180.dp
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = "Connect your computer",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "On your laptop, run this once:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "pixelpal-bridge setup",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier.padding(14.dp),
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Paste the pairing code it printed:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("pixelpal://pair?host=…&key=…") },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false
            ),
            keyboardActions = KeyboardActions(onDone = { onPair(pasted) })
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = "Connect",
            onClick = { onPair(pasted) },
            enabled = pasted.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))
        SecondaryButton(
            text = "Scan a QR code",
            onClick = onScanQr,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton(
            text = "Enter the address manually",
            onClick = onOpenManualSetup,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun NoAgentsState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LottiePetView(
            petType = "cat",
            animationState = AnimationState.SLEEP,
            size = 180.dp
        )
        Spacer(Modifier.height(Spacing.md))
        Text("Bridge connected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "No agents are running yet. Start Claude Code (or any supported " +
                "agent) in a terminal and it will appear here within a second.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AgentRowCard(
    row: AgentRow,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    val isWaiting = row.status == AgentState.WAITING_FOR_INPUT
    com.pixelpal.app.presentation.components.GlassCard(
        shape = RoundedCornerShape(com.pixelpal.app.presentation.theme.Radius.large),
        elevation = if (isWaiting) 4.dp else 1.dp,
        borderStroke = if (isWaiting) {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        },
        backgroundColor = if (isWaiting) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(
                    icon = Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = statusColor(row.status)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor(row.status))
                        )
                    }
                    Text(
                        text = buildString {
                            append(row.status.displayName)
                            if (row.project.isNotBlank()) append(" · ${row.project}")
                            append(" · ${formatAge(row.secondsSinceEvent)}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                row.progress?.let { progress ->
                    Text(
                        text = "$progress%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (row.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = row.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }

            if (row.toolsCompleted > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${row.toolsCompleted} tool calls this session",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // The whole point of the bridge: act without touching the laptop.
            if (isWaiting && row.pendingApprovalId != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = row.pendingApprovalAction ?: "Permission requested",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(text = "Approve", onClick = onApprove, modifier = Modifier.weight(1f))
                    SecondaryButton(text = "Deny", onClick = onDeny, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: AgentState) = when (status) {
    AgentState.WORKING -> MaterialTheme.colorScheme.primary
    AgentState.WAITING_FOR_INPUT -> MaterialTheme.colorScheme.tertiary
    AgentState.ERROR -> MaterialTheme.colorScheme.error
    AgentState.COMPLETED -> MaterialTheme.colorScheme.secondary
    AgentState.ONLINE -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.outline
}

private fun formatAge(seconds: Long): String = when {
    seconds < 5 -> "just now"
    seconds < 60 -> "${seconds}s ago"
    seconds < 3600 -> "${seconds / 60}m ago"
    else -> "${seconds / 3600}h ago"
}
