package com.chronoit.aivora

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.voice.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generated model audio tests decoding only, without implying a microphone or human wake pass. */
@RunWith(AndroidJUnit4::class)
class WakePhraseModelIntegrationTest {
    @Test fun bundledKeywordDetectorFindsBothSynthesizedWakePhrases() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (phrase in listOf("Hey Jarvis", "Hey Aivora")) {
            var rate = 0
            val audio = ArrayList<Float>()
            SherpaTextToSpeechEngine(context.assets) { sampleRate ->
                rate = sampleRate
                object : SpeechOutput {
                    override fun write(samples: FloatArray): Boolean { audio.addAll(samples.toList()); return true }
                    override fun stop() = Unit
                    override suspend fun drain() = Unit
                    override fun close() = Unit
                }
            }.use { it.speak(phrase) }
            assertTrue(audio.isNotEmpty())
            val resampled = ShortArray((audio.size.toLong() * 16000 / rate).toInt()) { index ->
                val position = index.toDouble() * rate / 16000
                val left = position.toInt().coerceAtMost(audio.lastIndex)
                val right = (left + 1).coerceAtMost(audio.lastIndex)
                val fraction = position - left
                ((audio[left] * (1 - fraction) + audio[right] * fraction) * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
            val frames = resampled.toList().chunked(1600).map { AudioFrame(it.toShortArray(), 16000) }
            val matched = awaitKeywordWake(context.assets,
                (frames + List(8) { AudioFrame(ShortArray(1600), 16000) }).asFlow())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("generated_phrase_decode", "$phrase -> $matched") })
            assertTrue("Keyword not detected: $phrase", matched)
        }
    }
}
