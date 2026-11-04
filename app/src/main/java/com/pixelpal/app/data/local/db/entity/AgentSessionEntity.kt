package com.pixelpal.app.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per **connected agent session**, as reported by the pixelpal-bridge
 * (see `tools/agent-bridge`).
 *
 * Why a separate table: [AgentConnectionEntity] is *configuration* (which URL
 * to talk to, keyed by companion), while this is *observable state* per agent
 * session. A single companion can be watched by several agents at once
 * (Claude Code, Codex, OMP, opencode, Hermes), each with its own live status.
 *
 * [id] is `${source}:${sessionId}` from the bridge, so re-reporting the same
 * session upserts instead of duplicating.
 */
@Entity(
    tableName = "agent_sessions",
    indices = [
        Index(value = ["source"]),
        Index(value = ["lastEventAt"]),
        Index(value = ["companionId"])
    ]
)
data class AgentSessionEntity(
    @PrimaryKey val id: String,
    val source: String,
    val sessionId: String,
    val agentName: String = "",
    val project: String = "",
    /** PixelPal AgentState id: WORKING / IDLE / COMPLETED / WAITING_FOR_INPUT… */
    val status: String = "IDLE",
    val message: String = "",
    val currentTask: String? = null,
    val progress: Int? = null,
    /** Set while this agent is blocked on the user (approval or question). */
    val pendingApprovalId: String? = null,
    val pendingApprovalAction: String? = null,
    /** Wall-clock of the newest event, used for "12s ago" and staleness. */
    val lastEventAt: Long = 0L,
    val toolsCompleted: Int = 0,
    /** Which companion this session was attributed to when first seen. */
    val companionId: Long = 0L,
    /** Soft grouping: the bridge that reported it (usually one). */
    val bridgeId: String = ""
)
