package com.chronoit.aivora.voice

import java.util.concurrent.atomic.AtomicReference

class MicrophoneInUseException : IllegalStateException("AIVORA's microphone is already in use")

/** One physical recorder across UI, assistant recognition and background wake components. */
object MicrophoneLease {
    private val owner = AtomicReference<Any?>(null)
    fun isAvailable(): Boolean = owner.get() == null
    fun acquire(): AutoCloseable {
        val token = Any()
        if (!owner.compareAndSet(null, token)) throw MicrophoneInUseException()
        return AutoCloseable { owner.compareAndSet(token, null) }
    }
}
