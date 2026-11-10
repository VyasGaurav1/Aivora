package com.chronoit.aivora.platform

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.media.AudioFormat
import android.speech.RecognizerIntent
import android.speech.RecognitionSupport
import androidx.annotation.RequiresApi
import com.chronoit.aivora.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.onEach

/** Android recognition-provider protocol backed solely by the bundled offline model. No actions. */
class AivoraRecognitionService : RecognitionService() {
    private val unavailable get() = if (Build.VERSION.SDK_INT >= 31) SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE else SpeechRecognizer.ERROR_CLIENT
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var request: Job? = null
    private val microphone by lazy { MicrophoneCapture(this) }
    private var descriptorSource: DescriptorAudioSource? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        if (request?.let { !it.isCompleted } == true) { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        val intent = recognizerIntent ?: Intent()
        if (!supportedLanguage(intent)) { listener.error(unavailable); return }
        request = scope.launch {
            try {
                SherpaSpeechToTextEngine(assets).use { engine ->
                    if (engine.readiness !is ModelReadiness.Ready) { listener.error(unavailable); return@use }
                    val supplied = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, ParcelFileDescriptor::class.java) else null
                    val audio = if (supplied != null && Build.VERSION.SDK_INT >= 33) {
                        require(intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000) == 16000)
                        require(intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1) == 1)
                        require(intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_16BIT)
                        DescriptorAudioSource(supplied).also { descriptorSource = it }.frames()
                    } else { microphone.prepare(); microphone.utteranceFrames() }
                    listener.readyForSpeech(Bundle())
                    var beginning = false
                    var ended = false
                    engine.transcribe(audio.onEach { frame ->
                        if (!beginning && UtteranceEndpoint.rms(frame) >= 0.012) { beginning = true; listener.beginningOfSpeech() }
                    }).collect { transcript ->
                        if (transcript.final) {
                            listener.endOfSpeech(); ended = true
                            if (transcript.text.isBlank()) listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                            else listener.results(resultBundle(transcript))
                        } else if (intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)) listener.partialResults(resultBundle(transcript))
                    }
                    if (!ended) listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: MicrophoneInUseException) { runCatching { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) } }
            catch (_: SecurityException) { runCatching { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) } }
            catch (_: IllegalArgumentException) { runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) } }
            catch (_: Exception) { runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) } }
            finally { microphone.stop(); descriptorSource?.close(); descriptorSource = null }
        }
    }
    override fun onCancel(listener: Callback?) { request?.cancel(); microphone.stop(); descriptorSource?.stop() }
    override fun onStopListening(listener: Callback) { microphone.stop(); descriptorSource?.stop() }
    @RequiresApi(33)
    override fun onCheckRecognitionSupport(recognizerIntent: Intent, supportCallback: SupportCallback) {
        if (!supportedLanguage(recognizerIntent)) { supportCallback.onError(unavailable); return }
        if (SherpaSpeechToTextEngine(assets).readiness !is ModelReadiness.Ready) { supportCallback.onError(unavailable); return }
        supportCallback.onSupportResult(RecognitionSupport.Builder().addInstalledOnDeviceLanguage("en-US").addSupportedOnDeviceLanguage("en-US").build())
    }
    private fun supportedLanguage(intent: Intent) = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)?.let { it.equals("en", true) || it.startsWith("en-", true) || it.startsWith("en_", true) } ?: true
    private fun resultBundle(transcript: SpeechTranscript) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(transcript.text))
        transcript.confidence?.takeIf { it.isFinite() }?.let { putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(it.toFloat())) }
    }
    override fun onDestroy() { scope.cancel(); microphone.stop(); descriptorSource?.close(); super.onDestroy() }
}
