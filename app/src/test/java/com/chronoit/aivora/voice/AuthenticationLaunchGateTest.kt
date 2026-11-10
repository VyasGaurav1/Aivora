package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.*
import org.junit.Assert.*
import org.junit.Test

class AuthenticationLaunchGateTest {
    private var now = 100L
    private val gate = AuthenticationLaunchGate { now }
    private val request = Confirmation("request", ActionPlan(listOf(Action.ShareFile("content://files/report", "application/pdf")), 1.0, "test"), 1000)

    @Test fun onlyOptedInVerifiedVoiceOpensOnePrompt() {
        for (source in CommandSource.entries) {
            for (enabled in listOf(false, true)) {
                gate.prepare(request, source, enabled)
                assertEquals(if (source == CommandSource.VERIFIED_VOICE && enabled) "request" else null, gate.consume(request, true))
                assertNull(gate.consume(request, true))
            }
        }
    }
    @Test fun waitsForForegroundWithoutGrantingAuthentication() {
        gate.prepare(request, CommandSource.VERIFIED_VOICE, true)
        assertNull(gate.consume(request, false))
        assertEquals("request", gate.consume(request, true))
    }
    @Test fun replacementExpiryAndCancelInvalidateLaunch() {
        listOf(request.copy(token = "other"), request.copy(plan = request.plan.copy(planId = "other")), request.copy(expiresAt = 2000), null).forEach {
            gate.prepare(request, CommandSource.VERIFIED_VOICE, true)
            assertNull(gate.consume(it, true))
            assertNull(gate.consume(request, true))
        }
        gate.prepare(request, CommandSource.VERIFIED_VOICE, true)
        now = 1000
        assertNull(gate.consume(request, true))
        now = 100
        gate.prepare(request, CommandSource.VERIFIED_VOICE, true)
        gate.clear()
        assertNull(gate.consume(request, true))
    }
}
