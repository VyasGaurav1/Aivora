package com.chronoit.aivora.voice

import android.util.AtomicFile
import com.chronoit.aivora.security.FieldCipher
import java.io.File

/** Atomic, authenticated feature storage. No raw recordings or unencrypted voiceprints. */
class VoiceProfileStore(directory: File, private val cipher: FieldCipher, private val identifier: String) {
    init { require(identifier.matches(Regex("[a-z0-9_.-]+"))); check(directory.exists() || directory.mkdirs()) }
    private val file = AtomicFile(File(directory, "$identifier.enc"))
    @Synchronized fun read(): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        require(file.baseFile.length() <= 2_000_000) { "Voice profile is too large" }
        return cipher.decrypt(file.readFully(), "com.chronoit.aivora.voice.$identifier")
    }
    @Synchronized fun write(value: String) {
        require(value.length <= 1_000_000)
        val bytes = cipher.encrypt(value, "com.chronoit.aivora.voice.$identifier")
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    @Synchronized fun delete() = file.delete()
}
