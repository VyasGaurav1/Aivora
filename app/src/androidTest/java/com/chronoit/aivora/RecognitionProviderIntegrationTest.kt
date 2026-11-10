package com.chronoit.aivora

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.*
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.platform.AivoraRecognitionService
import com.chronoit.aivora.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class RecognitionProviderIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun providerReportsBundledEnglishAndNoOnlineLanguages() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val completed = CountDownLatch(1)
            var support: RecognitionSupport? = null
            var error: Int? = null
            lateinit var client: SpeechRecognizer
            instrumentation.runOnMainSync {
                client = SpeechRecognizer.createSpeechRecognizer(context, ComponentName(context, AivoraRecognitionService::class.java))
                client.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US"), context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(result: RecognitionSupport) { support = result; completed.countDown() }
                        override fun onError(code: Int) { error = code; completed.countDown() }
                    })
            }
            try {
                assertTrue("Support callback timed out", completed.await(15, TimeUnit.SECONDS))
                assertNull("Support error $error", error)
                assertTrue(support!!.installedOnDeviceLanguages.contains("en-US"))
                assertTrue(support!!.onlineLanguages.isEmpty())
            } finally { instrumentation.runOnMainSync { client.destroy() } }
        }
    }
    @Test fun systemSpeechClientTranscribesProvidedPcmThroughTheOfflineService() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        }
        val raw = File.createTempFile("recognition-test", ".pcm", context.cacheDir)
        raw.writeBytes(referencePcm())
        val source = ParcelFileDescriptor.open(raw, ParcelFileDescriptor.MODE_READ_ONLY)
        ActivityScenario.launch(MainActivity::class.java).use {
            val completed = CountDownLatch(1)
            var recognized: String? = null
            var error: Int? = null
            lateinit var client: SpeechRecognizer
            instrumentation.runOnMainSync {
                client = SpeechRecognizer.createSpeechRecognizer(context, ComponentName(context, AivoraRecognitionService::class.java))
                client.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onError(code: Int) { error = code; completed.countDown() }
                    override fun onResults(results: Bundle?) { recognized = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); completed.countDown() }
                })
                client.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US").putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT))
            }
            try {
                assertTrue("Recognition callback timed out", completed.await(20, TimeUnit.SECONDS))
                assertNull("Recognition error $error", error)
                assertTrue(recognized.orEmpty(), recognized.orEmpty().contains("early nightfall", true))
                assertTrue(recognized.orEmpty(), recognized.orEmpty().contains("yellow lamps", true))
            } finally { instrumentation.runOnMainSync { client.destroy() }; source.close(); raw.delete() }
        }
    }
    private fun referencePcm(): ByteArray {
        val bytes = instrumentation.context.assets.open("speech/0.wav").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset + 8 + size <= bytes.size)
            if (String(bytes, offset, 4) == "data") return bytes.copyOfRange(offset + 8, offset + 8 + size)
            offset += 8 + size + size % 2
        }
        error("Missing reference PCM")
    }
}
