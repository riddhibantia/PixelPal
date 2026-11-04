package com.pixelpal.app.data.repository

import android.content.Context
import com.pixelpal.app.data.local.db.dao.ReminderDao
import com.pixelpal.app.data.local.db.entity.ReminderEntity
import com.pixelpal.app.data.remote.firebase.FirestoreSyncEngine
import com.pixelpal.app.domain.model.Reminder
import com.pixelpal.app.domain.repository.ReminderRepository
import com.pixelpal.app.worker.CloudPushScheduler
import com.pixelpal.app.worker.FirestorePushWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ReminderDao,
    private val syncEngine: FirestoreSyncEngine
) : ReminderRepository {

    override fun getPendingReminders(): Flow<List<Reminder>> {
        return dao.getPendingReminders().map { list -> list.map { it.toDomain() } }
    }

    override fun getPendingForCompanion(companionId: Long): Flow<List<Reminder>> {
        return dao.getPendingForCompanion(companionId).map { list -> list.map { it.toDomain() } }
    }

    override fun getCompletedReminders(): Flow<List<Reminder>> {
        return dao.getCompletedReminders().map { list -> list.map { it.toDomain() } }
    }

    override fun getAllReminders(): Flow<List<Reminder>> {
        return dao.getAllReminders().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getById(id: Long): Reminder? {
        return dao.getReminderById(id)?.toDomain()
    }

    override suspend fun insert(reminder: Reminder): Long {
        val entity = reminder.toEntity(updatedAt = System.currentTimeMillis())
        val id = dao.insert(entity)
        val persisted = entity.copy(id = id)
        syncEngine.pushReminderAsync(persisted)
        // Offline-safe retry so a reminder created without network still lands.
        CloudPushScheduler.enqueue(context, FirestorePushWorker.TYPE_REMINDER, persisted.cloudId)
        return id
    }

    override suspend fun update(reminder: Reminder) {
        // Preserve the existing cloudId so the update lands on the same cloud doc.
        val entity = dao.getReminderById(reminder.id)
            ?.let { reminder.toEntity(cloudId = it.cloudId) }
            ?.copy(updatedAt = System.currentTimeMillis())
            ?: reminder.toEntity(updatedAt = System.currentTimeMillis())
        dao.update(entity)
        pushAndRetry(entity)
    }

    override suspend fun delete(reminder: Reminder) {
        val entity = dao.getReminderById(reminder.id)
        if (entity != null) {
            dao.delete(entity)
            // Soft delete: a tombstone is written instead of removing the doc.
            syncEngine.deleteReminderAsync(entity.cloudId)
            CloudPushScheduler.enqueue(context, FirestorePushWorker.TYPE_DELETE_REMINDER, entity.cloudId)
        } else {
            dao.delete(reminder.toEntity())
        }
    }

    override suspend fun complete(id: Long) {
        val now = System.currentTimeMillis()
        dao.updateStatus(id, "COMPLETED", now, now)
        pushAndRetry(id)
    }

    override suspend fun snooze(id: Long, newTriggerTime: Long) {
        val now = System.currentTimeMillis()
        dao.snooze(id, newTriggerTime, now)
        pushAndRetry(id)
    }

    /**
     * Instant push (UI snappiness) + retryable queued push (offline safety).
     * Unique work + KEEP means repeated snoozes/edits collapse into one push.
     */
    private suspend fun pushAndRetry(id: Long) {
        val entity = dao.getReminderById(id) ?: return
        pushAndRetry(entity)
    }

    private fun pushAndRetry(entity: ReminderEntity) {
        syncEngine.pushReminderAsync(entity)
        CloudPushScheduler.enqueue(context, FirestorePushWorker.TYPE_REMINDER, entity.cloudId)
    }

    private fun ReminderEntity.toDomain() = Reminder(
        id = id,
        title = title,
        message = message,
        triggerTime = triggerTime,
        hour = hour,
        minute = minute,
        soundUri = soundUri,
        recurrence = recurrence,
        recurrenceInterval = recurrenceInterval,
        category = category,
        status = status,
        snoozeCount = snoozeCount,
        createdAt = createdAt,
        completedAt = completedAt,
        companionId = companionId
    )

    private fun Reminder.toEntity(
        updatedAt: Long = 0L,
        cloudId: String = java.util.UUID.randomUUID().toString()
    ) = ReminderEntity(
        id = id,
        title = title,
        message = message,
        triggerTime = triggerTime,
        hour = hour,
        minute = minute,
        soundUri = soundUri,
        recurrence = recurrence,
        recurrenceInterval = recurrenceInterval,
        category = category,
        status = status,
        snoozeCount = snoozeCount,
        createdAt = createdAt,
        completedAt = completedAt,
        companionId = companionId,
        updatedAt = updatedAt,
        cloudId = cloudId
    )
}
