package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.CommandSource
import com.chronoit.aivora.scheduler.*
import com.chronoit.aivora.voice.*
import org.junit.Assert.*
import org.junit.Test

class DueTaskChoicesTest {
    private fun task(id: String, due: Long = 100, action: Boolean = true) = Reminder(id, "fixture $id", due, 0, "PENDING", action)
    @Test fun listSnapshotsChronologicalOccurrencesAndConsumesOnlyOnce() {
        val choices = DueTaskChoices { 1 }
        val source = mutableListOf(task("b"), task("a"), task("future", 1000), task("reminder", action = false))
        val request = choices.present(source, 200)!!
        source.clear()
        assertEquals(listOf("a", "b"), choices.entries(request).map { it.id })
        assertNull(choices.consume(request, 2))
        assertEquals("b", choices.consume(request, 1)!!.id)
        assertNull(choices.consume(request, 1))
        assertNull(choices.current())
    }
    @Test fun expiryReplacementAndVoiceBindingCannotSelectAnotherList() {
        var now = 1L
        val choices = DueTaskChoices { now }
        val first = choices.present(listOf(task("a")), 200)!!
        val gate = VoiceChoiceGate()
        gate.arm(listOf(first))
        val second = choices.present(listOf(task("b")), 200)!!
        assertNull(choices.consume(first, 0))
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, ChoiceReply(index = 0), listOf(second)))
        gate.arm(listOf(second))
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, ChoiceReply(index = 0, kind = ChoiceKind.CONTACT), listOf(second)))
        now = 120001
        assertNull(choices.current())
        assertNull(choices.consume(second, 0))
    }
    @Test fun taskGrammarAndPagesKeepGlobalNumbers() {
        assertEquals(ChoiceReply(index = 1, kind = ChoiceKind.TASK), ChoiceReply.parse("choose task number two"))
        assertEquals(ChoiceReply(index = 1, kind = ChoiceKind.TASK), ChoiceReply.parse("the second task"))
        val page = ChoiceReadback.create(ChoiceKind.TASK, List(5) { "saved ${it + 1}" }, 1)!!
        assertTrue(page.prompt.contains("4. saved 4"))
        assertTrue(page.prompt.contains("choose task number"))
        assertFalse(page.prompt.contains("1. saved 4"))
    }
}
