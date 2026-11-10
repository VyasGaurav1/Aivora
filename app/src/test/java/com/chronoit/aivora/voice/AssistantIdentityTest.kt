package com.chronoit.aivora.voice

import com.chronoit.aivora.ProductIdentity
import org.junit.Assert.*
import org.junit.Test

class AssistantIdentityTest {
    @Test fun defaultsUseAivora() {
        assertEquals("Aivora", AssistantIdentity().wakeName)
        assertEquals("Aivora", AssistantIdentity().assistantDisplayName)
    }
    @Test fun customNameDoesNotChangeProductIdentity() {
        val identity = AssistantIdentity("Friday", "Friday")
        assertEquals("Friday", identity.wakeName)
        assertEquals("AIVORA", ProductIdentity.PRODUCT_NAME)
        assertEquals("com.chronoit.aivora", ProductIdentity.APPLICATION_ID)
    }
    @Test(expected = IllegalArgumentException::class) fun blankNameRejected() {
        AssistantIdentity(" ", " ")
    }
    @Test(expected = IllegalArgumentException::class) fun controlCharactersRejected() {
        AssistantIdentity("Nova\n", "Nova")
    }
}
