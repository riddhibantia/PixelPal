package com.pixelpal.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pixelpal.app.R
import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.domain.engine.BondEngine
import com.pixelpal.app.domain.engine.CompanionEngine
import com.pixelpal.app.domain.engine.EmotionEngine
import com.pixelpal.app.domain.model.AgentState
import com.pixelpal.app.domain.model.Emotion
import com.pixelpal.app.domain.repository.CompanionRepository
import com.pixelpal.app.presentation.MainActivity
import com.pixelpal.app.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground host for overlay sessions. Owns NO companion identity itself:
 * it resolves the desired set of on-screen companions
 * (master toggle + user selection, defaulting to the active companion) and
 * syncs [OverlayManager] sessions to it. Each session knows its own companion.
 */
@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var overlayManager: OverlayManager
    @Inject lateinit var companionEngine: CompanionEngine
    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var companionRepository: CompanionRepository
    @Inject lateinit var bondEngine: BondEngine
    @Inject lateinit var emotionEngine: EmotionEngine
    @Inject lateinit var agentConnectionRepository: com.pixelpal.app.domain.repository.AgentConnectionRepository
    @Inject lateinit var agentSessionDao: com.pixelpal.app.data.local.db.dao.AgentSessionDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Timber.d("OverlayService created")
        createNotificationChannel()
        startForeground(Constants.FOREGROUND_SERVICE_ID, buildNotification(Constants.DEFAULT_PET_NAME))

        scope.launch {
            if (!Settings.canDrawOverlays(this@OverlayService)) {
                Timber.w("SYSTEM_ALERT_WINDOW permission not granted, skipping overlays")
                stopSelf()
                return@launch
            }

            combine(
                preferencesManager.overlayEnabled,
                companionRepository.getPrimary(),
                overlayManager.appForeground
            ) { enabled, companion, foreground -> Triple(enabled, companion, foreground) }
                .collect { (enabled, companion, foreground) ->
                    // Park the bubble while the app itself is open: the pet is
                    // already on screen, and a second infinite Lottie loop
                    // costs roughly half the app's idle render budget.
                    val desired = if (enabled && companion != null && !foreground) {
                        listOf(companion.id)
                    } else {
                        emptyList()
                    }
                    Timber.d(
                        "[OVERLAY] Sync: enabled=%s companion=%s foreground=%s desired=%s",
                        enabled, companion?.id, foreground, desired
                    )
                    syncSessions(desired)
                    if (companion != null && desired.isNotEmpty()) {
                        notifyForeground(companion.name)
                    }
                }
        }
    }

    private suspend fun syncSessions(desiredIds: List<Long>) {
        // Stop sessions no longer wanted (single-overlay invariant: at most one).
        overlayManager.activeCompanionIds().filter { it !in desiredIds }.forEach {
            overlayManager.hideCompanionFor(it)
        }
        desiredIds.forEach { id ->
            val companion = companionRepository.getByIdDirect(id) ?: return@forEach
            if (!overlayManager.isShowing(id)) {
                overlayManager.showCompanionFor(
                    companionId = companion.id,
                    petType = companion.effectiveSpecies,
                    onTap = { cid ->
                        scope.launch {
                            bondEngine.recordTap(cid)
                            emotionEngine.triggerEmotion(Emotion.HAPPY)
                            val message = buildOverlayMessage(cid)
                            overlayManager.showSpeechBubble(cid, message)
                        }
                    },
                    onDoubleTap = { cid ->
                        companionEngine.onDoubleTap(cid)
                        // Double-tapping the overlay opens the app from anywhere.
                        val openApp = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(openApp)
                    },
                    onLongPress = { cid -> companionEngine.onFeed(cid) }
                )
            } else {
                overlayManager.updatePetTypeFor(id, companion.effectiveSpecies)
            }
        }
    }

    private suspend fun buildOverlayMessage(cid: Long): String {
        val bridgeSessions = agentSessionDao.getAllDirect()
        val bridgeUrl = preferencesManager.getAgentBridgeUrl()
        val isBridgePaired = bridgeUrl.isNotBlank()
        val customConn = agentConnectionRepository.getConnectionDirect(cid)
        val hasCustomConn = customConn != null &&
            customConn.endpointUrl.isNotBlank() &&
            customConn.currentStatus != AgentState.DISCONNECTED

        // 1. Check for actively working, error, or permission-seeking bridge sessions
        val activeBridgeSession = bridgeSessions.firstOrNull { session ->
            when (session.status) {
                "WAITING_FOR_INPUT", "ERROR", "WORKING" -> true
                "COMPLETED" -> (System.currentTimeMillis() - session.lastEventAt) < 10 * 60 * 1000L
                else -> false
            }
        }

        if (activeBridgeSession != null) {
            val agentName = activeBridgeSession.agentName.ifBlank { activeBridgeSession.source }
            return when (activeBridgeSession.status) {
                "WAITING_FOR_INPUT" -> {
                    val action = activeBridgeSession.pendingApprovalAction?.takeIf { it.isNotBlank() }
                        ?: activeBridgeSession.currentTask?.takeIf { it.isNotBlank() }
                    if (action != null) {
                        "⚠️ $agentName needs permission: ${action.take(35)}! Double-tap to review & approve."
                    } else {
                        "⚠️ $agentName is waiting for permission! Double-tap to review & approve."
                    }
                }
                "WORKING" -> {
                    val task = activeBridgeSession.currentTask?.takeIf { it.isNotBlank() && it != "WORKING" }
                        ?: activeBridgeSession.message.takeIf { it.isNotBlank() && it != "WORKING" }
                    val progress = activeBridgeSession.progress?.let { " ($it%)" } ?: ""
                    val tools = if (activeBridgeSession.toolsCompleted > 0) " • ${activeBridgeSession.toolsCompleted} tools done" else ""
                    if (task != null) {
                        "⚡ $agentName is working$progress: ${task.take(38)} (Double-tap to view)"
                    } else {
                        "⚡ $agentName is actively working$progress$tools! Double-tap to view."
                    }
                }
                "ERROR" -> {
                    val msg = activeBridgeSession.message.takeIf { it.isNotBlank() && it != "ERROR" }
                        ?: activeBridgeSession.currentTask
                    if (msg != null) {
                        "❌ $agentName needs attention: ${msg.take(35)}. Double-tap to check."
                    } else {
                        "❌ $agentName ran into an issue! Double-tap to inspect."
                    }
                }
                "COMPLETED" -> {
                    val task = activeBridgeSession.currentTask?.takeIf { it.isNotBlank() && it != "COMPLETED" }
                    if (task != null) {
                        "🎉 $agentName finished: ${task.take(35)}! Double-tap to see what was done."
                    } else {
                        "🎉 $agentName completed the task! Double-tap to view details."
                    }
                }
                else -> "$agentName: ${activeBridgeSession.status} (Double-tap to open)"
            }
        }

        // 2. Check for active custom agent connection (Gemini / HTTP / WebSocket)
        if (hasCustomConn && customConn != null) {
            when (customConn.currentStatus) {
                AgentState.WAITING_FOR_INPUT -> {
                    val task = customConn.currentTask?.take(35) ?: "Action required"
                    return "⚠️ Agent needs approval: $task! Double-tap to respond."
                }
                AgentState.WORKING -> {
                    val task = customConn.currentTask?.take(38) ?: "working on task"
                    val progress = customConn.progress?.let { " ($it%)" } ?: ""
                    return "⚡ Agent is working$progress: $task (Double-tap to view)"
                }
                AgentState.ERROR -> {
                    val msg = customConn.lastMessage?.take(35) ?: "Attention required"
                    return "❌ Agent error: $msg. Double-tap to inspect."
                }
                AgentState.COMPLETED -> {
                    return "🎉 Agent finished task! Double-tap to view details."
                }
                AgentState.CONNECTING -> {
                    return "Connecting to your agent… Double-tap to check status."
                }
                AgentState.OFFLINE -> {
                    return "Agent is offline right now. Double-tap to check settings."
                }
                AgentState.IDLE, AgentState.ONLINE -> {
                    // Handled in connected-but-idle state below
                }
                AgentState.DISCONNECTED -> Unit
            }
        }

        // 3. Bridge or custom provider is connected, but NO agent is currently working (All idle / ready)
        if (isBridgePaired || hasCustomConn) {
            val companion = companionRepository.getByIdDirect(cid)
            val petName = companion?.name ?: "PixelPal"
            val idleGreetings = listOf(
                "Bridge connected! No agents working right now. Double-tap to open PixelPal 🐾",
                "All quiet on your laptop! Ready when you are ✨ Double-tap to chat with me.",
                "Connected to your computer! Waiting for your next task 🚀 (Double-tap to open)",
                "$petName is standing by! Double-tap to open chat or assign a task 💻"
            )
            return idleGreetings.random()
        }

        // 4. Not connected at all
        val notConnectedGreetings = listOf(
            "Hi! Want to connect your coding agent? 🐾 Double-tap to open PixelPal & pair!",
            "No agent connected yet! Double-tap me to connect your workspace 🚀",
            "Hi there! Ready to pair your coding agent? Double-tap to open PixelPal ✨"
        )
        return notConnectedGreetings.random()
    }

    private fun notifyForeground(petNames: String) {
        getSystemService(NotificationManager::class.java)?.notify(
            Constants.FOREGROUND_SERVICE_ID,
            buildNotification(petNames)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("OverlayService destroyed")
        scope.cancel()
        overlayManager.stopAllOverlays()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CHANNEL_COMPANION,
                Constants.CHANNEL_COMPANION_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps your pixel companions active on screen"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(petNames: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.CHANNEL_COMPANION)
            .setContentTitle(petNames.ifBlank { "PixelPal" })
            .setContentText("$petNames ${if (petNames.contains(',')) "are" else "is"} hanging out with you 🐱")
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.stopService(intent)
        }
    }
}