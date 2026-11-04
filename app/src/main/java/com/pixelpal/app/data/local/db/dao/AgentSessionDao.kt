package com.pixelpal.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pixelpal.app.data.local.db.entity.AgentSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to observed agent sessions.
 *
 * Ordering is deliberate: agents that need the user (waiting for input) sort
 * first, then errors, then the ones actively working. The pet and the agent
 * rail both rely on this so the thing that needs attention is never buried.
 */
@Dao
interface AgentSessionDao {

    @Query(
        """
        SELECT * FROM agent_sessions
        ORDER BY
            CASE status
                WHEN 'WAITING_FOR_INPUT' THEN 0
                WHEN 'ERROR' THEN 1
                WHEN 'WORKING' THEN 2
                WHEN 'COMPLETED' THEN 3
                WHEN 'ONLINE' THEN 4
                WHEN 'IDLE' THEN 5
                ELSE 6
            END,
            lastEventAt DESC
        """
    )
    fun observeAll(): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions WHERE id = :id")
    suspend fun getById(id: String): AgentSessionEntity?

    @Query(
        """
        SELECT * FROM agent_sessions
        WHERE status = 'WAITING_FOR_INPUT'
        ORDER BY lastEventAt DESC
        """
    )
    fun observeWaiting(): Flow<List<AgentSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: AgentSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<AgentSessionEntity>)

    /**
     * Drop sessions for a bridge that the bridge no longer reports.
     *
     * Callers pass the ids still present; anything else is a phantom agent
     * that must disappear from the UI. Room expands the collection parameter
     * into `NOT IN (?, ?, …)`, so this must never be called with an empty list
     * (use [deleteAllForBridge] for that case).
     */
    @Query("DELETE FROM agent_sessions WHERE bridgeId = :bridgeId AND id NOT IN (:keepIds)")
    suspend fun deleteMissingForBridge(bridgeId: String, keepIds: List<String>)

    /** The bridge reported no agents at all. */
    @Query("DELETE FROM agent_sessions WHERE bridgeId = :bridgeId")
    suspend fun deleteAllForBridge(bridgeId: String)

    @Query("DELETE FROM agent_sessions")
    suspend fun deleteAll()
}
