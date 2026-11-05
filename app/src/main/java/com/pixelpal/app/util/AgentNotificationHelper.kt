package com.pixelpal.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pixelpal.app.R
import com.pixelpal.app.domain.model.AgentCheckResult
import com.pixelpal.app.domain.model.PendingApproval
import com.pixelpal.app.presentation.MainActivity
import com.pixelpal.app.receiver.AgentApprovalReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun notify(companionId: Long, companionName: String, result: AgentCheckResult) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return

        ensureChannel(manager)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            companionId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = result.message?.let { " — $it" }.orEmpty()
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle("$companionName needs attention")
            .setContentText("${result.state.displayName}$message")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(Constants.NOTIFICATION_ID_AGENT + companionId.toInt(), notification)
    }

    /**
     * Approval gate: high-priority notification with Approve/Deny actions.
     * Each approval id gets its own notification slot so concurrent asks
     * never overwrite each other.
     */
    fun notifyApproval(companionId: Long, companionName: String, approval: PendingApproval) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return

        ensureChannel(manager)

        val approveIntent = Intent(context, AgentApprovalReceiver::class.java).apply {
            action = Constants.ACTION_APPROVE_AGENT
            putExtra(AgentApprovalReceiver.EXTRA_COMPANION_ID, companionId)
            putExtra(AgentApprovalReceiver.EXTRA_APPROVAL_ID, approval.id)
        }
        val denyIntent = Intent(context, AgentApprovalReceiver::class.java).apply {
            action = Constants.ACTION_DENY_AGENT
            putExtra(AgentApprovalReceiver.EXTRA_COMPANION_ID, companionId)
            putExtra(AgentApprovalReceiver.EXTRA_APPROVAL_ID, approval.id)
        }
        // Unique request codes per (action, approval) so the two buttons
        // never collapse into one PendingIntent.
        val approve = PendingIntent.getBroadcast(
            context,
            approval.id.hashCode() xor 0xA0,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val deny = PendingIntent.getBroadcast(
            context,
            approval.id.hashCode() xor 0xD0,
            denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            context,
            companionId.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = buildString {
            append(approval.action)
            approval.detail?.takeIf { it.isNotBlank() }?.let { append(" — ${it.take(120)}") }
        }
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle("$companionName asks for approval")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .addAction(0, "Approve", approve)
            .addAction(0, "Deny", deny)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(approvalNotificationId(companionId, approval.id), notification)
    }

    fun cancelApproval(companionId: Long, approvalId: String) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(approvalNotificationId(companionId, approvalId))
    }

    /**
     * Approval ask coming from the multi-agent bridge (Claude Code, Codex, …).
     *
     * Same Approve/Deny actions as [notifyApproval], but the verdict is routed
     * back through [com.pixelpal.app.receiver.AgentApprovalReceiver]'s bridge
     * branch, which talks to `AgentBridgeTransport` instead of the legacy
     * single-agent endpoint.
     */
    fun notifyBridgeApproval(
        companionId: Long,
        agentName: String,
        approvalId: String,
        action: String,
        detail: String?
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return

        ensureChannel(manager)

        fun verdictIntent(approve: Boolean) = Intent(context, AgentApprovalReceiver::class.java).apply {
            this.action = if (approve) Constants.ACTION_APPROVE_AGENT else Constants.ACTION_DENY_AGENT
            putExtra(AgentApprovalReceiver.EXTRA_COMPANION_ID, companionId)
            putExtra(AgentApprovalReceiver.EXTRA_APPROVAL_ID, approvalId)
            putExtra(AgentApprovalReceiver.EXTRA_SOURCE, AgentApprovalReceiver.SOURCE_BRIDGE)
        }
        val approve = PendingIntent.getBroadcast(
            context,
            approvalId.hashCode() xor 0xA1,
            verdictIntent(approve = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val deny = PendingIntent.getBroadcast(
            context,
            approvalId.hashCode() xor 0xD1,
            verdictIntent(approve = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            context,
            companionId.toInt() xor 0xBB,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = buildString {
            append(action)
            detail?.takeIf { it.isNotBlank() }?.let { append(" — ${it.take(120)}") }
        }
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle("$agentName asks for approval")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .addAction(0, "Approve", approve)
            .addAction(0, "Deny", deny)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(approvalNotificationId(companionId, approvalId), notification)
    }

    private fun approvalNotificationId(companionId: Long, approvalId: String): Int =
        (Constants.NOTIFICATION_ID_AGENT + companionId + approvalId.hashCode()).toInt()

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CHANNEL_AGENT,
                Constants.CHANNEL_AGENT_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Status updates from your AI agent companions"
            }
            manager.createNotificationChannel(channel)
        }
    }
}