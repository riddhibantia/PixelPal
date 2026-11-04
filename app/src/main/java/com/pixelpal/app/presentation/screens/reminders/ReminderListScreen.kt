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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.pixelpal.app.presentation.components.PixelPalBottomBar
import com.pixelpal.app.presentation.components.PixelPalSnackbarHost
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.ReminderCard
import com.pixelpal.app.presentation.components.SnackbarEvent
import com.pixelpal.app.presentation.navigation.Screen
import com.pixelpal.app.presentation.theme.Spacing

private enum class ReminderFilter { UPCOMING, COMPLETED }

@OptIn(ExperimentalLayoutApi::class)
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

    androidx.compose.runtime.LaunchedEffect(Unit) {
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
            FloatingActionButton(
                onClick = { navController.navigate(Screen.CreateReminder.route) }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Reminder")
            }
        },
        bottomBar = {
            PixelPalBottomBar(navController = navController, selected = Screen.Reminders)
        },
        snackbarHost = { PixelPalSnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AppTopBar(title = "Reminders")

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)
            ) {
                SegmentedButton(
                    selected = filter == ReminderFilter.UPCOMING,
                    onClick = { filter = ReminderFilter.UPCOMING },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Upcoming")
                }
                SegmentedButton(
                    selected = filter == ReminderFilter.COMPLETED,
                    onClick = { filter = ReminderFilter.COMPLETED },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("Completed")
                }
            }

            // Category filter: "All" + one chip per category. Filtering happens
            // in the ViewModel so the empty states below stay honest.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                FilterChip(
                    selected = categoryFilter == null,
                    onClick = { viewModel.setCategoryFilter(null) },
                    label = { Text("All") }
                )
                ReminderCategory.ALL.forEach { option ->
                    FilterChip(
                        selected = categoryFilter == option,
                        onClick = {
                            viewModel.setCategoryFilter(
                                if (categoryFilter == option) null else option
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = option.icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text(option.displayName) }
                    )
                }
            }

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
                            message = "Create a reminder and Pixel will help you remember.",
                            petType = companion?.effectiveSpecies ?: "cat",
                            petState = AnimationState.WAVE,
                            content = {
                                PrimaryButton(
                                    text = "Create Reminder",
                                    onClick = { navController.navigate(Screen.CreateReminder.route) }
                                )
                            }
                        )
                    } else {
                        EmptyState(
                            title = "No completed reminders",
                            message = "Reminders you complete will show up here.",
                            petType = companion?.effectiveSpecies ?: "cat",
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
                        top = Spacing.md,
                        bottom = 96.dp
                    ),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.sm)
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