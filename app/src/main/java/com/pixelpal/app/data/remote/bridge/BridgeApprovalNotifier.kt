package com.pixelpal.app.data.remote.bridge

import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.data.repository.AgentSessionRepository
import com.pixelpal.app.util.AgentNotificationHelper
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns bridge blockages into Approve/Deny notifications.
 *
 * The Agents screen shows approvals inline, but that only works while it is on
 * screen. This reconciler diffs the current set of blocked sessions against the
 * set we already notified, posts one notification per **approval id** (never
 * twice), and cancels the notifications for approvals that resolved themselves
 * (the agent timed out, or the user answered on the laptop).
 */
@Singleton
class BridgeApprovalNotifier @Inject constructor(
    private val repository: AgentSessionRepository,
    private val helper: AgentNotificationHelper,
    private val preferences: PreferencesManager
) {

    /**
     * Reconcile notifications with the bridge's current state.
     *
     * @return how many approvals are still pending, so the poller can switch to
     *   its fast cadence.
     */
    suspend fun reconcile(): Int {
        val waiting = repository.waiting.first()
            .filter { !it.pendingApprovalId.isNullOrBlank() }

        val current = waiting.associateBy { key(it.companionId, it.pendingApprovalId!!) }
        val notified = preferences.getNotifiedBridgeApprovals()

        (notified - current.keys).forEach { stale ->
            val parsed = parseKey(stale) ?: return@forEach
            helper.cancelApproval(parsed.first, parsed.second)
        }

        current.forEach { (entry, session) ->
            if (entry in notified) return@forEach
            val parsed = parseKey(entry) ?: return@forEach
            helper.notifyBridgeApproval(
                companionId = parsed.first,
                agentName = session.agentName.ifBlank { session.source },
                approvalId = parsed.second,
                action = session.pendingApprovalAction?.takeIf { it.isNotBlank() }
                    ?: "is waiting for your answer",
                detail = session.message.takeIf { it.isNotBlank() }
                    ?: session.currentTask?.takeIf { it.isNotBlank() }
            )
        }

        if (current.keys != notified) {
            preferences.setNotifiedBridgeApprovals(current.keys)
        }
        Timber.d("Bridge approvals: ${current.size} pending, ${notified.size} notified")
        return current.size
    }

    /** Forget every notified approval (called on unpair / sign-out). */
    suspend fun reset() {
        preferences.getNotifiedBridgeApprovals().forEach { stale ->
            val parsed = parseKey(stale) ?: return@forEach
            helper.cancelApproval(parsed.first, parsed.second)
        }
        preferences.setNotifiedBridgeApprovals(emptySet())
    }

    private fun key(companionId: Long, approvalId: String) = "$companionId|$approvalId"

    private fun parseKey(raw: String): Pair<Long, String>? {
        val split = raw.indexOf('|')
        if (split <= 0 || split == raw.lastIndex) return null
        val companionId = raw.substring(0, split).toLongOrNull() ?: return null
        return companionId to raw.substring(split + 1)
    }
}
