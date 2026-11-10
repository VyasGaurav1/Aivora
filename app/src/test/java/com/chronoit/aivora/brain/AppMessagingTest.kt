package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppMessagingTest {
    @Test fun explicitAppsAndNumbersAreParsedWithoutCountryOrAppGuessing() {
        val parser = CommandParser()
        assertEquals(ParsedCommand.MessagePerson("Rahul", "I'll be late", MessagingApp.WHATSAPP), parser.parse("message Rahul on WhatsApp that I'll be late"))
        assertEquals(ParsedCommand.MessagePerson("Rahul", "Hello", MessagingApp.WHATSAPP_BUSINESS), parser.parse("text Rahul on WhatsApp Business saying Hello"))
        assertEquals(Action.DraftAppMessage(MessagingApp.WHATSAPP, "+919876543210", "Hello"), (parser.parse("message +919876543210 on WhatsApp that Hello") as ParsedCommand.Plan).value.actions.single())
        assertTrue(parser.parse("message 9876543210 on WhatsApp that Hello") is ParsedCommand.Clarify)
        assertTrue(parser.parse("message Rahul on Telegram that Hello") is ParsedCommand.Clarify)
    }
    @Test fun contactChoicesPreserveBusinessAppBodyAndExactNumber() = runTest {
        val people = listOf(ContactCandidate("1", "Rahul", "+919876543210", "mobile"), ContactCandidate("1", "Rahul", "+919876543211", "work"))
        val dialogue = DialogueCoordinator(CommandParser(), ContactDirectory { people }, object : DocumentDirectory {
            override suspend fun find(query: String) = emptyList<LocalDocument>()
            override suspend fun get(id: String): LocalDocument? = null
            override fun readable(document: LocalDocument) = false
        })
        val choice = dialogue.process("message Rahul on WhatsApp Business that I'll be late", emptyList()) as DialogueTurn.Contacts
        assertTrue(dialogue.chooseContact(1, "wrong-token") is DialogueTurn.Answer)
        val selected = dialogue.chooseContact(1, choice.token) as DialogueTurn.Command
        assertEquals(Action.DraftAppMessage(MessagingApp.WHATSAPP_BUSINESS, people[1].number, "I'll be late", "1", "Rahul"), (selected.command as ParsedCommand.Plan).value.actions.single())
        assertTrue(dialogue.chooseContact(1, choice.token) is DialogueTurn.Answer)
    }
    @Test fun phoneValidationRejectsServiceCodesLocalAndOversizedNumbers() {
        assertEquals("+919876543210", internationalPhone("+91 (98765) 43210"))
        listOf("9876543210", "+00919876543210", "+919876543210,123", "*123#", "+1234567890123456", "+1" + " ".repeat(100) + "23456789").forEach { assertNull(it, internationalPhone(it)) }
    }
    @Test fun draftsStillRequireExactPlanConfirmationAndRejectUnverifiedVoice() = runTest {
        var executed = false
        val action = Action.DraftAppMessage(MessagingApp.WHATSAPP, "+919876543210", "Hello & goodbye", "1", "Rahul")
        assertEquals(action, ActionCodec.decode(ActionCodec.encode(action)))
        assertTrue(action.requiresNetwork())
        val registry = ActionRegistry(ActionExecutor { executed = true; ActionResult(ExecutionStatus.REQUIRES_USER_INTERACTION, "Draft only") },
            ActionAudit { _, _, _, _ -> }, { com.chronoit.aivora.policy.ExecutionContext(true, true) })
        val plan = ActionPlan(listOf(action), 1.0, "test")
        assertTrue(registry.dispatch(plan, CommandSource.UNVERIFIED_VOICE) is DispatchResult.Rejected)
        assertFalse(executed)
        val confirmation = registry.dispatch(plan) as DispatchResult.NeedsConfirmation
        assertFalse(executed)
        assertTrue(registry.confirm(confirmation.confirmation.token) is DispatchResult.Executed)
        assertTrue(executed)
        assertTrue(registry.confirm(confirmation.confirmation.token) is DispatchResult.Rejected)
    }
}
