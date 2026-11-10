package com.chronoit.aivora.voice

import kotlin.math.*

/** Small MFCC frontend for personalized templates; no language model runs during wake detection. */
object WakeFeatures {
    fun extract(frame: AudioFrame): List<FloatArray> {
        require(frame.sampleRate == 16000 && frame.samples.size <= 16000 * 4)
        val voiced = frame.samples.indices.step(320).filter { start ->
            (start until minOf(start + 320, frame.samples.size)).sumOf { val x = frame.samples[it] / 32768.0; x * x } / 320 > 0.0002
        }
        if (voiced.size < 10) return emptyList()
        val start = (voiced.first() - 800).coerceAtLeast(0)
        val end = (voiced.last() + 1120).coerceAtMost(frame.samples.size)
        if (end - start < 400) return emptyList()
        val filters = melFilters()
        val result = mutableListOf<FloatArray>()
        for (offset in start..end - 400 step 160) {
            val real = DoubleArray(512)
            val imaginary = DoubleArray(512)
            for (i in 0 until 400) {
                val current = frame.samples[offset + i] / 32768.0
                val previous = if (offset + i > 0) frame.samples[offset + i - 1] / 32768.0 else 0.0
                real[i] = (current - 0.97 * previous) * (0.54 - 0.46 * cos(2 * PI * i / 399))
            }
            fft(real, imaginary)
            val spectrum = DoubleArray(257) { real[it] * real[it] + imaginary[it] * imaginary[it] }
            val bands = DoubleArray(26) { band -> ln(filters[band].indices.sumOf { bin -> filters[band][bin] * spectrum[bin] }.coerceAtLeast(1e-10)) }
            result += FloatArray(13) { c -> bands.indices.sumOf { b -> bands[b] * cos(PI * (c + 1) * (b + 0.5) / 26) }.toFloat() }
        }
        // Remove channel and overall loudness differences while preserving the temporal pattern.
        for (c in 0 until 13) {
            val mean = result.map { it[c].toDouble() }.average()
            val deviation = sqrt(result.sumOf { (it[c] - mean).pow(2) } / result.size).coerceAtLeast(1.0)
            result.forEach { it[c] = ((it[c] - mean) / deviation).toFloat() }
        }
        return result
    }
    fun distance(first: List<FloatArray>, second: List<FloatArray>): Double {
        if (first.isEmpty() || second.isEmpty() || first.any { it.size != 13 || it.any { value -> !value.isFinite() } } || second.any { it.size != 13 || it.any { value -> !value.isFinite() } }) return Double.POSITIVE_INFINITY
        if (first.size.toDouble() / second.size !in 0.5..2.0) return Double.POSITIVE_INFINITY
        var previous = DoubleArray(second.size + 1) { Double.POSITIVE_INFINITY }.apply { this[0] = 0.0 }
        val band = maxOf(abs(first.size - second.size), maxOf(first.size, second.size) / 3)
        for (i in 1..first.size) {
            val next = DoubleArray(second.size + 1) { Double.POSITIVE_INFINITY }
            for (j in maxOf(1, i - band)..minOf(second.size, i + band)) {
                val cost = (0 until 13).sumOf { (first[i - 1][it] - second[j - 1][it]).toDouble().pow(2) } / 13
                next[j] = cost + minOf(previous[j], next[j - 1], previous[j - 1])
            }
            previous = next
        }
        return sqrt(previous.last() / ((first.size + second.size) / 2.0))
    }
    private fun melFilters(): List<DoubleArray> {
        fun mel(hz: Double) = 2595 * log10(1 + hz / 700)
        fun hz(mel: Double) = 700 * (10.0.pow(mel / 2595) - 1)
        val low = mel(70.0); val high = mel(7600.0)
        val bins = IntArray(28) { floor(513 * hz(low + (high - low) * it / 27) / 16000).toInt().coerceIn(0, 256) }
        return (0 until 26).map { band -> DoubleArray(257) { bin -> when {
            bin < bins[band] || bin > bins[band + 2] -> 0.0
            bin <= bins[band + 1] -> (bin - bins[band]).toDouble() / maxOf(1, bins[band + 1] - bins[band])
            else -> (bins[band + 2] - bin).toDouble() / maxOf(1, bins[band + 2] - bins[band + 1])
        } } }
    }
    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var j = 0
        for (i in 1 until real.size) {
            var bit = real.size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tmp = real[i]; real[i] = real[j]; real[j] = tmp }
        }
        var size = 2
        while (size <= real.size) {
            val angle = -2 * PI / size
            for (start in real.indices step size) for (k in 0 until size / 2) {
                val a = start + k; val b = a + size / 2
                val wr = cos(angle * k); val wi = sin(angle * k)
                val tr = wr * real[b] - wi * imaginary[b]; val ti = wr * imaginary[b] + wi * real[b]
                real[b] = real[a] - tr; imaginary[b] = imaginary[a] - ti
                real[a] += tr; imaginary[a] += ti
            }
            size *= 2
        }
    }
}
