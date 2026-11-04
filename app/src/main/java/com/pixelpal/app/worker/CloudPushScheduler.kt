package com.pixelpal.app.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Single entry point for scheduling retryable cloud pushes.
 *
 * Pushes are enqueued as UNIQUE work keyed by (type, cloudId) with
 * KEEP policy, so repeated edits to the same row collapse into one pending
 * push instead of racing concurrent duplicate writes. Combined with the
 * repositories' instant fire-and-forget push, the online path stays snappy
 * while the offline path is never silently lost.
 */
object CloudPushScheduler {

    fun enqueue(
        context: Context,
        type: String,
        cloudId: String,
        parentCloudId: String? = null
    ) {
        if (cloudId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<FirestorePushWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10_000L,
                TimeUnit.MILLISECONDS
            )
            .setInputData(
                workDataOf(
                    FirestorePushWorker.KEY_TYPE to type,
                    FirestorePushWorker.KEY_CLOUD_ID to cloudId,
                    FirestorePushWorker.KEY_PARENT_CLOUD_ID to (parentCloudId ?: "")
                )
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(type, cloudId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Stable per-entity unique name so duplicate edits collapse into one push. */
    fun uniqueName(type: String, cloudId: String): String = "push_${type}_$cloudId"
}
