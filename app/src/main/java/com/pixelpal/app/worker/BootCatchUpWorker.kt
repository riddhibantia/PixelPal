package com.pixelpal.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pixelpal.app.R
import com.pixelpal.app.domain.engine.ActiveCompanionManager
import com.pixelpal.app.domain.model.ActivityType
import com.pixelpal.app.domain.model.Reminder
import com.pixelpal.app.domain.repository.ActivityEventRepository
import com.pixelpal.app.domain.repository.ReminderRepository
import com.pixelpal.app.presentation.MainActivity
import com.pixelpal.app.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Boot catch-up for reminders that came due while the device was off/rebooting.
 *
 * [ReminderScheduler.rescheduleAll] arms future alarms and rolls recurring
 * reminders forward; a past-due **one-shot** reminder cannot be armed (an alarm
 * for a past instant fires immediately), so the scheduler returns it and
 * [com.pixelpal.app.receiver.BootReceiver] enqueues this worker with its id.
 * The worker:
 *  1. marks each still-`PENDING` reminder `TRIGGERED` (`completedAt = null` —
 *     it was missed, not completed) so it can never sit PENDING forever,
 *  2. records one MISSED_REMINDER activity event,
 *  3. posts a single aggregated "You missed N reminders" notification.
 *
 * No bond is awarded: bond only accrues on actions the user actually took
 * (MEMORY.md §7 design intent). Ids only ever come from
 * [ReminderScheduler.nextTriggerTime] == null (i.e. genuinely one-shot), so the
 * recurrence rule lives in exactly one place.
 */
@HiltWorker
class BootCatchUpWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reminderRepository: ReminderRepository,
    private val activityEventRepository: ActivityEventRepository,
    private val activeCompanionManager: ActiveCompanionManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ids = inputData.getLongArray(KEY_IDS)?.toList().orEmpty()
        Timber.d("BootCatchUpWorker: ${ids.size} missed reminder candidate(s)")

        // Resolve BEFORE mutating so the status update can't hide the rows.
        val missed = ids.mapNotNull { reminderRepository.getById(it) }
            .filter { it.status == STATUS_PENDING }
        if (missed.isEmpty()) {
            Timber.d("BootCatchUpWorker: nothing to catch up (already handled)")
            return Result.success()
        }

        try {
            missed.forEach { reminder ->
                reminderRepository.update(reminder.copy(status = STATUS_TRIGGERED))
            }
            recordActivity(missed)
        } catch (e: Exception) {
            Timber.e(e, "BootCatchUpWorker: failed to mark ${missed.size} reminder(s) TRIGGERED")
            return Result.retry()
        }

        notifyMissed(missed)
        Timber.d("BootCatchUpWorker: caught up ${missed.size} missed reminder(s)")
        return Result.success()
    }

    private suspend fun recordActivity(missed: List<Reminder>) {
        val companionId = missed.firstNotNullOfOrNull { it.companionId }
            ?: activeCompanionManager.getActiveCompanionIdDirect()
            ?: return
        val title = if (missed.size == 1) {
            "Missed reminder \"${missed.first().title}\""
        } else {
            "Missed ${missed.size} reminders"
        }
        activityEventRepository.record(
            companionId = companionId,
            type = ActivityType.MISSED_REMINDER,
            title = title,
            description = missed.take(MAX_LISTED).joinToString(", ") { it.title }
        )
    }

    private fun notifyMissed(missed: List<Reminder>) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(manager)

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = when {
            missed.size == 1 && !missed.first().message.isNullOrBlank() -> missed.first().message
            missed.size == 1 -> "It was due while your phone was off."
            else -> missed.take(MAX_LISTED).joinToString(", ") { it.title } +
                if (missed.size > MAX_LISTED) "…" else ""
        }

        val notification = NotificationCompat.Builder(applicationContext, Constants.CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle(if (missed.size == 1) "You missed a reminder" else "You missed ${missed.size} reminders")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(Constants.NOTIFICATION_ID_MISSED_REMINDERS, notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Same channel as regular reminders (created idempotently — the
            // first creation wins), so the user's channel prefs are shared.
            val channel = NotificationChannel(
                Constants.CHANNEL_REMINDER,
                Constants.CHANNEL_REMINDER_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val KEY_IDS = "missed_reminder_ids"
        private const val WORK_NAME = "pixelpal_boot_catch_up"
        private const val REQUEST_CODE = 2003
        private const val MAX_LISTED = 3
        private const val STATUS_PENDING = "PENDING"
        private const val STATUS_TRIGGERED = "TRIGGERED"

        /**
         * Schedules the one-shot catch-up. Explicit ids are carried in the
         * work input data because WorkManager survives process death, while a
         * boot receiver only has a short goAsync() window. KEEP: a stale
         * duplicate enqueue is dropped.
         */
        fun enqueue(context: Context, missedReminderIds: List<Long>) {
            if (missedReminderIds.isEmpty()) return
            val request = OneTimeWorkRequestBuilder<BootCatchUpWorker>()
                .setInputData(Data.Builder().putLongArray(KEY_IDS, missedReminderIds.toLongArray()).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30_000L, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            Timber.d("BootCatchUpWorker enqueued for ${missedReminderIds.size} reminder id(s)")
        }
    }
}
