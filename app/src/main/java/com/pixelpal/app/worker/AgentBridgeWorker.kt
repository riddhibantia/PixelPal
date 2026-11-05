package com.pixelpal.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pixelpal.app.data.remote.bridge.AgentBridgeSync
import com.pixelpal.app.data.remote.bridge.BridgeApprovalNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Fallback bridge poll for when the app process is dead.
 *
 * [com.pixelpal.app.data.remote.bridge.AgentBridgePoller] covers the live
 * process; this picks up the slack at WorkManager's 15-minute floor so a
 * permission request is not invisible for hours. Approvals answered from here
 * still go through the same notification actions.
 */
@HiltWorker
class AgentBridgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: AgentBridgeSync,
    private val notifier: BridgeApprovalNotifier
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val outcome = sync.sync()
        if (outcome.reachable) notifier.reconcile()
        Result.success()
    } catch (e: Exception) {
        Timber.e(e, "Bridge worker failed (attempt ${runAttemptCount + 1})")
        if (runAttemptCount < 2) Result.retry() else Result.success()
    }
}
