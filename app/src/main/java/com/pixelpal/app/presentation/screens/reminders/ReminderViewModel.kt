package com.pixelpal.app.presentation.screens.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpal.app.domain.model.Reminder
import com.pixelpal.app.domain.model.ReminderCategory
import com.pixelpal.app.domain.model.ReminderSound
import com.pixelpal.app.domain.usecase.companion.GetActiveCompanionUseCase
import com.pixelpal.app.domain.usecase.reminder.CompleteReminderUseCase
import com.pixelpal.app.domain.usecase.reminder.CreateReminderUseCase
import com.pixelpal.app.domain.usecase.reminder.GetRemindersUseCase
import com.pixelpal.app.domain.repository.ReminderRepository
import com.pixelpal.app.presentation.components.SnackbarEvent
import com.pixelpal.app.worker.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reminders belong to THE single companion — created against the primary id.
 */
@HiltViewModel
class ReminderViewModel @Inject constructor(
    private val getActiveCompanionUseCase: GetActiveCompanionUseCase,
    getRemindersUseCase: GetRemindersUseCase,
    private val createReminderUseCase: CreateReminderUseCase,
    private val completeReminderUseCase: CompleteReminderUseCase,
    private val reminderRepository: ReminderRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    private val _reminderCreated = Channel<Boolean>()
    val reminderCreated = _reminderCreated.receiveAsFlow()

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>()
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /** Category chip row selection in the list screen; null = all categories. */
    private val _categoryFilter = MutableStateFlow<ReminderCategory?>(null)
    val categoryFilter: StateFlow<ReminderCategory?> = _categoryFilter

    val pendingReminders: StateFlow<List<Reminder>> = combine(
        getRemindersUseCase.getPendingReminders(),
        _categoryFilter
    ) { reminders, filter ->
        if (filter == null) reminders else reminders.filter { ReminderCategory.fromId(it.category) == filter }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val completedReminders: StateFlow<List<Reminder>> = combine(
        getRemindersUseCase.getCompletedReminders(),
        _categoryFilter
    ) { reminders, filter ->
        if (filter == null) reminders else reminders.filter { ReminderCategory.fromId(it.category) == filter }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Exposed so empty states render the active companion's species mascot. */
    val companion = getActiveCompanionUseCase.activeCompanion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setCategoryFilter(category: ReminderCategory?) {
        _categoryFilter.value = category
    }

    /**
     * @param soundUri a picked ringtone URI, [ReminderSound.NONE]'s sentinel via
     *   [silent], or null for the legacy "system default" behavior.
     */
    fun createReminder(
        title: String,
        message: String?,
        triggerTime: Long,
        hour: Int,
        minute: Int,
        soundUri: String?,
        category: ReminderCategory = ReminderCategory.CUSTOM,
        sound: ReminderSound? = null
    ) {
        viewModelScope.launch {
            val companionId = getActiveCompanionUseCase.activeCompanion.first()?.id
            val storedSoundUri = when {
                // An explicit logical sound (e.g. Silent) wins over any picked URI.
                sound == ReminderSound.NONE -> ReminderSound.SILENT_URI_TOKEN
                soundUri != null -> soundUri
                else -> null
            }
            val reminder = Reminder(
                title = title,
                message = message?.trim()?.takeIf { it.isNotEmpty() },
                triggerTime = triggerTime,
                hour = hour,
                minute = minute,
                soundUri = storedSoundUri,
                category = category.id,
                companionId = companionId
            )
            createReminderUseCase(reminder)
            _reminderCreated.send(true)
        }
    }

    fun completeReminder(id: Long) {
        viewModelScope.launch {
            completeReminderUseCase(id)
        }
    }

    fun deleteReminder(reminder: Reminder) {
        viewModelScope.launch {
            reminderScheduler.cancelReminder(reminder.id)
            reminderRepository.delete(reminder)
            _snackbarEvents.emit(SnackbarEvent.ReminderDeleted(reminder))
        }
    }

    fun undoDeleteReminder(reminder: Reminder) {
        viewModelScope.launch {
            reminderRepository.insert(reminder)
            if (reminder.triggerTime > System.currentTimeMillis()) {
                reminderScheduler.scheduleReminder(reminder)
            }
        }
    }
}
