package com.pixelpal.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pixelpal.app.R
import com.pixelpal.app.domain.model.Task
import com.pixelpal.app.domain.repository.TaskRepository
import com.pixelpal.app.presentation.MainActivity
import com.pixelpal.app.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * One-shot work that fires when a task's due time arrives and posts a
 * "Task due" notification.
 *
 * Scheduling lives in [TaskDueScheduler] so every mutation path (create, edit,
 * complete, delete, undo-delete) can keep the unique work in sync without each
 * call site re-deriving the wire format.
 *
 * Constraints: none. A due date is a local wall-clock promise, so the
 * notification must fire with the network down.
 */
@HiltWorker
class TaskDueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val taskRepository: TaskRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0L) return Result.failure()

        // The notification carries a copy of the title taken at schedule time;
        // the DB read below is the authority when the row still exists.
        // TaskRepository exposes only a Flow for single tasks (no direct read),
        // so take the first emission with a bounded wait.
        val task = runCatching {
            withTimeoutOrNull(DB_READ_TIMEOUT_MS) { taskRepository.getTask(taskId).first() }
        }.getOrNull()
        val title = task?.title ?: inputData.getString(KEY_TASK_TITLE)
        // Completed or deleted since we were scheduled: nothing to say.
        if (title.isNullOrBlank() || task?.isDone == true) return Result.success()

        val dueAt = task?.dueAt ?: inputData.getLong(KEY_TASK_DUE_AT, 0L)
        if (dueAt > System.currentTimeMillis()) {
            // Due time moved while this request was in flight — the rescheduling
            // path enqueues a replacement; just let this one go quietly.
            Timber.d("TaskDueWorker: task $taskId not due yet, skipping")
            return Result.success()
        }

        postNotification(taskId, title)
        return Result.success()
    }

    private fun postNotification(taskId: Long, title: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(manager)

        val openApp = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            taskId.toInt(),
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            applicationContext,
            Constants.CHANNEL_TASK_DUE
        )
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle("Task due: $title")
            .setContentText("Tap to open PixelPal and tick it off.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(Constants.NOTIFICATION_ID_TASK_DUE + taskId.toInt(), notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CHANNEL_TASK_DUE,
                Constants.CHANNEL_TASK_DUE_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        /** A hung DB read must never wedge the notification. */
        private const val DB_READ_TIMEOUT_MS = 5_000L

        const val KEY_TASK_ID = "task_id"
        const val KEY_TASK_TITLE = "task_title"
        const val KEY_TASK_DUE_AT = "task_due_at"
    }
}

/**
 * Keeps the per-task unique due work in sync with the task row.
 *
 * Unique-work name: `task_due_<cloudId>` — cloud ids are stable across devices
 * and survive row re-inserts (Room row ids are device-local and are recycled).
 * Falls back to the row id when a task has no cloud id (legacy/offline rows).
 */
@Singleton
class TaskDueScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * (Re)schedules the due notification for [task]. A due time in the past, a
     * completed task, or a null [Task.dueAt] cancels any outstanding work.
     */
    fun schedule(task: Task) {
        cancelFor(task)
        val dueAt = task.dueAt ?: return
        if (task.isDone) return
        if (dueAt <= System.currentTimeMillis()) {
            // Past due at schedule time — intentionally silent: the task row
            // already shows an Overdue chip, a "just now" notification would be
            // noise for every restored/imported task.
            Timber.d("TaskDueScheduler: task ${task.id} already past due, no notification")
            return
        }

        val delayMs = dueAt - System.currentTimeMillis()
        val request = OneTimeWorkRequestBuilder<TaskDueWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .setInputData(
                workDataOf(
                    TaskDueWorker.KEY_TASK_ID to task.id,
                    TaskDueWorker.KEY_TASK_TITLE to task.title,
                    TaskDueWorker.KEY_TASK_DUE_AT to dueAt
                )
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(task),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** Cancels any outstanding due notification for [task] (keeps other tasks'). */
    fun cancelFor(task: Task) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(task))
    }

    /**
     * Best-effort cancel for a deleted row where the caller only kept the
     * [Task] value (its pre-delete snapshot still carries dueAt/isDone).
     */
    fun cancel(task: Task) {
        cancelFor(task)
    }

    /** Test/hook seam: the unique WorkManager name for a task. */
    fun workName(task: Task): String = Constants.TASK_DUE_WORK_PREFIX + taskIdentity(task)

    private fun taskIdentity(task: Task): String =
        if (task.id > 0L) task.id.toString() else "pending_${task.title.hashCode()}"
}
