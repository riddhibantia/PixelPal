package com.pixelpal.app.presentation.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.pixelpal.app.presentation.components.AppTextField
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pixelpal.app.R
import com.pixelpal.app.animation.AnimationState
import com.pixelpal.app.domain.model.Companion
import com.pixelpal.app.domain.model.DayActivity
import com.pixelpal.app.domain.model.WeeklyActivity
import com.pixelpal.app.presentation.components.EmptyState
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.LoadingState
import com.pixelpal.app.presentation.components.LottiePetView
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import com.pixelpal.app.presentation.components.PixelPalBottomBar
import com.pixelpal.app.presentation.navigation.Screen
import com.pixelpal.app.presentation.theme.PixelPalGradients
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val hasCompanion by viewModel.hasCompanion.collectAsState()
    var showChatDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            PixelPalBottomBar(navController = navController, selected = Screen.Home)
        }
    ) { innerPadding ->
        PixelPalAtmosphere(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(bottom = Spacing.xs)
            ) {
                when {
                    isLoading -> LoadingState(modifier = Modifier.padding(top = Spacing.xl))

                !hasCompanion -> EmptyState(
                    title = stringResource(R.string.home_empty_no_companion_title),
                    message = stringResource(R.string.home_empty_no_companion_message),
                    petState = AnimationState.WAVE
                )

                else -> {
                    // 1. Top Header with Quick Actions & Centered Glowing Mascot
                    HomeHeaderSlot(
                        viewModel = viewModel,
                        onCustomizeClick = { navController.navigate(Screen.Customize.route) },
                        onBellClick = { navController.navigate(Screen.ActivityCenter.route) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // The core promise is noticing and acting when an agent
                    // needs you, so place the live agent state immediately
                    // after the greeting rather than below routine stats.
                    HomeAgentCard(
                        viewModel = viewModel,
                        onOpen = {
                            navController.navigate(Screen.ConnectedAgents.route) {
                                popUpTo(Screen.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. Action Cards: Interact (Chat) + Agent hub
                    HomeActionCardsRow(
                        onInteractClick = {
                            viewModel.interactWithCompanion()
                            showChatDialog = true
                        },
                        onWorkspaceClick = {
                            navController.navigate(Screen.ConnectedAgents.route) {
                                popUpTo(Screen.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. Weekly Consistency & Streak Bar Chart
                    HomeStreakCard(
                        viewModel = viewModel,
                        onClick = { navController.navigate(Screen.ActivityCenter.route) }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // 4. Quick Stats: Tasks & Reminders
                    HomeQuickStatsRow(
                        viewModel = viewModel,
                        onTasksClick = { navController.navigate(Screen.Tasks.route) },
                        onRemindersClick = { navController.navigate(Screen.Reminders.route) }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // 6. Today Task Checklist Preview
                    HomeTodayTasksSection(
                        viewModel = viewModel,
                        onViewAllClick = { navController.navigate(Screen.Tasks.route) }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                }
            }
        }
    }

    if (showChatDialog) {
        val hero by viewModel.heroSlice.collectAsState()
        val header by viewModel.headerSlice.collectAsState()
        val comp = hero.companion
        val petName = comp?.name ?: "Pixel"
        val who = header.userName.ifBlank { "friend" }
        val streak = hero.bond?.streakDays ?: 0

        val chatMessages = remember {
            androidx.compose.runtime.mutableStateListOf(
                false to "Hi $who! I'm $petName. I'm always here watching over your coding tasks and agents! ✨"
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
                            petType = comp?.effectiveSpecies ?: "rabbit",
                            animationState = AnimationState.WAVE,
                            size = 48.dp
                        )
                    }
                    Column {
                        Text(
                            text = "Chat with $petName",
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
                        .height(320.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Chat bubbles list
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(chatMessages) { (isUser, text) ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (isUser) 16.dp else 4.dp,
                                        bottomEnd = if (isUser) 4.dp else 16.dp
                                    ),
                                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth(0.85f)
                                ) {
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Suggestion Chips
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(suggestions) { prompt ->
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                modifier = Modifier.clickable {
                                    chatMessages.add(true to prompt)
                                    viewModel.interactWithCompanion()
                                    val reply = when {
                                        prompt.contains("How are you") -> "$petName is feeling super energetic today! Ready to take on new bugs and build great things together!"
                                        prompt.contains("motivation") -> "Every line of code you write gets you closer to mastery. Believe in yourself, $who!"
                                        prompt.contains("fun fact") -> "Did you know? The first computer bug was an actual moth found trapped inside a Mark II computer in 1947!"
                                        prompt.contains("streak") -> "We are on a $streak-day streak! Keep up the amazing momentum!"
                                        else -> "I love chatting with you, $who! Let's keep making progress today!"
                                    }
                                    chatMessages.add(false to reply)
                                }
                            ) {
                                Text(
                                    text = prompt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Input Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppTextField(
                            value = userText,
                            onValueChange = { userText = it },
                            label = "Message",
                            placeholder = "Say something to $petName...",
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val trimmed = userText.trim()
                                if (trimmed.isNotBlank()) {
                                    chatMessages.add(true to trimmed)
                                    userText = ""
                                    viewModel.interactWithCompanion()
                                    val reply = "That's wonderful, $who! I'm right here cheering for you. Let's do this!"
                                    chatMessages.add(false to reply)
                                }
                            },
                            enabled = userText.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (userText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
}

// -------------------------------------------------------------------------
// 1. HEADER SLOT
// -------------------------------------------------------------------------

@Composable
private fun HomeHeaderSlot(
    viewModel: HomeViewModel,
    onCustomizeClick: () -> Unit,
    onBellClick: () -> Unit
) {
    val header by viewModel.headerSlice.collectAsState()
    val hero by viewModel.heroSlice.collectAsState()
    val today by viewModel.todaySlice.collectAsState()
    val agent by viewModel.agentSlice.collectAsState()
    val companion = hero.companion

    // Mascot tap animations
    var interactionTick by remember { mutableIntStateOf(0) }
    var feedbackText by remember { mutableStateOf<String?>(null) }
    var currentExpression by remember { mutableStateOf(AnimationState.IDLE) }

    val scaleAnim = remember { Animatable(1f) }
    val rotationAnim = remember { Animatable(0f) }
    val sparkleAlpha = remember { Animatable(0f) }
    val sparkleScale = remember { Animatable(0.6f) }

    val reactionMessages = listOf(
        stringResource(
            R.string.home_reaction_happy,
            companion?.name ?: stringResource(R.string.home_default_pet_name)
        ),
        stringResource(R.string.home_reaction_coding_mode),
        stringResource(R.string.home_reaction_bond_strengthened),
        stringResource(R.string.home_reaction_lets_get_things_done),
        stringResource(R.string.home_reaction_always_here)
    )

    LaunchedEffect(interactionTick) {
        if (interactionTick == 0) return@LaunchedEffect
        feedbackText = reactionMessages.random()
        currentExpression = AnimationState.HAPPY

        launch {
            sparkleScale.snapTo(0.6f)
            sparkleAlpha.snapTo(1f)
            sparkleScale.animateTo(1.25f, tween(650, easing = FastOutSlowInEasing))
            sparkleAlpha.animateTo(0f, tween(550))
        }

        launch {
            scaleAnim.animateTo(1.16f, tween(120, easing = FastOutSlowInEasing))
            scaleAnim.animateTo(0.96f, tween(100))
            scaleAnim.animateTo(1.05f, tween(80))
            scaleAnim.animateTo(1f, spring(Spring.DampingRatioMediumBouncy))
        }
        launch {
            rotationAnim.animateTo(-6f, tween(90))
            rotationAnim.animateTo(6f, tween(110))
            rotationAnim.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
        }

        delay(900)
        currentExpression = AnimationState.IDLE
        delay(1200)
        feedbackText = null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs)
    ) {
        // Top action icons: Grid on left, Bell on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassCard(
                shape = CircleShape,
                modifier = Modifier.size(44.dp),
                onClick = onCustomizeClick
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.GridView,
                        contentDescription = stringResource(R.string.home_cd_customize_companion),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            GlassCard(
                shape = CircleShape,
                modifier = Modifier.size(44.dp),
                onClick = onBellClick
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    BadgedBox(badge = {
                        if (header.unreadCount > 0) {
                            Badge { Text(text = header.unreadCount.coerceAtMost(9).toString()) }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = stringResource(R.string.home_cd_activity_center),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        // Title row: Greeting, PixelPal & Tagline
        Column(modifier = Modifier.fillMaxWidth()) {
            val who = header.userName.ifBlank { stringResource(R.string.home_default_user_name) }
            Text(
                text = stringResource(R.string.home_greeting_hello, who),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Pixel",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Pal",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color(0xFF8B6CF6)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodySmall.copy(
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xs))

        // Centered Mascot with soft organic ambient glow (no square pedestal)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Soft atmospheric radial purple glow behind the mascot
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF8B6CF6).copy(alpha = 0.38f),
                                Color(0xFF6738EB).copy(alpha = 0.16f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )

            // Centered mascot pet
            Box(
                modifier = Modifier
                    .size(168.dp)
                    .clickable {
                        interactionTick++
                        viewModel.interactWithCompanion()
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(164.dp)
                        .graphicsLayer {
                            scaleX = scaleAnim.value
                            scaleY = scaleAnim.value
                            rotationZ = rotationAnim.value
                        },
                    contentAlignment = Alignment.Center
                ) {
                    LottiePetView(
                        petType = companion?.effectiveSpecies ?: "cat",
                        animationState = currentExpression,
                        size = 160.dp
                    )
                }

                // Stars, sparkles and hearts burst on tap
                SparkleOverlay(alpha = sparkleAlpha.value, scale = sparkleScale.value, size = 190.dp)
            }
        }

        // Feedback speech reaction under mascot if present
        feedbackText?.let { feedback ->
            Spacer(modifier = Modifier.height(Spacing.xs))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                    modifier = Modifier.padding(horizontal = Spacing.md)
                ) {
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
@Composable
private fun SparkleOverlay(alpha: Float, scale: Float, size: androidx.compose.ui.unit.Dp = 170.dp) {
    if (alpha <= 0.01f) return
    val sparkle = androidx.compose.ui.res.painterResource(id = R.drawable.particle_sparkle)
    val star = androidx.compose.ui.res.painterResource(id = R.drawable.particle_star)
    val heart = androidx.compose.ui.res.painterResource(id = R.drawable.particle_heart)
    val dot = androidx.compose.ui.res.painterResource(id = R.drawable.particle_dot)

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
    ) {
        // Top-left sparkle
        Image(
            painter = sparkle,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 10.dp, y = 10.dp)
                .size(26.dp)
        )
        // Top-right star
        Image(
            painter = star,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-12).dp, y = 12.dp)
                .size(28.dp)
        )
        // Bottom-left heart
        Image(
            painter = heart,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 14.dp, y = (-16).dp)
                .size(24.dp)
        )
        // Bottom-right star
        Image(
            painter = star,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-14).dp, y = (-12).dp)
                .size(26.dp)
        )
        // Mid-right sparkle
        Image(
            painter = sparkle,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-4).dp, y = (-8).dp)
                .size(22.dp)
        )
        // Mid-left dot glow
        Image(
            painter = dot,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 6.dp, y = 6.dp)
                .size(16.dp)
        )
    }
}

// 2. ACTION CARDS ROW (Interact & Agent hub)
// -------------------------------------------------------------------------

@Composable
private fun HomeActionCardsRow(
    onInteractClick: () -> Unit,
    onWorkspaceClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Left: Interact Card (Rich Purple Gradient)
        GlassCard(
            modifier = Modifier
                .weight(1f)
                .height(98.dp),
            backgroundBrush = PixelPalGradients.primaryCard,
            borderStroke = null,
            elevation = 6.dp,
            onClick = onInteractClick
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = stringResource(R.string.home_card_interact_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.home_card_interact_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // Right: Agent card
        GlassCard(
            modifier = Modifier
                .weight(1f)
                .height(98.dp),
            elevation = 2.dp,
            onClick = onWorkspaceClick
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = stringResource(R.string.home_card_agent_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.home_card_agent_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// 3. STREAK CARD WITH WEEKLY BAR CHART
// -------------------------------------------------------------------------

@Composable
private fun HomeStreakCard(viewModel: HomeViewModel, onClick: () -> Unit = {}) {
    val hero by viewModel.heroSlice.collectAsState()
    val weekly by viewModel.weeklyActivity.collectAsState()
    val streakDays = hero.bond?.streakDays ?: 0

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left flame badge and streak count
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Whatshot,
                        contentDescription = stringResource(R.string.home_cd_streak),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.md))

                Column {
                    Text(
                        text = stringResource(R.string.home_streak_days, streakDays),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.home_streak_keep_going),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Right: Mon-Sun weekly activity bar chart
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                weekly.days.forEach { day ->
                    WeeklyBarItem(day = day)
                }
            }
        }
    }
}

@Composable
private fun WeeklyBarItem(day: DayActivity) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        val barHeight = (26.dp * day.intensity.coerceIn(0.25f, 1f))
        val barModifier = if (day.isActive) {
            Modifier.background(PixelPalGradients.streakBar)
        } else {
            Modifier.background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
        }
        Box(
            modifier = Modifier
                .height(28.dp)
                .width(14.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .then(barModifier)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = day.dayLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// -------------------------------------------------------------------------
// 4. QUICK STATS ROW (Tasks Left & Reminders)
// -------------------------------------------------------------------------

@Composable
private fun HomeQuickStatsRow(
    viewModel: HomeViewModel,
    onTasksClick: () -> Unit,
    onRemindersClick: () -> Unit
) {
    val today by viewModel.todaySlice.collectAsState()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Left: Tasks Left Card
        GlassCard(
            modifier = Modifier.weight(1f),
            onClick = onTasksClick
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.sm))

                Column {
                    Text(
                        text = "${today.pendingCount}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.home_stats_tasks_left),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Right: Reminders Card
        GlassCard(
            modifier = Modifier.weight(1f),
            onClick = onRemindersClick
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.sm))

                Column {
                    Text(
                        text = if (today.hasUpcomingReminder) "1" else stringResource(R.string.home_stats_none),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.home_stats_reminders),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// 5. AI AGENT CARD (With Inline Approval Gate)
// -------------------------------------------------------------------------

@Composable
private fun HomeAgentCard(
    viewModel: HomeViewModel,
    onOpen: () -> Unit
) {
    val agent by viewModel.agentSlice.collectAsState()
    val isWaiting = agent.waiting > 0 && agent.pendingApprovalId != null

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderStroke = if (isWaiting) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        },
        onClick = onOpen
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Soft organic purple fluid wave in the bottom right corner matching inspiration
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 20.dp, y = 20.dp)
                    .size(90.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF8B6CF6).copy(alpha = 0.35f),
                                Color(0xFF6738EB).copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ViewInAr,
                                contentDescription = stringResource(R.string.home_agent_card_label),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(Spacing.sm))

                        Column {
                            Text(
                                text = stringResource(R.string.home_agent_card_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val title = if (agent.headline.isBlank() || agent.headline == "No agents connected") {
                                stringResource(R.string.home_agent_card_empty_title)
                            } else {
                                agent.headline
                            }
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isWaiting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (!isWaiting) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(PixelPalGradients.primaryCard),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.home_cd_add_or_pair_agent),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                val subtitle = if (agent.detail.isNullOrBlank()) {
                    stringResource(R.string.home_agent_card_pair_subtitle)
                } else {
                    agent.detail!!
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Progress bar if working
                agent.progress?.let { progress ->
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        strokeCap = StrokeCap.Round
                    )
                }

                // Inline Approve / Deny buttons if waiting for user permission!
                if (isWaiting) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        androidx.compose.material3.Button(
                            onClick = { agent.pendingApprovalId?.let { viewModel.approveAgentApproval(it) } },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(Radius.medium)
                        ) {
                            Text(stringResource(R.string.home_agent_action_approve), fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        androidx.compose.material3.OutlinedButton(
                            onClick = { agent.pendingApprovalId?.let { viewModel.denyAgentApproval(it) } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(Radius.medium)
                        ) {
                            Text(stringResource(R.string.home_agent_action_deny), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// 6. TODAY SECTION
// -------------------------------------------------------------------------

@Composable
private fun HomeTodayTasksSection(
    viewModel: HomeViewModel,
    onViewAllClick: () -> Unit
) {
    val today by viewModel.todaySlice.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_today_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onViewAllClick() }
            ) {
                Text(
                    text = stringResource(R.string.home_today_view_all),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xs))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onViewAllClick
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (today.pendingCount == 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.home_today_no_tasks),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.home_today_add_task_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = today.firstTaskTitle
                                    ?: stringResource(R.string.home_today_pending_tasks, today.pendingCount),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Decorative 3D-styled purple clipboard illustration badge matching inspiration
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .graphicsLayer { rotationZ = -6f }
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(
                                    Color(0xFF8B6CF6).copy(alpha = 0.35f),
                                    Color(0xFF6738EB).copy(alpha = 0.20f)
                                )
                            )
                        )
                        .border(
                            BorderStroke(1.dp, Color(0xFF8B6CF6).copy(alpha = 0.40f)),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Assignment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// 7. FOCUS TIME CARD (Matching Inspiration)
// -------------------------------------------------------------------------

@Composable
private fun HomeFocusTimeCard() {
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_focus_time_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "2h 15m",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.home_focus_time_goal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Circular progress indicator matching mockup: purple gradient arc with "54%" in center
            Box(
                modifier = Modifier.size(68.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(64.dp)) {
                    val stroke = 6.dp.toPx()
                    val radius = (size.minDimension - stroke) / 2
                    // Background track
                    drawCircle(
                        color = Color(0xFF8B6CF6).copy(alpha = 0.15f),
                        radius = radius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                    )
                    // Progress arc (54%)
                    drawArc(
                        brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                            listOf(
                                Color(0xFF8B6CF6),
                                Color(0xFF6738EB),
                                Color(0xFFA78BFA)
                            )
                        ),
                        startAngle = -90f,
                        sweepAngle = 360f * 0.54f,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = StrokeCap.Round
                        )
                    )
                }

                Text(
                    text = "54%",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
