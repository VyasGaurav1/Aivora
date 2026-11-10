package com.chronoit.aivora.voice

import kotlinx.coroutines.flow.Flow

data class AudioFrame(val samples: ShortArray, val sampleRate: Int)
data class SpeechTranscript(val text: String, val confidence: Double?, val final: Boolean)
data class SpeakerVerification(val verified: Boolean, val confidence: Double)
sealed interface ModelReadiness {
    data object Ready : ModelReadiness
    data class Unavailable(val reason: String) : ModelReadiness
}

interface WakeWordEngine : AutoCloseable {
    val readiness: ModelReadiness
    suspend fun enroll(name: String, samples: List<AudioFrame>): Boolean
    fun detect(frame: AudioFrame): Boolean
}
interface SpeakerVerifier : AutoCloseable {
    val readiness: ModelReadiness
    suspend fun enroll(samples: List<AudioFrame>): Boolean
    suspend fun verify(samples: List<AudioFrame>): SpeakerVerification
}
interface SpeechToTextEngine : AutoCloseable {
    val readiness: ModelReadiness
    fun transcribe(audio: Flow<AudioFrame>): Flow<SpeechTranscript>
}
interface TextToSpeechEngine : AutoCloseable {
    val readiness: ModelReadiness
    suspend fun speak(text: String)
    fun stop()
}

data class SpeechOptions(val voiceId: Int = 0, val speed: Float = 1f) {
    init { require(voiceId >= 0 && speed.isFinite() && speed in 0.5f..2f) }
}
