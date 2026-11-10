package com.chronoit.aivora.voice

import kotlin.math.sqrt

object VoiceQuality {
    /** Reject short/silent/clipped enrollment instead of saving an unusable voice identity. */
    fun acceptable(frame: AudioFrame, minimumSeconds: Double = 1.5, minimumVoicedSeconds: Double = 0.8): Boolean {
        if (frame.sampleRate != 16000 || frame.samples.size < frame.sampleRate * minimumSeconds || frame.samples.size > 16000 * 15) return false
        val clipped = frame.samples.count { kotlin.math.abs(it.toInt()) >= 32700 }
        if (clipped > frame.samples.size / 100) return false
        var voicedWindows = 0
        for (start in frame.samples.indices step 320) {
            val end = minOf(start + 320, frame.samples.size)
            val energy = (start until end).sumOf { val value = frame.samples[it] / 32768.0; value * value } / (end - start)
            if (sqrt(energy) >= 0.012) voicedWindows++
        }
        return voicedWindows * 0.02 >= minimumVoicedSeconds
    }
    fun cosine(first: FloatArray, second: FloatArray): Double {
        if (first.isEmpty() || first.size != second.size || first.any { !it.isFinite() } || second.any { !it.isFinite() }) return 0.0
        var dot = 0.0; var normA = 0.0; var normB = 0.0
        for (i in first.indices) { dot += first[i] * second[i]; normA += first[i] * first[i]; normB += second[i] * second[i] }
        if (normA <= 0 || normB <= 0) return 0.0
        return (dot / sqrt(normA * normB)).coerceIn(-1.0, 1.0)
    }
}
