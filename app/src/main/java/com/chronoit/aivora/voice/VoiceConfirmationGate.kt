package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.*

/** Binds a spoken approval to the exact confirmation present when capture began. */
class VoiceConfirmationGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var captured: Confirmation? = null
    @Synchronized fun arm(confirmation: Confirmation?) { captured = confirmation?.takeIf { it.expiresAt > clock() } }
    @Synchronized fun clear() { captured = null }
    @Synchronized fun consume(source: CommandSource, current: Confirmation?): String? {
        val previous = captured
        captured = null
        return previous?.takeIf { source == CommandSource.VERIFIED_VOICE && current != null &&
            it.token == current.token && it.plan.planId == current.plan.planId && it.expiresAt == current.expiresAt && it.expiresAt > clock() }?.token
    }
}
