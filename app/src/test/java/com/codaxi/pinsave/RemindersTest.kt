package com.codaxi.pinsave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class RemindersTest {
    private fun at(hour: Int, minute: Int, dayOffset: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 16 + dayOffset, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun picksTheNextTimeToday() {
        assertEquals(at(18, 30), Reminders.nextTrigger(listOf("09:00", "18:30"), at(12, 0)))
    }

    @Test
    fun rollsOverToTomorrow() {
        assertEquals(at(9, 0, 1), Reminders.nextTrigger(listOf("09:00", "18:30"), at(20, 0)))
    }

    @Test
    fun aTimeThatJustFiredWaitsUntilTomorrow() {
        assertEquals(at(9, 0, 1), Reminders.nextTrigger(listOf("09:00"), at(9, 0) + 2_000))
    }

    @Test
    fun noTimesMeansNoReminder() {
        assertNull(Reminders.nextTrigger(emptyList(), at(12, 0)))
        assertNull(Reminders.nextTrigger(listOf("xx:yy"), at(12, 0)))
    }
}
