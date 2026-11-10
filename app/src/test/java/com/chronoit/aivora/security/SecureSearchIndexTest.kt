package com.chronoit.aivora.security

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class SecureSearchIndexTest {
    private fun key() = KeyGenerator.getInstance("HmacSHA256").generateKey()
    @Test fun normalizedWordsAreStableButOtherKeysCannotGuessTokens() {
        val index = SecureSearchIndex { key }
        assertEquals(index.content("Rahul, report.PDF"), index.content("RAHUL REPORT pdf"))
        assertFalse(index.content("Rahul").contains("Rahul", true))
        val other = SecureSearchIndex { key() }
        assertNotEquals(index.content("Rahul"), other.content("Rahul"))
    }
    private val key = key()
    @Test fun queryCannotInjectFtsOperatorsAndEmptyTextIsNotAMatchAll() {
        val index = SecureSearchIndex { key }
        assertNull(index.query("***"))
        val query = index.query("' OR tokens:* --")!!
        assertTrue(query.matches(Regex("tokens:[a-f0-9]{64}( tokens:[a-f0-9]{64})*")))
    }
}
