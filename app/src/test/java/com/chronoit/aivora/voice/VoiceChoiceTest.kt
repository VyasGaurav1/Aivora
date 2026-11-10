package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.brain.*
import com.chronoit.aivora.policy.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoiceChoiceTest {
    private val request = ChoiceRequest(ChoiceKind.CONTACT, "contacts", 3)
    private val gate = VoiceChoiceGate()
    @Test fun acceptsExplicitOrdinalsNumbersKindsAndPagingWithoutParsingOtherCommands() {
        listOf("the second contact", "choose the second contact", "choose contact number two", "contact two", "#2", "2", "two", "the second one.").forEach {
            assertEquals(it, 1, ChoiceReply.parse(it)!!.index)
        }
        assertEquals(ChoiceKind.DOCUMENT, ChoiceReply.parse("the first file")!!.kind)
        assertEquals(ChoiceKind.DOCUMENT, ChoiceReply.parse("select document twenty")!!.kind)
        assertEquals(19, ChoiceReply.parse("select document twenty")!!.index)
        assertEquals(1, ChoiceReply.parse("next choices")!!.pageDelta)
        assertEquals(-1, ChoiceReply.parse("previous choices")!!.pageDelta)
        assertEquals(0, ChoiceReply.parse("read choices")!!.pageDelta)
        listOf("yes", "call the second contact", "choose contact two then call someone", "volume 2", "pay two").forEach { assertNull(ChoiceReply.parse(it)) }
    }
    @Test fun capturedListCannotBeReusedOrReplacedByAnotherSourceKindTokenOrCount() {
        val choice = ChoiceReply.parse("the second contact")!!
        for (source in CommandSource.entries) {
            gate.arm(listOf(request))
            assertEquals(if (source == CommandSource.VERIFIED_VOICE) request else null, gate.consume(source, choice, listOf(request)))
            assertNull(gate.consume(CommandSource.VERIFIED_VOICE, choice, listOf(request)))
        }
        listOf(request.copy(token = "new"), request.copy(kind = ChoiceKind.DOCUMENT), request.copy(count = 4)).forEach {
            gate.arm(listOf(request))
            assertNull(gate.consume(CommandSource.VERIFIED_VOICE, choice, listOf(it)))
            assertNull(gate.consume(CommandSource.VERIFIED_VOICE, choice, listOf(request)))
        }
        for (invalid in listOf(ChoiceReply(index = -1), ChoiceReply(index = 3), ChoiceReply(index = 0, kind = ChoiceKind.DOCUMENT), ChoiceReply(), ChoiceReply(index = 0, pageDelta = 1))) {
            gate.arm(listOf(request)); assertNull(gate.consume(CommandSource.VERIFIED_VOICE, invalid, listOf(request)))
        }
        gate.arm(listOf(request, request.copy(kind = ChoiceKind.DOCUMENT)))
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, choice, listOf(request)))
        gate.arm(listOf(request)); gate.clear()
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, choice, listOf(request)))
    }
    @Test fun contactSelectionPreservesExactAppNumberBodyAndStillRequiresConfirmation() = runTest {
        val people = listOf(ContactCandidate("1", "Rahul", "+919876543210", "mobile"), ContactCandidate("1", "Rahul", "+919876543211", "work"))
        val dialogue = DialogueCoordinator(CommandParser(), ContactDirectory { people }, files(emptyList()))
        val choices = dialogue.process("message Rahul on WhatsApp Business that I'll be late", emptyList()) as DialogueTurn.Contacts
        val captured = ChoiceRequest(ChoiceKind.CONTACT, choices.token, choices.choices.size)
        gate.arm(listOf(captured))
        val reply = ChoiceReply.parse("the second contact")!!
        val accepted = gate.consume(CommandSource.VERIFIED_VOICE, reply, listOf(captured))!!
        val plan = plan(dialogue.chooseContact(reply.index!!, accepted.token))
        assertEquals(Action.DraftAppMessage(MessagingApp.WHATSAPP_BUSINESS, people[1].number, "I'll be late", "1", "Rahul"), plan.actions.single())
        var executed = false
        val registry = ActionRegistry(ActionExecutor { executed = true; ActionResult(ExecutionStatus.SUCCESS, "test") }, ActionAudit { _, _, _, _ -> }, { ExecutionContext(true, true) })
        val confirmation = registry.dispatch(plan, CommandSource.VERIFIED_VOICE) as DispatchResult.NeedsConfirmation
        assertFalse(executed)
        assertTrue(registry.confirm(confirmation.confirmation.token) is DispatchResult.Executed)
        assertTrue(executed)
    }
    @Test fun fileThenContactChoiceRequiresFreshCaptureForEachListAndDeviceAuthentication() = runTest {
        val documents = listOf(document("one"), document("two"))
        val people = listOf(ContactCandidate("1", "Rahul", "+919876543210", "mobile"), ContactCandidate("2", "Rahul", "+919876543211", "work"))
        val dialogue = DialogueCoordinator(CommandParser(), ContactDirectory { people }, files(documents))
        val choices = dialogue.process("send report to Rahul", emptyList()) as DialogueTurn.Documents
        val fileRequest = ChoiceRequest(ChoiceKind.DOCUMENT, choices.token, 2)
        gate.arm(listOf(fileRequest))
        val first = ChoiceReply.parse("the first file")!!
        val accepted = gate.consume(CommandSource.VERIFIED_VOICE, first, listOf(fileRequest))!!
        val contacts = dialogue.chooseDocument(first.index!!, accepted.token) as DialogueTurn.Contacts
        val contactRequest = ChoiceRequest(ChoiceKind.CONTACT, contacts.token, 2)
        val second = ChoiceReply.parse("the second contact")!!
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, second, listOf(contactRequest)))
        gate.arm(listOf(contactRequest))
        assertNotNull(gate.consume(CommandSource.VERIFIED_VOICE, second, listOf(contactRequest)))
        val plan = plan(dialogue.chooseContact(second.index!!, contacts.token))
        assertEquals(Action.ShareFile(documents[0].uri, documents[0].mimeType, "Rahul", documents[0].name), plan.actions.single())
        var executed = false
        val registry = ActionRegistry(ActionExecutor { executed = true; ActionResult(ExecutionStatus.SUCCESS, "test") }, ActionAudit { _, _, _, _ -> }, { ExecutionContext(true, true) })
        assertTrue(registry.dispatch(plan, CommandSource.VERIFIED_VOICE) is DispatchResult.NeedsAuthentication)
        assertFalse(executed)
    }
    @Test fun expiryRevocationAndWrongChoiceKindNeverResolveAnotherFileOrContact() = runTest {
        var now = 100L
        var available = true
        val documents = listOf(document("one"), document("two"))
        val directory = object : DocumentDirectory {
            override suspend fun find(query: String) = documents
            override suspend fun get(id: String) = documents.find { it.id == id }?.takeIf { available }
            override fun readable(document: LocalDocument) = available
        }
        val dialogue = DialogueCoordinator(CommandParser(), ContactDirectory { emptyList() }, directory, { now })
        val choices = dialogue.process("open file report", emptyList()) as DialogueTurn.Documents
        assertTrue(dialogue.process("the first contact", emptyList()) is DialogueTurn.Answer)
        available = false
        assertTrue(dialogue.chooseDocument(0, choices.token) is DialogueTurn.Answer)
        available = true
        val newer = dialogue.process("open file report", emptyList()) as DialogueTurn.Documents
        val request = ChoiceRequest(ChoiceKind.DOCUMENT, newer.token, newer.choices.size)
        assertTrue(dialogue.choiceIsCurrent(request))
        now += 120_001
        assertFalse(dialogue.choiceIsCurrent(request))
        assertTrue(dialogue.chooseDocument(0, newer.token) is DialogueTurn.Answer)
    }
    @Test fun readbackPagesKeepGlobalNumbersAndNeverTruncateAnOption() {
        val options = (1..8).map { "report $it" }
        val first = ChoiceReadback.create(ChoiceKind.DOCUMENT, options, 0)!!
        val next = ChoiceReadback.create(ChoiceKind.DOCUMENT, options, first.page + 1)!!
        assertTrue(first.prompt.contains("1. report 1"))
        assertFalse(first.prompt.contains("4. report 4"))
        assertTrue(next.prompt.contains("4. report 4"))
        assertTrue(next.prompt.contains("6. report 6"))
        assertTrue(next.prompt.contains("previous choices"))
        assertTrue(ChoiceReadback.create(ChoiceKind.DOCUMENT, options, 999)!!.prompt.contains("8. report 8"))
        assertEquals(0, ChoiceReadback.create(ChoiceKind.DOCUMENT, options, -999)!!.page)
        assertNull(ChoiceReadback.create(ChoiceKind.CONTACT, listOf("x".repeat(1500)), 0))
    }
    private fun document(id: String) = LocalDocument(id, "content://files/$id", "$id.pdf", "application/pdf", 100, 1, "content://files/root")
    private fun files(documents: List<LocalDocument>) = object : DocumentDirectory {
        override suspend fun find(query: String) = documents
        override suspend fun get(id: String) = documents.find { it.id == id }
        override fun readable(document: LocalDocument) = true
    }
    private fun plan(turn: DialogueTurn) = ((turn as DialogueTurn.Command).command as ParsedCommand.Plan).value
}
