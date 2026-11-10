package com.chronoit.aivora

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.security.*
import com.chronoit.aivora.voice.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class VoiceBackendIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun androidAudioTrackPlaysAndReleasesLocalSpeech() = runBlocking {
        withTimeout(15000) { SherpaTextToSpeechEngine(context.assets).use { it.speak("Ready.") } }
    }
    @Test fun wakeProfileReplacementIsAtomicAndNeverChangesProductIdentity() = runBlocking {
        val directory = File(context.noBackupFilesDir, "test-wake-${UUID.randomUUID()}")
        val store = VoiceProfileStore(directory, FieldCipher(KeystoreKeys::dataKey), "wake.v1")
        val engine = PersonalizedWakeWordEngine(store)
        val sample = AudioFrame(ShortArray(16000) { i -> (sin(i * if (i < 8000) 0.1 else 0.2) * 8000).toInt().toShort() }, 16000)
        try {
            assertTrue(engine.enroll("Aivora", listOf(sample, sample, sample)))
            assertEquals("Aivora", PersonalizedWakeWordEngine(store).activeWakeName())
            assertFalse(engine.enroll("Friday", listOf(AudioFrame(ShortArray(16000), 16000))))
            assertEquals("Aivora", engine.activeWakeName())
            assertTrue(engine.enroll("Friday", listOf(sample, sample, sample)))
            assertEquals("Friday", PersonalizedWakeWordEngine(store).activeWakeName())
            assertEquals("com.chronoit.aivora", context.packageName)
            var detected = false
            (sample.samples.toList() + List<Short>(6400) { 0 }).chunked(1600).forEach {
                detected = engine.detect(AudioFrame(it.toShortArray(), 16000)) || detected
            }
            assertTrue(detected)
            engine.forget()
            assertNull(engine.activeWakeName())
        } finally { engine.close(); store.delete(); directory.delete() }
    }
    @Test fun callbackErrorsPropagateWithoutAbortingTheNativeProcess() = runBlocking {
        val engine = SherpaTextToSpeechEngine(context.assets) {
            object : SpeechOutput {
                override fun write(samples: FloatArray): Boolean = error("test output failure")
                override fun stop() = Unit
                override suspend fun drain() = Unit
                override fun close() = Unit
            }
        }
        engine.use { assertTrue(runCatching { it.speak("Hello.") }.exceptionOrNull() is IllegalStateException) }
    }
    @Test fun bundledTtsProducesNonSilentPcmAndSupportsVoiceAndSpeed() = runBlocking {
        var count = 0
        var energy = 0.0
        val engine = SherpaTextToSpeechEngine(context.assets) { rate ->
            assertTrue(rate in 16000..48000)
            object : SpeechOutput {
                override fun write(samples: FloatArray): Boolean {
                    assertTrue(samples.all { it.isFinite() })
                    count += samples.size; energy += samples.sumOf { it.toDouble() * it }
                    return true
                }
                override fun stop() = Unit
                override suspend fun drain() = Unit
                override fun close() = Unit
            }
        }
        engine.use {
            it.speak("Hello. I am ready.", SpeechOptions(0, 1f))
            val firstCount = count
            assertTrue(firstCount > 5000)
            it.speak("Hello. I am ready.", SpeechOptions(1, 1.25f))
            assertTrue(count > firstCount + 5000)
            assertTrue(energy > 0.1)
        }
    }
    @Test fun stoppingSpeechCancelsOutputAndDoesNotDrainQueuedAudio() = runBlocking {
        lateinit var engine: SherpaTextToSpeechEngine
        var written = 0
        var stopped = false
        var drained = false
        engine = SherpaTextToSpeechEngine(context.assets) {
            object : SpeechOutput {
                override fun write(samples: FloatArray): Boolean {
                    assertFalse(stopped); written++; engine.stop(); return false
                }
                override fun stop() { stopped = true }
                override suspend fun drain() { drained = true }
                override fun close() = Unit
            }
        }
        val failure = engine.use { runCatching { it.speak("Hello. I am ready. There are many more words to speak.") }.exceptionOrNull() }
        assertTrue("Stopped output must not report a complete readback", failure is kotlinx.coroutines.CancellationException)
        assertEquals(1, written)
        assertTrue(stopped)
        assertFalse(drained)
    }
    @Test fun refusingSpeechOutputAbortsReadbackWithoutDrainingOrLaterChunks() = runBlocking {
        var writes = 0
        var drained = false
        val engine = SherpaTextToSpeechEngine(context.assets) {
            object : SpeechOutput {
                override fun write(samples: FloatArray): Boolean { writes++; return false }
                override fun stop() = Unit
                override suspend fun drain() { drained = true }
                override fun close() = Unit
            }
        }
        val failure = engine.use { runCatching { it.speak("Hello. " + "There are many more words to speak. ".repeat(10)) }.exceptionOrNull() }
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(1, writes)
        assertFalse(drained)
    }
    @Test fun paymentSpellingSurvivesTheBundledLexiconAndProducesNonSilentPcm() = runBlocking {
        val words = context.assets.open("models/tts-vctk/lexicon-aivora.txt").bufferedReader().useLines { lines -> lines.map { it.substringBefore(' ') }.toSet() }
        val spelling = com.chronoit.aivora.voice.SpokenIdentifier.render((32..126).map(Int::toChar).joinToString(""))!!
        assertTrue(spelling.split(' ').all { it in words })
        assertEquals(spelling, com.chronoit.aivora.voice.SpeechTextNormalizer(words).normalize(spelling))
        var samples = 0
        var energy = 0.0
        val engine = SherpaTextToSpeechEngine(context.assets) {
            object : SpeechOutput {
                override fun write(audio: FloatArray): Boolean { samples += audio.size; energy += audio.sumOf { it.toDouble() * it }; return true }
                override fun stop() = Unit
                override suspend fun drain() = Unit
                override fun close() = Unit
            }
        }
        engine.use { it.speak(com.chronoit.aivora.voice.SpokenIdentifier.render("aA01@._-")!! + ". Amount zero Indian rupees and five hundredths of a rupee.") }
        assertTrue(samples > 5000)
        assertTrue(energy > 0.1)
    }
    @Test fun speakerEnrollmentAcceptsHeldOutOwnerAndRejectsOtherSpeakerAndSilence() = runBlocking {
        val directory = File(context.noBackupFilesDir, "test-speaker-${UUID.randomUUID()}")
        val store = VoiceProfileStore(directory, FieldCipher(KeystoreKeys::dataKey), "owner.v1")
        val verifier = SherpaSpeakerVerifier(context.assets, store)
        try {
            assertFalse(verifier.isEnrolled())
            assertFalse(verifier.enroll(listOf(wav("fangjun-sr-1.wav"))))
            assertTrue(verifier.enroll((1..3).map { wav("fangjun-sr-$it.wav") }))
            assertTrue(verifier.isEnrolled())
            val genuine = verifier.verify(listOf(wav("fangjun-test-sr-1.wav")))
            assertTrue("Held-out owner score ${genuine.confidence}", genuine.verified)
            val other = verifier.verify(listOf(wav("leijun-test-sr-1.wav")))
            assertFalse("Other speaker score ${other.confidence}", other.verified)
            assertFalse(verifier.verify(listOf(AudioFrame(ShortArray(48000), 16000))).verified)
            val encrypted = File(directory, "owner.v1.enc").readBytes()
            assertFalse(encrypted.toString(Charsets.UTF_8).contains("vectors"))
            assertTrue(SherpaSpeakerVerifier(context.assets, store).isEnrolled())
            verifier.forget()
            assertFalse(verifier.isEnrolled())
        } finally { verifier.close(); store.delete(); directory.delete() }
    }
    private fun wav(name: String): AudioFrame {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("speaker/$name").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12; var rate = 0; var samples = ShortArray(0)
        while (offset + 8 <= bytes.size) {
            val kind = String(bytes, offset, 4)
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset + 8 + size <= bytes.size)
            if (kind == "fmt ") {
                require(buffer.getShort(offset + 8).toInt() == 1 && buffer.getShort(offset + 10).toInt() == 1 && buffer.getShort(offset + 22).toInt() == 16)
                rate = buffer.getInt(offset + 12)
            }
            if (kind == "data") samples = ShortArray(size / 2) { buffer.getShort(offset + 8 + it * 2) }
            offset += 8 + size + size % 2
        }
        return AudioFrame(samples, rate)
    }
}
