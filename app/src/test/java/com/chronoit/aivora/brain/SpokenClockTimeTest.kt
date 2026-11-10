package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.Action
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SpokenClockTimeTest {
    private val parser = CommandParser { ZonedDateTime.of(2026, 1, 10, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata")) }
    private fun alarm(text: String) = ((parser.parse("set alarm for $text") as ParsedCommand.Plan).value.actions.single() as Action.Alarm).time

    @Test fun spokenClockFieldsSupportConventionalMinutesAndMeridiem() {
        assertEquals(LocalTime.of(19, 30), alarm("seven thirty PM"))
        assertEquals(LocalTime.of(7, 5), alarm("seven oh five a m"))
        assertEquals(LocalTime.of(7, 5), alarm("seven zero five AM"))
        assertEquals(LocalTime.of(0, 0), alarm("twelve a.m."))
        assertEquals(LocalTime.of(12, 0), alarm("twelve p.m."))
        assertEquals(LocalTime.of(23, 59), alarm("twenty three fifty nine"))
        assertEquals(LocalTime.of(7, 30), alarm("7:30"))
        assertEquals(LocalTime.of(19, 30), alarm("7:30pm"))
        assertEquals(LocalTime.of(19, 30), alarm("seven thirty p.m."))
    }

    @Test fun ambiguousInvalidAndMixedRequestsNeverCreateAnAlarm() {
        listOf("twenty three", "twenty one", "twenty five", "seven sixty", "zero pm", "thirteen pm", "25:00",
            "7:60", "seven thirty and pay Rahul", "half past seven", "seven point five", "-7", "seven am pm").forEach {
            assertTrue(it, parser.parse("alarm $it") is ParsedCommand.Clarify)
        }
    }

    @Test fun tomorrowPreservesTheExactActionPayloadAndAbsoluteDueTime() {
        val result = parser.parse("tomorrow at seven thirty p.m. message Rahul that Fifty Five Cafe at seven") as ParsedCommand.Schedule
        val due = ZonedDateTime.of(2026, 1, 11, 19, 30, 0, 0, ZoneId.of("Asia/Kolkata"))
        assertEquals(due.toInstant().toEpochMilli(), result.dueAtMillis)
        assertEquals(124_200_000, result.delayMillis)
        assertEquals("message Rahul that Fifty Five Cafe at seven", result.command)
        assertEquals((parser.parse("tomorrow at 7:30 pm volume 30") as ParsedCommand.Schedule).dueAtMillis,
            (parser.parse("tomorrow at seven thirty pm volume 30") as ParsedCommand.Schedule).dueAtMillis)
    }

    @Test fun anInvalidClockCannotFallBackToAPartialTimeOrImmediateAction() {
        listOf("tomorrow at seven sixty volume 30", "tomorrow at twenty three volume 30", "tomorrow at 25:30 volume 30",
            "tomorrow at seven thirty", "tomorrow at seven thirty pm", "tomorrow at seven and thirty volume 30").forEach {
            assertTrue(it, parser.parse(it) is ParsedCommand.Clarify)
        }
    }

    @Test fun spokenTomorrowTimesRetainDaylightSavingGapAndOverlapRefusal() {
        val zone = ZoneId.of("America/New_York")
        val gap = CommandParser { ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, zone) }
        assertTrue(gap.parse("tomorrow at two thirty am volume 30") is ParsedCommand.Clarify)
        val overlap = CommandParser { ZonedDateTime.of(2026, 10, 31, 12, 0, 0, 0, zone) }
        assertTrue(overlap.parse("tomorrow at one thirty am volume 30") is ParsedCommand.Clarify)
    }
}
