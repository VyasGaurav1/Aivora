package com.chronoit.aivora.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

/** Fixed release wake phrases, decoded continuously without identifying the speaker. */
internal class KeywordWakeDetector(assets: AssetManager) : AutoCloseable {
    private val directory = "models/wake-en"
    private val spotter = KeywordSpotter(assets, KeywordSpotterConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$directory/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                decoder = "$directory/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                joiner = "$directory/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            ),
            tokens = "$directory/tokens.txt", numThreads = 2, provider = "cpu", modelType = "zipformer2",
        ),
        keywordsFile = "$directory/keywords.txt",
        maxActivePaths = 8,
        keywordsScore = 2.0f,
        keywordsThreshold = 0.2f,
        numTrailingBlanks = 1,
    ))
    private val stream = spotter.createStream()
    fun accept(frame: AudioFrame): Boolean {
        require(frame.sampleRate == 16000)
        stream.acceptWaveform(FloatArray(frame.samples.size) { frame.samples[it] / 32768f }, 16000)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (keyword.isNotEmpty()) {
                android.util.Log.i("AivoraWake", "Fixed wake phrase matched: $keyword")
                return true
            }
        }
        return false
    }
    override fun close() {
        try { stream.release() } finally { spotter.release() }
    }
}

internal suspend fun awaitKeywordWake(assets: AssetManager, frames: Flow<AudioFrame>): Boolean =
    KeywordWakeDetector(assets).use { detector ->
        var count = 0
        var peakRms = 0.0
        var lastLogAt = android.os.SystemClock.elapsedRealtime()
        frames.firstOrNull { frame ->
            currentCoroutineContext().ensureActive()
            peakRms = maxOf(peakRms, UtteranceEndpoint.rms(frame))
            count++
            if (android.os.SystemClock.elapsedRealtime() - lastLogAt >= 4000) {
                android.util.Log.i("AivoraWake", "Wake audio frames=$count peakRms=$peakRms")
                peakRms = 0.0
                lastLogAt = android.os.SystemClock.elapsedRealtime()
            }
            detector.accept(frame)
        } != null
    }
