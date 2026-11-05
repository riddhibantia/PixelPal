package com.pixelpal.app.presentation.screens.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.data.local.db.entity.AgentSessionEntity
import com.pixelpal.app.data.remote.bridge.AgentBridgePoller
import com.pixelpal.app.data.remote.bridge.AgentBridgeSync
import com.pixelpal.app.data.remote.bridge.AgentBridgeTransport
import com.pixelpal.app.data.remote.bridge.BridgeApprovalNotifier
import com.pixelpal.app.data.remote.GenericHttpAgentConnector
import com.pixelpal.app.data.repository.AgentSessionRepository
import com.pixelpal.app.domain.engine.ActiveCompanionManager
import com.pixelpal.app.domain.model.AgentState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row in the Connected Agents list. */
data class AgentRow(
    val id: String,
    val source: String,
    val displayName: String,
    val project: String,
    val status: AgentState,
    val message: String,
    val task: String?,
    val progress: Int?,
    val secondsSinceEvent: Long,
    val pendingApprovalId: String?,
    val pendingApprovalAction: String?,
    val toolsCompleted: Int
)

data class ConnectedAgentsUiState(
    val rows: List<AgentRow> = emptyList(),
    val bridgeUrl: String = "",
    val isPaired: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastError: String? = null
) {
    val waitingCount: Int get() = rows.count { it.status == AgentState.WAITING_FOR_INPUT }
    val workingCount: Int get() = rows.count { it.status == AgentState.WORKING }
    val isEmpty: Boolean get() = rows.isEmpty()
}

/**
 * Backs the Connected Agents screen.
 *
 * Refreshes on demand (and on resume, via the screen) rather than polling on a
 * timer: the bridge is local, so a refresh is ~1 ms when it's up and instant
 * to fail when the laptop is asleep. Background updates are the WorkManager
 * worker's job so the app isn't holding wake locks.
 */
@HiltViewModel
class ConnectedAgentsViewModel @Inject constructor(
    private val repository: AgentSessionRepository,
    private val preferences: PreferencesManager,
    private val activeCompanion: ActiveCompanionManager,
    private val transport: AgentBridgeTransport,
    private val bridgeSync: AgentBridgeSync,
    private val poller: AgentBridgePoller,
    private val notifier: BridgeApprovalNotifier,
    private val workerScheduler: com.pixelpal.app.worker.WorkerScheduler
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectedAgentsUiState(isRefreshing = true))
    val state: StateFlow<ConnectedAgentsUiState> = _state

    private var companionId: Long = 0L

    init {
        viewModelScope.launch {
            companionId = activeCompanion.activeCompanion.first()?.id ?: 0L
            val url = preferences.getAgentBridgeUrl()
            val paired = preferences.getAgentBridgeKey().isNotBlank()
            _state.value = _state.value.copy(bridgeUrl = url, isPaired = paired)
            observe()
            refresh()
        }
    }

    private fun observe() {
        viewModelScope.launch {
            repository.sessions.collect { sessions ->
                _state.value = _state.value.copy(rows = sessions.map { it.toRow() })
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true)
            val paired = preferences.getAgentBridgeUrl().isNotBlank()
            if (!paired) {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    lastError = null,
                    isPaired = false
                )
                return@launch
            }

            val outcome = bridgeSync.sync()
            if (!outcome.reachable) {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    isPaired = true,
                    lastError = "Can't reach your computer. Is the bridge running?"
                )
                return@launch
            }
            // Notifications follow the same state the screen shows.
            notifier.reconcile()

            _state.value = _state.value.copy(
                isRefreshing = false,
                bridgeUrl = preferences.getAgentBridgeUrl(),
                isPaired = true,
                lastError = null
            )
        }
    }


    /**
     * Accept a pairing payload (from the QR scanner or the manual setup row).
     *
     * [applyPairing] takes the `pixelpal://host=…&key=…` URI the bridge prints;
     * the address may be a LAN URL or a Tailscale one — both are plain URLs.
     */
    fun applyPairing(rawUri: String): Boolean {
        val parsed = parsePairingUri(rawUri) ?: run {
            _state.value = _state.value.copy(
                lastError = "That pairing code doesn't look right."
            )
            return false
        }
        viewModelScope.launch {
            preferences.setAgentBridge(parsed.url, parsed.key, parsed.bridgeId)
            _state.value = _state.value.copy(
                bridgeUrl = parsed.url,
                isPaired = true,
                lastError = null
            )
            onPaired()
            refresh()
        }
        return true
    }

    /** Keep the bridge watched from now on, without waiting for a screen. */
    private fun onPaired() {
        poller.start()
        workerScheduler.scheduleBridgePolling()
    }

    fun unpair() {
        viewModelScope.launch {
            poller.stop()
            workerScheduler.cancelBridgePolling()
            notifier.reset()
            preferences.clearAgentBridge()
            repository.clear()
            _state.value = ConnectedAgentsUiState()
        }
    }

    fun approve(row: AgentRow) {
        val approvalId = row.pendingApprovalId ?: return
        viewModelScope.launch {
            transport.respondToApproval(
                preferences.getAgentBridgeUrl(),
                approvalId,
                approved = true
            )
            refresh()
        }
    }

    fun deny(row: AgentRow) {
        val approvalId = row.pendingApprovalId ?: return
        viewModelScope.launch {
            transport.respondToApproval(
                preferences.getAgentBridgeUrl(),
                approvalId,
                approved = false
            )
            refresh()
        }
    }

    companion object {
        data class Pairing(val url: String, val key: String, val bridgeId: String)

        /**
         * Parse `pixelpal://pair?host=…&port=…&key=…`.
         *
         * A QR code can come from a poster or a screen share, so only hosts we
         * are willing to talk to are accepted:
         *  - loopback and private LAN (10/8, 172.16/12, 192.168/16)
         *  - the Tailscale range 100.64.0.0/10, which is how the phone reaches
         *    the laptop from outside the home Wi-Fi
         *  - `*.ts.net` (Tailscale MagicDNS) and `*.local` (mDNS)
         *
         * Anything else returns null, so a stray code cannot point the app —
         * and the bridge key it sends — at an arbitrary server.
         */
        fun parsePairingUri(raw: String): Pairing? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("pixelpal://pair", ignoreCase = true)) return null
            val query = trimmed.substringAfter('?', "").ifBlank { return null }
            val params = query.split('&').mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
            }.toMap()
            val host = params["host"] ?: return null
            val key = params["key"] ?: return null
            if (key.length < 8) return null
            if (!isAllowedHost(host)) return null

            // A Tailscale host is reached over HTTPS; LAN hosts over plain HTTP.
            val https = host.endsWith(".ts.net", ignoreCase = true)
            val port = params["port"] ?: "8787"
            val url = if (https) "https://$host" else "http://$host:$port"
            return Pairing(url = url, key = key, bridgeId = "$host:$port")
        }

        /** Why: see [parsePairingUri]. Kept separate so it is unit-testable. */
        fun isAllowedHost(host: String): Boolean {
            val h = host.trim().lowercase()
            if (h.isBlank()) return false
            if (h == "localhost" || h == "10.0.2.2") return true
            if (h.endsWith(".local")) return true
            if (h.endsWith(".ts.net")) return true

            val octets = h.split('.')
                .map { it.toIntOrNull() ?: return false }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            val (a, b) = octets[0] to octets[1]
            return when {
                a == 127 -> true                       // loopback
                a == 10 -> true                        // 10.0.0.0/8
                a == 192 && b == 168 -> true           // 192.168.0.0/16
                a == 172 && b in 16..31 -> true        // 172.16.0.0/12
                a == 100 && b in 64..127 -> true       // Tailscale 100.64.0.0/10
                else -> false
            }
        }
    }
}

private fun AgentSessionEntity.toRow(): AgentRow = AgentRow(
    id = id,
    source = source,
    displayName = agentName.ifBlank { source },
    project = project,
    status = AgentState.fromId(status),
    message = message,
    task = currentTask,
    progress = progress,
    secondsSinceEvent = ((System.currentTimeMillis() - lastEventAt) / 1000).coerceAtLeast(0),
    pendingApprovalId = pendingApprovalId,
    pendingApprovalAction = pendingApprovalAction,
    toolsCompleted = toolsCompleted
)
