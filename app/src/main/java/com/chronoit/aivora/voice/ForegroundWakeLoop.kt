package com.chronoit.aivora.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

/** Owns only its wake capture, so pausing it cannot cancel a replacement command or reply. */
class ForegroundWakeLoop(
    private val scope: CoroutineScope,
    private val canStart: () -> Boolean,
    private val canContinue: () -> Boolean,
    private val listen: () -> Job,
    private val onStopped: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onStarted: () -> Unit = {},
) {
    private val generation = AtomicLong(0)
    private val paused = MutableStateFlow(false)
    @Volatile private var loop: Job? = null
    @Volatile private var listening: Job? = null

    @Synchronized fun start() {
        if (loop?.isActive == true) return
        paused.value = false
        val session = generation.incrementAndGet()
        onStarted()
        loop = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            try {
                while (isActive) {
                    paused.first { !it }
                    if (canStart()) {
                        val capture = listen()
                        listening = capture
                        try {
                            while (capture.isActive && canContinue()) delay(100)
                        } finally {
                            withContext(NonCancellable) { capture.cancelAndJoin() }
                            if (listening === capture) listening = null
                        }
                    }
                    delay(250)
                }
            } finally { stopped(session) }
        }.also { it.start() }
    }
    fun pause() { paused.value = true; listening?.cancel() }
    fun resume() { paused.value = false }
    @Synchronized private fun stopped(session: Long) { if (generation.get() == session) onStopped() }
    @Synchronized fun stop() {
        generation.incrementAndGet()
        loop?.cancel()
        loop = null
        pause()
        onStopped()
    }
}
