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
import com.pixelpal.app.domain.model.Companion
import com.pixelpal.app.domain.usecase.companion.TapCompanionUseCase
import com.pixelpal.app.domain.engine.BondEngine
import com.pixelpal.app.domain.repository.ActivityEventRepository
import com.pixelpal.app.domain.model.ActivityType
import com.pixelpal.app.domain.model.AgentState
import com.pixelpal.app.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
) {
    val sessionId: String get() = id.substringAfter(':')
}

data class ConnectedAgentsUiState(
    val rows: List<AgentRow> = emptyList(),
    val bridgeUrl: String = "",
    val isPaired: Boolean = false,
    val isBridgeReachable: Boolean = false,
    val isRefreshing: Boolean = false,
    val isPairing: Boolean = false,
    val lastError: String? = null,
    val message: String? = null
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
    private val workerScheduler: com.pixelpal.app.worker.WorkerScheduler,
    private val activityEvents: ActivityEventRepository,
    private val bondEngine: BondEngine,
    private val tapCompanionUseCase: TapCompanionUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectedAgentsUiState(isRefreshing = true))
    val state: StateFlow<ConnectedAgentsUiState> = _state

    /**
     * Species the empty/pairing states draw. Reading it from the primary
     * companion keeps the pet on this screen identical to the user's own pet;
     * a literal here shipped the default cat to every user regardless of the
     * species they picked during onboarding.
     */
    val companion: StateFlow<com.pixelpal.app.domain.model.Companion?> = activeCompanion.activeCompanion
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            null
        )

    val companionPetType: StateFlow<String> = activeCompanion.activeCompanion
        .map { it?.effectiveSpecies ?: Constants.DEFAULT_PET_TYPE }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            Constants.DEFAULT_PET_TYPE
        )

    val streakDays: StateFlow<Int> = bondEngine.bond
        .map { it.streakDays }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            0
        )

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

    fun interactWithCompanion() {
        viewModelScope.launch {
            tapCompanionUseCase()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isRefreshing = true,
                message = if (_state.value.message?.startsWith("Removed") == true) null else _state.value.message
            )
            if (_state.value.rows.isEmpty()) {
                preferences.clearDismissedAgentSessions()
            }
            val paired = preferences.getAgentBridgeUrl().isNotBlank()
            if (!paired) {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    lastError = null,
                    isPaired = false,
                    isBridgeReachable = false
                )
                return@launch
            }

            val outcome = bridgeSync.sync()
            if (!outcome.reachable) {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    isPaired = true,
                    isBridgeReachable = false,
                    lastError = "Can't reach ${preferences.getAgentBridgeId().ifBlank { "your computer" }}. " +
                        "Wake the computer and check that your phone is on the same Wi-Fi, " +
                        "or that both devices are connected to Tailscale. Tap retry when ready."
                )
                return@launch
            }
            // Notifications follow the same state the screen shows.
            notifier.reconcile()

            _state.value = _state.value.copy(
                isRefreshing = false,
                bridgeUrl = preferences.getAgentBridgeUrl(),
                isPaired = true,
                isBridgeReachable = true,
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
        val parsed = parsePairingUri(rawUri) ?: parseFallbackAddress(rawUri) ?: run {
            _state.value = _state.value.copy(
                lastError = "That pairing code doesn't look right. Please check the address or QR code."
            )
            return false
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isPairing = true, lastError = null)
            val effectiveKey = parsed.key.ifBlank { preferences.getAgentBridgeKey() }
            var workingUrl: String? = null
            val (ok, reason) = transport.verifyPairingDetailed(parsed.url, effectiveKey)
            if (ok) {
                workingUrl = parsed.url
            } else {
                // If direct verify failed (e.g. port or scheme mismatch), probe candidate ports and HTTP
                val uri = try { java.net.URI(parsed.url) } catch (e: Exception) { null }
                val host = uri?.host ?: parsed.bridgeId.substringBefore(':')
                val candidatePorts = listOf(8791, 8787, 8788, 8789, 8790, 8792)
                for (cp in candidatePorts) {
                    val candidateUrl = "http://$host:$cp"
                    if (candidateUrl != parsed.url && transport.verifyPairing(candidateUrl, effectiveKey)) {
                        workingUrl = candidateUrl
                        break
                    }
                }
            }

            if (workingUrl == null) {
                _state.value = _state.value.copy(
                    isPairing = false,
                    lastError = "PixelPal couldn't reach that bridge at ${parsed.url}: ${reason ?: "Ensure the bridge is running on your laptop and both devices are on the same Wi-Fi or Tailscale network."}"
                )
                return@launch
            }

            val bridgeId = try {
                val u = java.net.URI(workingUrl)
                "${u.host}:${u.port}"
            } catch (e: Exception) {
                parsed.bridgeId
            }

            preferences.setAgentBridge(workingUrl, effectiveKey, bridgeId)
            _state.value = _state.value.copy(
                bridgeUrl = workingUrl,
                isPaired = true,
                isBridgeReachable = true,
                isPairing = false,
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

    fun dismissAgent(row: AgentRow) {
        val rawSid = row.sessionId.substringAfter(':')
        _state.value = _state.value.copy(
            rows = _state.value.rows.filterNot { it.id == row.id || it.sessionId == row.sessionId || it.sessionId == rawSid },
            message = "Removed ${row.displayName}"
        )
        viewModelScope.launch {
            repository.dismissSession(row.id, row.source, preferences.getAgentBridgeUrl())
            refresh()
            delay(3000)
            if (_state.value.message == "Removed ${row.displayName}") {
                _state.value = _state.value.copy(message = null)
            }
        }
    }

    private val _chatMessages = MutableStateFlow<List<AgentSessionRepository.AgentChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<AgentSessionRepository.AgentChatMessage>> = _chatMessages.asStateFlow()

    private val _isSendingCommand = MutableStateFlow(false)
    val isSendingCommand: StateFlow<Boolean> = _isSendingCommand.asStateFlow()

    fun loadSessionMessages(row: AgentRow) {
        viewModelScope.launch {
            val msgs = repository.getSessionMessages(preferences.getAgentBridgeUrl(), row.sessionId)
            if (msgs.isNotEmpty()) {
                _chatMessages.value = msgs
            }
        }
    }

    fun sendCommandToAgent(row: AgentRow, command: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _isSendingCommand.value = true
            val trimmed = command.trim()
            if (trimmed.isNotBlank() && !trimmed.equals("stop", ignoreCase = true) && !trimmed.equals("pause", ignoreCase = true)) {
                _chatMessages.value = _chatMessages.value + AgentSessionRepository.AgentChatMessage(
                    id = "temp_${System.currentTimeMillis()}",
                    role = "user",
                    text = trimmed,
                    timestamp = System.currentTimeMillis()
                )
            }
            val ok = repository.sendAgentCommand(preferences.getAgentBridgeUrl(), row.sessionId, trimmed)
            _isSendingCommand.value = false
            if (ok) {
                _state.value = _state.value.copy(message = "Command sent: $trimmed")
                activityEvents.record(
                    companionId,
                    ActivityType.AGENT_STATUS_CHANGED,
                    "Sent command to ${row.displayName}",
                    trimmed
                )
                onDone(true)
                // Reload messages shortly after agent starts answering
                kotlinx.coroutines.delay(1200)
                val updated = repository.getSessionMessages(preferences.getAgentBridgeUrl(), row.sessionId)
                if (updated.isNotEmpty()) {
                    _chatMessages.value = updated
                }
                refresh()
            } else {
                _state.value = _state.value.copy(lastError = "Could not deliver command to ${row.displayName}. Check bridge status.")
                onDone(false)
            }
        }
    }

    fun approve(row: AgentRow) {
        val approvalId = row.pendingApprovalId ?: return
        viewModelScope.launch {
            val delivered = transport.respondToApproval(
                preferences.getAgentBridgeUrl(),
                approvalId,
                approved = true
            )
            completeApproval(row, delivered, true)
        }
    }

    fun deny(row: AgentRow) {
        val approvalId = row.pendingApprovalId ?: return
        viewModelScope.launch {
            val delivered = transport.respondToApproval(
                preferences.getAgentBridgeUrl(),
                approvalId,
                approved = false
            )
            completeApproval(row, delivered, false)
        }
    }

    private suspend fun completeApproval(row: AgentRow, delivered: Boolean, approved: Boolean) {
        if (delivered) {
            activityEvents.record(
                companionId,
                ActivityType.AGENT_APPROVAL_DECIDED,
                "${if (approved) "Approved" else "Denied"} ${row.displayName}'s request",
                row.pendingApprovalAction ?: row.task ?: row.project
            )
            _state.value = _state.value.copy(message = if (approved) "Approval sent" else "Request denied", lastError = null)
            refresh()
        } else {
            _state.value = _state.value.copy(lastError = "Couldn't send your decision. Check the bridge connection and retry.")
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

            // The bridge runs a plain HTTP daemon. Tailscale MagicDNS (*.ts.net) traffic
            // is already encrypted by WireGuard at layer 3. Never force https unless explicitly requested.
            val scheme = params["scheme"] ?: if (params["port"] == "443") "https" else "http"
            val port = params["port"] ?: if (scheme == "https") "443" else "8787"
            val url = if (scheme == "https" && port == "443") "https://$host" else "$scheme://$host:$port"
            return Pairing(url = url, key = key, bridgeId = "$host:$port")
        }

        fun parseFallbackAddress(raw: String): Pairing? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return null

            val lower = trimmed.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                val uri = try { java.net.URI(trimmed) } catch (e: Exception) { null } ?: return null
                val host = uri.host ?: return null
                if (!isAllowedHost(host)) return null
                val scheme = if (lower.startsWith("https://") && (host.endsWith(".ts.net") || host.endsWith(".tailscale.net") || host.endsWith(".local") || isPrivateLan(host))) {
                    "http"
                } else uri.scheme ?: "http"
                val port = if (uri.port > 0 && uri.port != 443 && uri.port != 80) uri.port.toString() else "8787"
                val query = uri.query.orEmpty()
                val params = query.split('&').mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
                }.toMap()
                val key = params["key"].orEmpty()
                val url = "$scheme://$host:$port"
                return Pairing(url = url, key = key, bridgeId = "$host:$port")
            }

            val hostPortRegex = Regex("""^([a-zA-Z0-9.-]+)(?::(\d+))?$""")
            val match = hostPortRegex.find(trimmed)
            if (match != null) {
                val host = match.groupValues[1]
                val port = match.groupValues[2].ifBlank { "8787" }
                if (isAllowedHost(host)) {
                    val url = "http://$host:$port"
                    return Pairing(url = url, key = "", bridgeId = "$host:$port")
                }
            }

            return null
        }

        fun isPrivateLan(host: String): Boolean {
            val h = host.trim().lowercase()
            val octets = h.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            val (a, b) = octets[0] to octets[1]
            return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
        }

        /** Why: see [parsePairingUri]. Kept separate so it is unit-testable. */
        fun isAllowedHost(host: String): Boolean {
            val h = host.trim().lowercase()
            if (h.isBlank()) return false
            if (h == "localhost" || h == "10.0.2.2") return true
            if (h.endsWith(".local")) return true
            if (h.endsWith(".ts.net") || h.endsWith(".tailscale.net") || h.endsWith(".tailscale.com")) return true

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
