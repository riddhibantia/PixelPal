package com.pixelpal.app.domain.engine

import java.util.Calendar

/**
 * When a reminder fires next.
 *
 * Pure and JVM-testable on purpose: the phone's clock, DST and short months are
 * exactly the things that break silently, and `ReminderScheduler` cannot be
 * constructed outside Android. Series are always re-anchored to the time the
 * user picked, never to "now plus a period".
 */
object RecurrenceMath {

    /**
     * @param recurrence ONCE / DAILY / WEEKLY / MONTHLY.
     * @param intervalMs custom period; when set (> 0) it wins over [recurrence].
     * @param dayOfMonth anchor day for MONTHLY series, 1–31; null = infer from
     *   [triggerTime]. A 31st anchor clamps to Feb 28 and returns to Mar 31.
     * @return the next trigger strictly after [from], or null for one-shots.
     */
    fun nextTriggerTime(
        triggerTime: Long,
        hour: Int,
        minute: Int,
        recurrence: String,
        intervalMs: Long? = null,
        dayOfMonth: Int? = null,
        from: Long = System.currentTimeMillis()
    ): Long? {
        if (intervalMs != null && intervalMs > 0) {
            var next = triggerTime
            // Guard against a pathologically small interval turning this into a
            // spin: jump whole periods instead of looping.
            if (next <= from) {
                val periods = (from - next) / intervalMs + 1
                next += periods * intervalMs
            }
            return next
        }

        return when (recurrence) {
            "DAILY", "WEEKLY", "MONTHLY" -> {
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = triggerTime
                    if (hour > 0 || minute > 0) {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                }
                when (recurrence) {
                    "DAILY" -> while (calendar.timeInMillis <= from) {
                        calendar.add(Calendar.DAY_OF_YEAR, 1)
                    }

                    "WEEKLY" -> while (calendar.timeInMillis <= from) {
                        calendar.add(Calendar.WEEK_OF_YEAR, 1)
                    }

                    else -> {
                        val anchor = (dayOfMonth ?: calendar.get(Calendar.DAY_OF_MONTH))
                            .coerceIn(1, 31)
                        while (calendar.timeInMillis <= from) {
                            calendar.add(Calendar.MONTH, 1)
                            // Land on the 1st before setting the day: add() from
                            // Jan 31 clamps to Feb 28, and bolting a new day onto
                            // a clamped date is what made series drift for good.
                            calendar.set(Calendar.DAY_OF_MONTH, 1)
                            val lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                            calendar.set(Calendar.DAY_OF_MONTH, anchor.coerceAtMost(lastDay))
                        }
                    }
                }
                calendar.timeInMillis
            }

            else -> null
        }
    }
}
