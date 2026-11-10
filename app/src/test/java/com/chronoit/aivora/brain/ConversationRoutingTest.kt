package com.chronoit.aivora.brain

import com.chronoit.aivora.connectivity.WebQuestionRouting
import org.junit.Assert.*
import org.junit.Test

class ConversationRoutingTest {
    @Test fun currentReplyMemoryAndCorrectionsNeverBecomeWebQueries() {
        listOf("Explain that", "What does that mean?", "Make it simpler", "Make it shorter", "Tell me more", "Give me an example", "What do you remember about me?", "The correct answer is Friday", "That was wrong").forEach {
            assertNotNull(it, ConversationRouting.local(it))
            assertNull(it, WebQuestionRouting.request(it, "photosynthesis"))
        }
    }
    @Test fun newQuestionsAndExplicitSearchRemainDistinct() {
        assertNull(ConversationRouting.local("What is photosynthesis?"))
        assertNotNull(WebQuestionRouting.request("search web for human memory", null))
        assertTrue(ConversationRouting.explicitSearch("Search for photosynthesis"))
        assertNotNull(WebQuestionRouting.clarification("python"))
        assertNull(WebQuestionRouting.clarification("Python programming language"))
    }
    @Test fun correctionPayloadIsData() {
        assertEquals("Friday", ConversationRouting.correction("The correct answer is Friday"))
    }
}
