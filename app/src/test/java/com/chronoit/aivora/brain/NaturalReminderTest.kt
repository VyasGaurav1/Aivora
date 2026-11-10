package com.chronoit.aivora.brain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class NaturalReminderTest {
    private val now = LocalDateTime.of(2026, 11, 5, 19, 0)
    @Test fun completeReminderInSubjectFollowupKeepsItsTime() {
        val dialogue = StarterVoiceDialogue { now }
        assertTrue(dialogue.next("set a reminder") is StarterVoiceDialogue.Turn.Ask)
        val result = dialogue.next("remind me about a competition that I am participating tomorrow morning 7 am") as StarterVoiceDialogue.Turn.Command
        val reminder = CommandParser().parse(result.text) as ParsedCommand.Reminder
        assertEquals("a competition that I am participating", reminder.text)
        assertEquals(12 * 3600000L, reminder.delayMillis)
    }
    @Test fun subjectAndDayCanBeFollowedByClockTime() {
        val dialogue = StarterVoiceDialogue { now }
        assertTrue(dialogue.next("remind me about my competition tomorrow") is StarterVoiceDialogue.Turn.Ask)
        val result = dialogue.next("7 am") as StarterVoiceDialogue.Turn.Command
        assertEquals(ParsedCommand.Reminder("my competition", 12 * 3600000L), CommandParser().parse(result.text))
    }
    @Test fun subjectOnlyThenRelativeDelayWorks() {
        val dialogue = StarterVoiceDialogue { now }
        dialogue.next("remind me about my competition")
        val result = dialogue.next("in ten mins") as StarterVoiceDialogue.Turn.Command
        assertEquals(ParsedCommand.Reminder("my competition", 600000L), CommandParser().parse(result.text))
    }
    @Test fun secondsPermitRealDeliveryCheckButShortRecurrenceIsRejected() {
        assertEquals(ParsedCommand.Reminder("test", 10000), CommandParser().parse("remind me in ten seconds to test"))
        assertTrue(CommandParser().parse("remind me every ten seconds to test") is ParsedCommand.Clarify)
    }
    @Test fun pastTodayAndConflictingPeriodDoNotScheduleWrongly() {
        assertTrue(NaturalReminder.parse("remind me about my competition today morning 7 am", now) is NaturalReminder.Result.NeedsTime)
        assertTrue(NaturalReminder.parse("remind me about my competition tomorrow morning 7 pm", now) is NaturalReminder.Result.NeedsTime)
    }
    @Test fun locationInSubjectIsNotMisreadAsADelay() {
        val result = NaturalReminder.parse("remind me about my competition in Delhi tomorrow morning 7 am", now) as NaturalReminder.Result.Ready
        assertEquals(ParsedCommand.Reminder("my competition in Delhi", 12 * 3600000L), CommandParser().parse(result.command))
    }
}
