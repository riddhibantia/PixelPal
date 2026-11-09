package com.pixelpal.app.presentation.screens.agents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.pixelpal.app.R
import com.pixelpal.app.animation.AnimationState
import com.pixelpal.app.domain.model.AgentState
import com.pixelpal.app.domain.model.Companion
import com.pixelpal.app.presentation.components.AppTextField
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.DestructiveButton
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.LottiePetView
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import com.pixelpal.app.presentation.components.PixelPalBottomBar
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.SecondaryButton
import com.pixelpal.app.presentation.navigation.Screen
import com.pixelpal.app.presentation.screens.companions.CustomProviderPanel
import com.pixelpal.app.presentation.theme.Spacing
import com.pixelpal.app.util.BridgeSetup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Connected Agents — the multi-agent home for the bridge.
 */
@Composable
fun ConnectedAgentsScreen(
    onBack: () -> Unit,
    onScanQr: () -> Unit,
    scannedUri: String? = null,
    onScannedConsumed: () -> Unit = {},
    navController: NavController? = null,
    viewModel: ConnectedAgentsViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(scannedUri) {
        val uri = scannedUri?.trim()
        if (!uri.isNullOrBlank()) {
            val handled = viewModel.applyPairing(uri)
            if (handled) {
                onScannedConsumed()
            }
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val companion by viewModel.companion.collectAsStateWithLifecycle()
    val companionPetType = companion?.effectiveSpecies ?: "rabbit"
    val companionName = companion?.name ?: "Pixel"
    val streakDays by viewModel.streakDays.collectAsStateWithLifecycle()
    var showChatDialog by remember { mutableStateOf(false) }
    var selectedAgentForControl by remember { mutableStateOf<AgentRow?>(null) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(
            androidx.lifecycle.Lifecycle.State.RESUMED
        ) {
            viewModel.refresh()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (navController != null) {
                PixelPalBottomBar(
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
            Column(modifier = Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.agent_title),
                    onBack = if (navController != null) null else onBack,
                    subtitle = when {
                        state.isEmpty -> null
                        else -> buildString {
                            append(state.rows.size)
                            append(" connected")
                            if (state.workingCount > 0) append(" • ${state.workingCount} working")
                            if (state.waitingCount > 0) append(" • ${state.waitingCount} need input")
                            val pausedCount = state.rows.count { it.status == AgentState.PAUSED }
                            if (pausedCount > 0) append(" • $pausedCount paused")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.refresh() },
                            enabled = !state.isRefreshing
                        ) {
                            if (state.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.agent_refresh_content_description)
                                )
                            }
                        }
                    }
                )

                state.lastError?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                state.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 8.dp,
                        bottom = 100.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, Color(0xFF8B6CF6).copy(alpha = 0.2f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Whatshot,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "$streakDays day streak",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "Keep it burning!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (!state.isPaired) {
                        item {
                            PairingState(
                                onPair = viewModel::applyPairing,
                                onScanQr = onScanQr,
                                isPairing = state.isPairing,
                                petType = companionPetType,
                                petName = companionName,
                                onPetTapped = { viewModel.interactWithCompanion() },
                                onOpenChat = { showChatDialog = true }
                            )
                        }
                    } else if (state.isEmpty) {
                        item {
                            NoAgentsState(
                                petType = companionPetType,
                                petName = companionName,
                                onPetTapped = { viewModel.interactWithCompanion() },
                                onOpenChat = { showChatDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Spacer(Modifier.height(16.dp))
                            DestructiveButton(
                                text = stringResource(R.string.agent_action_unpair),
                                onClick = viewModel::unpair,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        items(state.rows, key = { it.id }) { row ->
                            AgentRowCard(
                                row = row,
                                onApprove = { viewModel.approve(row) },
                                onDeny = { viewModel.deny(row) },
                                onDismiss = { viewModel.dismissAgent(row) },
                                onClick = { selectedAgentForControl = row }
                            )
                        }
                        item {
                            Spacer(Modifier.height(16.dp))
                            DestructiveButton(
                                text = stringResource(R.string.agent_action_unpair),
                                onClick = viewModel::unpair,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    item {
                        CustomProviderPanel(
                            scannedUrl = scannedUri?.takeUnless { it.startsWith("pixelpal://pair", ignoreCase = true) },
                            onScannedConsumed = onScannedConsumed,
                            onScanQr = onScanQr,
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            }
        }
    }

    selectedAgentForControl?.let { agent ->
        AgentControlDialog(
            agent = agent,
            viewModel = viewModel,
            onDismissRequest = { selectedAgentForControl = null }
        )
    }

    if (showChatDialog) {
        val who = "friend"
        val chatMessages = remember {
            mutableStateListOf(
                false to "Hi! I'm $companionName. I'm actively monitoring your coding agents! ✨"
            )
        }
        var userText by remember { mutableStateOf("") }
        val suggestions = listOf(
            "How are you doing? 😊",
            "Give me motivation ✨",
            "Tell me a fun fact 💡",
            "What's our streak? 🔥"
        )

        AlertDialog(
            onDismissRequest = { showChatDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF8B6CF6).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        LottiePetView(
                            petType = companionPetType,
                            animationState = AnimationState.WAVE,
                            size = 48.dp
                        )
                    }
                    Column {
                        Text(
                            text = "Chat with $companionName",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Your AI companion is listening",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(chatMessages) { (isUser, text) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (isUser) 16.dp else 4.dp,
                                        bottomEnd = if (isUser) 4.dp else 16.dp
                                    ),
                                    color = if (isUser) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        suggestions.take(2).forEach { suggestion ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        chatMessages.add(true to suggestion)
                                        val reply = when (suggestion) {
                                            "How are you doing? 😊" -> "I'm super energetic and watching your terminal! Keep coding!"
                                            "Give me motivation ✨" -> "You're capable of incredible things. One line of code at a time!"
                                            "What's our streak? 🔥" -> "We have a $streakDays day streak! Let's keep the fire burning!"
                                            else -> "Did you know? Coding with a companion boosts focus and creativity by 100%!"
                                        }
                                        chatMessages.add(false to reply)
                                    }
                            ) {
                                Text(
                                    text = suggestion,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppTextField(
                            value = userText,
                            onValueChange = { userText = it },
                            label = "Message",
                            placeholder = "Say something...",
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                if (userText.isNotBlank()) {
                                    val q = userText.trim()
                                    chatMessages.add(true to q)
                                    userText = ""
                                    chatMessages.add(false to "Got it! $companionName is right here with you! ✨")
                                }
                            },
                            enabled = userText.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send",
                                tint = if (userText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChatDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

/**
 * Interactive Pet Widget that bounces on tap, displays reaction speech bubbles, and triggers chat.
 */
@Composable
private fun InteractivePetHeader(
    petType: String,
    petName: String,
    onPetTapped: () -> Unit,
    onOpenChat: () -> Unit,
    animationState: AnimationState = AnimationState.WAVE
) {
    var interactionTick by remember { mutableIntStateOf(0) }
    var feedbackText by remember { mutableStateOf<String?>(null) }
    var currentExpression by remember { mutableStateOf(animationState) }
    val scaleAnim = remember { Animatable(1f) }
    val rotationAnim = remember { Animatable(0f) }

    val reactionMessages = listOf(
        "I'm keeping watch for your AI agents! 🤖✨",
        "Ready to pair and code together! 🚀",
        "You're doing awesome work! 💖",
        "Let's connect your workspace! 🔥"
    )

    LaunchedEffect(interactionTick) {
        if (interactionTick == 0) return@LaunchedEffect
        feedbackText = reactionMessages.random()
        currentExpression = AnimationState.HAPPY

        launch {
            scaleAnim.animateTo(1.18f, tween(120, easing = FastOutSlowInEasing))
            scaleAnim.animateTo(0.95f, tween(100))
            scaleAnim.animateTo(1.06f, tween(80))
            scaleAnim.animateTo(1f, spring(Spring.DampingRatioMediumBouncy))
        }
        launch {
            rotationAnim.animateTo(-7f, tween(90))
            rotationAnim.animateTo(7f, tween(110))
            rotationAnim.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
        }

        delay(1200)
        currentExpression = animationState
        delay(1200)
        feedbackText = null
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    interactionTick++
                    onPetTapped()
                    onOpenChat()
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                        rotationZ = rotationAnim.value
                    },
                contentAlignment = Alignment.Center
            ) {
                LottiePetView(
                    petType = petType,
                    animationState = currentExpression,
                    size = 180.dp
                )
            }
        }

        feedbackText?.let { feedback ->
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PairingState(
    onPair: (String) -> Unit,
    onScanQr: () -> Unit,
    isPairing: Boolean,
    petType: String,
    petName: String,
    onPetTapped: () -> Unit,
    onOpenChat: () -> Unit
) {
    val context = LocalContext.current
    var pasted by remember { mutableStateOf("") }
    var selectedOs by remember { mutableIntStateOf(0) } // 0 = macOS / Linux, 1 = Windows
    val activeCommand = if (selectedOs == 0) BridgeSetup.installCommand else BridgeSetup.installCommandWindows

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        InteractivePetHeader(
            petType = petType,
            petName = petName,
            onPetTapped = onPetTapped,
            onOpenChat = onOpenChat,
            animationState = AnimationState.WAVE
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(R.string.agent_pair_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Run this command in your laptop terminal to set up the bridge:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))

        // OS Selector Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("macOS / Linux", "Windows (PowerShell)").forEachIndexed { index, title ->
                val isSelected = selectedOs == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { selectedOs = index }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = activeCommand,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    textAlign = TextAlign.Start
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(BridgeSetup.CLIPBOARD_LABEL, activeCommand))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Copy command", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.agent_pair_step_scan),
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
            text = if (isPairing) stringResource(R.string.agent_pair_connecting) else stringResource(R.string.agent_action_connect),
            onClick = { onPair(pasted) },
            enabled = pasted.isNotBlank() && !isPairing,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        SecondaryButton(
            text = stringResource(R.string.agent_pair_scan_qr),
            onClick = onScanQr,
            enabled = !isPairing,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun NoAgentsState(
    petType: String,
    petName: String,
    onPetTapped: () -> Unit,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        InteractivePetHeader(
            petType = petType,
            petName = petName,
            onPetTapped = onPetTapped,
            onOpenChat = onOpenChat,
            animationState = AnimationState.SLEEP
        )
        Spacer(Modifier.height(Spacing.md))
        Text(stringResource(R.string.agent_empty_paired_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.agent_empty_paired_message),
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
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
    onClick: () -> Unit
) {
    val isWaiting = row.status == AgentState.WAITING_FOR_INPUT
    GlassCard(
        shape = RoundedCornerShape(com.pixelpal.app.presentation.theme.Radius.large),
        elevation = if (isWaiting) 4.dp else 1.dp,
        borderStroke = if (isWaiting) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        },
        backgroundColor = if (isWaiting) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
        },
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when (row.status) {
                                AgentState.WORKING -> MaterialTheme.colorScheme.primary
                                AgentState.WAITING_FOR_INPUT -> MaterialTheme.colorScheme.error
                                AgentState.PAUSED -> Color(0xFFF59E0B)
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (row.project.isNotBlank()) {
                    Text(
                        text = " • ${row.project}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatRelativeTime(row.secondsSinceEvent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove agent session",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (row.message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = row.message,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            row.task?.let { task ->
                if (task != row.message) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = task,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.clickable { onClick() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Control & Chat",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (row.toolsCompleted > 0) {
                    Text(
                        text = "${row.toolsCompleted} tools",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isWaiting) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SecondaryButton(
                        text = "Deny",
                        onClick = onDeny,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryButton(
                        text = "Approve",
                        onClick = onApprove,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentControlDialog(
    agent: AgentRow,
    viewModel: ConnectedAgentsViewModel,
    onDismissRequest: () -> Unit
) {
    var commandText by remember { mutableStateOf("") }
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isSending by viewModel.isSendingCommand.collectAsState()
    val listState = rememberLazyListState()

    // Poll live messages and tool activity from the bridge / agent every 2.5s
    LaunchedEffect(agent.id) {
        viewModel.loadSessionMessages(agent)
        while (isActive) {
            delay(2500)
            viewModel.loadSessionMessages(agent)
        }
    }

    // Auto-scroll to latest message
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    val quickCommands = listOf(
        "⏹ Stop" to "stop",
        "⏸ Pause" to "pause",
        "📋 Status" to "status",
        "🧪 Tests" to "run tests"
    )

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    when (agent.status) {
                                        AgentState.WORKING -> MaterialTheme.colorScheme.primary
                                        AgentState.WAITING_FOR_INPUT -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                )
                        )
                        Column {
                            Text(
                                text = agent.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (agent.project.isNotBlank()) "Project: ${agent.project}" else "Active Session: ${agent.sessionId.take(12)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Compact status banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Status: ${agent.status.displayName}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val detail = agent.task ?: agent.message
                            if (detail.isNotBlank()) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                viewModel.loadSessionMessages(agent)
                                viewModel.refresh()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Quick command pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickCommands.forEach { (label, cmd) ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (cmd == "stop") MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    viewModel.sendCommandToAgent(agent, cmd)
                                }
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (cmd == "stop") MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 7.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Spacious Chat Feed
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(10.dp)
                ) {
                    if (chatMessages.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "No conversation messages yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Type an instruction below to chat with your agent!",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(chatMessages, key = { it.id.ifBlank { "${it.timestamp}_${it.role}_${it.text.hashCode()}" } }) { msg ->
                                when (msg.role) {
                                    "user" -> {
                                        // User message (right aligned)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.End,
                                                modifier = Modifier.fillMaxWidth(0.85f)
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(bottom = 2.dp)
                                                ) {
                                                    Text(
                                                        text = msg.text,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                                    )
                                                }
                                                Text(
                                                    text = "You",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                        }
                                    }
                                    "tool" -> {
                                        // Simplified, clean inline indicator: e.g. "✓ Opened MainActivity.kt", "⚡ Running...", "✓ Done"
                                        val isDone = msg.status == "completed" || msg.status == "done" || msg.status == "success" || msg.status == "succeeded"
                                        val displaySummary = when {
                                            msg.summary.isNotBlank() -> msg.summary
                                            msg.command.contains("git", ignoreCase = true) -> if (isDone) "Done (git)" else "Running git..."
                                            msg.command.contains("gradle", ignoreCase = true) -> if (isDone) "Done (gradle)" else "Running gradle..."
                                            msg.name.contains("file", ignoreCase = true) -> "Opened file"
                                            isDone -> "Done"
                                            else -> "Running..."
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 1.dp),
                                            horizontalArrangement = Arrangement.Start
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    if (isDone) {
                                                        Text(
                                                            text = "✓",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                                            color = Color(0xFF4CAF50)
                                                        )
                                                    } else {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(10.dp),
                                                            strokeWidth = 1.5.dp,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                    Text(
                                                        text = displaySummary,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        // Agent message (left aligned)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Start
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.Start,
                                                modifier = Modifier.fillMaxWidth(0.88f)
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    modifier = Modifier.padding(bottom = 2.dp)
                                                ) {
                                                    Text(
                                                        text = msg.text,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                                    )
                                                }
                                                Text(
                                                    text = agent.displayName,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Prompt input box
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppTextField(
                        value = commandText,
                        onValueChange = { commandText = it },
                        label = "Prompt Agent",
                        placeholder = "Type prompt or command...",
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (commandText.isNotBlank()) {
                                val c = commandText.trim()
                                commandText = ""
                                viewModel.sendCommandToAgent(agent, c)
                            }
                        },
                        enabled = commandText.isNotBlank() && !isSending
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (commandText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun formatRelativeTime(seconds: Long): String = when {
    seconds < 5 -> "just now"
    seconds < 60 -> "${seconds}s ago"
    seconds < 3600 -> "${seconds / 60}m ago"
    seconds < 86400 -> "${seconds / 3600}h ago"
    else -> "${seconds / 86400}d ago"
}

