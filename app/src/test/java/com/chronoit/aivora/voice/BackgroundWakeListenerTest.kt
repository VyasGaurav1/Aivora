package com.chronoit.aivora.voice

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BackgroundWakeListenerTest {
    @Test fun greetingWakePhrasesAcceptBothReleaseNamesAndRejectLongerRequests() {
        for (phrase in listOf("Hey Jarvis", "HEY AIVORA!", "Hey, Jar vis.", "Aivora")) {
            assertTrue(phrase, wakeTextMatches(phrase, "Aivora"))
        }
        for (phrase in listOf("call Jarvis", "hey Aivora set an alarm", "hey", "hello Friday")) {
            assertFalse(phrase, wakeTextMatches(phrase, "Aivora"))
        }
        assertTrue(wakeTextMatches("hey Friday", "Friday"))
        assertFalse(wakeTextMatches("hey Jarvis", "Friday"))
    }
    @Test fun spokenNameFallbackChecksOnlyCompletedShortUtterances() = runTest {
        val wake = object : WakeWordEngine {
            override val readiness = ModelReadiness.Ready
            override suspend fun enroll(name: String, samples: List<AudioFrame>) = true
            override fun detect(frame: AudioFrame) = false
            override fun close() = Unit
        }
        var calls = 0
        val speech = object : SpeechToTextEngine {
            override val readiness = ModelReadiness.Ready
            override fun transcribe(audio: Flow<AudioFrame>) = flow {
                assertTrue(audio.toList().isNotEmpty())
                calls++
                emit(SpeechTranscript(if (calls == 1) "Call Jarvis" else "Jar vis.", null, true))
            }
            override fun close() = Unit
        }
        val silence = List(3) { AudioFrame(ShortArray(1600), 16000) }
        val voiced = List(3) { AudioFrame(ShortArray(1600) { 1000 }, 16000) }
        assertTrue(awaitWakeName(wake, (silence + voiced + silence + voiced + silence).asFlow(), "Jarvis", speech))
        assertEquals(2, calls)
    }
    @Test fun idleWakeNeverLoadsSpeechAndUnknownOrInvalidSpeakerCannotActivate() = runTest {
        var detections = 0
        var verifications = 0
        var closed = false
        val wake = object : WakeWordEngine {
            override val readiness = ModelReadiness.Ready
            override suspend fun enroll(name: String, samples: List<AudioFrame>) = true
            override fun detect(frame: AudioFrame): Boolean { detections++; return detections >= 2 }
            override fun close() { closed = true }
        }
        val speaker = object : SpeakerVerifier {
            override val readiness = ModelReadiness.Ready
            override suspend fun enroll(samples: List<AudioFrame>) = true
            override suspend fun verify(samples: List<AudioFrame>): SpeakerVerification { verifications++; return SpeakerVerification(true, Double.NaN) }
            override fun close() = Unit
        }
        val audio = List(4) { AudioFrame(ShortArray(1600) { 1000 }, 16000) }.asFlow()
        assertFalse(BackgroundWakeListener(wake, speaker).awaitOwner(audio))
        assertEquals(4, detections)
        assertEquals(3, verifications)
        assertTrue(closed)
    }
}
