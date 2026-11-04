package com.pixelpal.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.pixelpal.app.data.local.db.PixelPalDatabase
import com.pixelpal.app.data.local.db.dao.ActivityEventDao
import com.pixelpal.app.data.local.db.dao.SubtaskDao
import com.pixelpal.app.data.local.db.dao.TaskDao
import com.pixelpal.app.data.local.db.entity.ActivityEventEntity
import com.pixelpal.app.data.local.db.entity.TaskEntity
import com.pixelpal.app.data.remote.firebase.FirestoreSyncEngine
import com.pixelpal.app.domain.model.ActivityType
import com.pixelpal.app.domain.model.Task
import com.pixelpal.app.domain.repository.TaskRepository
import com.pixelpal.app.widget.TasksWidgetProvider
import com.pixelpal.app.widget.HomeWidgetProvider
import com.pixelpal.app.worker.CloudPushScheduler
import com.pixelpal.app.worker.FirestorePushWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: PixelPalDatabase,
    private val taskDao: TaskDao,
    private val subtaskDao: SubtaskDao,
    private val activityEventDao: ActivityEventDao,
    private val syncEngine: FirestoreSyncEngine
) : TaskRepository {

    override fun getTasks(companionId: Long): Flow<List<Task>> {
        return taskDao.getTasks(companionId).map { list -> list.map { it.toDomain() } }
    }

    override fun getTask(taskId: Long): Flow<Task?> {
        return taskDao.getTaskFlow(taskId).map { it?.toDomain() }
    }

    override suspend fun updateTask(task: Task) {
        val existing = taskDao.getTaskById(task.id) ?: return
        val entity = task.copy(id = existing.id)
            .toEntity()
            .copy(
                cloudId = existing.cloudId,
                updatedAt = System.currentTimeMillis()
            )
        taskDao.update(entity)
        syncEngine.pushTaskAsync(entity)
        // Offline-safe retry: unique work + KEEP collapses repeats of the same row.
        CloudPushScheduler.enqueue(context, FirestorePushWorker.TYPE_TASK, entity.cloudId)
    }

    override suspend fun addTask(task: Task): Long {
        val entity = task.toEntity(updatedAt = System.currentTimeMillis())
        val id = taskDao.insert(entity)
        val persisted = entity.copy(id = id)
        // Fire-and-forget for instant UI + WorkManager retry for offline.
        syncEngine.pushTaskAsync(persisted)
        CloudPushScheduler.enqueue(context, FirestorePushWorker.TYPE_TASK, persisted.cloudId)
        TasksWidgetProvider.updateAllWidgets(context)
        HomeWidgetProvider.updateAllWidgets(context)
        return id
    }

    override suspend fun completeTask(task: Task): Boolean {
        if (task.isDone) return false
        val now = System.currentTimeMillis()
        database.withTransaction {
            taskDao.markDone(task.id, now)
            activityEventDao.insert(
                ActivityEventEntity(
                    companionId = task.companionId,
                    type = ActivityType.TASK_COMPLETED.id,
                    title = "Completed \"${task.title}\"",
                    createdAt = now
                )
            )
        }
        pushAndRetry(task.id)
        TasksWidgetProvider.updateAllWidgets(context)
        HomeWidgetProvider.updateAllWidgets(context)
        return true
    }

    override suspend fun toggleTask(task: Task) {
        if (task.isDone) {
            taskDao.markUndone(task.id, System.currentTimeMillis())
            pushAndRetry(task.id)
        } else {
            completeTask(task)
            return
        }
        TasksWidgetProvider.updateAllWidgets(context)
        HomeWidgetProvider.updateAllWidgets(context)
    }

    override suspend fun deleteTask(task: Task) {
        val entity = taskDao.getTaskById(task.id)
        if (entity != null) {
            // Tombstone the child subtasks first, then the task itself: a peer
            // that pulls between the two writes still sees the parent.
            subtaskDao.getByTaskDirect(entity.id).forEach { subtask ->
                syncEngine.deleteSubtaskAsync(subtask.cloudId)
                CloudPushScheduler.enqueue(context, FirestorePushWorker.TYPE_DELETE_SUBTASK, subtask.cloudId)
            }
            taskDao.delete(entity)
            syncEngine.deleteTaskAsync(entity.cloudId)
            CloudPushScheduler.enqueue(context, FirestorePushWorker.TYPE_DELETE_TASK, entity.cloudId)
        } else {
            taskDao.delete(task.toEntity())
        }
        TasksWidgetProvider.updateAllWidgets(context)
        HomeWidgetProvider.updateAllWidgets(context)
    }

    override suspend fun reinsertTask(task: Task): Long {
        val entity = task.toEntity(updatedAt = System.currentTimeMillis())
        val id = taskDao.insert(entity)
        val persisted = entity.copy(id = id)
        syncEngine.pushTaskAsync(persisted)
        CloudPushScheduler.enqueue(context, FirestorePushWorker.TYPE_TASK, persisted.cloudId)
        TasksWidgetProvider.updateAllWidgets(context)
        HomeWidgetProvider.updateAllWidgets(context)
        return id
    }

    /**
     * Instant push (UI snappiness) + retryable queued push (offline safety).
     * The row is re-read so the push carries the DAO's post-write timestamp.
     */
    private suspend fun pushAndRetry(taskId: Long) {
        val entity = taskDao.getTaskById(taskId) ?: return
        syncEngine.pushTaskAsync(entity)
        CloudPushScheduler.enqueue(context, FirestorePushWorker.TYPE_TASK, entity.cloudId)
    }

    private fun TaskEntity.toDomain() = Task(
        id = id,
        companionId = companionId,
        title = title,
        description = description,
        isDone = isDone,
        dueAt = dueAt,
        createdAt = createdAt,
        completedAt = completedAt,
        photoUri = photoUri
    )

    private fun Task.toEntity(updatedAt: Long = 0L) = TaskEntity(
        id = id,
        companionId = companionId,
        title = title,
        description = description,
        isDone = isDone,
        dueAt = dueAt,
        createdAt = createdAt,
        completedAt = completedAt,
        updatedAt = updatedAt,
        photoUri = photoUri
    )
}
