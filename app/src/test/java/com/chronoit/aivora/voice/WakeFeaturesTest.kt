package com.chronoit.aivora.voice

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class WakeFeaturesTest {
    private fun pattern(alternate: Boolean = false) = AudioFrame(ShortArray(16000) { i ->
        val frequency = if (i < 8000) 250.0 else if (alternate) 900.0 else 500.0
        (sin(2 * PI * frequency * i / 16000) * 8000).toInt().toShort()
    }, 16000)
    @Test fun silenceProducesNoWakeFeatures() { assertTrue(WakeFeatures.extract(AudioFrame(ShortArray(32000), 16000)).isEmpty()) }
    @Test fun stableTemplatesAndDifferentAcousticsHaveDifferentDistances() {
        val first = WakeFeatures.extract(pattern())
        assertTrue(first.size > 30)
        assertEquals(0.0, WakeFeatures.distance(first, first), 0.0001)
        assertTrue(WakeFeatures.distance(first, WakeFeatures.extract(pattern(true))) > 0.1)
    }
    @Test fun malformedAndDurationMismatchedTemplatesNeverMatch() {
        assertTrue(WakeFeatures.distance(emptyList(), listOf(FloatArray(13))).isInfinite())
        assertTrue(WakeFeatures.distance(List(10) { FloatArray(13) }, List(100) { FloatArray(13) }).isInfinite())
        assertTrue(WakeFeatures.distance(listOf(floatArrayOf(Float.NaN)), listOf(FloatArray(13))).isInfinite())
    }
}
