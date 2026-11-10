package com.chronoit.aivora.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Prefer an installed offline Indian English voice; retain bundled speech when unavailable. */
class IndianEnglishSpeechEngine(context: Context) : TextToSpeechEngine {
    private val fallback = SherpaTextToSpeechEngine(context.assets)
    private val initialized = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val engine = TextToSpeech(context.applicationContext) { initialized.complete(it == TextToSpeech.SUCCESS) }
    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { Log.i("AivoraWake", "Indian English speech playback started") }
            override fun onDone(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
            @Deprecated("Platform callback")
            override fun onError(utteranceId: String) { pending.remove(utteranceId)?.completeExceptionally(IllegalStateException("Speech engine failed")) }
            override fun onStop(utteranceId: String, interrupted: Boolean) { pending.remove(utteranceId)?.cancel() }
        })
    }
    override val readiness: ModelReadiness get() = fallback.readiness
    override suspend fun speak(text: String) = speak(text, SpeechOptions())
    suspend fun speak(text: String, options: SpeechOptions) {
        require(text.isNotBlank() && text.length <= 4000)
        val request = generation.incrementAndGet()
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (generation.get() != request) throw CancellationException("Speech superseded")
            val available = withTimeoutOrNull(8000) { initialized.await() } == true
            val voice = if (available) engine.voices.orEmpty().filter {
                it.locale.language == "en" && it.locale.country == "IN" && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name }).firstOrNull() else null
            if (voice == null) {
                Log.i("AivoraWake", "Offline Indian English voice unavailable; using bundled speech")
                fallback.speak(text, options)
                return@withLock
            }
            val id = "aivora-speech-$request"
            val done = CompletableDeferred<Unit>()
            pending[id] = done
            try {
                check(engine.setVoice(voice) == TextToSpeech.SUCCESS)
                engine.setSpeechRate(options.speed)
                engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                Log.i("AivoraWake", "Selected offline Indian English voice: ${voice.name}")
                check(engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.SUCCESS)
                withTimeout(90000) { done.await() }
                Log.i("AivoraWake", "Indian English speech playback completed")
            } catch (cancelled: CancellationException) {
                engine.stop()
                throw cancelled
            } catch (_: Exception) {
                engine.stop()
                currentCoroutineContext().ensureActive()
                if (generation.get() != request) throw CancellationException("Speech superseded")
                Log.i("AivoraWake", "Indian English playback failed; using bundled speech")
                fallback.speak(text, options)
            } finally { pending.remove(id) }
        }
    }
    override fun stop() {
        generation.incrementAndGet()
        engine.stop()
        pending.values.forEach { it.cancel() }
        pending.clear()
        fallback.stop()
    }
    override fun close() { stop(); engine.shutdown(); fallback.close() }
}
