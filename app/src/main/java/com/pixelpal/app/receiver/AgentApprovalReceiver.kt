package com.pixelpal.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.data.remote.bridge.AgentBridgeTransport
import com.pixelpal.app.domain.repository.AgentConnectionRepository
import com.pixelpal.app.util.AgentNotificationHelper
import com.pixelpal.app.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Answers agent approval gates from the notification shade.
 *
 * Two sources share this receiver:
 *  - **bridge** ([SOURCE_BRIDGE]) — the multi-agent bridge; verdict goes back
 *    over LAN or relay via [AgentBridgeTransport].
 *  - **legacy** (no source extra) — the single-agent HTTP endpoint.
 *
 * Notification is always cancelled afterwards.
 */
@AndroidEntryPoint
class AgentApprovalReceiver : BroadcastReceiver() {

    @Inject lateinit var agentConnectionRepository: AgentConnectionRepository
    @Inject lateinit var agentNotificationHelper: AgentNotificationHelper
    @Inject lateinit var bridgeTransport: AgentBridgeTransport
    @Inject lateinit var preferences: PreferencesManager

    override fun onReceive(context: Context, intent: Intent) {
        val companionId = intent.getLongExtra(EXTRA_COMPANION_ID, -1L)
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID).orEmpty()
        if (companionId == -1L || approvalId.isBlank()) return
        val approved = intent.action == Constants.ACTION_APPROVE_AGENT
        val fromBridge = intent.getStringExtra(EXTRA_SOURCE) == SOURCE_BRIDGE

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (fromBridge) {
                    val ok = bridgeTransport.respondToApproval(
                        bridgeUrl = preferences.getAgentBridgeUrl(),
                        approvalId = approvalId,
                        approved = approved
                    )
                    if (!ok) {
                        Timber.w("Bridge approval $approvalId could not be delivered")
                    }
                } else {
                    val result =
                        agentConnectionRepository.respondToApproval(companionId, approvalId, approved)
                    if (result.isFailure) {
                        Timber.w("Approval response failed: ${result.exceptionOrNull()?.message}")
                    }
                }
                agentNotificationHelper.cancelApproval(companionId, approvalId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_COMPANION_ID = "companion_id"
        const val EXTRA_APPROVAL_ID = "approval_id"

        /**
         * Which system asked for the verdict. Absent/legacy = the old
         * single-agent endpoint; [SOURCE_BRIDGE] = the multi-agent bridge.
         */
        const val EXTRA_SOURCE = "approval_source"
        const val SOURCE_BRIDGE = "bridge"
    }
}
