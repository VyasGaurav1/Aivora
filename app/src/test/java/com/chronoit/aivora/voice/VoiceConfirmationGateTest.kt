package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.brain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoiceConfirmationGateTest {
    private var now = 100L
    private val gate = VoiceConfirmationGate { now }
    private val confirmation = Confirmation("token", ActionPlan(listOf(Action.SetBrightness(30)), 1.0, "test"), 1000)
    @Test fun onlyVerifiedVoiceCanConsumeTheExactCaptureTimePlanOnce() {
        gate.arm(confirmation)
        assertNull(gate.consume(CommandSource.UNVERIFIED_VOICE, confirmation))
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, confirmation))
        gate.arm(confirmation)
        assertEquals("token", gate.consume(CommandSource.VERIFIED_VOICE, confirmation))
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, confirmation))
    }
    @Test fun changedTokenPlanExpiryOrCancellationCannotApproveAnotherRequest() {
        listOf(confirmation.copy(token = "new"), confirmation.copy(plan = confirmation.plan.copy(planId = "changed")), confirmation.copy(expiresAt = 2000)).forEach {
            gate.arm(confirmation); assertNull(gate.consume(CommandSource.VERIFIED_VOICE, it))
        }
        gate.arm(confirmation); now = 1000
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, confirmation))
        now = 100; gate.arm(confirmation); gate.clear()
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, confirmation))
    }
    @Test fun explicitApprovalCannotReplaceAuthenticationOrBypassRegistryPolicy() = runTest {
        var executed = false
        val registry = ActionRegistry(ActionExecutor { executed = true; ActionResult(ExecutionStatus.SUCCESS, "test") },
            ActionAudit { _, _, _, _ -> }, { com.chronoit.aivora.policy.ExecutionContext(true, true) }, { now })
        val high = registry.dispatch(ActionPlan(listOf(Action.ShareFile("content://files/report", "application/pdf")), 1.0, "test"), CommandSource.VERIFIED_VOICE) as DispatchResult.NeedsAuthentication
        gate.arm(null)
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, null))
        assertFalse(executed)
        val approved = registry.authenticate(high.confirmation.token) as DispatchResult.NeedsConfirmation
        gate.arm(approved.confirmation)
        val token = gate.consume(CommandSource.VERIFIED_VOICE, approved.confirmation)!!
        assertTrue(registry.confirm(token) is DispatchResult.Executed)
        assertTrue(executed)
        assertTrue(registry.confirm(token) is DispatchResult.Rejected)
    }
    @Test fun ambiguousYesIsNotAnActionApproval() {
        assertEquals(ParsedCommand.ConfirmAction, CommandParser().parse("confirm action"))
        assertEquals(ParsedCommand.ConfirmAction, CommandParser().parse("approve action"))
        assertEquals(ParsedCommand.ConfirmAction, CommandParser().parse("confirm the action I just reviewed"))
        assertTrue(CommandParser().parse("yes") is ParsedCommand.Clarify)
    }
}
