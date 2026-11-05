package com.pixelpal.app.domain.model

/**
 * Daily activity snapshot for the Mon-Fri streak consistency chart on Home.
 */
data class DayActivity(
    val dayLabel: String,
    val intensity: Float,
    val isToday: Boolean,
    val isActive: Boolean
)

data class WeeklyActivity(
    val days: List<DayActivity> = defaultDays()
) {
    val streakDays: Int
        get() = days.count { it.isActive }

    companion object {
        fun defaultDays(): List<DayActivity> = listOf(
            DayActivity("Mon", 0.45f, isToday = false, isActive = true),
            DayActivity("Tue", 0.90f, isToday = false, isActive = true),
            DayActivity("Wed", 0.65f, isToday = true, isActive = true),
            DayActivity("Thu", 0.30f, isToday = false, isActive = false),
            DayActivity("Fri", 0.75f, isToday = false, isActive = true)
        )
    }
}
