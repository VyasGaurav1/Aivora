package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.Action
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReferenceVocabularyTest {
    @Test fun projectScopedLearningReusesStableFileAndNeverFallsBackAfterRevocation() = runTest {
        val saved = mutableMapOf<Pair<String, String>, FileAlias>()
        var private = false
        val vocabulary = object : ReferenceVocabulary {
            override suspend fun lookup(scope: String, phrase: String) = saved[scope to normalizeReference(phrase).removePrefix("the ")]
            override suspend fun save(alias: FileAlias): Boolean { if (private) return false; saved[alias.scope to alias.phrase] = alias; return true }
        }
        val report = LocalDocument("id", "content://files/report", "FinalReport.pdf", "application/pdf", 1, 1, "content://files/root")
        var available = true
        val documents = object : DocumentDirectory {
            override suspend fun find(query: String) = listOf(report).filter { query == it.name }
            override suspend fun get(id: String) = report.takeIf { available && id == it.id }
            override fun readable(document: LocalDocument) = available
        }
        fun coordinator() = DialogueCoordinator(CommandParser(), ContactDirectory { emptyList() }, documents, vocabulary = vocabulary)
        val dialogue = coordinator()
        assertTrue(dialogue.process("blue one means file FinalReport.pdf", emptyList()) is DialogueTurn.Answer)
        assertTrue(saved.isEmpty())
        dialogue.process("use project Atlas", emptyList())
        dialogue.process("blue one means file FinalReport.pdf", emptyList())
        assertEquals(FileAlias.create("atlas", "blue one", "id"), saved.values.single())
        val recreated = coordinator()
        recreated.process("use project Atlas", emptyList())
        val turn = recreated.process("open file the blue one", emptyList()) as DialogueTurn.Command
        assertEquals(Action.OpenFile(report.uri, report.mimeType, report.name), (turn.command as ParsedCommand.Plan).value.actions.single())
        available = false
        assertTrue(recreated.process("open file blue one", emptyList()) is DialogueTurn.Answer)
        available = true
        recreated.process("use project Other", emptyList())
        assertTrue(recreated.process("open file blue one", emptyList()) is DialogueTurn.Answer)
        private = true
        recreated.process("blue one means file FinalReport.pdf", emptyList())
        assertEquals(1, saved.size)
    }
    @Test fun structuredAliasesRejectMalformedAndReservedReferences() {
        val alias = FileAlias.create("Atlas", "the blue one", "stable-id")
        assertEquals(alias, FileAlias.decode(alias.encode()))
        assertNull(FileAlias.decode("other\natlas\nblue one\nid"))
        assertThrows(IllegalArgumentException::class.java) { FileAlias.create("atlas", "it", "id") }
        assertThrows(IllegalArgumentException::class.java) { FileAlias.create("atlas", "blue\none", "id") }
    }
}
