package com.chronoit.aivora.voice

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VerifiedVoicePipelineTest {
    private var verified = true
    private var confidence = 0.9
    private var sttCalls = 0
    private var time = 100L
    private var transcript = SpeechTranscript("volume 40%", null, true)
    private val speaker = object : SpeakerVerifier {
        override val readiness = ModelReadiness.Ready
        override suspend fun enroll(samples: List<AudioFrame>) = true
        override suspend fun verify(samples: List<AudioFrame>) = SpeakerVerification(verified, confidence)
        override fun close() = Unit
    }
    private val speech = object : SpeechToTextEngine {
        override val readiness = ModelReadiness.Ready
        override fun transcribe(audio: Flow<AudioFrame>): Flow<SpeechTranscript> { sttCalls++; return flowOf(transcript) }
        override fun close() = Unit
    }
    private val pipeline = VerifiedVoicePipeline(speaker, speech) { time }
    private val audio = listOf(AudioFrame(ShortArray(32000) { 1000 }, 16000))
    @Test fun ownerVerificationPrecedesSpeechAndRejectsLowOrInvalidConfidence() = runTest {
        verified = false
        assertTrue(pipeline.interpret(audio) is VoiceCommandResult.Rejected)
        verified = true; confidence = 0.5
        assertTrue(pipeline.interpret(audio) is VoiceCommandResult.Rejected)
        confidence = Double.NaN
        assertTrue(pipeline.interpret(audio) is VoiceCommandResult.Rejected)
        assertEquals(0, sttCalls)
    }
    @Test fun verifiedCommandIsSingleUseAndExpires() = runTest {
        val command = (pipeline.interpret(audio) as VoiceCommandResult.Verified).command
        assertEquals("volume 40%", command.consume())
        assertNull(command.consume())
        val expired = (pipeline.interpret(audio) as VoiceCommandResult.Verified).command
        time += 10_001
        assertNull(expired.consume())
    }
    @Test fun incompleteOrUncertainSpeechDoesNotAuthorizeAnAction() = runTest {
        transcript = SpeechTranscript("volume 40%", 0.5, true)
        assertTrue(pipeline.interpret(audio) is VoiceCommandResult.Rejected)
        transcript = SpeechTranscript("volume 40%", null, false)
        assertTrue(pipeline.interpret(audio) is VoiceCommandResult.Rejected)
        transcript = SpeechTranscript("", null, true)
        assertTrue(pipeline.interpret(audio) is VoiceCommandResult.Rejected)
    }
    @Test fun malformedAudioIsRejectedBeforeModelsRun() = runTest {
        assertTrue(pipeline.interpret(emptyList()) is VoiceCommandResult.Rejected)
        assertTrue(pipeline.interpret(listOf(AudioFrame(ShortArray(100), 8000))) is VoiceCommandResult.Rejected)
        assertEquals(0, sttCalls)
    }
    @Test fun noSpeechTimeoutAndTrailingSilenceAreBounded() {
        val silence = AudioFrame(ShortArray(1600), 16000)
        var endpoint = UtteranceEndpoint(trailingSilenceMillis = 300, noSpeechMillis = 500)
        repeat(4) { assertTrue(endpoint.accept(silence)) }
        assertFalse(endpoint.accept(silence))
        assertFalse(endpoint.speechDetected)
        endpoint = UtteranceEndpoint(trailingSilenceMillis = 300)
        repeat(2) { assertTrue(endpoint.accept(AudioFrame(ShortArray(1600) { 3000 }, 16000))) }
        assertTrue(endpoint.speechDetected)
        repeat(2) { assertTrue(endpoint.accept(silence)) }
        assertFalse(endpoint.accept(silence))
    }
    @Test fun speechStartingAtNoSpeechDeadlineCanFinish() {
        val endpoint = UtteranceEndpoint(trailingSilenceMillis = 300, noSpeechMillis = 500)
        val silence = AudioFrame(ShortArray(1600), 16000)
        val speech = AudioFrame(ShortArray(1600) { 3000 }, 16000)
        repeat(4) { assertTrue(endpoint.accept(silence)) }
        assertTrue(endpoint.accept(speech))
        assertTrue(endpoint.accept(speech))
        assertTrue(endpoint.speechDetected)
        repeat(2) { assertTrue(endpoint.accept(silence)) }
        assertFalse(endpoint.accept(silence))
    }
}
