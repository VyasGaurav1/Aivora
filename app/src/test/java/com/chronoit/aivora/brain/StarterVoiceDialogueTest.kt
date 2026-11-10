package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.Action
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class StarterVoiceDialogueTest {
    @Test fun naturalAlarmAndCorrectionFollowupKeepTheirSlots() {
        val dialogue = eveningDialogue()
        assertTrue(dialogue.next("I need an alarm") is StarterVoiceDialogue.Turn.Ask)
        assertEquals(StarterVoiceDialogue.Turn.Command("set alarm for 06:00"), dialogue.next("no make it six AM"))
    }
    @Test fun reminderTimeBeforeContentAndDontForgetAreUnderstood() {
        val dialogue = eveningDialogue()
        assertTrue(dialogue.next("remind me tomorrow morning seven AM to attend the competition") is StarterVoiceDialogue.Turn.Command)
        assertTrue(dialogue.next("don't let me forget to submit the form in three days") is StarterVoiceDialogue.Turn.Command)
    }

    private fun eveningDialogue() = StarterVoiceDialogue { java.time.LocalDateTime.of(2026, 11, 5, 19, 0) }
    @Test fun tomorrowMorningIsAcceptedInAlarmFollowup() {
        val dialogue = eveningDialogue()
        dialogue.next("set alarm")
        assertEquals(StarterVoiceDialogue.Turn.Command("set alarm for 05:00"), dialogue.next("tomorrow morning 5 am"))
    }
    @Test fun wakeMeUpPhraseAndMissingTimeRetainDayAndPeriod() {
        val dialogue = eveningDialogue()
        assertTrue(dialogue.next("wake me up tomorrow morning") is StarterVoiceDialogue.Turn.Ask)
        assertEquals(StarterVoiceDialogue.Turn.Command("set alarm for 05:00"), dialogue.next("five"))
    }
    @Test fun futureDatedAlarmIsNotSilentlyChangedToTonight() {
        val dialogue = eveningDialogue()
        val turn = dialogue.next("set alarm for tomorrow evening 8 pm")
        assertTrue(turn is StarterVoiceDialogue.Turn.Ask)
    }
    @Test fun naturalReminderOrderRetainsContentAndDelay() {
        val turn = eveningDialogue().next("remind me to buy milk after three days") as StarterVoiceDialogue.Turn.Command
        assertEquals(ParsedCommand.Reminder("buy milk", 259200000L), CommandParser().parse(turn.text))
    }
    @Test fun alarmWithoutTimeAsksAndUsesSpokenReply() {
        val dialogue = StarterVoiceDialogue()
        assertTrue(dialogue.next("Set the alarm.") is StarterVoiceDialogue.Turn.Ask)
        val command = dialogue.next("Seven thirty AM.") as StarterVoiceDialogue.Turn.Command
        assertEquals(LocalTime.of(7, 30), ((CommandParser().parse(command.text) as ParsedCommand.Plan).value.actions.single() as Action.Alarm).time)
    }
    @Test fun naturalCompleteAlarmUsesTheRequestedTime() {
        val command = StarterVoiceDialogue().next("Can you set an alarm at seven in the morning?") as StarterVoiceDialogue.Turn.Command
        assertEquals(LocalTime.of(7, 0), ((CommandParser().parse(command.text) as ParsedCommand.Plan).value.actions.single() as Action.Alarm).time)
    }
    @Test fun invalidTimeDoesNotExecuteAndCanBeCorrected() {
        val dialogue = StarterVoiceDialogue()
        assertTrue(dialogue.next("set alarm for twenty five") is StarterVoiceDialogue.Turn.Ask)
        assertTrue(dialogue.next("seven PM") is StarterVoiceDialogue.Turn.Command)
    }
    @Test fun reminderCollectsContentThenDelay() {
        val dialogue = StarterVoiceDialogue()
        assertTrue(dialogue.next("set a reminder") is StarterVoiceDialogue.Turn.Ask)
        assertTrue(dialogue.next("buy milk") is StarterVoiceDialogue.Turn.Ask)
        val command = dialogue.next("after three days") as StarterVoiceDialogue.Turn.Command
        val parsed = CommandParser().parse(command.text) as ParsedCommand.Reminder
        assertEquals("buy milk", parsed.text)
        assertEquals(259200000L, parsed.delayMillis)
    }
    @Test fun newTaskReplacesAnUnfinishedAlarmAndClearDropsFollowup() {
        val dialogue = StarterVoiceDialogue()
        dialogue.next("set alarm")
        assertEquals(StarterVoiceDialogue.Turn.Command("remember that my appointment is Monday"), dialogue.next("remember that my appointment is Monday"))
        dialogue.clear()
        assertEquals(StarterVoiceDialogue.Turn.Command("seven"), dialogue.next("seven"))
    }
}
