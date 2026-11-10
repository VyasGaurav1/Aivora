package com.chronoit.aivora

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.platform.BackgroundWakeService
import com.chronoit.aivora.ui.MainActivity
import com.chronoit.aivora.voice.DescriptorAudioSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackgroundWakeIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun serviceStartsFromVisibleUiAndEitherPausesOrExplainsMissingEnrollment() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                instrumentation.runOnMainSync { assertTrue(BackgroundWakeService.start(context)) }
                val status = withTimeout(5000) { BackgroundWakeService.state.first { state ->
                    state.running || state.message.contains("Enroll an owner voice")
                } }
                if (status.running) assertTrue(status.message, status.message.contains("paused"))
                else assertTrue(status.message, status.message.contains("Enroll an owner voice"))
            } finally { instrumentation.runOnMainSync { BackgroundWakeService.stop(context) } }
        }
    }
    @Test fun descriptorPcmDecodingPreservesSignedSamplesAndRejectsOddByteTail() = runBlocking {
        val raw = File.createTempFile("descriptor-test", ".pcm", context.cacheDir)
        try {
            raw.writeBytes(byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1))
            var source = DescriptorAudioSource(ParcelFileDescriptor.open(raw, ParcelFileDescriptor.MODE_READ_ONLY))
            val frames = source.frames().toList()
            assertArrayEquals(shortArrayOf(0, 32767, -32768, -1), frames.flatMap { it.samples.toList() }.toShortArray())
            raw.writeBytes(byteArrayOf(1, 0, 2))
            source = DescriptorAudioSource(ParcelFileDescriptor.open(raw, ParcelFileDescriptor.MODE_READ_ONLY))
            assertTrue(runCatching { source.frames().toList() }.exceptionOrNull() is IllegalArgumentException)
        } finally { raw.delete() }
    }
}
