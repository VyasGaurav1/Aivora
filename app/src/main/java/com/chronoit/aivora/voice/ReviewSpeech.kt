package com.chronoit.aivora.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Speech completing successfully is required before the caller may start reply audio. */
internal object ReviewSpeech {
    suspend fun present(stillCurrent: () -> Boolean, speak: suspend () -> Unit): Boolean {
        currentCoroutineContext().ensureActive()
        if (!stillCurrent()) return false
        speak()
        currentCoroutineContext().ensureActive()
        return stillCurrent()
    }
}
