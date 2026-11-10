package com.chronoit.aivora.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses the phone's recognizer, never our own exported PCM recognition service. */
object AndroidSpeechInput {
    @Volatile private var current: SpeechRecognizer? = null
    fun finishRecording() { android.os.Handler(android.os.Looper.getMainLooper()).post { current?.stopListening() } }
    @Volatile private var onDeviceUsable = true
    private class RecognitionFailure(val code: Int) : IllegalStateException("Android speech recognition error $code")

    suspend fun listen(context: Context, wakeOnly: Boolean = false, onPartial: (String) -> Unit = {}, onReady: () -> Unit = {}): String? =
        withContext(Dispatchers.Main.immediate) {
            // Keep wake decoding on the external provider confirmed by the user;
            // foreground commands can still prefer on-device recognition.
            val offline = !wakeOnly && onDeviceUsable && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            try { listenOnce(context, offline, wakeOnly, onPartial, onReady) }
            catch (failure: RecognitionFailure) {
                if (!offline || failure.code !in setOf(4, 5, 11, 12, 13, 14)) throw failure
                onDeviceUsable = false
                Log.i("AivoraWake", "On-device speech unavailable (${failure.code}); using authorized system fallback")
                delay(400)
                listenOnce(context, false, wakeOnly, onPartial, onReady)
            }
        }

    private suspend fun listenOnce(context: Context, offline: Boolean, wakeOnly: Boolean, onPartial: (String) -> Unit, onReady: () -> Unit): String? {
        val lease = MicrophoneLease.acquire()
        var recognizer: SpeechRecognizer? = null
        try {
            val active = if (offline && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else {
                val providers = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.MATCH_ALL)
                    .map { it.serviceInfo }.filter { it.packageName != context.packageName && it.enabled && it.exported }
                val provider = providers.sortedWith(compareByDescending<android.content.pm.ServiceInfo> { it.packageName == "com.google.android.tts" }
                    .thenBy { it.packageName }).firstOrNull()
                    ?: throw IllegalStateException("No external Android speech recognizer is installed")
                Log.i("AivoraWake", "System speech provider: ${provider.packageName}; network permitted by user")
                SpeechRecognizer.createSpeechRecognizer(context, ComponentName(provider.packageName, provider.name))
            }
            recognizer = active
            current = active
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            return withTimeoutOrNull(30000) {
                suspendCancellableCoroutine { continuation ->
                    active.setRecognitionListener(object : RecognitionListener {
                        private fun texts(bundle: Bundle?) = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        private fun deliver(bundle: Bundle?, final: Boolean) {
                            if (!continuation.isActive) return
                            val candidates = texts(bundle)
                            val first = candidates.firstOrNull().orEmpty()
                            if (!wakeOnly) onPartial(first)
                            val match = if (wakeOnly) candidates.firstOrNull { wakeTextMatches(it, "Aivora") } else null
                            if (wakeOnly && match != null) Log.i("AivoraWake", "Phone recognizer matched the wake phrase")
                            if (match != null || final) continuation.resume(match ?: first.takeIf { it.isNotBlank() })
                        }
                        override fun onReadyForSpeech(params: Bundle?) { onReady(); Log.i("AivoraWake", "Android speech microphone ready: en-IN, onDevice=$offline, wake=$wakeOnly") }
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onPartialResults(partialResults: Bundle?) = deliver(partialResults, false)
                        override fun onResults(results: Bundle?) = deliver(results, true)
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        override fun onError(error: Int) {
                            if (!continuation.isActive) return
                            Log.i("AivoraWake", "Android speech ended with code=$error, onDevice=$offline")
                            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) continuation.resume(null)
                            else continuation.resumeWithException(RecognitionFailure(error))
                        }
                    })
                    active.startListening(request)
                }
            }
        } finally {
            if (current === recognizer) current = null
            try { recognizer?.cancel(); recognizer?.destroy() } finally { lease.close() }
        }
    }
}
