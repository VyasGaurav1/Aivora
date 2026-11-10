package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.CommandSource
import com.chronoit.aivora.actions.Confirmation

/** Permission to open Android's prompt; this never grants authentication or executes an action. */
class AuthenticationLaunchGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var pending: Confirmation? = null
    @Synchronized fun prepare(request: Confirmation, source: CommandSource, handsFree: Boolean) {
        pending = request.takeIf { source == CommandSource.VERIFIED_VOICE && handsFree && it.expiresAt > clock() }
    }
    @Synchronized fun clear() { pending = null }
    @Synchronized fun consume(current: Confirmation?, canLaunch: Boolean): String? {
        val request = pending ?: return null
        if (current == null || request.token != current.token || request.plan.planId != current.plan.planId ||
            request.expiresAt != current.expiresAt || request.expiresAt <= clock()) {
            pending = null
            return null
        }
        if (!canLaunch) return null
        pending = null
        return request.token
    }
}
