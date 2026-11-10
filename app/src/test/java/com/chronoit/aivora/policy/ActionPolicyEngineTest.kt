package com.chronoit.aivora.policy

import com.chronoit.aivora.actions.*
import org.junit.Assert.*
import org.junit.Test

class ActionPolicyEngineTest {
    private val engine = ActionPolicyEngine()
    private fun plan(action: Action) = ActionPlan(listOf(action), 1.0, "test")
    @Test fun unverifiedVoiceAndLockedDevicesNeverExecute() {
        assertTrue(engine.evaluate(plan(Action.Flashlight(true)), ExecutionContext(true, false, false)) is PolicyDecision.Authenticate)
        assertTrue(engine.evaluate(plan(Action.Flashlight(true)), ExecutionContext(false, true, true)) is PolicyDecision.Authenticate)
        assertEquals(PolicyDecision.Allow, engine.evaluate(plan(Action.Flashlight(true)), ExecutionContext(true, false, true)))
    }
    @Test fun sensitiveSharingCannotUseVoiceAsSoleAuthentication() {
        assertTrue(engine.evaluate(plan(Action.ShareFile("content://files/1", "application/pdf")), ExecutionContext(true, false, true)) is PolicyDecision.Authenticate)
    }
    @Test fun arbitraryUrisAndShellStringsCannotBecomeExecutableActions() {
        listOf(Action.OpenFile("file:///private/data", "text/plain"), Action.OpenApp("am start; rm -rf /"),
            Action.Dial("*123#"), Action.SetVolume(-1)).forEach {
            assertTrue(engine.evaluate(plan(it), ExecutionContext(true, true)) is PolicyDecision.Deny)
        }
    }
}
