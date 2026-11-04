package com.pixelpal.app.presentation.screens.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.data.local.db.entity.AgentSessionEntity
import com.pixelpal.app.data.remote.bridge.AgentBridgeTransport
import com.pixelpal.app.data.remote.bridge.BridgeRelayClient
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

/** Progress of the "type a 9-character code" flow. */
sealed interface CodePairState {
    data object Idle : CodePairState
    data object Working : CodePairState
    data class Success(val agentsFound: Int) : CodePairState
    data class Error(val message: String) : CodePairState
}

data class ConnectedAgentsUiState(
    val rows: List<AgentRow> = emptyList(),
    val bridgeUrl: String = "",
    val isPaired: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastError: String? = null,
    val codePair: CodePairState = CodePairState.Idle,
    /** True when we are reaching the bridge over the relay, not the LAN. */
    val usingRelay: Boolean = false
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
    private val relay: BridgeRelayClient
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
            val url = preferences.getAgentBridgeUrl()
            val relayId = preferences.getAgentRelayId()

            if (url.isBlank() && relayId.isBlank()) {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    lastError = null,
                    isPaired = false
                )
                return@launch
            }

            val result = transport.refresh(url, relayId)
            if (result == null) {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    isPaired = true,
                    lastError = "Can't reach your computer. Is the bridge running?"
                )
                return@launch
            }

            // Direct transport already mirrored into Room; relay payload needs
            // the same treatment so both paths converge on one data source.
            if (result.kind == AgentBridgeTransport.Kind.RELAY) {
                repository.replaceFromPayload(
                    rawPayload = result.fromRelayPayload.orEmpty(),
                    bridgeId = "relay:$relayId",
                    companionId = companionId
                )
            } else {
                val bridgeId = preferences.getAgentBridgeId().ifBlank { url }
                repository.refresh(url, bridgeId, companionId)
            }

            _state.value = _state.value.copy(
                isRefreshing = false,
                bridgeUrl = url,
                isPaired = true,
                usingRelay = result.kind == AgentBridgeTransport.Kind.RELAY,
                lastError = null
            )
        }
    }

    /**
     * Pair by typing the 9-character code printed by `pixelpal-bridge setup`.
     *
     * This is the beginner path: no IP address, no QR, works off-Wi-Fi.
     */
    fun pairWithCode(rawCode: String) {
        val normalized = com.pixelpal.app.data.remote.bridge.RelayCrypto.normalizeCode(rawCode)
        if (normalized == null) {
            _state.value = _state.value.copy(
                codePair = CodePairState.Error(
                    "That code doesn't look right. It's 9 letters and numbers."
                )
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(codePair = CodePairState.Working)
            when (val result = relay.pair(normalized)) {
                is BridgeRelayClient.PairResult.Success -> {
                    val lookupId =
                        com.pixelpal.app.data.remote.bridge.RelayCrypto.lookupId(normalized)
                    preferences.setAgentRelayId(lookupId)
                    val agents = relay.parsePayload(result.payload)
                    if (agents.isNotEmpty()) {
                        repository.replaceFromPayload(
                            rawPayload = result.payload.orEmpty(),
                            bridgeId = "relay:$lookupId",
                            companionId = companionId
                        )
                    }
                    _state.value = _state.value.copy(
                        codePair = CodePairState.Success(agents.size),
                        isPaired = true,
                        usingRelay = true,
                        lastError = null
                    )
                }

                BridgeRelayClient.PairResult.WrongCode -> {
                    _state.value = _state.value.copy(
                        codePair = CodePairState.Error("Wrong code — check the 9 characters.")
                    )
                }

                BridgeRelayClient.PairResult.BridgeOffline -> {
                    _state.value = _state.value.copy(
                        codePair = CodePairState.Error(
                            "No bridge found. On your computer run `pixelpal-bridge setup`."
                        )
                    )
                }

                is BridgeRelayClient.PairResult.Failed -> {
                    _state.value = _state.value.copy(
                        codePair = CodePairState.Error(result.message)
                    )
                }
            }
        }
    }

    fun clearCodePairState() {
        _state.value = _state.value.copy(codePair = CodePairState.Idle)
    }

    /** Pairing payload from the QR code: pixelpal://pair?host=…&port=…&key=… */
    fun applyPairing(uri: String): Boolean {
        val parsed = parsePairingUri(uri) ?: return false
        viewModelScope.launch {
            preferences.setAgentBridge(parsed.url, parsed.key, parsed.bridgeId)
            _state.value = _state.value.copy(bridgeUrl = parsed.url, isPaired = true, lastError = null)
            refresh()
        }
        return true
    }

    fun unpair() {
        viewModelScope.launch {
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
                preferences.getAgentRelayId(),
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
                preferences.getAgentRelayId(),
                approvalId,
                approved = false
            )
            refresh()
        }
    }

    companion object {
        data class Pairing(val url: String, val key: String, val bridgeId: String)

        /**
         * Parse `pixelpal://pair?host=1.2.3.4&port=8787&key=…`.
         * Returns null for anything that isn't a valid pairing URI, so a stray
         * QR code can never silently reconfigure the app.
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
            val port = params["port"] ?: "8787"
            val key = params["key"] ?: return null
            if (key.length < 8) return null
            return Pairing(
                url = "http://$host:$port",
                key = key,
                bridgeId = "$host:$port"
            )
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
