package com.chronoit.aivora.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Personalized acoustic templates. Physical-device false-accept/false-reject testing is required. */
class PersonalizedWakeWordEngine(private val store: VoiceProfileStore) : WakeWordEngine {
    private data class Profile(val name: String, val templates: List<List<FloatArray>>, val threshold: Double)
    private var loadFailure = false
    @Volatile private var profile = try { load() } catch (_: Exception) { loadFailure = true; null }
    private val pending = ArrayList<Short>(64000)
    private var trailingSamples = 0
    private var elapsed = 0L
    private var lastActivation = -32000L
    override val readiness: ModelReadiness get() = when {
        loadFailure -> ModelReadiness.Unavailable("Wake enrollment could not be read. Authenticate and enroll again.")
        profile == null -> ModelReadiness.Unavailable("Choose a name and record three wake-name samples.")
        else -> ModelReadiness.Ready
    }
    fun activeWakeName(): String? = profile?.name
    override suspend fun enroll(name: String, samples: List<AudioFrame>): Boolean = withContext(Dispatchers.Default) {
        if (name.isBlank() || name.length > 40 || name.any(Char::isISOControl) || samples.size !in 3..6 || samples.any { it.sampleRate != 16000 || it.samples.size !in 4800..64000 }) return@withContext false
        val templates = samples.map(WakeFeatures::extract)
        if (templates.any { it.size !in 15..350 }) return@withContext false
        val pairwise = templates.indices.flatMap { i -> (0 until i).map { j -> WakeFeatures.distance(templates[i], templates[j]) } }
        if (pairwise.any { !it.isFinite() || it > 1.25 }) return@withContext false
        val threshold = (pairwise.max() * 1.25 + 0.1).coerceIn(0.45, 0.85)
        val value = JSONObject().put("version", 1).put("name", name.trim()).put("threshold", threshold)
            .put("templates", JSONArray().apply { templates.forEach { template -> put(JSONArray().apply { template.forEach { vector -> put(JSONArray().apply { vector.forEach { put(it.toDouble()) } }) } }) } })
        currentCoroutineContext().ensureActive()
        store.write(value.toString())
        profile = Profile(name.trim(), templates, threshold)
        loadFailure = false
        reset()
        true
    }
    @Synchronized override fun detect(frame: AudioFrame): Boolean {
        require(frame.sampleRate == 16000 && frame.samples.size <= 16000)
        elapsed += frame.samples.size
        val active = profile ?: return false
        if (frame.samples.isEmpty()) return false
        val rms = kotlin.math.sqrt(frame.samples.sumOf { val x = it / 32768.0; x * x } / frame.samples.size)
        if (rms > 0.014) {
            pending.addAll(frame.samples.toList()); trailingSamples = 0
        } else if (pending.isNotEmpty()) { pending.addAll(frame.samples.toList()); trailingSamples += frame.samples.size }
        if (pending.size > 64000) { reset(); return false }
        if (trailingSamples < 4800 || pending.isEmpty()) return false
        val utterance = AudioFrame(pending.toShortArray(), 16000)
        reset()
        if (elapsed - lastActivation < 32000) return false
        val features = WakeFeatures.extract(utterance)
        val distances = active.templates.map { WakeFeatures.distance(features, it) }.sorted()
        val matched = distances.size >= 3 && distances[1] <= active.threshold
        if (matched) lastActivation = elapsed
        return matched
    }
    @Synchronized private fun reset() { pending.clear(); trailingSamples = 0 }
    private fun load(): Profile? {
        val json = store.read()?.let(::JSONObject) ?: return null
        require(json.getInt("version") == 1)
        val name = json.getString("name"); require(name.isNotBlank() && name.length <= 40)
        val threshold = json.getDouble("threshold"); require(threshold in 0.45..0.85)
        val templates = json.getJSONArray("templates"); require(templates.length() in 3..6)
        return Profile(name, (0 until templates.length()).map { t ->
            val frames = templates.getJSONArray(t); require(frames.length() in 15..350)
            (0 until frames.length()).map { f ->
                val vector = frames.getJSONArray(f); require(vector.length() == 13)
                FloatArray(13) { vector.getDouble(it).toFloat().also { value -> require(value.isFinite()) } }
            }
        }, threshold)
    }
    fun forget() { store.delete(); profile = null; reset() }
    override fun close() = reset()
}
