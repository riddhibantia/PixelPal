package com.pixelpal.app.presentation.screens.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pixelpal.app.animation.AnimationState
import com.pixelpal.app.domain.model.ReminderCategory
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.EmptyState
import com.pixelpal.app.presentation.components.GlassChip
import com.pixelpal.app.presentation.components.GlassSegmentedRow
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import com.pixelpal.app.presentation.components.PixelPalBottomBar
import com.pixelpal.app.presentation.components.PixelPalSnackbarHost
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.ReminderCard
import com.pixelpal.app.presentation.components.SnackbarEvent
import com.pixelpal.app.presentation.navigation.Screen
import com.pixelpal.app.presentation.theme.Spacing

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.pixelpal.app.presentation.components.GlassCard

private enum class ReminderFilter { UPCOMING, COMPLETED }

@Composable
fun ReminderListScreen(
    navController: NavController,
    viewModel: ReminderViewModel = hiltViewModel()
) {
    val pendingReminders by viewModel.pendingReminders.collectAsState()
    val completedReminders by viewModel.completedReminders.collectAsState()
    val companion by viewModel.companion.collectAsState()
    val categoryFilter by viewModel.categoryFilter.collectAsState()
    var filter by remember { mutableStateOf(ReminderFilter.UPCOMING) }
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Collect snackbar events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { event ->
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (event) {
                    is SnackbarEvent.ReminderDeleted -> viewModel.undoDeleteReminder(event.reminder)
                    else -> {}
                }
            }
        }
    }

    val reminders = when (filter) {
        ReminderFilter.UPCOMING -> pendingReminders
        ReminderFilter.COMPLETED -> completedReminders
    }

    Scaffold(
        floatingActionButton = {
            if (reminders.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { navController.navigate(Screen.CreateReminder.route) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Reminder")
                }
            }
        },
        bottomBar = {
            PixelPalBottomBar(navController = navController, selected = Screen.Reminders)
        },
        snackbarHost = { PixelPalSnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        PixelPalAtmosphere(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Professional Header with Title, Count Subtitle & Frosted Glass Action Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.screenHorizontal)
                        .padding(top = Spacing.xs, bottom = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Reminders",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val subtitle = if (pendingReminders.isEmpty()) {
                            "All caught up"
                        } else {
                            "${pendingReminders.size} scheduled · ${completedReminders.size} completed"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Top Quick Action: Create Reminder
                    GlassCard(
                        shape = CircleShape,
                        modifier = Modifier.size(42.dp),
                        onClick = { navController.navigate(Screen.CreateReminder.route) }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Reminder",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Segmented Control with Live Tab Badges
                GlassSegmentedRow(
                    options = listOf(
                        "Upcoming (${pendingReminders.size})",
                        "Completed (${completedReminders.size})"
                    ),
                    selectedIndex = if (filter == ReminderFilter.UPCOMING) 0 else 1,
                    onSelected = { index ->
                        filter = if (index == 0) ReminderFilter.UPCOMING else ReminderFilter.COMPLETED
                    },
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)
                )

                Spacer(modifier = Modifier.height(Spacing.xs))

                // Smooth Horizontal Category Filter Row (Replaces Cluttered FlowRow)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    item {
                        GlassChip(
                            label = "All",
                            selected = categoryFilter == null,
                            onClick = { viewModel.setCategoryFilter(null) }
                        )
                    }
                    items(ReminderCategory.ALL) { option ->
                        GlassChip(
                            label = option.displayName,
                            selected = categoryFilter == option,
                            onClick = {
                                viewModel.setCategoryFilter(
                                    if (categoryFilter == option) null else option
                                )
                            },
                            leadingIcon = option.icon
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                if (reminders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 96.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (filter == ReminderFilter.UPCOMING) {
                            EmptyState(
                                title = "Nothing planned yet",
                                message = "Create a reminder and Pixel will keep you on schedule.",
                                petType = companion?.effectiveSpecies ?: "rabbit",
                                petState = AnimationState.WAVE,
                                content = {
                                    Button(
                                        onClick = { navController.navigate(Screen.CreateReminder.route) },
                                        shape = RoundedCornerShape(24.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        ),
                                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Create Reminder",
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            )
                        } else {
                            EmptyState(
                                title = "No completed reminders",
                                message = "Reminders you complete will show up here.",
                                petType = companion?.effectiveSpecies ?: "rabbit",
                                petState = AnimationState.SLEEP
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = Spacing.screenHorizontal,
                            end = Spacing.screenHorizontal,
                            top = Spacing.xs,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        items(reminders, key = { it.id }) { reminder ->
                            ReminderCard(
                                reminder = reminder,
                                onComplete = { viewModel.completeReminder(reminder.id) },
                                onDelete = { viewModel.deleteReminder(reminder) }
                            )
                        }
                    }
                }
            }
        }
    }
}