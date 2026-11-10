package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.scheduler.*
import com.chronoit.aivora.policy.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DueActionReviewTest {
    @Test fun connectionPreparationNeverWaivesChargingOrOfflineActionConditions() {
        val offline = ConditionSnapshot(false, false, true)
        val network = TaskConditions(network = RequiredNetwork.VALIDATED)
        assertTrue(network.permitsConnectionReview(offline, true))
        assertFalse(network.permitsConnectionReview(offline, false))
        assertFalse(TaskConditions().permitsConnectionReview(offline, true))
        val charged = TaskConditions(charging = true, network = RequiredNetwork.UNMETERED)
        assertFalse(charged.permitsConnectionReview(offline, true))
        assertTrue(charged.permitsConnectionReview(offline.copy(charging = true), true))
        assertFalse(charged.satisfied(offline.copy(charging = true, validatedNetwork = true)))
    }
    @Test fun savedReviewGuardRunsBeforeConfirmationAndCannotExecuteOnFailure() = runTest {
        var ready = false
        var effects = 0
        val registry = ActionRegistry(ActionExecutor { effects++; ActionResult(ExecutionStatus.SUCCESS, "fixture") },
            ActionAudit { _, _, _, _ -> }, { ExecutionContext(true, true) }, requireConfirmation = true, reviewGuard = { ready })
        val plan = ActionPlan(listOf(Action.SetVolume(0)), 1.0, "saved")
        assertTrue(registry.dispatch(plan) is DispatchResult.Rejected)
        assertEquals(0, effects)
        ready = true
        val pending = registry.dispatch(plan) as DispatchResult.NeedsConfirmation
        assertEquals(0, effects)
        ready = false
        assertTrue(registry.dispatch(plan) is DispatchResult.Rejected)
        assertTrue(registry.confirm(pending.confirmation.token) is DispatchResult.Rejected)
        assertEquals(0, effects)
    }
    private fun task(id: String, due: Long = 100, state: String = "AWAITING_REVIEW", action: Boolean = true) = Reminder(id, "fixture", due, 0, state, action)
    @Test fun onlyDueUnfinishedActionsCanBeSelected() {
        val ignored = listOf(task("future", 200), task("reminder", action = false), task("started", state = "STARTED"), task("handled", state = "HANDLED"), task("paused", state = "PAUSED"), task("canceled", state = "CANCELED"))
        assertEquals(DueActionSelection.None, DueActionSelector.select(ignored, 150))
        for (state in ScheduledReviewPolicy.states) assertEquals("ready", (DueActionSelector.select(ignored + task("ready", state = state), 150) as DueActionSelection.Selected).task.id)
    }
    @Test fun nextHasStableChronologicalOrderAndUnqualifiedMultipleDoesNotGuess() {
        val tasks = listOf(task("z", 90), task("b", 80), task("a", 80))
        assertEquals(DueActionSelection.Multiple(3), DueActionSelector.select(tasks, 100))
        assertEquals("a", (DueActionSelector.select(tasks, 100, next = true) as DueActionSelection.Selected).task.id)
        assertEquals("z", (DueActionSelector.select(tasks, 100, id = "z") as DueActionSelection.Selected).task.id)
        assertEquals(DueActionSelection.None, DueActionSelector.select(tasks, 100, id = "unknown"))
    }
    @Test fun parserDistinguishesReviewFromExecutionAndBindsOnlyExactIds() {
        val parser = CommandParser()
        val id = "12345678-1234-1234-1234-123456789abc"
        assertEquals(ParsedCommand.ReviewScheduledAction(), parser.parse("review due action"))
        assertEquals(ParsedCommand.ReviewScheduledAction(next = true), parser.parse("review next due action"))
        assertEquals(ParsedCommand.ReviewScheduledAction(id), parser.parse("review scheduled action $id"))
        assertTrue(parser.parse("review action invalid") is ParsedCommand.Clarify)
        assertTrue(parser.parse("run all due actions") is ParsedCommand.Clarify)
    }
    @Test fun scheduledScopeRequiresExactLowRiskConfirmationAndPreservesVoiceSource() = runTest {
        var effects = 0
        val sources = mutableListOf<CommandSource>()
        val registry = ActionRegistry(ActionExecutor { effects++; ActionResult(ExecutionStatus.SUCCESS, "fixture") },
            ActionAudit { _, _, confirmed, source -> assertTrue(confirmed); sources += source }, { ExecutionContext(true, true) }, requireConfirmation = true)
        val review = registry.dispatch(ActionPlan(listOf(Action.SetVolume(30)), 1.0, "saved"), CommandSource.VERIFIED_VOICE) as DispatchResult.NeedsConfirmation
        assertEquals(0, effects)
        assertTrue(registry.confirm(review.confirmation.token) is DispatchResult.Executed)
        assertEquals(1, effects)
        assertTrue(sources.isNotEmpty() && sources.all { it == CommandSource.VERIFIED_VOICE })
        assertTrue(registry.confirm(review.confirmation.token) is DispatchResult.Rejected)
        assertEquals(1, effects)
    }
    @Test fun reviewRequirementCannotWeakenAuthenticationExpiryOrSourceTrust() = runTest {
        var now = 0L
        var effects = 0
        val registry = ActionRegistry(ActionExecutor { effects++; error("must not execute") }, ActionAudit { _, _, _, _ -> },
            { ExecutionContext(true, true) }, clock = { now }, requireConfirmation = true)
        val low = ActionPlan(listOf(Action.SetVolume(30)), 1.0, "saved")
        assertTrue(registry.dispatch(low, CommandSource.UNVERIFIED_VOICE) is DispatchResult.Rejected)
        val pending = registry.dispatch(low) as DispatchResult.NeedsConfirmation
        now = pending.confirmation.expiresAt
        assertTrue(registry.confirm(pending.confirmation.token) is DispatchResult.Rejected)
        val high = ActionPlan(listOf(Action.ShareFile("content://files/one", "text/plain")), 1.0, "saved")
        assertTrue(registry.dispatch(high) is DispatchResult.NeedsAuthentication)
        assertEquals(0, effects)
    }
}
