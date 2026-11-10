package com.chronoit.aivora.connectivity

import org.junit.Assert.*
import org.junit.Test

class WebQuestionRoutingTest {
    @Test fun searchPhrasingsAndBareTopicsReachWeb() {
        for (text in listOf("search web for photosynthesis", "search the web for photosynthesis", "search Google for photosynthesis", "look up photosynthesis", "photosynthesis"))
            assertEquals("photosynthesis" to false, WebQuestionRouting.request(text, null))
    }
    @Test fun GeneralQuestionsRouteWithoutActionKeywords() {
        for (text in listOf("Is photosynthesis possible at night?", "Can plants survive without sunlight?", "Explain photosynthesis"))
            assertNotNull(WebQuestionRouting.request(text, null))
    }
    @Test fun unsupportedPhoneTasksAreNotLeakedToWeb() {
        for (text in listOf("delete my messages", "set reminder", "pay thirty rupees", "remember my password", "Can you send a message", "Could you pay thirty rupees", "Are you able to open WhatsApp"))
            assertNull(WebQuestionRouting.request(text, null))
    }
    @Test fun suggestionsAndFollowupKeepThePreviousTopic() {
        assertEquals("photosynthesis" to true, WebQuestionRouting.request("show suggestions", "photosynthesis"))
        assertNull(WebQuestionRouting.request("explain more", "photosynthesis"))
        assertNull(WebQuestionRouting.request("explain more", null))
    }
}
