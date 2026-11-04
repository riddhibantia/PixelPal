package com.pixelpal.app.presentation.screens.tasks

import com.pixelpal.app.domain.model.Task
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One due-date line for a task row: the short label ("Due today", "Overdue",
 * "Due 12 Nov") plus enough context for the row to style itself.
 */
data class TaskDueInfo(
    val label: String,
    val isOverdue: Boolean,
    val isDueToday: Boolean
)

/**
 * Relative due label shown on the Tasks list. A done task never reads as
 * overdue — once ticked, the due date is historical.
 */
fun taskDueInfo(
    task: Task,
    now: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault()
): TaskDueInfo? {
    val dueAt = task.dueAt ?: return null

    val today = startOfDay(now)
    val dueDay = startOfDay(dueAt)
    val dayDiff = ((dueDay - today) / DAY_MS).toInt()

    if (task.isDone) {
        return TaskDueInfo(
            label = "Due ${shortDate(dueAt, locale)}",
            isOverdue = false,
            isDueToday = dayDiff == 0
        )
    }

    val overdue = dueAt < now
    val label = when {
        overdue && dayDiff == 0 -> "Overdue — due today ${clock(dueAt, locale)}"
        overdue && dayDiff == -1 -> "Overdue — was due yesterday"
        overdue -> "Overdue — was due ${shortDate(dueAt, locale)}"
        dayDiff == 0 -> "Due today ${clock(dueAt, locale)}"
        dayDiff == 1 -> "Due tomorrow ${clock(dueAt, locale)}"
        else -> "Due ${shortDate(dueAt, locale)}"
    }
    return TaskDueInfo(label = label, isOverdue = overdue, isDueToday = dayDiff == 0)
}

/** Full "Wed, 12 Nov 2026 at 14:30" form used by the detail/new-task pickers. */
fun formatDueDateTime(
    dueAt: Long,
    locale: Locale = Locale.getDefault()
): String = SimpleDateFormat("EEE, d MMM yyyy 'at' HH:mm", locale).format(Date(dueAt))

/**
 * Combines a Material3 [androidx.compose.material3.DatePicker] UTC-midnight
 * selection with a picker hour/minute into a local epoch millis value.
 *
 * Mirrors CreateReminderScreen's date+time math: DatePicker hands back UTC
 * midnight of the chosen calendar day, so re-interpret that day in the device
 * time zone before applying the time of day.
 */
fun combineDueDateAndTime(dateUtcMillis: Long, hour: Int, minute: Int): Long {
    val offset = java.util.TimeZone.getDefault().getOffset(dateUtcMillis)
    val pick = Calendar.getInstance().apply { timeInMillis = dateUtcMillis + offset }
    return Calendar.getInstance().apply {
        set(Calendar.YEAR, pick.get(Calendar.YEAR))
        set(Calendar.MONTH, pick.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, pick.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** Default due time for a newly-shown picker: today 18:00, rolled to tomorrow if already past. */
fun defaultDueSlot(now: Long = System.currentTimeMillis()): Pair<Long, Int> {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 18)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
    }
    return startOfDay(calendar.timeInMillis) to 18
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun shortDate(millis: Long, locale: Locale): String =
    SimpleDateFormat("d MMM", locale).format(Date(millis))

private fun clock(millis: Long, locale: Locale): String =
    SimpleDateFormat("HH:mm", locale).format(Date(millis))
