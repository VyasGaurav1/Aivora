package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.Action
import com.chronoit.aivora.scheduler.ScheduleTiming
import com.chronoit.aivora.scheduler.TaskConditions
import org.junit.Assert.*
import org.junit.Test

class SpokenSchedulingTest {
    private val parser = CommandParser()

    @Test fun spokenDurationsPreserveTheExactReminderAndActionText() {
        assertEquals(ParsedCommand.Reminder("Call Rahul at Fifty Five Cafe", 300_000),
            parser.parse("remind me in five minutes to Call Rahul at Fifty Five Cafe"))
        assertEquals(ParsedCommand.Reminder("stretch", 1_800_000, 1_800_000), parser.parse("remind me every thirty minutes to stretch"))
        assertEquals(ParsedCommand.Reminder(null, 259_200_000), parser.parse("remind me after 3days for this"))
        assertEquals(ParsedCommand.Reminder("Call Rahul", 259_200_000), parser.parse("remind me after three days to Call Rahul"))
        assertEquals(ParsedCommand.Schedule(1_200_000, "volume twenty five"), parser.parse("in twenty minutes volume twenty five"))
        val recurring = parser.parse("every one hour, disable wifi") as ParsedCommand.Schedule
        assertEquals(3_600_000, recurring.delayMillis)
        assertEquals(recurring.delayMillis, recurring.repeatMillis)
        assertEquals("disable wifi", recurring.command)
        assertEquals(listOf(Action.SetWifi(false)), (parser.parse(recurring.command) as ParsedCommand.Plan).value.actions)
    }

    @Test fun spokenBoundsUseTheExistingSchedulerLimitsWithoutOverflowOrClamping() {
        val maximum = parser.parse("in three hundred and sixty-five days volume 30") as ParsedCommand.Schedule
        assertEquals(ScheduleTiming.MAX_DELAY, maximum.delayMillis)
        assertEquals(ParsedCommand.Schedule(86_400_000, "volume 30"), parser.parse("in twenty four hours volume 30"))
        assertEquals(ParsedCommand.Schedule(60_000_000, "volume 30"), parser.parse("in a thousand minutes volume 30"))
        assertTrue(parser.parse("in three hundred sixty six days volume 30") is ParsedCommand.Clarify)
        assertTrue(parser.parse("in nine hundred ninety nine thousand nine hundred ninety nine days volume 30") is ParsedCommand.Clarify)
        assertTrue(parser.parse("in zero minutes volume 30") is ParsedCommand.Clarify)
        assertTrue(parser.parse("remind me every fourteen minutes to stretch") is ParsedCommand.Clarify)
        assertTrue(parser.parse("every fourteen minutes volume 30") is ParsedCommand.Clarify)
        val minimum = parser.parse("every fifteen minutes volume 30") as ParsedCommand.Schedule
        assertEquals(ScheduleTiming.MIN_REPEAT, minimum.repeatMillis)
    }

    @Test fun malformedFractionalNegativeOrCompoundDurationsNeverBecomeSchedules() {
        listOf("minus five", "-5", "five point five", "5.5", "one and a half", "one hour and five", "one hundred hundred",
            "zero hundred", "one thousand zero", "one thousand thousand", "twenty zero", "five 5", "9999999", "one million",
            "twenty--five", "and five", "one hundred and").forEach { count ->
            assertTrue(count, parser.parse("in $count minutes volume 30") is ParsedCommand.Clarify)
            assertTrue(count, parser.parse("remind me every $count minutes to stretch") is ParsedCommand.Clarify)
        }
    }

    @Test fun conditionedCommandsAndDigitFormsRetainTheirExistingMeaning() {
        val outer = parser.parse("when charging, in five minutes volume 30") as ParsedCommand.Conditioned
        assertEquals(TaskConditions(charging = true), outer.conditions)
        assertEquals(ParsedCommand.Schedule(300_000, "volume 30"), parser.parse(outer.command))
        assertEquals(parser.parse("in 25 minutes volume 30"), parser.parse("in twenty-five minutes volume 30"))
        assertEquals(parser.parse("remind me in 100 hours to stretch"), parser.parse("remind me in one hundred hours to stretch"))
        assertTrue(parser.parse("volume one thousand") is ParsedCommand.Clarify)
        assertEquals(ParsedCommand.Remember("in five minutes means soon"), parser.parse("remember in five minutes means soon"))
    }

    @Test fun boundedNumberGrammarRejectsMixedDigitsScalesAndUnboundedInput() {
        assertEquals(999999, EnglishNumber.parse("nine hundred ninety-nine thousand nine hundred and ninety-nine"))
        assertEquals(100001, EnglishNumber.parse("one hundred thousand and one"))
        assertEquals(0, EnglishNumber.parse("zero"))
        listOf("five\nminutes", "1 thousand", "hundred five", "ten hundred", "one thousand and", "9".repeat(151)).forEach {
            assertNull(it, EnglishNumber.parse(it))
        }
    }
}
