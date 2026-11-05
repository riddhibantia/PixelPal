package com.pixelpal.app.presentation.screens.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pixelpal.app.R
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.SecondaryButton
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing
import java.util.Calendar

/**
 * Optional due-date control shared by New Task and Task Detail.
 *
 * Shows "No due date" with an "Add due date" affordance until a date is picked;
 * once set it renders the full date/time with an edit (tap the row) and a clear
 * button. The Material3 date dialog and the time input mirror the patterns in
 * CreateReminderScreen (UTC-midnight date selection reinterpreted in the device
 * time zone via [combineDueDateAndTime]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueDateSection(
    dueAt: Long?,
    onDueAtChange: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimeInput by remember { mutableStateOf(false) }

    val initial = remember { dueAt ?: defaultDueSlot().first }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
    val initialCalendar = remember(showTimeInput) {
        Calendar.getInstance().apply { timeInMillis = dueAt ?: defaultDueSlot().first + 18 * 60 * 60 * 1000L }
    }
    val timePickerState = rememberTimePickerState(
        initialHour = initialCalendar.get(Calendar.HOUR_OF_DAY),
        initialMinute = initialCalendar.get(Calendar.MINUTE),
        is24Hour = true
    )

    Column(modifier = modifier.fillMaxWidth()) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.large)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Event,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = Spacing.sm)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            if (dueAt == null) {
                                // First tap opens the date dialog with today preselected;
                                // the dialog's "Set time" button leads to the time input.
                                showDatePicker = true
                            } else {
                                showTimeInput = true
                            }
                        }
                        .padding(vertical = Spacing.sm)
                ) {
                    Text(
                        text = if (dueAt != null) stringResource(R.string.tasks_due_date_title) else stringResource(R.string.tasks_due_date_none),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (dueAt != null) {
                            formatDueDateTime(dueAt)
                        } else {
                            stringResource(R.string.tasks_due_date_none_hint)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (dueAt != null) FontWeight.Medium else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (dueAt != null) {
                    IconButton(onClick = { onDueAtChange(null) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.tasks_due_date_clear_content_description),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        val selected = datePickerState.selectedDateMillis
                        val base = selected ?: System.currentTimeMillis()
                        // Apply whatever time is currently in the time picker.
                        onDueAtChange(
                            combineDueDateAndTime(base, timePickerState.hour, timePickerState.minute)
                        )
                    }
                ) { Text(stringResource(R.string.tasks_action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.tasks_action_cancel)) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimeInput) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    text = stringResource(R.string.tasks_due_time_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            TimeInput(state = timePickerState)
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecondaryButton(
                    text = stringResource(R.string.tasks_due_date_action_pick),
                    onClick = { showDatePicker = true },
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = if (dueAt == null) stringResource(R.string.tasks_due_time_action_set) else stringResource(R.string.tasks_due_time_action_done),
                    onClick = {
                        val base = dueAt ?: (datePickerState.selectedDateMillis ?: System.currentTimeMillis())
                        onDueAtChange(combineDueDateAndTime(base, timePickerState.hour, timePickerState.minute))
                        showTimeInput = false
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
