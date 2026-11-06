package com.pixelpal.app.presentation.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.domain.model.AgentState
import com.pixelpal.app.domain.model.Bond
import com.pixelpal.app.domain.model.Companion
import com.pixelpal.app.domain.model.Reminder
import com.pixelpal.app.domain.model.Task
import com.pixelpal.app.domain.repository.ActivityEventRepository
import com.pixelpal.app.domain.repository.BondRepository
import com.pixelpal.app.domain.repository.ReminderRepository
import com.pixelpal.app.domain.repository.TaskRepository
import com.pixelpal.app.domain.usecase.companion.FeedCompanionUseCase
import com.pixelpal.app.domain.usecase.companion.GetActiveCompanionUseCase
import com.pixelpal.app.domain.usecase.companion.TapCompanionUseCase
import com.pixelpal.app.overlay.OverlayService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    getActiveCompanionUseCase: GetActiveCompanionUseCase,
    private val agentSessionRepository: com.pixelpal.app.data.repository.AgentSessionRepository,
    private val activityEventRepository: ActivityEventRepository,
    private val taskRepository: TaskRepository,
    private val reminderRepository: ReminderRepository,
    bondRepository: BondRepository,
    private val tapCompanionUseCase: TapCompanionUseCase,
    private val feedCompanionUseCase: FeedCompanionUseCase,
    private val agentBridgeTransport: com.pixelpal.app.data.remote.bridge.AgentBridgeTransport
) : ViewModel() {




    private val userName = preferencesManager.userName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val overlayEnabled = preferencesManager.overlayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val unreadActivityCount = activityEventRepository.unreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val companion = getActiveCompanionUseCase.activeCompanion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private data class Features(
        val pendingTasks: List<Task> = emptyList(),
        val completedToday: Int = 0,
        val nextReminder: Reminder? = null
    )

    /** Feature queries re-keyed to THE companion's id. */
    private val features = companion.flatMapLatest { c ->
        if (c == null) flowOf(Features())
        else combine(
            taskRepository.getTasks(c.id),
            reminderRepository.getPendingForCompanion(c.id)
        ) { tasks, reminders ->
            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            Features(
                pendingTasks = tasks.filter { !it.isDone },
                completedToday = tasks.count { it.isDone && (it.completedAt ?: 0L) >= startOfDay },
                nextReminder = reminders.minByOrNull { it.triggerTime }
            )
        }
    }

    private val bondFlow = companion.flatMapLatest { c ->
        if (c == null) flowOf(null) else bondRepository.getBond(c.id)
    }

    private val agentFlow = combine(
        agentSessionRepository.sessions,
        preferencesManager.bridgePaired
    ) { sessions, paired -> sessions to paired }
        .map { (sessions, paired) -> summarizeAgents(sessions, paired) }
        .distinctUntilChanged()

    /** Agent card data only — status polls must not redraw the hero. */
    val agentSlice: StateFlow<AgentSlice> = agentFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentSlice())

    /**
     * Everything Home, the Today chip and the widget show about agents comes
     * from the bridge's `agent_sessions` mirror — never from the legacy
     * `agent_connection` config row, which a bridge user never populates.
     */
    private fun summarizeAgents(
        sessions: List<com.pixelpal.app.data.local.db.entity.AgentSessionEntity>,
        paired: Boolean
    ): AgentSlice {
        val working = sessions.count { AgentState.fromId(it.status) == AgentState.WORKING }
        val waiting = sessions.count { AgentState.fromId(it.status) == AgentState.WAITING_FOR_INPUT }
        val errors = sessions.count { AgentState.fromId(it.status) == AgentState.ERROR }

        val headline: String
        val detail: String?
        when {
            !paired -> {
                headline = "Not connected"
                detail = null
            }
            sessions.isEmpty() -> {
                headline = "Idle"
                detail = null
            }
            waiting > 0 -> {
                headline = if (waiting == 1) "1 waiting for you" else "$waiting waiting for you"
                detail = sessions.first { AgentState.fromId(it.status) == AgentState.WAITING_FOR_INPUT }
                    .let { s -> s.agentName.ifBlank { s.source } + " · " + s.project }.trim(' ', '·')
            }
            errors > 0 -> {
                headline = "Agent needs attention"
                detail = sessions.first { AgentState.fromId(it.status) == AgentState.ERROR }.message
            }
            working > 0 -> {
                headline = if (working == 1) "Working" else "$working agents working"
                val active = sessions.first { AgentState.fromId(it.status) == AgentState.WORKING }
                detail = active.currentTask?.takeIf { it.isNotBlank() } ?: active.project
            }
            else -> {
                headline = "All idle"
                detail = null
            }
        }

        val waitingSession = sessions.firstOrNull { AgentState.fromId(it.status) == AgentState.WAITING_FOR_INPUT }

        return AgentSlice(
            paired = paired,
            total = sessions.size,
            working = working,
            waiting = waiting,
            attention = waiting > 0 || errors > 0,
            headline = headline,
            detail = detail,
            progress = sessions.firstOrNull { AgentState.fromId(it.status) == AgentState.WORKING }?.progress,
            pendingApprovalId = waitingSession?.pendingApprovalId,
            pendingApprovalAction = waitingSession?.pendingApprovalAction ?: waitingSession?.currentTask
        )
    }


    // ---- Scoped slices (perf): HomeScreen collects one slice per section
    // so a change in one area (e.g. unread badge from a background worker
    // poll, agent status) does NOT recompose the Lottie hero and the whole
    // screen. Each slice is distinctUntilChanged — collectors wake only when
    // their own data actually changes.

    /** False after the first companion emission (even null = known-empty). */
    val isLoading: StateFlow<Boolean> = companion
        .map { false }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val hasCompanion: StateFlow<Boolean> = companion
        .map { it != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    data class HeaderSlice(val userName: String = "", val unreadCount: Int = 0)

    val headerSlice: StateFlow<HeaderSlice> = combine(userName, unreadActivityCount) { user, unread ->
        HeaderSlice(userName = user, unreadCount = unread)
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeaderSlice())

    data class HeroSlice(val companion: Companion? = null, val bond: Bond? = null)

    val heroSlice: StateFlow<HeroSlice> = combine(companion, bondFlow) { c, bond ->
        HeroSlice(companion = c, bond = bond)
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeroSlice())

    /**
     * What Home, the Today chip and the widget show about agents. Derived from
     * the bridge's `agent_sessions` mirror, never from the legacy
     * `agent_connection` config row (which a bridge user never populates).
     */
    data class AgentSlice(
        val paired: Boolean = false,
        val total: Int = 0,
        val working: Int = 0,
        val waiting: Int = 0,
        val attention: Boolean = false,
        val headline: String = "No agent connected",
        val detail: String? = null,
        val progress: Int? = null,
        val pendingApprovalId: String? = null,
        val pendingApprovalAction: String? = null
    )

    data class TodaySlice(
        val pendingCount: Int = 0,
        val completedToday: Int = 0,
        val hasUpcomingReminder: Boolean = false,
        val pendingTasks: List<Task> = emptyList(),
        val firstTaskTitle: String? = null
    )

    val todaySlice: StateFlow<TodaySlice> = features
        .map { f ->
            TodaySlice(
                pendingCount = f.pendingTasks.size,
                completedToday = f.completedToday,
                hasUpcomingReminder = f.nextReminder != null,
                pendingTasks = f.pendingTasks,
                firstTaskTitle = f.pendingTasks.firstOrNull()?.title
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodaySlice())

    val weeklyActivity: StateFlow<com.pixelpal.app.domain.model.WeeklyActivity> = combine(
        bondFlow,
        activityEventRepository.getCenterEvents(100)
    ) { bond, events ->
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val streak = bond?.streakDays ?: 0
        val tapsToday = bond?.tapsToday ?: 0

        // Map Calendar day of week to 0..6 (Mon=0, Tue=1, Wed=2, Thu=3, Fri=4, Sat=5, Sun=6)
        val todayIndex = when (dayOfWeek) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6 // Calendar.SUNDAY
        }

        val mondayCal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val mondayMillis = mondayCal.timeInMillis
        val oneDayMillis = TimeUnit.DAYS.toMillis(1)

        val countsPerDay = IntArray(7)
        for (event in events) {
            val diff = event.createdAt - mondayMillis
            if (diff >= 0) {
                val dayIdx = (diff / oneDayMillis).toInt()
                if (dayIdx in 0..6) {
                    countsPerDay[dayIdx]++
                }
            }
        }

        val dayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val baseHeights = listOf(0.65f, 0.85f, 0.70f, 0.90f, 0.80f, 0.60f, 0.50f)

        val effectiveStreak = if (streak > 0) streak else if (tapsToday > 0) 1 else 0
        val streakStart = (todayIndex - effectiveStreak + 1).coerceAtLeast(0)

        val days = dayLabels.mapIndexed { idx, label ->
            val isToday = (idx == todayIndex)
            val inStreak = effectiveStreak > 0 && idx in streakStart..todayIndex
            val realCount = countsPerDay[idx]
            val hasActivity = inStreak || realCount > 0 || (isToday && (tapsToday > 0 || streak > 0))

            val intensity = when {
                realCount > 0 -> (0.45f + (realCount * 0.15f)).coerceAtMost(0.95f)
                isToday && inStreak -> 0.88f
                inStreak -> baseHeights[idx % baseHeights.size]
                isToday -> 0.40f
                else -> 0.22f
            }

            com.pixelpal.app.domain.model.DayActivity(
                dayLabel = label,
                intensity = intensity,
                isToday = isToday,
                isActive = hasActivity
            )
        }
        com.pixelpal.app.domain.model.WeeklyActivity(days)
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.pixelpal.app.domain.model.WeeklyActivity())

    fun approveAgentApproval(approvalId: String) {
        viewModelScope.launch {
            val delivered = agentBridgeTransport.respondToApproval(
                preferencesManager.getAgentBridgeUrl(),
                approvalId,
                approved = true
            )
            if (delivered) activityEventRepository.record(
                companion.value?.id ?: return@launch,
                com.pixelpal.app.domain.model.ActivityType.AGENT_APPROVAL_DECIDED,
                "Approved agent request",
                approvalId.take(80)
            )
        }
    }

    fun denyAgentApproval(approvalId: String) {
        viewModelScope.launch {
            val delivered = agentBridgeTransport.respondToApproval(
                preferencesManager.getAgentBridgeUrl(),
                approvalId,
                approved = false
            )
            if (delivered) activityEventRepository.record(
                companion.value?.id ?: return@launch,
                com.pixelpal.app.domain.model.ActivityType.AGENT_APPROVAL_DECIDED,
                "Denied agent request",
                approvalId.take(80)
            )
        }
    }

    data class OverlaySlice(val enabled: Boolean = true, val companionName: String = "Pixel")

    val overlaySlice: StateFlow<OverlaySlice> = combine(overlayEnabled, companion) { enabled, c ->
        OverlaySlice(enabled = enabled, companionName = c?.name ?: "Pixel")
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverlaySlice())


    /** Casual interaction with THE companion. */
    fun interactWithCompanion() {
        viewModelScope.launch { tapCompanionUseCase() }
    }

    fun toggleTask(task: Task) {
        viewModelScope.launch {
            taskRepository.toggleTask(task)
        }
    }

    /** Cosmetic-only interaction (no bond inflation by design). */
    fun feedCompanion() {
        viewModelScope.launch { feedCompanionUseCase() }
    }

    fun toggleOverlay(context: Context) {
        viewModelScope.launch {
            val next = !overlayEnabled.value
            preferencesManager.setOverlayEnabled(next)
            if (next) {
                OverlayService.start(context)
            } else {
                OverlayService.stop(context)
            }
        }
    }
}
