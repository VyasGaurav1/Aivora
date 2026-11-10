package com.chronoit.aivora.security

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class FieldCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = FieldCipher { key }
    @Test fun encryptsAndAuthenticatesRecord() {
        val payload = cipher.encrypt("private memory", "record-1")
        assertFalse(payload.toString(Charsets.UTF_8).contains("private memory"))
        assertEquals("private memory", cipher.decrypt(payload, "record-1"))
        assertFalse(payload.contentEquals(cipher.encrypt("private memory", "record-1")))
    }
    @Test fun rejectsTamperingAndRecordSwaps() {
        val payload = cipher.encrypt("private memory", "record-1")
        assertTrue(runCatching { cipher.decrypt(payload, "record-2") }.isFailure)
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decrypt(payload, "record-1") }.isFailure)
    }
}
