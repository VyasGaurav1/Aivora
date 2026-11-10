package com.chronoit.aivora.voice

/** HTTP reads can end halfway through a sample. AudioTrack must receive whole PCM16 frames. */
internal class Pcm16Framer {
    private var carry: Byte? = null
    fun accept(input: ByteArray, count: Int): ByteArray {
        require(count in 0..input.size)
        if (count == 0) return ByteArray(0)
        val prefix = carry
        val total = count + if (prefix == null) 0 else 1
        val aligned = total - total % 2
        val output = ByteArray(aligned)
        if (prefix != null) {
            output[0] = prefix
            input.copyInto(output, 1, 0, aligned - 1)
        } else input.copyInto(output, 0, 0, aligned)
        carry = if (total % 2 == 0) null else input[count - 1]
        return output
    }
    fun finish() { check(carry == null) { "Incomplete PCM16 sample" } }
}
