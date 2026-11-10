package com.chronoit.aivora.brain

import com.chronoit.aivora.scheduler.ScheduleTiming
import org.junit.Assert.*
import org.junit.Test

class ScheduleTimingTest {
    @Test fun skipsMissedOccurrencesWithoutDriftOrReminderStorm() {
        val interval = ScheduleTiming.MIN_REPEAT
        assertEquals(100L + interval, ScheduleTiming.nextOccurrence(100, interval, 100))
        assertEquals(100L + interval * 4, ScheduleTiming.nextOccurrence(100, interval, interval * 3 + 200))
        assertEquals(200L, ScheduleTiming.nextOccurrence(200, interval, 100))
        assertThrows(ArithmeticException::class.java) { ScheduleTiming.nextOccurrence(Long.MAX_VALUE - 1, interval, Long.MAX_VALUE) }
    }
    @Test fun parserBoundsRecurringAndOneTimeDefinitions() {
        val parser = CommandParser()
        assertEquals(ParsedCommand.Reminder("stretch", 60_000), parser.parse("remind me in 1 minute to stretch"))
        assertEquals(ParsedCommand.Reminder("drink water", 3_600_000, 3_600_000), parser.parse("remind me every 1 hour to drink water"))
        listOf("remind me in 0 minutes to stretch", "remind me every 1 minute to stretch", "remind me in 999999 days to stretch").forEach {
            assertTrue(parser.parse(it) is ParsedCommand.Clarify)
        }
    }
}
