package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.*
import org.junit.Assert.*
import org.junit.Test

class SpokenPaymentFieldsTest {
    private val request = PaymentRequest("a.A01@bank", "Shop", "0012", "Ref-001", 12505, "Tea & coffee", "https://example.com/a?b=1&c=2")
    private val action = Action.PaymentHandoff(PaymentApp.GOOGLE_PAY, request)
    @Test fun punctuationZerosAndCaseAreExplicitInsteadOfProseNumbers() {
        assertEquals("alpha dot capital alpha zero one at bravo alpha november kilo", SpokenIdentifier.render("a.A01@bank"))
        assertEquals("zero zero one two", SpokenIdentifier.render("0012"))
        assertEquals("hyphen underscore dot slash backslash", SpokenIdentifier.render("-_. /\\".replace(" ", "")))
        assertEquals("ampersand equals plus percent question mark", SpokenIdentifier.render("&=+%?"))
    }
    @Test fun everyPrintableAsciiCharacterHasADistinctSpelling() {
        val spellings = (32..126).map { SpokenIdentifier.render(it.toChar().toString())!! }
        assertEquals(95, spellings.toSet().size)
        assertTrue(spellings.none(String::isBlank))
        assertNotEquals(SpokenIdentifier.render("0012"), SpokenIdentifier.render("12"))
        assertNotEquals(SpokenIdentifier.render("a.b"), SpokenIdentifier.render("ab"))
        assertNotEquals(SpokenIdentifier.render("A"), SpokenIdentifier.render("a"))
    }
    @Test fun exactAmountsUseIntegerRupeesAndHundredthsWithoutRounding() {
        mapOf(1L to "0 Indian rupees and 1 hundredth", 5L to "0 Indian rupees and 5 hundredths",
            100L to "1 Indian rupee and 0 hundredths", 12505L to "125 Indian rupees and 5 hundredths",
            12550L to "125 Indian rupees and 50 hundredths", 999999999999L to "9999999999 Indian rupees and 99 hundredths").forEach { (amount, expected) ->
            assertTrue(PaymentReadback.create(action.copy(request = request.copy(amountPaise = amount)))!!.contains(expected))
        }
    }
    @Test fun unsupportedCharactersFailSpokenReviewWithoutChangingTheActualRequest() {
        assertNull(SpokenIdentifier.render("a\n"))
        assertNull(SpokenIdentifier.render("\u20B9"))
        assertNull(SpokenIdentifier.render("a".repeat(2049)))
        val unicode = action.copy(request = request.copy(payeeName = "Shop \u20B9"))
        assertTrue(unicode.request.valid())
        assertNull(PaymentReadback.create(unicode))
        assertNull(ActionReadback.create(ActionPlan(listOf(unicode), 1.0, "test")))
        assertEquals(unicode, ActionCodec.decode(ActionCodec.encode(unicode)))
        assertTrue(unicode.describe().contains("Shop \u20B9"))
    }
    @Test fun optionalEmptyFieldsCannotBeConfusedWithLiteralTextAndAppsStayExplicit() {
        val empty = PaymentReadback.create(action.copy(request = request.copy(note = "", transactionUrl = null)))!!
        assertTrue(empty.contains("Note: empty. Transaction URL: none."))
        val literal = PaymentReadback.create(action.copy(request = request.copy(note = "empty")))!!
        assertTrue(literal.contains("Note: echo mike papa tango yankee."))
        assertTrue(PaymentReadback.create(action.copy(app = PaymentApp.PHONEPE))!!.contains("Phone Pay"))
        assertEquals(action, ActionCodec.decode(ActionCodec.encode(action)))
    }
}
