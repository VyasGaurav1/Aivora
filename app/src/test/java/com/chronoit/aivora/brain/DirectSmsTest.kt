package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.platform.*
import com.chronoit.aivora.policy.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DirectSmsTest {
    @Test fun statusQuestionsCannotCreateOrRepeatASend() {
        val parser = CommandParser()
        for (text in listOf("SMS status", "what is the status of my last SMS?", "did my last SMS send"))
            assertEquals(ParsedCommand.SmsStatus, parser.parse(text))
    }
    private val target = SmsTarget(1, 0, 1, "Fixture SIM")
    private val draft = Action.DraftMessage("+12025550123", "Hello", "42", "Fixture")
    private fun plan(action: Action) = ActionPlan(listOf(action), 1.0, "fixture")
    @Test fun plannerBindsOnlyEligibleSingleSmsDraftAndPreservesFallbacks() {
        val source = plan(draft)
        val bound = DirectSmsPlanner.bind(source, target)
        assertEquals(Action.SendSms(draft.phoneNumber, draft.body, target, draft.contactId, draft.contactName), bound.actions.single())
        assertSame(source, DirectSmsPlanner.bind(source, null))
        for (body in listOf("x".repeat(161))) {
            val long = plan(draft.copy(body = body)); assertSame(long, DirectSmsPlanner.bind(long, target))
        }
        val local = plan(draft.copy(phoneNumber = "5551234")); assertSame(local, DirectSmsPlanner.bind(local, target))
        val multiple = source.copy(actions = source.actions + Action.SetVolume(0)); assertSame(multiple, DirectSmsPlanner.bind(multiple, target))
        val whatsapp = plan(Action.DraftAppMessage(MessagingApp.WHATSAPP, draft.phoneNumber, "Hello")); assertSame(whatsapp, DirectSmsPlanner.bind(whatsapp, target))
    }
    @Test fun exactMessageTargetCodecAndStandaloneRiskArePreserved() {
        val action = Action.SendSms(draft.phoneNumber, "Exact & complete", target)
        assertEquals(RiskLevel.HIGH, action.risk())
        assertFalse(action.requiresNetwork())
        assertEquals(action, ActionCodec.decode(ActionCodec.encode(action)))
        assertTrue(action.describe().contains(action.body))
        assertTrue(action.describe().contains(target.label))
        assertNotNull(PlanValidator.error(plan(action.copy(body = "x".repeat(161)))))
        assertNotNull(PlanValidator.error(plan(action.copy(phoneNumber = "112"))))
        assertNotNull(PlanValidator.error(plan(action).copy(actions = listOf(action, Action.SetVolume(0)))))
    }
    @Test fun sendingRequiresAuthenticationAndFreshConfirmation() = runTest {
        var effects = 0
        val registry = ActionRegistry(ActionExecutor { effects++; ActionResult(ExecutionStatus.SUCCESS, "fixture submission") }, ActionAudit { _, _, _, _ -> }, { ExecutionContext(true, true) })
        val auth = registry.dispatch(plan(Action.SendSms(draft.phoneNumber, draft.body, target))) as DispatchResult.NeedsAuthentication
        assertEquals(0, effects)
        val confirmation = registry.authenticate(auth.confirmation.token) as DispatchResult.NeedsConfirmation
        assertEquals(0, effects)
        assertTrue(registry.confirm(confirmation.confirmation.token) is DispatchResult.Executed)
        assertEquals(1, effects)
        assertTrue(registry.confirm(confirmation.confirmation.token) is DispatchResult.Rejected)
    }
    @Test fun callbackNoncesConsumeExactlyOnceAndCanceledObserversCannotComplete() = runTest {
        val first = SmsSentResults.register()
        val second = SmsSentResults.register()
        assertNotEquals(first.first, second.first)
        SmsSentResults.complete("unknown", -1)
        assertFalse(first.second.isCompleted)
        SmsSentResults.complete(first.first, -1)
        assertEquals(-1, first.second.await())
        SmsSentResults.complete(first.first, 9)
        assertEquals(-1, first.second.await())
        assertFalse(second.second.isCompleted)
        SmsSentResults.remove(second.first)
        SmsSentResults.complete(second.first, -1)
        assertTrue(second.second.isCancelled)
    }
}

