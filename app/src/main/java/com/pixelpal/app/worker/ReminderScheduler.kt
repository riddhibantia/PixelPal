package com.pixelpal.app.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pixelpal.app.domain.engine.RecurrenceMath
import com.pixelpal.app.domain.model.Reminder
import com.pixelpal.app.domain.repository.ReminderRepository
import com.pixelpal.app.receiver.AlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderRepository: ReminderRepository
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleReminder(reminder: Reminder) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("reminder_id", reminder.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = reminder.triggerTime

        // Exact alarms require SCHEDULE_EXACT_ALARM on Android 12+ (unless exempt).
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                // Fallback: inexact alarm still fires, just not to the second.
                Timber.w("Exact alarm permission missing for reminder ${reminder.id}, using inexact alarm")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException scheduling reminder ${reminder.id}, falling back to inexact alarm")
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } catch (e2: Exception) {
                Timber.e(e2, "Failed to schedule fallback alarm for reminder ${reminder.id}")
            }
        }
    }

    fun cancelReminder(reminderId: Long) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    /**
     * Re-arms every pending/triggered reminder after a reboot (alarms do not
     * survive a restart).
     *
     * @return the past-due **one-shot** reminders that were deliberately left
     *   untouched, in the order they were found. They are NOT rescheduled (an
     *   alarm for a past instant fires immediately, which is spam) and NOT
     *   marked here — [BootCatchUpWorker] owns them: it marks them TRIGGERED,
     *   records the MISSED_REMINDER activity (no bond — the user didn't act)
     *   and posts one aggregated "you missed N reminders" notification.
     *   Recurring past-due reminders are rolled forward to their next
     *   occurrence and reported as not missed.
     */
    suspend fun rescheduleAll(): List<Reminder> {
        val pendingReminders = reminderRepository.getPendingReminders().first()
        val now = System.currentTimeMillis()
        val missed = mutableListOf<Reminder>()
        for (reminder in pendingReminders) {
            if (reminder.triggerTime > now) {
                scheduleReminder(reminder)
            } else {
                // Past due: advance recurring reminders to their next occurrence so a
                // missed period doesn't pause the schedule forever.
                val next = nextTriggerTime(reminder, now)
                if (next != null) {
                    val updated = reminder.copy(triggerTime = next, snoozeCount = 0)
                    reminderRepository.update(updated)
                    scheduleReminder(updated)
                } else {
                    // One-shot already in the past: nothing to arm. Hand it to
                    // the boot catch-up so it cannot stay PENDING forever.
                    missed += reminder
                    Timber.w("Past-due one-shot reminder ${reminder.id} (\"${reminder.title}\") left for boot catch-up")
                }
            }
        }
        Timber.d("rescheduleAll: ${pendingReminders.size} pending, ${missed.size} missed one-shot(s)")
        return missed
    }

    /**
     * Next trigger time for a recurring reminder after [from], or null for a
     * one-shot. All the clock/DST/month-end rules live in [RecurrenceMath].
     */
    fun nextTriggerTime(reminder: Reminder, from: Long = System.currentTimeMillis()): Long? =
        RecurrenceMath.nextTriggerTime(
            triggerTime = reminder.triggerTime,
            hour = reminder.hour,
            minute = reminder.minute,
            recurrence = reminder.recurrence,
            intervalMs = reminder.recurrenceInterval,
            dayOfMonth = reminder.recurrenceDayOfMonth,
            from = from
        )
}
