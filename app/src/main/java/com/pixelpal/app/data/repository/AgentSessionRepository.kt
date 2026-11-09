package com.pixelpal.app.data.repository

import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.data.local.db.dao.AgentSessionDao
import com.pixelpal.app.data.local.db.entity.AgentSessionEntity
import com.pixelpal.app.domain.model.AgentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the pixelpal-bridge's `/agents` endpoint and mirrors it into Room.
 *
 * The bridge (see `tools/agent-bridge`) is the single source of truth: it
 * normalizes Claude Code / Codex / agy / OMP / opencode / Hermes into one
 * envelope. The app therefore needs exactly ONE integration, and adding a new
 * agent is a bridge-side change — no app release required.
 */
@Singleton
class AgentSessionRepository @Inject constructor(
    private val dao: AgentSessionDao,
    private val preferences: PreferencesManager,
    private val client: OkHttpClient
) {

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    val sessions: Flow<List<AgentSessionEntity>> = dao.observeAll()

    /** Agents blocked on the user, newest first. Drives notifications. */
    val waiting: Flow<List<AgentSessionEntity>> = dao.observeWaiting()

    /**
     * Pull `/agents` from the configured bridge and mirror it into Room.
     *
     * [bridgeUrl] comes from [PreferencesManager.getAgentBridgeUrl]. Returns the number
     * of sessions synced, or -1 when the bridge is unreachable (callers treat
     * that as "leave existing rows alone" — a laptop being asleep must not
     * erase the agents you saw).
     */
    suspend fun refresh(
        bridgeUrl: String,
        bridgeId: String,
        companionId: Long
    ): Int = withContext(Dispatchers.IO) {
        if (bridgeUrl.isBlank()) return@withContext -1

        val agents = fetchAgents(bridgeUrl) ?: return@withContext -1
        applyAgents(agents, bridgeId, companionId)
    }

    /**
     * Mirror an already-fetched `/agents` list into Room.
     *
     * Split out from [refresh] so callers that fetched the payload themselves
     * (the poller, the relay path) persist it without a second HTTP round-trip.
     */
    suspend fun applyAgents(
        agents: List<WireAgent>,
        bridgeId: String,
        companionId: Long
    ): Int = withContext(Dispatchers.IO) {
        val dismissed = preferences.getDismissedAgentSessions()
        val workingSids = agents.filter { it.status.equals("WORKING", ignoreCase = true) || it.status.equals("PAUSED", ignoreCase = true) }
            .flatMap { listOf(it.sessionId, it.sessionId.substringAfter(':'), "${it.source}:${it.sessionId.substringAfter(':')}") }
            .toSet()

        val activeDismissed = if (workingSids.isNotEmpty() && dismissed.any { it in workingSids }) {
            val remaining = dismissed.filterNot { it in workingSids }.toSet()
            preferences.setDismissedAgentSessions(remaining)
            remaining
        } else {
            dismissed
        }

        val filtered = if (activeDismissed.isEmpty()) {
            agents
        } else {
            agents.filterNot { agent ->
                if (agent.status.equals("WORKING", ignoreCase = true) || agent.status.equals("PAUSED", ignoreCase = true)) {
                    false
                } else {
                    val sid = agent.sessionId
                    val rawSid = sid.substringAfter(':')
                    val fullSid = "${agent.source}:$rawSid"
                    sid in activeDismissed || rawSid in activeDismissed || fullSid in activeDismissed
                }
            }
        }

        if (filtered.isEmpty()) {
            dao.deleteAllForBridge(bridgeId)
            return@withContext 0
        }

        val now = System.currentTimeMillis()
        val entities = filtered.map { it.toEntity(bridgeId, companionId, now) }
        dao.upsertAll(entities)
        dao.deleteMissingForBridge(bridgeId, entities.map { it.id })
        entities.size
    }

    /**
     * Mirror a relay payload into Room.
     *
     * The relay carries the same `/agents` JSON as the direct transport, so it
     * is parsed by the same wire types — one data path, two transports.
     */
    suspend fun replaceFromPayload(
        rawPayload: String,
        bridgeId: String,
        companionId: Long
    ): Int = withContext(Dispatchers.IO) {
        if (rawPayload.isBlank()) return@withContext 0
        val agents = try {
            json.decodeFromString<WireAgentsResponse>(rawPayload).agents
        } catch (e: Exception) {
            Timber.e(e, "Malformed relay payload")
            return@withContext -1
        }
        applyAgents(agents, bridgeId, companionId)
    }

    /** One-shot fetch; null means "unreachable", an empty list means "no agents". */
    suspend fun fetchAgents(bridgeUrl: String, pairingKey: String? = null): List<WireAgent>? = withContext(Dispatchers.IO) {
        val cleanBase = bridgeUrl.trimEnd('/')
            .removeSuffix("/agents")
            .removeSuffix("/status")
            .removeSuffix("/")
        val url = "$cleanBase/agents"
        val key = pairingKey ?: preferences.getAgentBridgeKey()
        val request = Request.Builder()
            .url(url)
            .apply {
                if (key.isNotBlank()) header("X-PixelPal-Key", key)
            }
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Agent bridge returned HTTP ${response.code} for $url")
                    return@withContext null
                }
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString<WireAgentsResponse>(body)
                parsed.agents
            }
        } catch (e: Exception) {
            Timber.e(e, "Agent bridge unreachable at $url")
            if (url.startsWith("https://", ignoreCase = true)) {
                val uri = try { java.net.URI(url) } catch (ignored: Exception) { null }
                val host = uri?.host ?: url.substringAfter("://").substringBefore(':').substringBefore('/')
                val port = if (uri?.port != null && uri.port > 0 && uri.port != 443) uri.port else 8787
                val fallbackUrl = "http://$host:$port"
                Timber.i("Retrying bridge over HTTP after TLS failure: $fallbackUrl")
                val fallbackAgents = fetchAgents(fallbackUrl, key)
                if (fallbackAgents != null) {
                    val savedUrl = preferences.getAgentBridgeUrl()
                    if (savedUrl.startsWith("https://", ignoreCase = true)) {
                        preferences.setAgentBridge(fallbackUrl, key, "$host:$port")
                    }
                    return@withContext fallbackAgents
                }
            }

            // Self-healing bridge discovery when IP or port changes (e.g. Wi-Fi router DHCP renewal)
            val recovered = trySelfHealBridge(cleanBase, key)
            if (recovered != null) {
                return@withContext recovered
            }
            null
        }
    }

    suspend fun verifyPairingWithReason(bridgeUrl: String, pairingKey: String): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        var cleanBase = bridgeUrl.trimEnd('/')
            .removeSuffix("/agents")
            .removeSuffix("/status")
            .removeSuffix("/")
        // Bridge daemon runs over plain HTTP (Tailscale is WireGuard layer-3 encrypted).
        // Normalize any https to http and strip default https port 443.
        if (cleanBase.startsWith("https://", ignoreCase = true)) {
            val uri = try { java.net.URI(cleanBase) } catch (e: Exception) { null }
            val host = uri?.host ?: cleanBase.substringAfter("://").substringBefore(':')
            val port = if (uri?.port != null && uri.port > 0 && uri.port != 443) uri.port else 8787
            cleanBase = "http://$host:$port"
        }
        val url = "$cleanBase/agents"
        val key = pairingKey.ifBlank { preferences.getAgentBridgeKey() }
        val request = Request.Builder()
            .url(url)
            .apply {
                if (key.isNotBlank()) header("X-PixelPal-Key", key)
            }
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = when (response.code) {
                        401 -> "Bridge rejected connection (HTTP 401 Unauthorized)."
                        404 -> "Endpoint not found (HTTP 404)."
                        else -> "Bridge returned HTTP ${response.code}"
                    }
                    Timber.w("Pairing check failed: $msg")
                    return@withContext false to msg
                }
                true to null
            }
        } catch (e: Exception) {
            Timber.e(e, "Pairing network error for $url")
            val msg = e.localizedMessage ?: e.message ?: "Could not connect to $url"
            false to msg
        }
    }

    private suspend fun trySelfHealBridge(failedBaseUrl: String, key: String): List<WireAgent>? = withContext(Dispatchers.IO) {
        val uri = try { java.net.URI(failedBaseUrl) } catch (e: Exception) { null } ?: return@withContext null
        val host = uri.host ?: return@withContext null
        val port = if (uri.port > 0) uri.port else 8787

        // 1. Probe common candidate ports on the same host (in case bridge was restarted on another port)
        val candidatePorts = listOf(8791, 8787, 8788, 8789, 8790, 8792).filter { it != port }
        for (candidatePort in candidatePorts) {
            val candidateUrl = "http://$host:$candidatePort"
            val agents = probeAgentsDirect(candidateUrl, key)
            if (agents != null) {
                Timber.i("Self-healed bridge: port changed from $port to $candidatePort on $host")
                preferences.setAgentBridge(candidateUrl, key, "$host:$candidatePort")
                return@withContext agents
            }
        }

        // 2. If host is a private LAN IP (e.g. 192.168.1.18), router DHCP may have reassigned it
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size == 4 && ((octets[0] == 192 && octets[1] == 168) || octets[0] == 10)) {
            val prefix = "${octets[0]}.${octets[1]}.${octets[2]}"
            val currentLast = octets[3]
            val candidateLastOctets = (1..35).filter { it != currentLast }

            val fastClient = client.newBuilder()
                .connectTimeout(350, TimeUnit.MILLISECONDS)
                .readTimeout(350, TimeUnit.MILLISECONDS)
                .build()

            val foundHost = coroutineScope {
                candidateLastOctets.map { last ->
                    async {
                        val testHost = "$prefix.$last"
                        val testUrl = "http://$testHost:$port/health"
                        try {
                            val req = Request.Builder()
                                .url(testUrl)
                                .apply { if (key.isNotBlank()) header("X-PixelPal-Key", key) }
                                .get()
                                .build()
                            fastClient.newCall(req).execute().use { res ->
                                if (res.isSuccessful || res.code == 401) testHost else null
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll().firstOrNull { it != null }
            }

            if (foundHost != null) {
                val recoveredUrl = "http://$foundHost:$port"
                val agents = probeAgentsDirect(recoveredUrl, key)
                if (agents != null) {
                    Timber.i("Self-healed bridge: IP reassigned from $host to $foundHost!")
                    preferences.setAgentBridge(recoveredUrl, key, "$foundHost:$port")
                    return@withContext agents
                }
            }
        }

        null
    }

    private fun probeAgentsDirect(baseUrl: String, key: String): List<WireAgent>? {
        val url = "${baseUrl.trimEnd('/')}/agents"
        val req = Request.Builder()
            .url(url)
            .apply { if (key.isNotBlank()) header("X-PixelPal-Key", key) }
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                json.decodeFromString<WireAgentsResponse>(body).agents
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolve a pending approval on the bridge, which returns the verdict to
     * the agent's PermissionRequest hook — this is what unblocks the agent.
     */
    suspend fun respondToApproval(
        bridgeUrl: String,
        approvalId: String,
        approved: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val body = """{"approvalId":"$approvalId","decision":"${if (approved) "approve" else "deny"}"}"""
        val request = Request.Builder()
            .url(bridgeUrl.trimEnd('/') + "/")
            .header("X-PixelPal-Key", preferences.getAgentBridgeKey())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve approval $approvalId")
            false
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { dao.deleteAll() }

    suspend fun dismissSession(sessionId: String, source: String, bridgeUrl: String) = withContext(Dispatchers.IO) {
        val rawSessionId = sessionId.substringAfter(':')
        preferences.addDismissedAgentSession(sessionId)
        preferences.addDismissedAgentSession(rawSessionId)
        preferences.addDismissedAgentSession("$source:$rawSessionId")

        dao.deleteById(sessionId)
        dao.deleteById("${source}:${rawSessionId}")
        dao.deleteById(rawSessionId)
        if (bridgeUrl.isBlank()) return@withContext
        val cleanBase = bridgeUrl.trimEnd('/')
            .removeSuffix("/agents")
            .removeSuffix("/status")
            .removeSuffix("/")
        val url = "$cleanBase/sessions/dismiss"
        val payload = """{"source":"$source","sessionId":"$rawSessionId"}"""
        val req = Request.Builder()
            .url(url)
            .apply {
                val key = preferences.getAgentBridgeKey()
                if (key.isNotBlank()) header("X-PixelPal-Key", key)
            }
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            Timber.d(e, "Failed to notify bridge of dismissed session")
        }
    }

    suspend fun sendAgentCommand(bridgeUrl: String, sessionId: String, command: String): Boolean = withContext(Dispatchers.IO) {
        if (bridgeUrl.isBlank()) return@withContext false
        val cleanBase = bridgeUrl.trimEnd('/')
            .removeSuffix("/agents")
            .removeSuffix("/status")
            .removeSuffix("/")
        val rawSessionId = sessionId.substringAfter(':')
        val url = "$cleanBase/"
        val payload = """{"command":"$command","sessionId":"$rawSessionId"}"""
        val req = Request.Builder()
            .url(url)
            .header("X-PixelPal-Key", preferences.getAgentBridgeKey())
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Timber.e(e, "Failed to send command to agent")
            false
        }
    }

    suspend fun getSessionMessages(bridgeUrl: String, sessionId: String): List<AgentChatMessage> = withContext(Dispatchers.IO) {
        if (bridgeUrl.isBlank()) return@withContext emptyList()
        val cleanBase = bridgeUrl.trimEnd('/')
            .removeSuffix("/agents")
            .removeSuffix("/status")
            .removeSuffix("/")
        val rawSessionId = sessionId.substringAfter(':')
        val url = "$cleanBase/sessions/messages?sessionId=$rawSessionId"
        val req = Request.Builder()
            .url(url)
            .apply {
                val key = preferences.getAgentBridgeKey()
                if (key.isNotBlank()) header("X-PixelPal-Key", key)
            }
            .get()
            .build()
        try {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString<AgentMessagesResponse>(body)
                parsed.messages
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch session messages from bridge")
            emptyList()
        }
    }

    // --- wire models ---------------------------------------------------

    @Serializable
    data class AgentChatMessage(
        val id: String = "",
        val role: String = "user",
        val text: String = "",
        val name: String = "",
        val command: String = "",
        val status: String = "",
        val summary: String = "",
        val output: String = "",
        val timestamp: Long = 0L
    )

    @Serializable
    data class AgentMessagesResponse(
        val ok: Boolean = false,
        val sessionId: String? = null,
        val messages: List<AgentChatMessage> = emptyList()
    )

    @Serializable
    data class WireAgentsResponse(
        val agents: List<WireAgent> = emptyList(),
        val count: Int = 0
    )

    @Serializable
    data class WireAgent(
        val status: String = "IDLE",
        val message: String = "",
        val currentTask: String? = null,
        val progress: Int? = null,
        val source: String = "generic",
        val agentName: String = "",
        val sessionId: String = "default",
        val project: String = "",
        val lastEventAgeSeconds: Long? = null,
        val toolsCompleted: Int = 0,
        val pendingApproval: WireApproval? = null
    )

    @Serializable
    data class WireApproval(
        val id: String = "",
        val action: String = "",
        val detail: String? = null
    )

    private fun WireAgent.toEntity(
        bridgeId: String,
        companionId: Long,
        now: Long
    ): AgentSessionEntity {
        val ageMs = (lastEventAgeSeconds ?: 0L) * 1000L
        return AgentSessionEntity(
            id = "$source:$sessionId",
            source = source,
            sessionId = sessionId,
            agentName = agentName.ifBlank { source },
            project = project,
            status = AgentState.fromId(status).id,
            message = message,
            currentTask = currentTask,
            progress = progress,
            pendingApprovalId = pendingApproval?.id?.takeIf { it.isNotBlank() },
            pendingApprovalAction = pendingApproval?.action?.takeIf { it.isNotBlank() },
            lastEventAt = now - ageMs,
            toolsCompleted = toolsCompleted,
            companionId = companionId,
            bridgeId = bridgeId
        )
    }
}
