package com.pixelpal.app.data.remote.bridge

import android.content.Context
import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.data.repository.AgentSessionRepository
import com.pixelpal.app.domain.engine.ActiveCompanionManager
import com.pixelpal.app.widget.HomeWidgetProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that turns "what the bridge reports" into "what Room holds".
 *
 * Every consumer — the Agents screen, the background poller and the fallback
 * worker — goes through [sync] so the mirroring rules (which
 * `bridgeId` rows belong to, what happens when the laptop is asleep) exist
 * once. Before this class the same logic lived in the screen's ViewModel, which
 * is why agent state only existed while that screen was open.
 */
@Singleton
class AgentBridgeSync @Inject constructor(
    private val transport: AgentBridgeTransport,
    private val repository: AgentSessionRepository,
    private val preferences: PreferencesManager,
    private val activeCompanion: ActiveCompanionManager,
    @ApplicationContext private val context: Context
) {

    /**
     * Cheap signature of what the widget renders, so a 20-second poll does not
     * rebuild RemoteViews when nothing moved.
     */
    private var widgetSignature: String? = null

    data class Outcome(
        /** A bridge address has been configured at all. */
        val paired: Boolean,
        /** The bridge answered. False means "leave existing rows alone". */
        val reachable: Boolean,
        /** Sessions mirrored this round. */
        val agents: Int
    ) {
        companion object {
            val NOT_PAIRED = Outcome(paired = false, reachable = false, agents = 0)
        }
    }

    suspend fun sync(): Outcome {
        val url = preferences.getAgentBridgeUrl()
        if (url.isBlank()) return Outcome.NOT_PAIRED

        val companionId = activeCompanion.activeCompanion.first()?.id ?: 0L
        // A Wi-Fi (LAN) address and a Tailscale address are both plain URLs;
        // the bridge simply reports whichever the user pasted in.
        val result = transport.refresh(url)
            ?: return Outcome(paired = true, reachable = false, agents = 0)

        return try {
            val bridgeId = preferences.getAgentBridgeId().ifBlank { url }
            val synced = repository.applyAgents(result.agents ?: emptyList(), bridgeId, companionId)
            Outcome(paired = true, reachable = true, agents = synced.coerceAtLeast(0))
        } catch (e: Exception) {
            Timber.w(e, "Bridge payload could not be persisted")
            Outcome(paired = true, reachable = true, agents = 0)
        }.also { refreshWidgetIfChanged() }
    }

    /** The home widget's agent line reads `agent_sessions`; keep it current. */
    private suspend fun refreshWidgetIfChanged() {
        val sessions = repository.sessions.first()
        val signature = sessions.joinToString("|") { "${it.id}:${it.status}:${it.pendingApprovalId}" }
        if (signature == widgetSignature) return
        widgetSignature = signature
        try {
            HomeWidgetProvider.updateAllWidgets(context)
        } catch (e: Exception) {
            Timber.w(e, "Widget refresh failed")
        }
    }
}
