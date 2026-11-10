package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.*
import org.junit.Assert.*
import org.junit.Test

class ActionReadbackTest {
    private val request = PaymentRequest("test.shop@bank", "Test Shop", "5411", "checkout-123", 12550, "Tea and coffee", "https://example.com/order?id=123")
    private val action = Action.PaymentHandoff(PaymentApp.GOOGLE_PAY, request)
    private fun plan(action: Action) = ActionPlan(listOf(action), 1.0, "Explicit fixture")

    @Test fun completePaymentReviewAboveOldLimitIncludesEveryTransmittedField() {
        val prompt = ActionReadback.create(plan(action))!!
        assertTrue(prompt.length > 300)
        assertTrue(prompt.length <= ActionReadback.MAX_PROMPT_CHARACTERS)
        listOf(request.payeeVpa, request.payeeName, request.merchantCode, request.reference, request.note, request.transactionUrl!!)
            .forEach { assertTrue(it, prompt.contains(SpokenIdentifier.render(it)!!)) }
        assertTrue(prompt.contains("125 Indian rupees and 50 hundredths of a rupee"))
        assertTrue(prompt.contains(action.app.displayName))
        assertTrue(prompt.contains(PaymentReadback.create(action)!!))
        assertTrue(prompt.contains("Payee identity is unverified"))
        assertTrue(prompt.endsWith("Say confirm the action I just reviewed to approve, or cancel."))
    }
    @Test fun onlineReviewRetainsExactTaskAndNeverAsksForActionExecutionApproval() {
        val prompt = ActionReadback.create(plan(action), connection = true)!!
        assertTrue(prompt.contains(PaymentReadback.create(action)!!))
        assertTrue(prompt.endsWith("Say connect for this task to allow connection controls, or cancel."))
        assertFalse(prompt.contains("Say confirm the action"))
    }
    @Test fun oversizedAndInvalidPlansAreRefusedWithoutTruncation() {
        val long = action.copy(request = request.copy(transactionUrl = "https://example.com/" + "a".repeat(1400)))
        assertTrue(long.request.valid())
        assertNull(ActionReadback.create(plan(long)))
        assertNull(ActionReadback.create(plan(long), connection = true))
        assertNull(ActionReadback.create(plan(action.copy(request = request.copy(amountPaise = -1)))))
        assertNull(ActionReadback.create(ActionPlan(emptyList(), 1.0, "invalid")))
    }
    @Test fun messageReviewPreservesTheWholeBodyThroughThePromptBoundary() {
        val prompts = (1..1200).map { size ->
            val message = Action.DraftMessage("+919876543210", "x".repeat(size))
            size to ActionReadback.create(plan(message))
        }
        val last = prompts.last { it.second != null }
        assertEquals(ActionReadback.MAX_PROMPT_CHARACTERS, last.second!!.length)
        assertTrue(last.second!!.contains("x".repeat(last.first)))
        assertNull(prompts.first { it.first == last.first + 1 }.second)
    }
}
