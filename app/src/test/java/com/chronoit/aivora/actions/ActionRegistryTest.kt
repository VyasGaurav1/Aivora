package com.chronoit.aivora.actions

import com.chronoit.aivora.policy.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ActionRegistryTest {
    private val executed = mutableListOf<Action>()
    private var unlocked = true
    private var time = 1_000L
    private val registry = ActionRegistry(ActionExecutor { executed += it; ActionResult(ExecutionStatus.SUCCESS, "Verified") },
        ActionAudit { _, _, _, _ -> }, { ExecutionContext(unlocked, true) }, { time })
    private fun plan(vararg actions: Action, confidence: Double = 1.0) = ActionPlan(actions.toList(), confidence, "test")

    @Test fun notificationReplyNeedsFreshApprovalAndCannotBeStoredOrBundled() = runTest {
        val action = Action.ReplyToNotification(MessagingApp.WHATSAPP, "Priya", "Are you coming?", "Yes", "123e4567-e89b-12d3-a456-426614174000")
        assertTrue(action.requiresNetwork())
        assertTrue(runCatching { ActionCodec.encode(action) }.isFailure)
        assertTrue(registry.dispatch(plan(action, Action.Flashlight(true))) is DispatchResult.Rejected)
        val auth = registry.dispatch(plan(action)) as DispatchResult.NeedsAuthentication
        assertTrue(executed.isEmpty())
        val confirm = registry.authenticate(auth.confirmation.token) as DispatchResult.NeedsConfirmation
        assertTrue(executed.isEmpty())
        registry.confirm(confirm.confirmation.token)
        assertEquals(listOf(action), executed)
        assertTrue(registry.confirm(confirm.confirmation.token) is DispatchResult.Rejected)
    }

    @Test fun lowRiskExecutesAfterValidation() = runTest {
        assertTrue(registry.dispatch(plan(Action.SetVolume(40))) is DispatchResult.Executed)
        assertEquals(listOf(Action.SetVolume(40)), executed)
    }
    @Test fun malformedAndUncertainPlansNeverExecute() = runTest {
        assertTrue(registry.dispatch(plan(Action.SetVolume(101))) is DispatchResult.Rejected)
        assertTrue(registry.dispatch(plan(Action.Flashlight(true), confidence = 0.5)) is DispatchResult.Rejected)
        assertTrue(registry.dispatch(plan(Action.SetVolume(40), confidence = Double.NaN)) is DispatchResult.Rejected)
        assertTrue(executed.isEmpty())
    }
    @Test fun confirmationIsBoundToExactPlanAndSingleUse() = runTest {
        val result = registry.dispatch(plan(Action.SetBrightness(40))) as DispatchResult.NeedsConfirmation
        assertTrue(executed.isEmpty())
        registry.confirm(result.confirmation.token)
        assertEquals(listOf(Action.SetBrightness(40)), executed)
        assertTrue(registry.confirm(result.confirmation.token) is DispatchResult.Rejected)
    }
    @Test fun lockAndExpiryAreRechecked() = runTest {
        var result = registry.dispatch(plan(Action.SetBrightness(40))) as DispatchResult.NeedsConfirmation
        unlocked = false
        assertTrue(registry.confirm(result.confirmation.token) is DispatchResult.Rejected)
        unlocked = true
        result = registry.dispatch(plan(Action.SetBrightness(40))) as DispatchResult.NeedsConfirmation
        time += 60_001
        assertTrue(registry.confirm(result.confirmation.token) is DispatchResult.Rejected)
        assertTrue(executed.isEmpty())
    }
    @Test fun cancelAndNewCommandInvalidateOldConfirmation() = runTest {
        val first = registry.dispatch(plan(Action.SetBrightness(40))) as DispatchResult.NeedsConfirmation
        registry.cancel()
        assertTrue(registry.confirm(first.confirmation.token) is DispatchResult.Rejected)
        val second = registry.dispatch(plan(Action.SetBrightness(40))) as DispatchResult.NeedsConfirmation
        registry.dispatch(plan(Action.Flashlight(false)))
        assertTrue(registry.confirm(second.confirmation.token) is DispatchResult.Rejected)
        assertEquals(listOf(Action.Flashlight(false)), executed)
    }
    @Test fun pipelineStopsAtFirstUnverifiedResult() = runTest {
        var calls = 0
        val registry = ActionRegistry(ActionExecutor { calls++; ActionResult(ExecutionStatus.REQUIRES_USER_INTERACTION, "Continue in system UI") },
            ActionAudit { _, _, _, _ -> }, { ExecutionContext(true, true) })
        registry.dispatch(plan(Action.Camera, Action.Flashlight(true)))
        assertEquals(1, calls)
    }
    @Test fun auditFailurePreventsSideEffects() = runTest {
        val registry = ActionRegistry(ActionExecutor { executed += it; ActionResult(ExecutionStatus.SUCCESS, "done") },
            ActionAudit { _, _, _, _ -> error("storage unavailable") }, { ExecutionContext(true, true) })
        assertTrue(runCatching { registry.dispatch(plan(Action.Flashlight(true))) }.isFailure)
        assertTrue(executed.isEmpty())
    }
    @Test fun unverifiedVoiceCannotBorrowForegroundOwnership() = runTest {
        assertTrue(registry.dispatch(plan(Action.SetVolume(40)), CommandSource.UNVERIFIED_VOICE) is DispatchResult.Rejected)
        assertTrue(executed.isEmpty())
        assertTrue(registry.dispatch(plan(Action.SetVolume(40)), CommandSource.VERIFIED_VOICE) is DispatchResult.Executed)
    }
    @Test fun sensitivePlanNeedsAuthenticationThenSeparateConfirmation() = runTest {
        val share = Action.ShareFile("content://files/private", "application/pdf")
        val auth = registry.dispatch(plan(share)) as DispatchResult.NeedsAuthentication
        assertTrue(executed.isEmpty())
        val confirm = registry.authenticate(auth.confirmation.token) as DispatchResult.NeedsConfirmation
        assertTrue(executed.isEmpty())
        assertNotEquals(auth.confirmation.token, confirm.confirmation.token)
        registry.confirm(confirm.confirmation.token)
        assertEquals(listOf(share), executed)
        assertTrue(registry.confirm(confirm.confirmation.token) is DispatchResult.Rejected)
    }
    @Test fun authenticationTokenCannotBeUsedAsConfirmationOrReplayed() = runTest {
        val share = plan(Action.ShareFile("content://files/private", "application/pdf"))
        val auth = registry.dispatch(share) as DispatchResult.NeedsAuthentication
        assertTrue(registry.confirm(auth.confirmation.token) is DispatchResult.Rejected)
        assertTrue(registry.authenticate(auth.confirmation.token) is DispatchResult.Rejected)
        assertTrue(executed.isEmpty())
    }
    @Test fun authenticationExpiresAndDoesNotBypassLockRecheck() = runTest {
        val share = plan(Action.ShareFile("content://files/private", "application/pdf"))
        var auth = registry.dispatch(share) as DispatchResult.NeedsAuthentication
        time += 60_001
        assertTrue(registry.authenticate(auth.confirmation.token) is DispatchResult.Rejected)
        auth = registry.dispatch(share) as DispatchResult.NeedsAuthentication
        val confirm = registry.authenticate(auth.confirmation.token) as DispatchResult.NeedsConfirmation
        unlocked = false
        assertTrue(registry.confirm(confirm.confirmation.token) is DispatchResult.Rejected)
        assertTrue(executed.isEmpty())
    }
    @Test fun plansAreImmutableSnapshotsAndLockIsRecheckedBetweenSteps() = runTest {
        val actions = mutableListOf<Action>(Action.SetBrightness(40), Action.SetVolume(20))
        val pending = registry.dispatch(ActionPlan(actions, 1.0, "test")) as DispatchResult.NeedsConfirmation
        actions.clear()
        assertEquals(2, pending.confirmation.plan.actions.size)
        assertTrue(runCatching { (pending.confirmation.plan.actions as MutableList<Action>).clear() }.isFailure)
        val calls = mutableListOf<Action>()
        val registry = ActionRegistry(ActionExecutor { calls += it; unlocked = false; ActionResult(ExecutionStatus.SUCCESS, "verified") },
            ActionAudit { _, _, _, _ -> }, { ExecutionContext(unlocked, true) })
        registry.dispatch(plan(Action.SetVolume(10), Action.Flashlight(true)))
        assertEquals(listOf(Action.SetVolume(10)), calls)
    }
}
