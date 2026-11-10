package com.chronoit.aivora.voice

import java.util.UUID

/** One-use, short-lived UI handoff. An exported activity never trusts an intent's claimed voice identity. */
class VoiceTicketStore(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Ticket(val token: String, val expiresAt: Long, val starterCommand: Boolean)
    private var ticket: Ticket? = null
    @Synchronized fun issue(starterCommand: Boolean = false): String =
        UUID.randomUUID().toString().also { ticket = Ticket(it, clock() + 30_000, starterCommand) }
    /** Notification fallback must not invalidate a panel handoff that is still resuming. */
    @Synchronized fun pendingOrIssue(): String = ticket?.takeIf { it.expiresAt > clock() && !it.starterCommand }?.token ?: issue()
    fun consume(token: String): Boolean = consumeStarterMode(token) != null
    @Synchronized fun consumeStarterMode(token: String): Boolean? {
        val current = ticket ?: return null
        if (current.token != token) return null
        ticket = null
        return current.starterCommand.takeIf { current.expiresAt > clock() }
    }
}

object VoiceLaunchTickets { val store = VoiceTicketStore() }
