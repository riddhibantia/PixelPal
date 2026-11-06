package com.pixelpal.app.data.repository

import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.data.local.db.dao.AgentSessionDao
import com.pixelpal.app.data.local.db.entity.AgentSessionEntity
import com.pixelpal.app.domain.model.AgentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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
        if (agents.isEmpty()) {
            dao.deleteAllForBridge(bridgeId)
            return@withContext 0
        }

        val now = System.currentTimeMillis()
        val entities = agents.map { it.toEntity(bridgeId, companionId, now) }
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
            if (url.startsWith("https://", ignoreCase = true) && (e is javax.net.ssl.SSLException || e.cause is java.security.cert.CertificateException)) {
                val fallbackUrl = "http://" + url.substring(8).substringBefore("/agents")
                Timber.i("Retrying bridge over HTTP after TLS failure: $fallbackUrl")
                val fallbackAgents = fetchAgents(fallbackUrl, key)
                if (fallbackAgents != null) {
                    val savedUrl = preferences.getAgentBridgeUrl()
                    if (savedUrl.startsWith("https://", ignoreCase = true)) {
                        preferences.setAgentBridge(fallbackUrl, key, preferences.getAgentBridgeId())
                    }
                }
                return@withContext fallbackAgents
            }
            null
        }
    }

    suspend fun verifyPairingWithReason(bridgeUrl: String, pairingKey: String): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val cleanBase = bridgeUrl.trimEnd('/')
            .removeSuffix("/agents")
            .removeSuffix("/status")
            .removeSuffix("/")
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
            if (url.startsWith("https://", ignoreCase = true) && (e is javax.net.ssl.SSLException || e.cause is java.security.cert.CertificateException)) {
                val fallbackUrl = "http://" + url.substring(8).substringBefore("/agents")
                Timber.i("Retrying pairing verification over HTTP after TLS failure: $fallbackUrl")
                return@withContext verifyPairingWithReason(fallbackUrl, key)
            }
            val msg = e.localizedMessage ?: e.message ?: "Could not connect to $url"
            false to msg
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
        dao.deleteById(sessionId)
        val rawSessionId = sessionId.substringAfter(':')
        dao.deleteById("${source}:${rawSessionId}")
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
