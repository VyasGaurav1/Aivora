package com.chronoit.aivora.voice

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.lastOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Issued by local speaker+speech verification, never parsed from a model's action JSON. */
class VerifiedVoiceCommand internal constructor(
    private val text: String,
    private val issuedAt: Long,
    private val clock: () -> Long,
) {
    private val consumed = AtomicBoolean(false)
    fun consume(): String? = if (clock() - issuedAt in 0..10_000 && consumed.compareAndSet(false, true)) text else null
}

sealed interface VoiceCommandResult {
    data class Verified(val command: VerifiedVoiceCommand) : VoiceCommandResult
    data class Rejected(val reason: String) : VoiceCommandResult
}

class VerifiedVoicePipeline(
    private val speaker: SpeakerVerifier,
    private val speech: SpeechToTextEngine,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun interpret(audio: List<AudioFrame>): VoiceCommandResult {
        if (audio.isEmpty() || audio.any { it.sampleRate != 16000 } || audio.sumOf { it.samples.size } > 240000)
            return VoiceCommandResult.Rejected("The recording is empty, too long or unsupported.")
        if (speaker.readiness !is ModelReadiness.Ready || speech.readiness !is ModelReadiness.Ready)
            return VoiceCommandResult.Rejected("The required local voice models are unavailable.")
        val ownerAudio = combine(audio)
        val owner = try { speaker.verify(listOf(ownerAudio)) } finally { ownerAudio.samples.fill(0) }
        if (!owner.verified || !owner.confidence.isFinite() || owner.confidence < 0.7)
            return VoiceCommandResult.Rejected("I couldn't verify the owner voice. Authenticate or use typed input. No action was executed.")
        val result = speech.transcribe(audio.asFlow()).lastOrNull()
        if (result == null || !result.final || result.text.isBlank()) return VoiceCommandResult.Rejected("No complete speech command was recognized.")
        if (result.text.length > 4000 || (result.confidence != null && (!result.confidence.isFinite() || result.confidence < 0.85)))
            return VoiceCommandResult.Rejected("I need you to repeat or clarify that command.")
        return VoiceCommandResult.Verified(VerifiedVoiceCommand(result.text, clock(), clock))
    }
    private fun combine(audio: List<AudioFrame>): AudioFrame {
        val samples = ShortArray(audio.sumOf { it.samples.size })
        var offset = 0
        audio.forEach { it.samples.copyInto(samples, offset); offset += it.samples.size }
        return AudioFrame(samples, 16000)
    }
}
