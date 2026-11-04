package com.pixelpal.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pixelpal.app.data.remote.firebase.FirestoreSyncCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Retrying push for a single entity. Replaces the old fire-and-forget
 * `scope.launch { syncX() }` so offline writes are not silently lost.
 * WorkManager retries with exponential backoff until NetworkType.CONNECTED.
 *
 * Scheduling goes through [CloudPushScheduler] (unique work + KEEP), so
 * repeated edits to the same row collapse into a single pending push.
 *
 * Deletes are SOFT deletes: `delete_*` types write a tombstone
 * (`deleted = true`) instead of removing the document, so a peer that never saw
 * the delete cannot resurrect the row by pushing its stale local copy.
 */
@HiltWorker
class FirestorePushWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncCoordinator: FirestoreSyncCoordinator
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE) ?: return Result.failure()
        val cloudId = inputData.getString(KEY_CLOUD_ID) ?: return Result.failure()
        if (cloudId.isBlank()) return Result.success()
        // Signed out: nothing to push and the row will be re-pushed by the next
        // fullSync after login, so retrying is meaningless but not harmful.
        if (!syncCoordinator.isUserLoggedIn) {
            Timber.d("FirestorePushWorker %s skipped: user not logged in", type)
            return Result.success()
        }
        // The parent cloud id carried in the input is deliberately ignored: the
        // coordinator re-derives it from the current task row, which may have
        // been re-linked (or re-pushed) after the work item was enqueued.
        return when (syncCoordinator.pushEntity(type, cloudId)) {
            FirestoreSyncCoordinator.PushOutcome.DONE -> Result.success()
            FirestoreSyncCoordinator.PushOutcome.REMOVED_REMOTELY -> Result.success()
            FirestoreSyncCoordinator.PushOutcome.RETRY -> Result.retry()
        }
    }

    companion object {
        const val KEY_TYPE = "push_type"
        const val KEY_CLOUD_ID = "cloud_id"
        const val KEY_PARENT_CLOUD_ID = "parent_cloud_id"

        const val TYPE_TASK = "task"
        const val TYPE_REMINDER = "reminder"
        const val TYPE_SUBTASK = "subtask"
        const val TYPE_DELETE_TASK = "delete_task"
        const val TYPE_DELETE_REMINDER = "delete_reminder"
        const val TYPE_DELETE_SUBTASK = "delete_subtask"
    }
}
