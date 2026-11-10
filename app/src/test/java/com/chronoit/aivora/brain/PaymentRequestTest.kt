package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.policy.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PaymentRequestTest {
    private val request = PaymentRequest("example.shop@bank", "Test Shop", "5411", "checkout-test-1", 12550, "Tea & coffee?", "https://example.com/order?id=1&item=2")
    private val action = Action.PaymentHandoff(PaymentApp.GOOGLE_PAY, request)

    @Test fun exactPaiseAndAllowlistedFieldsSurviveCanonicalStorage() {
        assertTrue(request.valid())
        assertEquals("125.50", request.amount)
        assertEquals(request, PaymentRequest.parse(request.uri()))
        assertEquals(action, ActionCodec.decode(ActionCodec.encode(action)))
        assertEquals(RiskLevel.CRITICAL, action.risk())
        assertTrue(action.requiresNetwork())
        assertEquals(1L, PaymentRequest.parse(request.uri().replace("125.50", "0.01"))!!.amountPaise)
        assertNull(PaymentRequest.parse(request.uri().replace("125.50", "0.001")))
        assertNull(PaymentRequest.parse(request.uri().replace("125.50", "1e2")))
    }

    @Test fun incompleteDuplicateInjectedAndNonInrLinksAreRejected() {
        val uri = request.uri()
        listOf(uri + "&pa=other%40bank", uri + "&mode=04", uri + "&source=VERIFIED_VOICE", uri + "#fragment",
            uri.replace("upi://pay", "https://pay"), uri.replace("upi://pay", "upi://pay.evil"),
            uri.replace("cu=INR", "cu=USD"), uri.replace("125.50", "0"), uri.replace("125.50", "-1"),
            uri.replace("mc=5411&", ""), uri.replace("tr=checkout-test-1&", ""),
            uri.replace("pn=Test+Shop", "pn=%E2%80%AEHidden"), uri.replace("pn=Test+Shop", "pn=%FF"),
            uri.replace("pn=Test+Shop", "pn=%00Hidden")).forEach { assertNull(it, PaymentRequest.parse(it)) }
        assertNull(PaymentRequest.parse("upi://pay?pa=person@bank&am=10&cu=INR"))
    }

    @Test fun invalidTypedRequestsCannotBypassParserOrIntentBoundaries() {
        listOf(request.copy(payeeVpa = "person@bank&am=1"), request.copy(amountPaise = 0), request.copy(amountPaise = Long.MAX_VALUE),
            request.copy(merchantCode = ""), request.copy(reference = "bad/ref"), request.copy(payeeName = " Unreviewed "),
            request.copy(transactionUrl = "http://example.com/order"), request.copy(transactionUrl = "https://user:secret@example.com"),
            request.copy(transactionUrl = "https://example.com/#override")).forEach {
            assertFalse(it.valid())
            assertNotNull(PlanValidator.error(ActionPlan(listOf(action.copy(request = it)), 1.0, "test")))
            assertTrue(runCatching { it.uri() }.isFailure)
        }
        assertNotNull(PlanValidator.error(ActionPlan(listOf(Action.SetVolume(30), action), 1.0, "test")))
        assertTrue(runCatching { ActionCodec.decode(ActionCodec.encode(action) + "\textra") }.isFailure)
    }

    @Test fun explicitPaymentCommandsPreserveChosenAppAndNeverInventMerchantFields() {
        assertEquals(action, ((CommandParser().parse("review payment ${request.uri()} using Google Pay") as ParsedCommand.Plan).value.actions.single()))
        assertEquals(action.copy(app = PaymentApp.PHONEPE), ((CommandParser().parse("pay ${request.uri()} using PhonePe") as ParsedCommand.Plan).value.actions.single()))
        assertTrue(CommandParser().parse("pay upi://pay?pa=person@bank&am=125 using gpay") is ParsedCommand.Clarify)
        assertTrue(CommandParser().parse("pay Rahul one hundred rupees using gpay") is ParsedCommand.Clarify)
        assertTrue(CommandParser().parse("don't pay ${request.uri()} using gpay") is ParsedCommand.Clarify)
        assertNull(LearnedPhrase.create("pay shop", "pay ${request.uri()} using gpay").plan(CommandParser(), emptyList()))
    }

    @Test fun suppliedAmountMustMatchTheMerchantLinkExactly() {
        val parser = CommandParser()
        val thirty = request.copy(amountPaise = 3000)
        val accepted = parser.parse("pay 30 rs from ${thirty.uri()} using gpay") as ParsedCommand.Plan
        assertEquals(action.copy(request = thirty), accepted.value.actions.single())
        val spoken = parser.parse("pay thirty rupees from ${thirty.uri()} using gpay") as ParsedCommand.Plan
        assertEquals(accepted.value.actions, spoken.value.actions)
        assertTrue(parser.parse("pay thirty one rupees from ${thirty.uri()} using gpay") is ParsedCommand.Clarify)
        assertTrue(parser.parse("pay around thirty rupees from ${thirty.uri()} using gpay") is ParsedCommand.Clarify)
        assertTrue(parser.parse("pay 30.01 rupees from ${thirty.uri()} using gpay") is ParsedCommand.Clarify)
    }

    @Test fun missingLinkAmountRequiresAnExplicitPositiveAmount() {
        val parser = CommandParser()
        val link = request.uri().replace("&am=125.50", "")
        val parsed = parser.parse("pay thirty rupees from $link using gpay") as ParsedCommand.Plan
        assertEquals(action.copy(request = request.copy(amountPaise = 3000)), parsed.value.actions.single())
        assertTrue(parser.parse("pay $link using gpay") is ParsedCommand.Clarify)
        assertTrue(parser.parse("pay zero rupees from $link using gpay") is ParsedCommand.Clarify)
        assertTrue(parser.parse("pay thirty rupees from $link&am= using gpay") is ParsedCommand.Clarify)
    }

    @Test fun criticalHandoffNeedsAuthenticationThenNewExactConfirmationAndNoReplay() = runTest {
        var attempts = 0
        val registry = ActionRegistry(ActionExecutor { attempts++; ActionResult(ExecutionStatus.REQUIRES_USER_INTERACTION, "Unverified review handoff") },
            ActionAudit { _, _, _, _ -> }, { ExecutionContext(true, true) })
        val plan = ActionPlan(listOf(action), 1.0, "Explicit payment review")
        assertTrue(registry.dispatch(plan, CommandSource.UNVERIFIED_VOICE) is DispatchResult.Rejected)
        val first = registry.dispatch(plan) as DispatchResult.NeedsAuthentication
        assertEquals(0, attempts)
        assertTrue(registry.confirm(first.confirmation.token) is DispatchResult.Rejected)
        val second = registry.dispatch(plan) as DispatchResult.NeedsAuthentication
        val review = registry.authenticate(second.confirmation.token) as DispatchResult.NeedsConfirmation
        assertNotEquals(second.confirmation.token, review.confirmation.token)
        assertEquals(0, attempts)
        val result = registry.confirm(review.confirmation.token) as DispatchResult.Executed
        assertEquals(ExecutionStatus.REQUIRES_USER_INTERACTION, result.results.single().status)
        assertEquals(1, attempts)
        assertTrue(registry.confirm(review.confirmation.token) is DispatchResult.Rejected)
        assertEquals(1, attempts)
    }

    @Test fun lockedDeviceNeverReachesPaymentExecutor() = runTest {
        var attempts = 0
        val registry = ActionRegistry(ActionExecutor { attempts++; error("Must not execute") }, ActionAudit { _, _, _, _ -> }, { ExecutionContext(false, true) })
        assertTrue(registry.dispatch(ActionPlan(listOf(action), 1.0, "test")) is DispatchResult.Rejected)
        assertEquals(0, attempts)
    }
}
