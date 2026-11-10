package com.chronoit.aivora.voice

import org.junit.Assert.*
import org.junit.Test

class Pcm16FramerTest {
    @Test fun arbitraryOddHttpChunkBoundariesPreserveEverySample() {
        val original = ByteArray(64) { it.toByte() }
        for (chunk in 1..17) {
            val framer = Pcm16Framer()
            val result = original.toList().chunked(chunk).flatMap {
                val bytes = it.toByteArray()
                framer.accept(bytes, bytes.size).also { framed -> assertEquals(0, framed.size % 2) }.toList()
            }.toByteArray()
            framer.finish()
            assertArrayEquals(original, result)
        }
    }
    @Test fun truncatedFinalSampleIsRejected() {
        val framer = Pcm16Framer()
        assertEquals(0, framer.accept(byteArrayOf(1), 1).size)
        assertTrue(runCatching { framer.finish() }.isFailure)
    }
}
