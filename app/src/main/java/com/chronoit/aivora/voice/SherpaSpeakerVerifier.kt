package com.chronoit.aivora.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Cosine similarity is a model score, not a calibrated probability or liveness proof. */
class SherpaSpeakerVerifier(private val assets: AssetManager, private val store: VoiceProfileStore) : SpeakerVerifier {
    private val model = "models/speaker-en/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"
    override val readiness: ModelReadiness get() = if (runCatching { assets.open(model).close() }.isSuccess) ModelReadiness.Ready
        else ModelReadiness.Unavailable("The bundled speaker model is missing.")
    fun isEnrolled(): Boolean = readVectors().size >= 3
    fun forget() = store.delete()

    override suspend fun enroll(samples: List<AudioFrame>): Boolean = withContext(Dispatchers.Default) {
        if (samples.size !in 3..6 || samples.any { !VoiceQuality.acceptable(it) }) return@withContext false
        val vectors = extract(samples)
        if (vectors.any { it.isEmpty() || it.any { value -> !value.isFinite() } }) return@withContext false
        for (i in vectors.indices) for (j in 0 until i) {
            if (VoiceQuality.cosine(vectors[i], vectors[j]) < 0.6) return@withContext false
        }
        val json = JSONObject().put("version", 1).put("model", "eres2net-en-v1").put("vectors", JSONArray().apply {
            vectors.forEach { vector -> put(JSONArray().apply { vector.forEach { put(it.toDouble()) } }) }
        })
        currentCoroutineContext().ensureActive()
        store.write(json.toString())
        true
    }
    override suspend fun verify(samples: List<AudioFrame>): SpeakerVerification = withContext(Dispatchers.Default) {
        val references = readVectors()
        if (references.size < 3 || samples.isEmpty() || samples.any { !VoiceQuality.acceptable(it, minimumSeconds = 0.75, minimumVoicedSeconds = 0.3) }) return@withContext SpeakerVerification(false, 0.0)
        val queries = extract(samples)
        val score = queries.minOf { query -> references.map { VoiceQuality.cosine(query, it) }.average() }
        SpeakerVerification(score >= 0.7, score)
    }
    private fun readVectors(): List<FloatArray> {
        val value = store.read() ?: return emptyList()
        val json = JSONObject(value)
        require(json.getInt("version") == 1 && json.getString("model") == "eres2net-en-v1")
        val vectors = json.getJSONArray("vectors")
        require(vectors.length() in 3..6)
        return (0 until vectors.length()).map { i ->
            val vector = vectors.getJSONArray(i)
            require(vector.length() in 32..2048)
            FloatArray(vector.length()) { vector.getDouble(it).toFloat().also { value -> require(value.isFinite()) } }
        }
    }
    private fun extract(samples: List<AudioFrame>): List<FloatArray> {
        check(readiness == ModelReadiness.Ready)
        val extractor = SpeakerEmbeddingExtractor(assets, SpeakerEmbeddingExtractorConfig(model = model, numThreads = 2))
        return try {
            samples.map { frame ->
                val stream = extractor.createStream()
                try {
                    stream.acceptWaveform(FloatArray(frame.samples.size) { frame.samples[it] / 32768f }, frame.sampleRate)
                    stream.inputFinished()
                    if (extractor.isReady(stream)) extractor.compute(stream) else FloatArray(0)
                } finally { stream.release() }
            }
        } finally { extractor.release() }
    }
    override fun close() = Unit
}
