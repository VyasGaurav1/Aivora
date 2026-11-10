package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.platform.SmsTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExplicitSmsDraftTest {
    private val target = SmsTarget(1, 0, 1, "Fixture SIM")
    private val documents = object : DocumentDirectory {
        override suspend fun find(query: String) = emptyList<LocalDocument>()
        override suspend fun get(id: String): LocalDocument? = null
        override fun readable(document: LocalDocument) = false
    }
    private fun action(turn: DialogueTurn) = ((turn as DialogueTurn.Command).command as ParsedCommand.Plan).value.actions.single() as Action.DraftMessage
    @Test fun explicitNumericDraftCannotBecomeASendAndPreservesBody() {
        val parser = CommandParser()
        for (prefix in listOf("draft SMS to", "draft a text to", "draft message")) {
            val plan = (parser.parse("$prefix +12025550123 that Hello & Stay HERE!") as ParsedCommand.Plan).value
            assertEquals(Action.DraftMessage("+12025550123", "Hello & Stay HERE!", draftOnly = true), plan.actions.single())
            assertSame(plan, DirectSmsPlanner.bind(plan, target))
        }
        assertTrue(parser.parse("draft sms to Rahul on WhatsApp that Hello") is ParsedCommand.Clarify)
    }
    @Test fun contactChoiceAndPronounRetainDraftIntent() = runTest {
        val first = ContactCandidate("1", "Rahul", "+12025550123", "mobile")
        val second = first.copy(number = "+12025550124", label = "work")
        val dialogue = DialogueCoordinator(CommandParser(), ContactDirectory { listOf(first, second) }, documents)
        val choices = dialogue.process("draft SMS to Rahul that Hello", emptyList()) as DialogueTurn.Contacts
        val selected = action(dialogue.chooseContact(1, choices.token))
        assertEquals(Action.DraftMessage(second.number, "Hello", second.id, second.name, true), selected)
        val followup = action(dialogue.process("draft text to him saying Another message", emptyList()))
        assertTrue(followup.draftOnly)
        assertEquals(second.number, followup.phoneNumber)
    }
    @Test fun oldCodecBytesStayStableAndRoutinesCannotUpgradeExplicitDrafts() {
        val old = Action.DraftMessage("+12025550123", "Hello")
        assertEquals("AIVORA_ACTION_1\nbWVzc2FnZQ\tKzEyMDI1NTUwMTIz\tSGVsbG8\t~\t~", ActionCodec.encode(old))
        val draft = old.copy(draftOnly = true)
        assertEquals(draft, ActionCodec.decode(ActionCodec.encode(draft)))
        assertNotEquals(ActionCodec.encode(old), ActionCodec.encode(draft))
        assertTrue(draft.describe().contains("Sending remains"))
        val learned = LearnedPhrase.create("prepare hello", "draft SMS to +12025550123 that Hello").plan(CommandParser(), emptyList())!!
        assertSame(learned, DirectSmsPlanner.bind(learned, target))
    }
}
