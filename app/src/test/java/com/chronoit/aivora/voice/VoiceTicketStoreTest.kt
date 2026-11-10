package com.chronoit.aivora.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceTicketStoreTest {
    @Test fun notificationFallbackPreservesAnUnconsumedPanelTicket() {
        val store = VoiceTicketStore()
        val pending = store.issue()
        assertEquals(pending, store.pendingOrIssue())
        assertTrue(store.consume(pending))
        assertNotEquals(pending, store.pendingOrIssue())
    }
    @Test fun forgedExpiredReplayedAndReplacedTicketsCannotOpenVoiceCapture() {
        var time = 100L
        val store = VoiceTicketStore { time }
        val first = store.issue()
        assertFalse(store.consume("forged"))
        assertTrue(store.consume(first))
        assertFalse(store.consume(first))
        val expired = store.issue(); time += 30_001
        assertFalse(store.consume(expired))
        val replaced = store.issue(); val current = store.issue()
        assertFalse(store.consume(replaced)); assertTrue(store.consume(current))
        assertEquals(false, store.consumeStarterMode(store.issue()))
        val shortcut = store.issue(starterCommand = true)
        assertEquals(true, store.consumeStarterMode(shortcut))
        assertNull(store.consumeStarterMode(shortcut))
    }
}
