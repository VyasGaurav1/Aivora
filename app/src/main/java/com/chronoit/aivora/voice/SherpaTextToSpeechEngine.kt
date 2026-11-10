package com.chronoit.aivora.voice

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** A sink allows synthesis verification without requiring a speaker or an installed TTS engine. */
interface SpeechOutput : AutoCloseable {
    fun write(samples: FloatArray): Boolean
    fun stop()
    suspend fun drain()
}

class SherpaTextToSpeechEngine(
    private val assets: AssetManager,
    private val outputFactory: (Int) -> SpeechOutput = { AndroidSpeechOutput(it) },
) : TextToSpeechEngine {
    private val mutex = Mutex()
    private val generation = AtomicLong(0)
    @Volatile private var output: SpeechOutput? = null
    private val directory = "models/tts-vctk"
    private val normalizer by lazy {
        val words = assets.open("$directory/lexicon-aivora.txt").bufferedReader().useLines { lines -> lines.map { it.substringBefore(' ') }.toSet() }
        SpeechTextNormalizer(words)
    }
    override val readiness: ModelReadiness get() = if (listOf("vits-vctk.int8.onnx", "lexicon-aivora.txt", "tokens.txt").all {
        runCatching { assets.open("$directory/$it").close() }.isSuccess
    }) ModelReadiness.Ready else ModelReadiness.Unavailable("The bundled speech output model is missing.")

    override suspend fun speak(text: String) = speak(text, SpeechOptions())
    suspend fun speak(text: String, options: SpeechOptions) {
        require(text.isNotBlank() && text.length <= 4000)
        val request = generation.incrementAndGet()
        output?.stop()
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (generation.get() != request) throw CancellationException("Speech request was superseded")
                check(readiness == ModelReadiness.Ready)
                val job = currentCoroutineContext().job
                val tts = OfflineTts(assets, OfflineTtsConfig(model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = "$directory/vits-vctk.int8.onnx",
                        tokens = "$directory/tokens.txt", lexicon = "$directory/lexicon-aivora.txt"), numThreads = 2,
                ), maxNumSentences = 1))
                var sink: SpeechOutput? = null
                try {
                    require(options.voiceId < tts.numSpeakers()) { "Unknown speech voice" }
                    if (generation.get() != request) throw CancellationException("Speech request was superseded")
                    val activeOutput = outputFactory(tts.sampleRate())
                    sink = activeOutput
                    output = activeOutput
                    var callbackFailure: Throwable? = null
                    var aborted = false
                    // JNI looks up invoke(float[]):Integer; an indy lambda exposes only invoke(Object).
                    val callback = object : (FloatArray) -> Int {
                        override fun invoke(samples: FloatArray): Int = try {
                            if (job.isActive && generation.get() == request && activeOutput.write(samples)) 1 else { aborted = true; 0 }
                        } catch (error: Throwable) { callbackFailure = error; 0 }
                    }
                    val normalized = normalizer.normalize(text)
                    require(normalized.isNotBlank()) { "No supported speech text" }
                    for (chunk in normalized.split(' ').chunked(30)) {
                        if (!job.isActive || generation.get() != request || callbackFailure != null || aborted) break
                        tts.generateWithCallback(chunk.joinToString(" "), options.voiceId, options.speed, callback)
                    }
                    callbackFailure?.let { throw it }
                    job.ensureActive()
                    if (aborted || generation.get() != request) throw CancellationException("Speech output stopped before completion")
                    activeOutput.drain()
                    job.ensureActive()
                    if (generation.get() != request) throw CancellationException("Speech output stopped during drain")
                } finally {
                    if (output === sink) output = null
                    try { sink?.close() } finally { tts.release() }
                }
                job.ensureActive()
            }
        }
    }
    override fun stop() { generation.incrementAndGet(); output?.stop() }
    override fun close() = stop()
}

private class AndroidSpeechOutput(sampleRate: Int) : SpeechOutput {
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), sampleRate / 5 * 4))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    @Volatile private var stopped = false
    private var framesWritten = 0L
    init { check(track.state == AudioTrack.STATE_INITIALIZED); track.play() }
    override fun write(samples: FloatArray): Boolean {
        var offset = 0
        while (!stopped && offset < samples.size) {
            val count = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (count <= 0) return false
            offset += count
            framesWritten += count
        }
        return !stopped
    }
    override suspend fun drain() {
        while (!stopped && (track.playbackHeadPosition.toLong() and 0xffffffffL) < framesWritten) delay(10)
    }
    override fun stop() {
        stopped = true
        runCatching { track.pause(); track.flush() }
    }
    override fun close() { stop(); track.release() }
}
