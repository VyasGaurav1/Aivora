package com.chronoit.aivora.brain

import org.junit.Assert.*
import org.junit.Test

class ContactResolverTest {
    private val rahul = ContactCandidate("1", "Rahul", "+919876543210", "mobile")
    @Test fun uniqueExactNameResolvesButPartialAndMultipleNumbersAsk() {
        assertEquals(ContactResolution.Resolved(rahul), ContactResolver.resolve("rahul", listOf(rahul)))
        assertTrue(ContactResolver.resolve("Rah", listOf(rahul)) is ContactResolution.Choose)
        assertTrue(ContactResolver.resolve("Rahul", listOf(rahul, rahul.copy(number = "9876543211", label = "work"))) is ContactResolution.Choose)
        assertEquals(ContactResolution.Missing, ContactResolver.resolve("Mom", emptyList()))
    }
    @Test fun duplicateRowsDoNotCreateFalseAmbiguityButOversizedResultsCannotResolve() {
        assertEquals(ContactResolution.Resolved(rahul), ContactResolver.resolve("Rahul", listOf(rahul, rahul)))
        assertEquals(ContactResolution.TooMany, ContactResolver.resolve("Rahul", List(101) { rahul.copy(id = "$it") }))
    }
    @Test fun dangerousDialStringsAreRejectedRatherThanTransformedIntoDifferentNumbers() {
        assertEquals("+919876543210", normalizePhone("+91 (987) 654-3210"))
        listOf("*123#", "123;456", "1-800-FLOWERS", "++123", "12+34").forEach { assertEquals(it, "", normalizePhone(it)) }
    }
}
