package com.pixelpal.app.presentation.screens.reminders

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.pixelpal.app.domain.model.ReminderCategory
import com.pixelpal.app.domain.model.ReminderSound
import com.pixelpal.app.presentation.components.AppTextField
import com.pixelpal.app.presentation.components.AppTopBar
import com.pixelpal.app.presentation.components.GlassCard
import com.pixelpal.app.presentation.components.GlassChip
import com.pixelpal.app.presentation.components.GlassSegmentedRow
import com.pixelpal.app.presentation.components.GroupDivider
import com.pixelpal.app.presentation.components.PixelPalAtmosphere
import com.pixelpal.app.presentation.components.PrimaryButton
import com.pixelpal.app.presentation.components.SectionHeader
import com.pixelpal.app.presentation.components.SettingsGroup
import com.pixelpal.app.presentation.components.SettingsRow
import com.pixelpal.app.presentation.theme.Radius
import com.pixelpal.app.presentation.theme.Spacing
import com.pixelpal.app.util.PermissionHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private enum class TimeMode { QUICK, CLOCK, DATE_TIME }

/** Recurrence offered in the UI; ids match ReminderEntity.recurrence. */
private enum class RepeatOption(val id: String, val label: String) {
    ONCE("ONCE", "Once"),
    DAILY("DAILY", "Daily"),
    WEEKLY("WEEKLY", "Weekly"),
    MONTHLY("MONTHLY", "Monthly")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateReminderScreen(
    navController: NavController,
    viewModel: ReminderViewModel = hiltViewModel()
) {
    var title by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(ReminderCategory.CUSTOM) }
    var saveBlocked by rememberSaveable { mutableStateOf(false) }
    var repeat by rememberSaveable { mutableStateOf(RepeatOption.ONCE) }
    val context = LocalContext.current

    var selectedMode by rememberSaveable { mutableStateOf(TimeMode.QUICK) }
    var selectedQuickMinutes by rememberSaveable { mutableStateOf(60L) }

    val currentTime = Calendar.getInstance()
    val timePickerState = rememberTimePickerState(
        initialHour = currentTime.get(Calendar.HOUR_OF_DAY),
        initialMinute = currentTime.get(Calendar.MINUTE),
        is24Hour = false
    )

    var useClockPicker by rememberSaveable { mutableStateOf(true) }

    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis()
    )

    // User-picked ringtone; null = "not chosen" so the category's sound hint
    // decides (see ReminderCategory.soundHint / ReminderNotificationHelper).
    var pickedSoundUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedSoundName by rememberSaveable { mutableStateOf<String?>(null) }
    // Explicit logical override (currently only "Silent").
    var explicitSound by rememberSaveable { mutableStateOf<ReminderSound?>(null) }

    val soundSummary = when {
        explicitSound == ReminderSound.NONE -> "Silent"
        pickedSoundUri != null -> pickedSoundName ?: "Custom sound"
        else -> "${category.displayName} default (${category.soundHint.displayName})"
    }

    val dateFormatter = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }

    val ringtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    Uri::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri == null) {
                // The system picker hands back null for "silent": this used to be
                // stored as soundUri=null, which means "default sound" downstream.
                // Record it as an explicit silent choice instead.
                explicitSound = ReminderSound.NONE
                pickedSoundUri = null
                pickedSoundName = null
            } else {
                explicitSound = null
                pickedSoundUri = uri.toString()
                pickedSoundName = try {
                    RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Custom sound"
                } catch (e: Exception) {
                    "Custom sound"
                }
            }
        }
    }

    fun combineDateAndTime(dateMillis: Long, hour: Int, minute: Int): Long {
        val localMidnight = dateMillis + TimeZone.getDefault().getOffset(dateMillis)
        val date = Calendar.getInstance().apply { timeInMillis = localMidnight }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, date.get(Calendar.YEAR))
            set(Calendar.MONTH, date.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, date.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun computeTriggerTime(): Long? {
        return when (selectedMode) {
            TimeMode.QUICK -> {
                Calendar.getInstance().timeInMillis + selectedQuickMinutes * 60 * 1000L
            }
            TimeMode.DATE_TIME -> {
                val dateMillis = datePickerState.selectedDateMillis ?: System.currentTimeMillis()
                combineDateAndTime(dateMillis, timePickerState.hour, timePickerState.minute)
            }
            TimeMode.CLOCK -> {
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                    set(Calendar.MINUTE, timePickerState.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (before(Calendar.getInstance())) {
                        add(Calendar.DAY_OF_MONTH, 1)
                    }
                }
                calendar.timeInMillis
            }
        }
    }

    fun saveReminder() {
        val triggerTime = computeTriggerTime() ?: return
        // Hard guard: never persist a reminder we cannot schedule exactly. This
        // was the bug — the permission result was ignored and an inexact alarm
        // silently replaced the user's intent.
        if (!PermissionHelper.canScheduleExactAlarms(context)) {
            saveBlocked = true
            return
        }
        if (title.isNotBlank()) {
            val cal = Calendar.getInstance().apply { timeInMillis = triggerTime }
            viewModel.createReminder(
                title = title,
                message = message,
                triggerTime = triggerTime,
                hour = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE),
                soundUri = pickedSoundUri,
                category = category,
                sound = explicitSound,
                recurrence = repeat.id
            )
        }
    }

    // Exact-alarm permission flow: only persist the reminder when the user
    // actually granted it. Cancelling (or returning without the grant) used to
    // save an inexact alarm silently, which looked like the reminder was lost.
    val exactAlarmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (PermissionHelper.canScheduleExactAlarms(context)) {
            saveBlocked = false
            saveReminder()
        } else {
            saveBlocked = true
        }
    }

    fun onSavePressed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !PermissionHelper.canScheduleExactAlarms(context)
        ) {
            val intent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            )
            exactAlarmLauncher.launch(intent)
        } else {
            saveReminder()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.reminderCreated.collect {
            navController.popBackStack()
        }
    }

    PixelPalAtmosphere(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            AppTopBar(title = "New Reminder", onBack = { navController.popBackStack() })

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal)
                    .padding(bottom = Spacing.xl)
                    .navigationBarsPadding()
            ) {
            // ── WHAT ──
            SectionHeader(title = "What")
            AppTextField(
                value = title,
                onValueChange = {
                    title = it
                    if (it.isNotBlank()) saveBlocked = false
                },
                label = "Reminder",
                placeholder = "What do you want to be reminded of?"
            )

            Spacer(modifier = Modifier.height(Spacing.md))
            AppTextField(
                value = message,
                onValueChange = { message = it.take(MAX_MESSAGE_LENGTH) },
                label = "Details (optional)",
                placeholder = "Extra context Pixel can show when it fires",
                singleLine = false,
                supportingText = "${message.length}/$MAX_MESSAGE_LENGTH"
            )

            // ── CATEGORY ──
            SectionHeader(title = "Category")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                ReminderCategory.ALL.forEach { option ->
                    GlassChip(
                        label = option.displayName,
                        selected = category == option,
                        onClick = { category = option },
                        leadingIcon = option.icon
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "Sound: ${category.soundHint.displayName} by default. $CATEGORY_HINT",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── WHEN ──
            SectionHeader(title = "When")
            GlassSegmentedRow(
                options = listOf("Quick", "Clock", "Date & Time"),
                selectedIndex = when (selectedMode) {
                    TimeMode.QUICK -> 0
                    TimeMode.CLOCK -> 1
                    TimeMode.DATE_TIME -> 2
                },
                onSelected = { index ->
                    selectedMode = when (index) {
                        0 -> TimeMode.QUICK
                        1 -> TimeMode.CLOCK
                        else -> TimeMode.DATE_TIME
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            when (selectedMode) {
                TimeMode.QUICK -> {
                    Text(
                        text = "Remind me in:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    val quickOptions = listOf(
                        15L to "15 min",
                        30L to "30 min",
                        60L to "1 hour",
                        180L to "3 hours",
                        360L to "6 hours",
                        720L to "12 hours",
                        1440L to "1 day"
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        quickOptions.forEach { (minutes, label) ->
                            GlassChip(
                                label = label,
                                selected = selectedQuickMinutes == minutes,
                                onClick = { selectedQuickMinutes = minutes }
                            )
                        }
                    }
                }

                TimeMode.CLOCK -> {
                    // Toggle between analog clock and digital input
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { useClockPicker = true }) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Analog clock",
                                tint = if (useClockPicker) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { useClockPicker = false }) {
                            Icon(
                                imageVector = Icons.Default.Keyboard,
                                contentDescription = "Digital input",
                                tint = if (!useClockPicker) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    if (useClockPicker) {
                        TimePicker(state = timePickerState)
                    } else {
                        TimeInput(state = timePickerState)
                    }
                }

                TimeMode.DATE_TIME -> {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.medium),
                        onClick = { showDatePicker = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Event,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(Spacing.md))
                            Text(
                                text = datePickerState.selectedDateMillis?.let {
                                    dateFormatter.format(Date(it + TimeZone.getDefault().getOffset(it)))
                                } ?: "Select date",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (showDatePicker) {
                        DatePickerDialog(
                            onDismissRequest = { showDatePicker = false },
                            confirmButton = {
                                TextButton(onClick = { showDatePicker = false }) {
                                    Text("OK")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDatePicker = false }) {
                                    Text("Cancel")
                                }
                            }
                        ) {
                            DatePicker(state = datePickerState)
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))
                    TimeInput(state = timePickerState)
                }
            }

            // ── REPEAT ──
            SectionHeader(title = "Repeat")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                RepeatOption.entries.forEach { option ->
                    GlassChip(
                        label = option.label,
                        selected = repeat == option,
                        onClick = { repeat = option }
                    )
                }
            }
            if (repeat != RepeatOption.ONCE) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = when (repeat) {
                        RepeatOption.DAILY -> "Fires every day at this time."
                        RepeatOption.WEEKLY -> "Fires every week on this weekday."
                        else -> "Fires every month on this day. Short months use their last day."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── NOTIFICATION ──
            SectionHeader(title = "Notification")
            SettingsGroup {
                SettingsRow(
                    title = "Notification Sound",
                    description = "The sound Pixel plays when the reminder fires",
                    value = soundSummary,
                    icon = Icons.Default.MusicNote,
                    onClick = {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            explicitSound?.let { if (it != ReminderSound.NONE) pickedSoundUri?.let { uri -> putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(uri)) } }
                        }
                        ringtoneLauncher.launch(intent)
                    }
                )
            }

            // Genuinely silent is its own choice; on API 26+ it also routes the
            // notification through a silent channel (see ReminderNotificationHelper).
            Spacer(modifier = Modifier.height(Spacing.sm))
            GlassChip(
                label = "Silent — no sound at all",
                selected = explicitSound == ReminderSound.NONE,
                leadingIcon = Icons.Default.NotificationsOff,
                onClick = {
                    explicitSound = if (explicitSound == ReminderSound.NONE) null else ReminderSound.NONE
                    if (explicitSound == ReminderSound.NONE) {
                        pickedSoundUri = null
                        pickedSoundName = null
                    }
                }
            )

            if (saveBlocked) {
                Spacer(modifier = Modifier.height(Spacing.lg))
                Surface(
                    shape = RoundedCornerShape(Radius.medium),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Exact alarms aren't allowed yet, so this reminder was NOT saved. " +
                            "Allow PixelPal to schedule exact alarms (Settings → Alarms & reminders) " +
                            "and press Save again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(Spacing.md)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            PrimaryButton(
                text = "Save Reminder",
                enabled = title.isNotBlank(),
                onClick = { onSavePressed() }
            )
        }
    }
}
}

private const val MAX_MESSAGE_LENGTH = 200
private const val CATEGORY_HINT = "Meeting and Assignment use a soft chime, Medicine rings like an alarm."
