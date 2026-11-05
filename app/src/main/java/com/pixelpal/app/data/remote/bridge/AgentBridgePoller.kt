package com.pixelpal.app.data.remote.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps bridge state fresh while the app process is alive.
 *
 * Before this existed, agent status only moved when the Agents screen resumed,
 * so a permission request that arrived while you were anywhere else was never
 * shown. The poller runs for the life of the process, polls faster while an
 * approval is waiting, and backs off when the laptop is asleep.
 *
 * Process death is covered by [com.pixelpal.app.worker.AgentBridgeWorker].
 */
@Singleton
class AgentBridgePoller @Inject constructor(
    private val sync: AgentBridgeSync,
    private val notifier: BridgeApprovalNotifier
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Idempotent: safe from Application.onCreate, after pairing, on resume. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val waiting = tick()
                delay(if (waiting > 0) APPROVAL_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One poll cycle. Returns how many approvals are still waiting. */
    suspend fun tick(): Int = try {
        val outcome = sync.sync()
        if (outcome.reachable) notifier.reconcile() else 0
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Bridge poll failed")
        0
    }

    private companion object {
        /** Tight loop only while the user is actually needed. */
        const val APPROVAL_POLL_MS = 4_000L
        /** LAN round-trip is ~1 ms; this is about battery, not latency. */
        const val IDLE_POLL_MS = 20_000L
    }
}
