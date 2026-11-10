package com.chronoit.aivora.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Wake capture yields to foreground conversations and spoken reminders, including on Home. */
object VoiceRuntime {
    private val lock = Any()
    private var owners = 0
    private var conversations = 0
    private val mutableBusy = MutableStateFlow(false)
    private val mutableConversation = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    val inConversation = mutableConversation.asStateFlow()
    private val speech = Mutex()
    fun hold(conversation: Boolean = false): AutoCloseable {
        synchronized(lock) {
            owners++
            if (conversation) conversations++
            mutableBusy.value = true
            mutableConversation.value = conversations > 0
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(lock) {
                owners--
                if (conversation) conversations--
                mutableBusy.value = owners > 0
                mutableConversation.value = conversations > 0
            }
        }
    }
    suspend fun speak(block: suspend () -> Unit) = speech.withLock { hold().use { block() } }
    suspend fun awaitIdle() { busy.first { !it } }
    suspend fun awaitQuiet() { speech.withLock { } }
    suspend fun speakWhenIdle(block: suspend () -> Unit) {
        while (true) {
            awaitIdle()
            speech.lock()
            val ownership = synchronized(lock) { if (owners == 0) hold() else null }
            if (ownership == null) { speech.unlock(); continue }
            try { block() } finally { ownership.close(); speech.unlock() }
            return
        }
    }
}
