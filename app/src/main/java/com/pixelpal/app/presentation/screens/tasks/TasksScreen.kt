package com.pixelpal.app.presentation.screens.tasks

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pixelpal.app.animation.AnimationState
import com.pixelpal.app.domain.model.Task
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.EmptyState
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.LoadingState
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import com.pixelpal.app.presentation.components.PixelPalSnackbarHost
import com.pixelpal.app.presentation.components.SnackbarEvent
import com.pixelpal.app.presentation.navigation.Screen
import com.pixelpal.app.presentation.theme.PixelPalGradients
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing

/**
 * The Tasks list. Creation happens on the dedicated New Task screen (via the
 * "+" FAB); each row opens the Task Detail screen. The list itself stays clean.
 */
@Composable
fun TasksScreen(
    navController: NavController,
    viewModel: TasksViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val companion by viewModel.companion.collectAsState()
    val widgetEnabled by viewModel.tasksWidgetEnabled.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Swipe- or detail-delete emits TaskDeleted; Undo reinserts the snapshot
    // (task + subtasks). Mirrors ReminderViewModel's delete/undo pattern.
    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { event ->
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (event) {
                    is SnackbarEvent.TaskDeleted -> viewModel.undoDeleteTask()
                    else -> {}
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { PixelPalSnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            com.pixelpal.app.presentation.components.PixelPalBottomBar(
                navController = navController,
                selected = Screen.Tasks
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate(Screen.NewTask.route) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Task")
            }
        }
    ) { padding ->
        PixelPalAtmosphere(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val pendingCount = uiState.items.count { !it.task.isDone }
                val completedCount = uiState.items.count { it.task.isDone }
                AppTopBar(
                    title = "Tasks",
                    subtitle = when {
                        uiState.items.isEmpty() -> null
                        pendingCount == 0 -> "All caught up! Great job 🎉"
                        else -> "$pendingCount pending · $completedCount completed"
                    }
                )

                when {
                uiState.loading -> LoadingState()
                uiState.items.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        title = "No tasks yet",
                        message = "Pixel is napping until your first task.",
                        petType = companion?.effectiveSpecies ?: "cat",
                        petState = AnimationState.SLEEP
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.screenHorizontal,
                        end = Spacing.screenHorizontal,
                        top = Spacing.sm,
                        bottom = 100.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(uiState.items, key = { it.task.id }) { item ->
                        SwipeableTaskRow(
                            item = item,
                            onToggle = { viewModel.toggleTask(item.task) },
                            onDelete = { viewModel.deleteTask(item.task) },
                            onOpen = { navController.navigate(Screen.TaskDetail.route(item.task.id)) }
                        )
                    }
                    item {
                        WidgetOptInRow(
                            enabled = widgetEnabled,
                            onToggle = { enabled ->
                                viewModel.setTasksWidgetEnabled(enabled)
                                if (enabled) {
                                    try {
                                        val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context)
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                                            appWidgetManager.isRequestPinAppWidgetSupported
                                        ) {
                                            val provider = android.content.ComponentName(
                                                context,
                                                com.pixelpal.app.widget.TasksWidgetProvider::class.java
                                            )
                                            appWidgetManager.requestPinAppWidget(provider, null, null)
                                        }
                                    } catch (_: Exception) { }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
private fun WidgetOptInRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.large)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Home Screen Widget",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (enabled) {
                        "Enabled — add it from your launcher's widget picker"
                    } else {
                        "Show your tasks on your home screen"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SwipeableTaskRow(
    item: TaskWithSubtasks,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.background
                },
                label = "task-swipe-bg"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color, RoundedCornerShape(Radius.large))
                    .padding(horizontal = Spacing.md),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete task",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        TaskRow(item = item, onToggle = onToggle, onOpen = onOpen)
    }
}

@Composable
private fun TaskRow(
    item: TaskWithSubtasks,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    val task = item.task
    val subtasks = item.subtasks
    val doneCount = subtasks.count { it.isDone }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.large),
        onClick = onOpen
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(
                        if (task.isDone) {
                            Modifier.background(PixelPalGradients.primaryCard)
                        } else {
                            Modifier
                                .background(MaterialTheme.colorScheme.surface)
                                .border(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                    CircleShape
                                )
                        }
                    )
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                if (task.isDone) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Completed",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (task.isDone) FontWeight.Normal else FontWeight.SemiBold,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                    color = if (task.isDone) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2
                )
                if (subtasks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$doneCount/${subtasks.size} subtasks",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TaskDueLabel(task = task)
            }

            if (subtasks.isNotEmpty()) {
                LinearProgressIndicator(
                    progress = { doneCount / subtasks.size.toFloat() },
                    modifier = Modifier
                        .width(44.dp)
                        .padding(horizontal = Spacing.xs)
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open task",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Relative due line on a task row ("Due today 18:00", "Overdue — was due
 * yesterday", "Due 12 Nov") in the error color while overdue.
 */
@Composable
private fun TaskDueLabel(task: Task) {
    // Re-key on the local day so "Due tomorrow"/"Overdue" roll over at midnight
    // for a screen left open in the foreground.
    val todayKey = remember { java.util.Calendar.getInstance() }
        .let { it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR) }
    val info = remember(task.dueAt, task.isDone, task.id, todayKey) { taskDueInfo(task) } ?: return
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = info.label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (info.isOverdue) FontWeight.SemiBold else FontWeight.Normal,
        color = if (info.isOverdue) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1
    )
}
