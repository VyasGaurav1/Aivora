package com.chronoit.aivora.memory

import org.junit.Assert.*
import org.junit.Test

class ConversationCommandsTest {
    @Test fun replayPhrasesAreLocalCommandsRatherThanSearches() {
        listOf("Repeat that!", "Can you repeat that?", "Say that again", "I didn't hear you").forEach {
            assertTrue(it, ConversationMemory.repeatRequest(it))
        }
        assertFalse(ConversationMemory.repeatRequest("Explain how repeat loops work"))
    }
    @Test fun recentHistoryIsDistinctFromGeneralMemoryQuestions() {
        assertTrue(ConversationMemory.historyRequest("What did we discuss?"))
        assertFalse(ConversationMemory.historyRequest("What is human memory?"))
    }
}
