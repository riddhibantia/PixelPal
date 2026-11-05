package com.pixelpal.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pixelpal.app.R
import com.pixelpal.app.data.local.db.PixelPalDatabase
import com.pixelpal.app.domain.model.Task
import com.pixelpal.app.domain.repository.TaskRepository
import com.pixelpal.app.domain.usecase.task.CompleteTaskUseCase
import com.pixelpal.app.presentation.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Hilt access for widget code. App widgets are not `@AndroidEntryPoint`
 * components, so everything the widget needs is pulled through a single entry
 * point instead of being re-implemented inline.
 *
 * Deliberately requests [CompleteTaskUseCase] (not the DAOs/BondEngine
 * separately): completing a task from the widget must be *exactly* the in-app
 * flow — mark done + TASK_COMPLETED activity (TaskRepositoryImpl.completeTask,
 * transactional) + bond reward through BondEngine.recordTaskCompleted (the
 * single source of bond rules) + widget refresh + cloud push.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TasksWidgetEntryPoint {
    fun database(): PixelPalDatabase
    fun taskRepository(): TaskRepository
    fun completeTaskUseCase(): CompleteTaskUseCase
}

class TasksWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_TASK = "com.pixelpal.app.widget.TOGGLE_TASK"
        const val EXTRA_TASK_ID = "task_id"

        /** Refreshes the task list widget AND the companion home widget. */
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TasksWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, TasksWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
            // Also update home widget
            HomeWidgetProvider.updateAllWidgets(context)
        }

        /** Application-scoped worker scope: outlives any single provider instance. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_TASK) {
            // Own the whole broadcast: every widget instance shares this
            // PendingIntent, so the coroutine must complete inside
            // goAsync()/finish() — otherwise the toggle and its bond award
            // are lost whenever the (cold, widget-only) process dies.
            val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
            if (taskId == -1L) {
                super.onReceive(context, intent)
                return
            }
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    handleToggle(context, taskId)
                } catch (e: Exception) {
                    Timber.e(e, "TasksWidget: toggle failed for task $taskId")
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * Mirrors in-app completion precisely:
     *  - pending task → [CompleteTaskUseCase]: mark done + TASK_COMPLETED
     *    activity (TaskRepositoryImpl.completeTask, transactional) + bond via
     *    BondEngine.recordTaskCompleted + cloud push.
     *  - already-done task → un-complete only (no bond, no activity) — exactly
     *    what [TaskRepository.toggleTask] does for the in-app checkbox.
     * Widgets are refreshed in all cases.
     */
    private suspend fun handleToggle(context: Context, taskId: Long) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, TasksWidgetEntryPoint::class.java
        )
        val db = entryPoint.database()
        val taskRepository = entryPoint.taskRepository()
        val completeTaskUseCase = entryPoint.completeTaskUseCase()

        // Tasks are shown per companion but toggled by device-local row id.
        val entity = db.taskDao().getTaskById(taskId)
        if (entity == null) {
            Timber.w("TasksWidget: task $taskId no longer exists, refreshing widgets only")
            updateAllWidgets(context)
            return
        }
        val task = Task(
            id = entity.id,
            companionId = entity.companionId,
            title = entity.title,
            description = entity.description,
            isDone = entity.isDone,
            dueAt = entity.dueAt,
            createdAt = entity.createdAt,
            completedAt = entity.completedAt,
            photoUri = entity.photoUri
        )

        if (task.isDone) {
            // Undo: no bond, no activity — TaskRepositoryImpl.toggleTask's
            // documented "not done" branch.
            taskRepository.toggleTask(task)
        } else {
            // Completion goes through the SAME use case the app uses so the
            // widget can never drift from in-app bond/activity semantics.
            completeTaskUseCase(task)
        }
        Timber.d("TasksWidget: toggled task ${task.id} (wasDone=${task.isDone})")

        // The repository/use-case paths already refresh widgets; this is the
        // safety net for the exception paths above.
        updateAllWidgets(context)
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        Timber.d("TasksWidget: onUpdate called for widget $appWidgetId")
        scope.launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext, TasksWidgetEntryPoint::class.java
                )
                val db = entryPoint.database()
                val companion = db.companionDao().getPrimaryDirect()
                Timber.d("TasksWidget: companion loaded: ${companion?.name} id=${companion?.id}")
                val companionId = companion?.id ?: run {
                    Timber.w("TasksWidget: no companion found, showing empty state for widget $appWidgetId")
                    val views = RemoteViews(context.packageName, R.layout.widget_pixelpal_tasks)
                    for (i in 0..5) views.setViewVisibility(getRowId(i), android.view.View.GONE)
                    views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
                    views.setTextViewText(R.id.widget_empty, "No companion yet.\nOpen PixelPal!")
                    views.setViewVisibility(R.id.widget_summary, android.view.View.GONE)
                    manager.updateAppWidget(appWidgetId, views)
                    return@launch
                }

                // Use direct DB query to avoid Flow
                val cursor = db.openHelper.readableDatabase.query(
                    "SELECT id, title, isDone FROM tasks WHERE companionId = $companionId ORDER BY isDone ASC, createdAt DESC LIMIT 6"
                )
                val tasks = mutableListOf<Triple<Long, String, Boolean>>()
                while (cursor.moveToNext()) {
                    tasks.add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getInt(2) != 0))
                }
                cursor.close()
                Timber.d("TasksWidget: found ${tasks.size} tasks for companion $companionId for widget $appWidgetId")

                val views = RemoteViews(context.packageName, R.layout.widget_pixelpal_tasks)

                // Add task button -> open app
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val openPending = PendingIntent.getActivity(
                    context, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_add_task, openPending)

                if (tasks.isEmpty()) {
                    for (i in 0..5) {
                        views.setViewVisibility(getRowId(i), android.view.View.GONE)
                    }
                    views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
                    views.setViewVisibility(R.id.widget_summary, android.view.View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_empty, android.view.View.GONE)
                    val pendingCount = tasks.count { !it.third }
                    views.setViewVisibility(R.id.widget_summary, android.view.View.VISIBLE)
                    views.setTextViewText(R.id.widget_summary, "$pendingCount tasks remaining")

                    for (i in 0..5) {
                        if (i < tasks.size) {
                            val (taskId, title, isDone) = tasks[i]
                            views.setViewVisibility(getRowId(i), android.view.View.VISIBLE)
                            views.setTextViewText(getTitleId(i), title)
                            views.setTextViewText(getCheckboxId(i), if (isDone) "✓" else "")
                            views.setInt(
                                getCheckboxId(i),
                                "setBackgroundResource",
                                if (isDone) R.drawable.widget_checkbox_checked
                                else R.drawable.widget_checkbox_background
                            )
                            // Dim completed tasks via text color
                            views.setTextColor(getCheckboxId(i), android.graphics.Color.parseColor("#7653C8"))
                            views.setTextColor(getTitleId(i), if (isDone) android.graphics.Color.parseColor("#806B5B87") else android.graphics.Color.parseColor("#342653"))

                            val toggleIntent = Intent(context, TasksWidgetProvider::class.java).apply {
                                action = ACTION_TOGGLE_TASK
                                putExtra(EXTRA_TASK_ID, taskId)
                            }
                            val togglePending = PendingIntent.getBroadcast(
                                context, taskId.toInt(), toggleIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            views.setOnClickPendingIntent(getCheckboxId(i), togglePending)
                            // Also make the title clickable — the row itself is an
                            // open-app target so the widget's main click still works.
                            views.setOnClickPendingIntent(getTitleId(i), togglePending)
                        } else {
                            views.setViewVisibility(getRowId(i), android.view.View.GONE)
                        }
                    }
                }

                manager.updateAppWidget(appWidgetId, views)
                Timber.d("TasksWidget: RemoteViews update succeeded for widget $appWidgetId")
            } catch (e: Exception) {
                Timber.e(e, "TasksWidget: update failed for widget $appWidgetId")
            }
        }
    }

    private fun getRowId(index: Int): Int = when (index) {
        0 -> R.id.widget_task_row_0
        1 -> R.id.widget_task_row_1
        2 -> R.id.widget_task_row_2
        3 -> R.id.widget_task_row_3
        4 -> R.id.widget_task_row_4
        else -> R.id.widget_task_row_5
    }

    private fun getCheckboxId(index: Int): Int = when (index) {
        0 -> R.id.widget_task_checkbox_0
        1 -> R.id.widget_task_checkbox_1
        2 -> R.id.widget_task_checkbox_2
        3 -> R.id.widget_task_checkbox_3
        4 -> R.id.widget_task_checkbox_4
        else -> R.id.widget_task_checkbox_5
    }

    private fun getTitleId(index: Int): Int = when (index) {
        0 -> R.id.widget_task_title_0
        1 -> R.id.widget_task_title_1
        2 -> R.id.widget_task_title_2
        3 -> R.id.widget_task_title_3
        4 -> R.id.widget_task_title_4
        else -> R.id.widget_task_title_5
    }
}
