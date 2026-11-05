package com.pixelpal.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.pixelpal.app.data.local.db.entity.ActivityEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityEventDao {
    @Query("SELECT * FROM activity_events WHERE companionId = :companionId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun getForCompanion(companionId: Long, limit: Int): Flow<List<ActivityEventEntity>>

    @Query("SELECT * FROM activity_events ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<ActivityEventEntity>>

    /**
     * Activity Center source: reverse-chronological meaningful events only.
     * Legacy TAP/FEED rows are excluded so interaction noise never floods the feed.
     */
    @Query(
        "SELECT * FROM activity_events " +
            "WHERE type NOT IN ('TAP', 'FEED') " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    fun getCenterEvents(limit: Int): Flow<List<ActivityEventEntity>>

    @Query(
        "SELECT * FROM activity_events " +
            "WHERE companionId = :companionId AND type NOT IN ('TAP', 'FEED') " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    fun getCenterEventsForCompanion(companionId: Long, limit: Int): Flow<List<ActivityEventEntity>>

    @Query(
        "SELECT COUNT(*) FROM activity_events " +
            "WHERE isRead = 0 AND type NOT IN ('TAP', 'FEED')"
    )
    fun unreadCount(): Flow<Int>

    @Query("UPDATE activity_events SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllRead()

    @Query("SELECT COUNT(*) FROM activity_events WHERE companionId = :companionId")
    suspend fun countForCompanion(companionId: Long): Int

    /**
     * Everything a companion did since [since] (epoch millis), oldest first.
     * PersonalityWorker derives its daily interaction stats from this instead of
     * inventing them: reminder outcomes and activity hours are real rows.
     */
    @Query(
        "SELECT * FROM activity_events " +
            "WHERE companionId = :companionId AND createdAt >= :since " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun getSince(companionId: Long, since: Long): List<ActivityEventEntity>

    @Insert
    suspend fun insert(event: ActivityEventEntity)
}