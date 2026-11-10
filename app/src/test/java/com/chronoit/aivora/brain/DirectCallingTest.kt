package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.platform.VoiceCallTarget
import com.chronoit.aivora.policy.ExecutionContext
import com.chronoit.aivora.data.AssistantSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DirectCallingTest {
    private val target = VoiceCallTarget(1, 0, 10, "Test carrier")
    private val action = Action.PlaceCall("+12025550123", target, "1", "Test contact")
    private val plan get() = ActionPlan(listOf(action), 1.0, "Explicit call")
    @Test fun typedCallIsOfflineHighRiskAndStrictlyStoredWithoutAccountSecrets() {
        assertFalse(action.requiresNetwork())
        assertEquals(RiskLevel.HIGH, action.risk())
        assertEquals(action, ActionCodec.decode(ActionCodec.encode(action)))
        assertTrue(runCatching { ActionCodec.decode(ActionCodec.encode(action) + "\textra") }.isFailure)
        assertFalse(AssistantSettings().directCalling)
    }
    @Test fun onlyCanonicalInternationalOrdinaryNumbersAndValidCallerMetadataAreAccepted() {
        listOf("112", "911", "12025550123", "+1 (202) 555-0123", "+12025550123#", "*21*+12025550123#", "tel:+12025550123", "+12025550123;123").forEach {
            assertNotNull(it, PlanValidator.error(ActionPlan(listOf(action.copy(phoneNumber = it)), 1.0, "test")))
        }
        listOf(target.copy(subscriptionId = -1), target.copy(slotIndex = -1), target.copy(label = "")).forEach {
            assertNotNull(PlanValidator.error(ActionPlan(listOf(action.copy(target = it)), 1.0, "test")))
        }
        assertNotNull(PlanValidator.error(ActionPlan(listOf(action, Action.SetVolume(30)), 1.0, "mixed")))
    }
    @Test fun authenticationAndRotatedExactConfirmationPrecedeTheSingleTransportRequest() = runTest {
        var calls = 0
        val registry = ActionRegistry(ActionExecutor { calls++; ActionResult(ExecutionStatus.REQUIRES_USER_INTERACTION, "Requested only") },
            ActionAudit { _, _, _, _ -> }, { ExecutionContext(true, true) })
        val auth = registry.dispatch(plan) as DispatchResult.NeedsAuthentication
        assertEquals(0, calls)
        assertTrue(registry.confirm(auth.confirmation.token) is DispatchResult.Rejected)
        val fresh = registry.dispatch(plan) as DispatchResult.NeedsAuthentication
        val review = registry.authenticate(fresh.confirmation.token) as DispatchResult.NeedsConfirmation
        assertNotEquals(fresh.confirmation.token, review.confirmation.token)
        assertEquals(0, calls)
        assertEquals(action, review.confirmation.plan.actions.single())
        assertEquals(ExecutionStatus.REQUIRES_USER_INTERACTION, (registry.confirm(review.confirmation.token) as DispatchResult.Executed).results.single().status)
        assertTrue(registry.confirm(review.confirmation.token) is DispatchResult.Rejected)
        assertEquals(1, calls)
    }
    @Test fun lockedAndUnverifiedRequestsNeverReachTransport() = runTest {
        var calls = 0
        fun registry(unlocked: Boolean) = ActionRegistry(ActionExecutor { calls++; error("must not call") }, ActionAudit { _, _, _, _ -> }, { ExecutionContext(unlocked, true) })
        assertTrue(registry(false).dispatch(plan) is DispatchResult.Rejected)
        assertTrue(registry(true).dispatch(plan, CommandSource.UNVERIFIED_VOICE) is DispatchResult.Rejected)
        assertEquals(0, calls)
    }
    @Test fun reviewKeepsRecipientAndCallerSimDistinctFromDialerOnlyRequests() {
        val description = action.describe()
        listOf(action.phoneNumber, "Test contact", target.label, "SIM slot 1", "Carrier charges", "not verified").forEach { assertTrue(description.contains(it)) }
        assertTrue((CommandParser().parse("call +12025550123") as ParsedCommand.Plan).value.actions.single() is Action.Dial)
        assertEquals(RiskLevel.MEDIUM, Action.Dial("+12025550123").risk())
    }
    @Test fun eligibleDialerRequestBindsCallerMetadataAndRetainsItsPlanIdentity() {
        val original = ActionPlan(listOf(Action.Dial("+1 (202) 555-0123", "1", "Test contact")), 1.0, "Call contact")
        val bound = DirectCallPlanner.bind(original, target)
        assertEquals(action, bound.actions.single())
        assertEquals(original.planId, bound.planId)
        assertEquals(original.confidence, bound.confidence, 0.0)
        assertEquals(RiskLevel.HIGH, bound.riskLevel)
    }
    @Test fun shortServiceEmergencyAndLocalNumbersKeepTheExactDialerRequest() {
        listOf("112", "911", "12345", "2025550123").forEach { number ->
            val original = ActionPlan(listOf(Action.Dial(number)), 1.0, "Original dialer request")
            assertSame(original, DirectCallPlanner.bind(original, target))
            assertEquals(RiskLevel.MEDIUM, original.riskLevel)
            assertTrue(original.actions.single().describe().startsWith("Open dialer"))
        }
    }
    @Test fun absentOrInvalidCallerSelectionCannotUpgradeOrRetargetTheDialer() {
        val original = ActionPlan(listOf(Action.Dial("+12025550123", "1", "Test contact")), 1.0, "Original")
        assertSame(original, DirectCallPlanner.bind(original, null))
        assertSame(original, DirectCallPlanner.bind(original, target.copy(subscriptionId = -1)))
    }
    @Test fun mixedPlansAndInvalidDialStringsNeverBecomePartlyDirectCalls() {
        val mixed = ActionPlan(listOf(Action.SetVolume(30), Action.Dial("+12025550123")), 1.0, "Mixed")
        assertSame(mixed, DirectCallPlanner.bind(mixed, target))
        val invalid = ActionPlan(listOf(Action.Dial("*21*+12025550123#")), 1.0, "Invalid")
        assertSame(invalid, DirectCallPlanner.bind(invalid, target))
        assertNotNull(PlanValidator.error(invalid))
        assertFalse(invalid.actions.any { it is Action.PlaceCall })
    }

}
