package com.pixelpal.app.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Series scheduling is where clocks lie: DST days are 23/25 hours, February has
 * no 31st, and the phone may be off for weeks. These pin the behaviour.
 */
class RecurrenceMathTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun fields(millis: Long): List<Int> =
        Calendar.getInstance().apply { timeInMillis = millis }.let {
            listOf(
                it.get(Calendar.YEAR),
                it.get(Calendar.MONTH) + 1,
                it.get(Calendar.DAY_OF_MONTH),
                it.get(Calendar.HOUR_OF_DAY),
                it.get(Calendar.MINUTE)
            )
        }

    @Test
    fun `one-shot has no next occurrence`() {
        assertNull(
            RecurrenceMath.nextTriggerTime(
                triggerTime = at(2026, 1, 10, 9),
                hour = 9,
                minute = 0,
                recurrence = "ONCE",
                from = at(2026, 1, 10, 10)
            )
        )
    }

    @Test
    fun `daily keeps the time of day`() {
        val next = RecurrenceMath.nextTriggerTime(
            triggerTime = at(2026, 1, 10, 8),
            hour = 8,
            minute = 0,
            recurrence = "DAILY",
            from = at(2026, 1, 10, 9)
        )
        assertEquals(listOf(2026, 1, 11, 8, 0), fields(next!!))
    }

    @Test
    fun `weekly lands on the same weekday`() {
        val next = RecurrenceMath.nextTriggerTime(
            triggerTime = at(2026, 1, 5, 7, 30), // Monday
            hour = 7,
            minute = 30,
            recurrence = "WEEKLY",
            from = at(2026, 1, 5, 8)
        )
        val resolved = requireNotNull(next)
        val cal = Calendar.getInstance().apply { timeInMillis = resolved }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(listOf(2026, 1, 12, 7, 30), fields(resolved))
    }

    /**
     * The bug this exists for: a series created on the 31st used to become the
     * 28th forever after its first short month.
     */
    @Test
    fun `monthly clamps to the short month and returns to the anchor day`() {
        val february = RecurrenceMath.nextTriggerTime(
            triggerTime = at(2026, 1, 31, 9),
            hour = 9,
            minute = 0,
            recurrence = "MONTHLY",
            dayOfMonth = 31,
            from = at(2026, 1, 31, 10)
        )
        assertEquals(listOf(2026, 2, 28, 9, 0), fields(february!!))

        val march = RecurrenceMath.nextTriggerTime(
            triggerTime = february,
            hour = 9,
            minute = 0,
            recurrence = "MONTHLY",
            dayOfMonth = 31,
            from = at(2026, 2, 28, 10)
        )
        assertEquals(listOf(2026, 3, 31, 9, 0), fields(march!!))
    }

    @Test
    fun `monthly keeps an ordinary day`() {
        val next = RecurrenceMath.nextTriggerTime(
            triggerTime = at(2026, 1, 15, 18),
            hour = 18,
            minute = 0,
            recurrence = "MONTHLY",
            dayOfMonth = 15,
            from = at(2026, 1, 15, 19)
        )
        assertEquals(listOf(2026, 2, 15, 18, 0), fields(next!!))
    }

    @Test
    fun `monthly anchor of 30 clamps in february`() {
        val next = RecurrenceMath.nextTriggerTime(
            triggerTime = at(2026, 1, 30, 9),
            hour = 9,
            minute = 0,
            recurrence = "MONTHLY",
            dayOfMonth = 30,
            from = at(2026, 1, 30, 10)
        )
        assertEquals(listOf(2026, 2, 28, 9, 0), fields(next!!))
    }

    /** Daily across a spring-forward boundary must keep the wall-clock hour. */
    @Test
    fun `daily survives a DST transition`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            // 2026-03-08 02:00 is the US spring-forward.
            val next = RecurrenceMath.nextTriggerTime(
                triggerTime = at(2026, 3, 7, 8),
                hour = 8,
                minute = 0,
                recurrence = "DAILY",
                from = at(2026, 3, 7, 9)
            )
            assertEquals(listOf(2026, 3, 8, 8, 0), fields(next!!))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `interval jumps whole periods without looping`() {
        val hour = 3_600_000L
        val from = at(2026, 1, 1, 0) + 10 * hour
        val next = RecurrenceMath.nextTriggerTime(
            triggerTime = at(2026, 1, 1, 0),
            hour = 0,
            minute = 0,
            recurrence = "ONCE",
            intervalMs = hour,
            from = from
        )
        assertTrue("must move forward", next!! > from)
        assertEquals(from + hour, next)
    }
}
