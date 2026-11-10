package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.policy.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ForgetPersonalFactTest {
    private val action = Action.ForgetPersonalFact("favourite colour", "blue", 2, "a".repeat(64))
    @Test fun deletionHasExactReviewAndStrictStandaloneCodec() {
        assertEquals(RiskLevel.DESTRUCTIVE, action.risk())
        assertEquals(action, ActionCodec.decode(ActionCodec.encode(action)))
        assertTrue(action.describe().contains("including historical"))
        assertTrue(action.describe().contains("blue"))
        assertNotNull(PlanValidator.error(ActionPlan(listOf(action, Action.SetVolume(0)), 1.0, "mixed")))
        for (invalid in listOf(action.copy(recordCount = 0), action.copy(fingerprint = "wrong"), action.copy(summary = "hidden\nvalue")))
            assertNotNull(PlanValidator.error(ActionPlan(listOf(invalid), 1.0, "invalid")))
    }
    @Test fun explicitSubjectCommandsAreNotReusablePlans() {
        val parser = CommandParser()
        assertEquals(ParsedCommand.ForgetPersonalFact("favourite colour"), parser.parse("forget my favourite colour"))
        assertEquals(ParsedCommand.ForgetPersonalFact("doctor"), parser.parse("forget what I told you about my doctor"))
        assertTrue(parser.parse("forget everything") is ParsedCommand.Clarify)
        assertNull(LearnedPhrase.create("forget colour", "forget my favourite colour").plan(parser, emptyList()))
    }
    @Test fun destructiveDeletionCannotRunAtPreparationAuthenticationOrReplay() = runTest {
        var effects = 0
        val registry = ActionRegistry(ActionExecutor { effects++; ActionResult(ExecutionStatus.SUCCESS, "fixture") }, ActionAudit { _, _, _, _ -> }, { ExecutionContext(true, true) })
        val plan = ActionPlan(listOf(action), 1.0, "owner deletion")
        val authentication = registry.dispatch(plan) as DispatchResult.NeedsAuthentication
        assertEquals(0, effects)
        assertTrue(registry.confirm(authentication.confirmation.token) is DispatchResult.Rejected)
        val fresh = registry.dispatch(plan) as DispatchResult.NeedsAuthentication
        val confirmation = registry.authenticate(fresh.confirmation.token) as DispatchResult.NeedsConfirmation
        assertEquals(0, effects)
        assertNotEquals(fresh.confirmation.token, confirmation.confirmation.token)
        assertTrue(registry.confirm(confirmation.confirmation.token) is DispatchResult.Executed)
        assertEquals(1, effects)
        assertTrue(registry.confirm(confirmation.confirmation.token) is DispatchResult.Rejected)
        assertEquals(1, effects)
    }
}
