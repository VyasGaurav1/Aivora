package com.chronoit.aivora.voice

import kotlin.math.sqrt

/** Lightweight energy VAD/endpointer. No inference or audio persistence in this stage. */
class UtteranceEndpoint(private val trailingSilenceMillis: Int = 900, private val noSpeechMillis: Int = 5000) {
    var speechDetected: Boolean = false
        private set
    private var voicedSamples = 0
    private var silenceSamples = 0
    private var totalSamples = 0
    fun accept(frame: AudioFrame): Boolean {
        require(frame.sampleRate == 16000)
        totalSamples += frame.samples.size
        val voiced = rms(frame) >= 0.012
        if (voiced) { voicedSamples += frame.samples.size; silenceSamples = 0 }
        else silenceSamples += frame.samples.size
        if (voicedSamples >= 3200) speechDetected = true
        return if (speechDetected) silenceSamples < trailingSilenceMillis * 16 else voiced || totalSamples < noSpeechMillis * 16
    }
    companion object {
        fun rms(frame: AudioFrame): Double = if (frame.samples.isEmpty()) 0.0 else sqrt(frame.samples.sumOf { val value = it / 32768.0; value * value } / frame.samples.size)
    }
}
