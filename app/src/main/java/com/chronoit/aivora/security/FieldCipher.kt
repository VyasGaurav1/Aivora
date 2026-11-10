package com.chronoit.aivora.security

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned authenticated encryption; record identity is bound to the ciphertext. */
class FieldCipher(private val keyProvider: () -> SecretKey) {
    fun encrypt(plaintext: String, recordId: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(recordId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(2 + cipher.iv.size + encrypted.size)
            .put(1.toByte()).put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
    }
    fun decrypt(payload: ByteArray, recordId: String): String {
        require(payload.size >= 30 && payload[0] == 1.toByte()) { "Invalid encrypted record" }
        val ivSize = payload[1].toInt() and 0xff
        require(ivSize == 12 && payload.size >= 2 + ivSize + 16) { "Invalid encrypted record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, payload.copyOfRange(2, 2 + ivSize)))
        cipher.updateAAD(recordId.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(payload.copyOfRange(2 + ivSize, payload.size)).toString(Charsets.UTF_8)
    }
}
