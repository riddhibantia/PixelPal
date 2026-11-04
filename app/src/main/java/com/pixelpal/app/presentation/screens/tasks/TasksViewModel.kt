package com.pixelpal.app.presentation.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.domain.model.Subtask
import com.pixelpal.app.domain.model.Task
import com.pixelpal.app.domain.repository.SubtaskRepository
import com.pixelpal.app.domain.repository.TaskRepository
import com.pixelpal.app.domain.usecase.companion.GetActiveCompanionUseCase
import com.pixelpal.app.domain.usecase.task.CompleteTaskUseCase
import com.pixelpal.app.presentation.components.SnackbarEvent
import com.pixelpal.app.worker.TaskDueScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TasksUiState(
    val items: List<TaskWithSubtasks> = emptyList(),
    val loading: Boolean = true
)

/**
 * The deleted-task payload kept between a swipe-delete and its undo tap. The
 * [Task] is the pre-delete snapshot ([Task.id] is the now-gone Room row id);
 * [subtasks] are captured because Room cascades their deletion with the task.
 */
data class PendingTaskDelete(
    val task: Task,
    val subtasks: List<Subtask>
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val getActiveCompanionUseCase: GetActiveCompanionUseCase,
    private val activeCompanionManager: com.pixelpal.app.domain.engine.ActiveCompanionManager,
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val completeTaskUseCase: CompleteTaskUseCase,
    private val preferencesManager: PreferencesManager,
    private val taskDueScheduler: TaskDueScheduler
) : ViewModel() {

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    private val activeCompanion = getActiveCompanionUseCase.activeCompanion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** Exposed so empty states render the active companion's species mascot. */
    val companion: StateFlow<com.pixelpal.app.domain.model.Companion?> = activeCompanion

    private var pendingDelete: PendingTaskDelete? = null

    private val tasks = activeCompanion.flatMapLatest { companion ->
        if (companion == null) flowOf(emptyList()) else taskRepository.getTasks(companion.id)
    }

    private val tasksWithSubtasks = tasks.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList())
        else combine(
            list.map { task ->
                subtaskRepository.getByTask(task.id).map { subtasks ->
                    TaskWithSubtasks(task, subtasks)
                }
            }
        ) { pairs -> pairs.toList() }
    }

    val uiState: StateFlow<TasksUiState> = tasksWithSubtasks
        .map { items -> TasksUiState(items = items, loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    /** Home-screen widget opt-in (moved from the workspace). */
    val tasksWidgetEnabled: StateFlow<Boolean> = preferencesManager.tasksWidgetEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setTasksWidgetEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setTasksWidgetEnabled(enabled) }
    }

    /**
     * Creates the task from the New Task screen, then its subtasks in order.
     * Uses a direct DB read so it never silently drops when the StateFlow hasn't emitted yet.
     */
    fun createTask(
        title: String,
        description: String?,
        subtaskTitles: List<String>,
        dueAt: Long? = null
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val companionId = activeCompanion.value?.id
                ?: activeCompanionManager.getActiveCompanionDirect()?.id
                ?: return@launch
            val newTask = Task(
                companionId = companionId,
                title = title.trim(),
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                dueAt = dueAt
            )
            val id = taskRepository.addTask(newTask)
            subtaskTitles.filter { it.isNotBlank() }.forEach { subtaskRepository.add(id, it) }
            // Row id is only known now; schedule under the (fresh) due work name.
            if (dueAt != null && !newTask.isDone) {
                taskDueScheduler.schedule(newTask.copy(id = id))
            }
        }
    }

    fun toggleTask(task: Task) {
        viewModelScope.launch {
            if (task.isDone) {
                // Untick: flip local+cloud state without a second bond reward.
                taskRepository.toggleTask(task)
                // Re-armed only if the due time is still in the future.
                taskDueScheduler.schedule(task.copy(isDone = false))
            } else {
                completeTaskUseCase(task)
                taskDueScheduler.cancel(task)
            }
        }
    }

    /**
     * Deletes the task and emits [SnackbarEvent.TaskDeleted] so the screen can
     * offer Undo. The snapshot (task + its subtasks) is held in
     * [pendingDelete] until [undoDeleteTask] runs or another delete replaces it.
     */
    fun deleteTask(task: Task) {
        viewModelScope.launch {
            // Snapshot subtasks BEFORE the delete: Room's FK cascade removes them
            // with the task and SubtaskRepository has no direct (non-Flow) read.
            val subtasks = runCatching { subtaskRepository.getByTask(task.id).first() }
                .getOrDefault(emptyList())
            pendingDelete = PendingTaskDelete(task = task, subtasks = subtasks)
            taskDueScheduler.cancel(task)
            taskRepository.deleteTask(task)
            _snackbarEvents.emit(SnackbarEvent.TaskDeleted(task))
        }
    }

    /**
     * Restores the most recently deleted task and its subtasks.
     *
     * Note: [TaskRepository.reinsertTask] inserts a fresh row (new Room id, new
     * cloudId) — acceptable, since the original cloud doc was already deleted
     * and the reinsert re-pushes under a new doc. Title/description/dueAt/
     * isDone/photo are preserved from the snapshot.
     *
     * Subtasks are re-added in their original order (sortOrder is assigned by
     * insertion order), then any that were ticked are re-ticked so the
     * checklist comes back exactly as it was.
     */
    fun undoDeleteTask() {
        val pending = pendingDelete ?: return
        pendingDelete = null
        viewModelScope.launch {
            val newId = taskRepository.reinsertTask(pending.task)
            pending.subtasks.forEach { subtask -> subtaskRepository.add(newId, subtask.title) }
            val doneTitles = pending.subtasks.filter { it.isDone }.map { it.title }.toMutableList()
            if (doneTitles.isNotEmpty()) {
                subtaskRepository.getByTask(newId).first().forEach { restored ->
                    if (doneTitles.remove(restored.title)) subtaskRepository.toggle(restored)
                }
            }
            taskDueScheduler.schedule(pending.task.copy(id = newId))
        }
    }

    fun updateTask(task: Task) {
        viewModelScope.launch {
            taskRepository.updateTask(task)
            taskDueScheduler.schedule(task)
        }
    }

    fun addSubtask(task: Task, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { subtaskRepository.add(task.id, title) }
    }

    fun toggleSubtask(subtask: Subtask) {
        viewModelScope.launch { subtaskRepository.toggle(subtask) }
    }

    fun deleteSubtask(subtask: Subtask) {
        viewModelScope.launch { subtaskRepository.delete(subtask) }
    }
}
