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

    /** One-shot fetch; null means "unreachable", an empty list means "no agents". */
    suspend fun fetchAgents(bridgeUrl: String): List<WireAgent>? = withContext(Dispatchers.IO) {
        val url = bridgeUrl.trimEnd('/') + "/agents"
        val request = Request.Builder()
            .url(url)
            .header("X-PixelPal-Key", preferences.getAgentBridgeKey())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Agent bridge returned HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString<WireAgentsResponse>(body)
                parsed.agents
            }
        } catch (e: Exception) {
            Timber.d(e, "Agent bridge unreachable at $url")
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

    // --- wire models ---------------------------------------------------

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
