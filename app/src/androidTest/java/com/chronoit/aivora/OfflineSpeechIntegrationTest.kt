package com.chronoit.aivora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.voice.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class OfflineSpeechIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun bundledNativeModelTranscribesRecordedAudio() = runBlocking {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("speech/0.wav").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        var offset = 12
        var samples = ShortArray(0)
        var rate = 0
        while (offset + 8 <= bytes.size) {
            val kind = String(bytes, offset, 4)
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset + 8 + size <= bytes.size)
            if (kind == "fmt ") {
                assertEquals(1, buffer.getShort(offset + 8).toInt())
                assertEquals(1, buffer.getShort(offset + 10).toInt())
                rate = buffer.getInt(offset + 12)
                assertEquals(16, buffer.getShort(offset + 22).toInt())
            }
            if (kind == "data") samples = ShortArray(size / 2) { buffer.getShort(offset + 8 + it * 2) }
            offset += 8 + size + size % 2
        }
        assertEquals(16000, rate)
        assertTrue(samples.isNotEmpty())
        val audio = flow { samples.asList().chunked(1600).forEach { emit(AudioFrame(it.toShortArray(), rate)) } }
        val transcript = SherpaSpeechToTextEngine(context.assets).use { engine -> engine.transcribe(audio).last() }
        assertTrue(transcript.final)
        assertTrue(transcript.text, transcript.text.contains("early nightfall", ignoreCase = true))
        assertTrue(transcript.text, transcript.text.contains("yellow lamps", ignoreCase = true))
    }
}
