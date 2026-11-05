package com.pixelpal.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pixelpal.app.data.local.db.dao.ActivityEventDao
import com.pixelpal.app.domain.engine.DailyInteractionStats
import com.pixelpal.app.domain.engine.PersonalityEngine
import com.pixelpal.app.domain.model.ActivityType
import com.pixelpal.app.domain.repository.BondRepository
import com.pixelpal.app.domain.repository.CompanionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import timber.log.Timber

@HiltWorker
class PersonalityWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val personalityEngine: PersonalityEngine,
    private val bondRepository: BondRepository,
    private val companionRepository: CompanionRepository,
    private val activityEventDao: ActivityEventDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            companionRepository.getPrimaryDirect()?.let { companion ->
                val bond = bondRepository.getBondDirect(companion.id)

                // Real inputs, not placeholders: reminder outcomes come from
                // today's activity rows, and "morning/late-night" comes from the
                // hour of the last recorded interaction.
                val zone = ZoneId.systemDefault()
                val startOfToday = LocalDate.now(zone)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
                val todayEvents = activityEventDao.getSince(companion.id, startOfToday)
                val completed = todayEvents.count {
                    it.type == ActivityType.REMINDER_COMPLETED.id
                }
                val missed = todayEvents.count {
                    it.type == ActivityType.MISSED_REMINDER.id
                }
                val lastInteractionHour = bond.lastInteractionTime
                    .takeIf { it > 0L }
                    ?.let { Instant.ofEpochMilli(it).atZone(zone).hour }

                val stats = DailyInteractionStats(
                    tapCount = bond.tapsToday,
                    feedCount = bond.feedsToday,
                    remindersCompleted = completed,
                    remindersIgnored = missed,
                    isLateNightActive = lastInteractionHour != null && lastInteractionHour < LATE_NIGHT_ENDS_HOUR,
                    isMorningActive = lastInteractionHour != null &&
                        lastInteractionHour in MORNING_STARTS_HOUR until MORNING_ENDS_HOUR
                )
                personalityEngine.recalculateDaily(companion.id, stats)
            }
            Timber.d("Personality recalculated from real daily interaction stats")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Error recalculating personality in worker")
            Result.failure()
        }
    }

    private companion object {
        /** 00:00–04:59 counts as late-night activity. */
        const val LATE_NIGHT_ENDS_HOUR = 5

        /** 05:00–10:59 counts as morning activity. */
        const val MORNING_STARTS_HOUR = 5
        const val MORNING_ENDS_HOUR = 11
    }
}
