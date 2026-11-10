package com.chronoit.aivora.voice

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class VoiceQualityTest {
    @Test fun rejectsSilenceClippingShortAndWrongRate() {
        assertFalse(VoiceQuality.acceptable(AudioFrame(ShortArray(32000), 16000)))
        assertFalse(VoiceQuality.acceptable(AudioFrame(ShortArray(32000) { 32767 }, 16000)))
        assertFalse(VoiceQuality.acceptable(AudioFrame(ShortArray(100) { 1000 }, 16000)))
        assertFalse(VoiceQuality.acceptable(AudioFrame(ShortArray(32000) { 1000 }, 8000)))
    }
    @Test fun acceptsSustainedNonClippedAudio() {
        assertTrue(VoiceQuality.acceptable(AudioFrame(ShortArray(32000) { (sin(it * 0.1) * 8000).toInt().toShort() }, 16000)))
    }
    @Test fun similarityRejectsMalformedAndSeparatesOppositeVectors() {
        assertEquals(1.0, VoiceQuality.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), 0.0001)
        assertEquals(0.0, VoiceQuality.cosine(floatArrayOf(0f), floatArrayOf(0f)), 0.0001)
        assertEquals(0.0, VoiceQuality.cosine(floatArrayOf(Float.NaN), floatArrayOf(1f)), 0.0001)
        assertEquals(-1.0, VoiceQuality.cosine(floatArrayOf(1f), floatArrayOf(-1f)), 0.0001)
    }
}
