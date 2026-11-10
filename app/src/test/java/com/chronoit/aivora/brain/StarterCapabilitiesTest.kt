package com.chronoit.aivora.brain

import org.junit.Assert.*
import org.junit.Test

class StarterCapabilitiesTest {
    @Test fun unsupportedActionGuardOverridesModelRouting() {
        assertEquals("UNSUPPORTED", StarterCapabilities.knownRoute("Please transfer thirty rupees to my friend"))
        assertEquals("UNSUPPORTED", StarterCapabilities.knownRoute("Can you send a message"))
        assertNull(StarterCapabilities.knownRoute("How do money transfers work?"))
        assertEquals("ALARM", StarterCapabilities.knownRoute("I need an alarm to wake me up"))
    }
    @Test fun capabilitiesAreLocalAndDoNotNeedSearch() {
        listOf("What can you do?", "Tell me your capabilities", "How can you help me?", "What are your features?").forEach {
            assertTrue(it, StarterCapabilities.isQuestion(it))
        }
        assertFalse(StarterCapabilities.isQuestion("What can you do about photosynthesis in plants?"))
    }
    @Test fun modelCannotInventExecutableRoutes() {
        assertNull(StarterCapabilities.label(ReasoningResult.Answer("PAYMENT")))
        assertNull(StarterCapabilities.label(ReasoningResult.Answer("ALARM then send money")))
        assertEquals("UNSUPPORTED", StarterCapabilities.label(ReasoningResult.Answer("UNSUPPORTED")))
        assertNull(StarterCapabilities.label(ReasoningResult.Unavailable("No model")))
    }
    @Test fun capabilityDescriptionMatchesReleaseScope() {
        assertTrue(StarterCapabilities.description.contains("aren't available"))
        assertTrue(StarterCapabilities.description.contains("Clock"))
    }
}
