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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pixelpal.app.domain.model.AgentState
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.DestructiveButton
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.SecondaryButton
import com.pixelpal.app.presentation.components.SectionHeader

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
    viewModel: ConnectedAgentsViewModel = hiltViewModel()
) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AppTopBar(
            title = "Connected Agents",
            onBack = onBack,
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = when {
                    state.bridgeUrl.isNotBlank() -> state.bridgeUrl
                    state.usingRelay -> "Connected over relay"
                    state.isPaired -> "Relay paired"
                    else -> "Not connected"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = viewModel::refresh, enabled = state.bridgeUrl.isNotBlank()) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh agents")
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
            !state.isPaired && state.codePair !is CodePairState.Success ->
                PairingState(
                    state = state,
                    onPair = viewModel::pairWithCode,
                    onScanQr = onScanQr,
                    onOpenManualSetup = onOpenManualSetup
                )

            state.isEmpty -> NoAgentsState()

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp,
                    vertical = 8.dp
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

@Composable
private fun PairingState(
    state: ConnectedAgentsUiState,
    onPair: (String) -> Unit,
    onScanQr: () -> Unit,
    onOpenManualSetup: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CodePairingCard(
            state = state.codePair,
            onPair = onPair,
            onScanQr = onScanQr
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onOpenManualSetup) {
            Text("Set up manually instead")
        }
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
        Text("Bridge connected", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "No agents are running yet. Start Claude Code (or any supported " +
                "agent) in a terminal and it will appear here within a second.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = if (isWaiting) 4.dp else 1.dp,
        color = if (isWaiting) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor(row.status))
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
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
