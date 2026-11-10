package com.chronoit.aivora.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.lastOrNull

/** Short wake utterances open the panel without assigning a speaker identity. */
internal suspend fun awaitWakeName(
    wake: WakeWordEngine,
    audio: Flow<AudioFrame>,
    name: String,
    speech: SpeechToTextEngine,
    useTemplates: Boolean = true,
    onDecoded: (Boolean) -> Unit = {},
): Boolean {
    val candidate = mutableListOf<AudioFrame>()
    var samples = 0
    var voiced = 0
    var trailing = 0
    fun clear() {
        candidate.forEach { it.samples.fill(0) }
        candidate.clear()
        samples = 0
        voiced = 0
        trailing = 0
    }
    return try {
        audio.firstOrNull { frame ->
            if (useTemplates && wake.detect(frame)) return@firstOrNull true
            val audible = UtteranceEndpoint.rms(frame) >= 0.012
            if (!audible && candidate.isEmpty()) return@firstOrNull false
            candidate += AudioFrame(frame.samples.copyOf(), frame.sampleRate)
            samples += frame.samples.size
            if (audible) {
                voiced += frame.samples.size
                trailing = 0
            } else trailing += frame.samples.size
            if (trailing < 4800 && samples < 48000) return@firstOrNull false
            try {
                if (voiced < 2400 || trailing < 4800) false
                else {
                    val transcript = speech.transcribe(candidate.asFlow()).lastOrNull()
                    val matched = transcript?.final == true && wakeTextMatches(transcript.text, name)
                    onDecoded(matched)
                    matched
                }
            } finally { clear() }
        } != null
    } finally {
        clear()
        wake.close()
    }
}

internal fun wakeTextMatches(transcript: String, name: String): Boolean {
    fun normalize(text: String) = text.lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit)
    val expected = normalize(name)
    val heard = normalize(transcript)
    val names = if (expected in setOf("aivora", "jarvis")) setOf("aivora", "jarvis") else setOf(expected)
    return expected.isNotEmpty() && names.any { heard == it || heard == "hey$it" }
}

/** Wake templates and a short RAM ring only; STT/reasoning are not part of idle listening. */
class BackgroundWakeListener(private val wake: WakeWordEngine, private val speaker: SpeakerVerifier) {
    suspend fun awaitOwner(audio: Flow<AudioFrame>): Boolean {
        val ring = ShortArray(48000)
        var position = 0; var size = 0
        return try {
            audio.firstOrNull { frame ->
                for (sample in frame.samples) { ring[position] = sample; position = (position + 1) % ring.size; size = minOf(size + 1, ring.size) }
                if (!wake.detect(frame)) false else {
                    val start = (position - size + ring.size) % ring.size
                    val utterance = AudioFrame(ShortArray(size) { ring[(start + it) % ring.size] }, 16000)
                    val owner = try { speaker.verify(listOf(utterance)) } finally { utterance.samples.fill(0) }
                    owner.verified && owner.confidence.isFinite() && owner.confidence >= 0.7
                }
            } != null
        } finally { ring.fill(0); wake.close() }
    }
}
