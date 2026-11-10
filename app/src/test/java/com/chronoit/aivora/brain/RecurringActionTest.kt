package com.chronoit.aivora.brain

import org.junit.Assert.*
import org.junit.Test

class RecurringActionTest {
    @Test fun completedOccurrenceAlwaysAdvancesEvenAfterAClockRollback() {
        val interval = com.chronoit.aivora.scheduler.ScheduleTiming.MIN_REPEAT
        assertEquals(1_000_000L + interval, com.chronoit.aivora.scheduler.ScheduleTiming.afterExecution(1_000_000, interval, 100))
        assertEquals(1_000_000L + interval * 4, com.chronoit.aivora.scheduler.ScheduleTiming.afterExecution(1_000_000, interval, 1_000_000 + interval * 3))
    }
    @Test fun recurringDefinitionsAreBoundedAndConditionsComposeWithThem() {
        val parser = CommandParser()
        assertEquals(ParsedCommand.Schedule(3_600_000, "volume 30", repeatMillis = 3_600_000), parser.parse("every 1 hour volume 30"))
        listOf("every 0 minutes volume 30", "every 14 minutes volume 30", "every 366 days volume 30").forEach {
            assertTrue(parser.parse(it) is ParsedCommand.Clarify)
        }
        val conditional = parser.parse("when charging, every 1 hour volume 30") as ParsedCommand.Conditioned
        assertTrue(conditional.conditions.charging)
        assertEquals(ParsedCommand.Schedule(3_600_000, "volume 30", repeatMillis = 3_600_000), parser.parse(conditional.command))
    }
}
