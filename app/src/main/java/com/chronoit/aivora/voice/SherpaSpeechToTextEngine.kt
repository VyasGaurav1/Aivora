package com.chronoit.aivora.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*

/** Bundled English CPU model. Native state exists only during an active transcription. */
class SherpaSpeechToTextEngine(private val assets: AssetManager) : SpeechToTextEngine {
    private val directory = "models/stt-en-20m"
    private val requiredFiles = listOf("tokens.txt", "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.onnx")
    override val readiness: ModelReadiness
        get() = if (requiredFiles.all { file -> runCatching { assets.open("$directory/$file").close() }.isSuccess }) ModelReadiness.Ready
        else ModelReadiness.Unavailable("The bundled English speech model is missing.")

    override fun transcribe(audio: Flow<AudioFrame>): Flow<SpeechTranscript> = flow {
        check(readiness == ModelReadiness.Ready)
        val recognizer = OnlineRecognizer(assets, OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$directory/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$directory/decoder-epoch-99-avg-1.onnx",
                    joiner = "$directory/joiner-epoch-99-avg-1.onnx",
                ), tokens = "$directory/tokens.txt", numThreads = 2, provider = "cpu", modelType = "zipformer",
            ), enableEndpoint = false, decodingMethod = "modified_beam_search", maxActivePaths = 4,
        ))
        var stream: OnlineStream? = null
        try {
            val activeStream = recognizer.createStream()
            stream = activeStream
            // Supply the streaming model's initial acoustic context even when speech starts at sample zero.
            activeStream.acceptWaveform(FloatArray(8000), 16000)
            var last = ""
            audio.collect { frame ->
                require(frame.sampleRate == 16000)
                activeStream.acceptWaveform(FloatArray(frame.samples.size) { frame.samples[it] / 32768f }, frame.sampleRate)
                while (recognizer.isReady(activeStream)) { currentCoroutineContext().ensureActive(); recognizer.decode(activeStream) }
                val text = recognizer.getResult(activeStream).text.trim()
                if (text != last) { last = text; emit(SpeechTranscript(text, null, false)) }
            }
            activeStream.acceptWaveform(FloatArray(8000), 16000)
            activeStream.inputFinished()
            while (recognizer.isReady(activeStream)) { currentCoroutineContext().ensureActive(); recognizer.decode(activeStream) }
            emit(SpeechTranscript(recognizer.getResult(activeStream).text.trim(), null, true))
        } finally { try { stream?.release() } finally { recognizer.release() } }
    }.flowOn(Dispatchers.Default)

    override fun close() = Unit
}
