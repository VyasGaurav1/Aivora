package com.chronoit.aivora.voice

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean

/** Offline wake-word barge-in. Never uploads playback or saved-memory readbacks. */
internal class SpeechInterruption(private val context: Context) {
    suspend fun play(stop: () -> Unit, onWake: () -> Unit, audio: Flow<AudioFrame>? = null, playback: suspend () -> Unit): Boolean = coroutineScope {
        val microphone = MicrophoneCapture(context, echoCancelled = true)
        val interrupted = AtomicBoolean()
        val listener = launch(Dispatchers.IO) {
            try {
                withTimeout(2500) { while (!MicrophoneLease.isAvailable()) delay(20) }
                microphone.prepare()
                do {
                    if (awaitKeywordWake(context.assets, audio ?: microphone.frames(60))) {
                        interrupted.set(true)
                        onWake()
                        stop()
                        break
                    }
                } while (audio == null && isActive)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.util.Log.i("AivoraWake", "Offline interruption detector unavailable for this playback") }
        }
        try { playback() }
        catch (cancelled: CancellationException) { if (!interrupted.get() || !currentCoroutineContext().isActive) throw cancelled }
        catch (error: Exception) { if (!interrupted.get()) throw error }
        finally { microphone.stop(); listener.cancelAndJoin() }
        !interrupted.get()
    }
}
