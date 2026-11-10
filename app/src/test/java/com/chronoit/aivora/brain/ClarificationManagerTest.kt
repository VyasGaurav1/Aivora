package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.policy.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ClarificationManagerTest {
    @Test fun answersAreBoundToReferenceProjectExpiryAndSingleUse() {
        var now = 0L
        val manager = ClarificationManager { now }
        val answer = "Rahul. In this project, blue one means FinalReport.pdf."
        manager.ask("the blue one", "him", "atlas")
        assertEquals(ClarificationManager.Answer("Rahul", "blue one", "FinalReport.pdf"), manager.answer(answer, "atlas"))
        assertNull(manager.answer(answer, "atlas"))
        manager.ask("the red one", "him", "atlas")
        assertNull(manager.answer(answer, "atlas"))
        manager.ask("the blue one", "him", "atlas")
        assertNull(manager.answer(answer, "other"))
        manager.ask("the blue one", "him", "atlas"); now += 120_000
        assertNull(manager.answer(answer, "atlas"))
        manager.ask("the blue one", "him", "atlas"); manager.clear()
        assertNull(manager.answer(answer, "atlas"))
    }
    @Test fun unresolvedShareLearnsExplanationAndResolvesCurrentAndFutureRequests() = runTest {
        val aliases = mutableListOf<FileAlias>()
        val vocabulary = object : ReferenceVocabulary {
            override suspend fun lookup(scope: String, phrase: String) = aliases.firstOrNull { it.scope == scope && it.phrase == normalizeReference(phrase).removePrefix("the ") }
            override suspend fun save(alias: FileAlias): Boolean { aliases.add(alias); return true }
        }
        val report = LocalDocument("id", "content://files/report", "FinalReport.pdf", "application/pdf", 1, 1, "content://files/root")
        val documents = object : DocumentDirectory {
            override suspend fun find(query: String) = listOf(report).filter { it.name == query }
            override suspend fun get(id: String) = report.takeIf { it.id == id }
            override fun readable(document: LocalDocument) = true
        }
        val dialogue = DialogueCoordinator(CommandParser(), ContactDirectory { listOf(ContactCandidate("rahul", "Rahul", "5550199", "mobile")) }, documents, vocabulary = vocabulary)
        dialogue.process("use project Atlas", emptyList())
        val question = dialogue.process("send him the blue one", emptyList()) as DialogueTurn.Answer
        assertTrue(question.text.contains("Who do you mean"))
        fun action(turn: DialogueTurn) = ((turn as DialogueTurn.Command).command as ParsedCommand.Plan).value.actions.single()
        val expected = Action.ShareFile(report.uri, report.mimeType, "Rahul", report.name)
        val resolved = dialogue.process("Rahul. In this project, blue one means FinalReport.pdf.", emptyList())
        assertEquals(expected, action(resolved))
        var executed = false
        val registry = ActionRegistry(ActionExecutor { executed = true; ActionResult(ExecutionStatus.SUCCESS, "test") },
            ActionAudit { _, _, _, _ -> }, { ExecutionContext(true, true) })
        val plan = ((resolved as DialogueTurn.Command).command as ParsedCommand.Plan).value
        val authentication = registry.dispatch(plan) as DispatchResult.NeedsAuthentication
        assertFalse(executed)
        val confirmation = registry.authenticate(authentication.confirmation.token) as DispatchResult.NeedsConfirmation
        assertFalse(executed)
        assertTrue(registry.confirm(confirmation.confirmation.token) is DispatchResult.Executed)
        assertTrue(executed)
        assertEquals(FileAlias.create("atlas", "blue one", "id"), aliases.single())
        assertEquals(expected, action(dialogue.process("send Rahul the blue one", emptyList())))
        dialogue.process("send him the red one", emptyList())
        dialogue.process("cancel", emptyList())
        assertTrue(dialogue.process("Rahul. In this project, red one means FinalReport.pdf.", emptyList()) is DialogueTurn.Command)
        assertEquals(1, aliases.size)
    }
}
