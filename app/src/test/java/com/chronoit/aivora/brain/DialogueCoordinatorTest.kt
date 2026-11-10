package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DialogueCoordinatorTest {
    private var time = 100L
    private var permission = true
    private var fileAvailable = true
    private val rahul = ContactCandidate("1", "Rahul", "+919876543210", "mobile")
    private var people = listOf(rahul)
    private val report = LocalDocument("file1", "content://files/report", "FinalReport.pdf", "application/pdf", 100, 1, "content://files/root")
    private val coordinator = DialogueCoordinator(CommandParser(), ContactDirectory { if (!permission) throw SecurityException(); people },
        object : DocumentDirectory {
            override suspend fun find(query: String) = if (fileAvailable) listOf(report) else emptyList()
            override suspend fun get(id: String) = report.takeIf { fileAvailable && it.id == id }
            override fun readable(document: LocalDocument) = fileAvailable
        }, { time })
    private fun action(turn: DialogueTurn) = ((turn as DialogueTurn.Command).command as ParsedCommand.Plan).value.actions.single()
    @Test fun resolvesContactAndMessageWithoutExecutingAndPreservesRecipient() = runTest {
        assertEquals(Action.Dial(rahul.number, rahul.id, rahul.name), action(coordinator.process("call Rahul", emptyList())))
        assertEquals(Action.DraftMessage(rahul.number, "I'll be there soon", rahul.id, rahul.name), action(coordinator.process("message Rahul that I'll be there soon", emptyList())))
    }
    @Test fun multipleNumbersRequireAnExactLiveChoiceToken() = runTest {
        people = listOf(rahul, rahul.copy(number = "9876543211", label = "work"))
        val choice = coordinator.process("call Rahul", emptyList()) as DialogueTurn.Contacts
        assertTrue(coordinator.chooseContact(0, "forged") is DialogueTurn.Answer)
        assertEquals(Action.Dial("9876543211", rahul.id, rahul.name), action(coordinator.chooseContact(1, choice.token)))
        assertTrue(coordinator.chooseContact(0, choice.token) is DialogueTurn.Answer)
    }
    @Test fun revokedPermissionAndMissingContextAskInsteadOfGuessing() = runTest {
        permission = false
        assertTrue(coordinator.process("call Rahul", emptyList()) is DialogueTurn.Answer)
        assertTrue(coordinator.process("open it", emptyList()) is DialogueTurn.Answer)
        assertTrue(coordinator.process("send it to him", emptyList()) is DialogueTurn.Answer)
    }
    @Test fun findThenSendItResolvesFileAndContactAndRevocationInvalidatesContext() = runTest {
        assertEquals(DialogueTurn.Answer("I found FinalReport.pdf."), coordinator.process("find FinalReport.pdf", emptyList()))
        assertEquals(Action.ShareFile(report.uri, report.mimeType, "Rahul", report.name), action(coordinator.process("send it to Rahul", emptyList())))
        fileAvailable = false
        assertTrue(coordinator.process("open it", emptyList()) is DialogueTurn.Answer)
    }
    @Test fun cancellationAndExpiryRemovePersonalReferencesAndPendingChoices() = runTest {
        coordinator.process("find FinalReport.pdf", emptyList())
        coordinator.process("cancel", emptyList())
        assertTrue(coordinator.process("open it", emptyList()) is DialogueTurn.Answer)
        people = listOf(rahul, rahul.copy(number = "9876543211"))
        val choice = coordinator.process("call Rahul", emptyList()) as DialogueTurn.Contacts
        time += 120_001
        assertTrue(coordinator.chooseContact(0, choice.token) is DialogueTurn.Answer)
    }
}
