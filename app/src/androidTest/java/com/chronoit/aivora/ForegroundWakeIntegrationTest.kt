package com.chronoit.aivora

import android.Manifest
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.security.*
import com.chronoit.aivora.ui.MainActivity
import com.chronoit.aivora.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class ForegroundWakeIntegrationTest {
    @Test fun actualRecorderPausesResumesAndStopsWithoutRecognizingOrExecutingACommand() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        val directory = File(app.noBackupFilesDir, "test-foreground-wake-${UUID.randomUUID()}")
        val cipher = FieldCipher(KeystoreKeys::dataKey)
        val ownerStore = VoiceProfileStore(directory, cipher, "owner.v1")
        val wakeStore = VoiceProfileStore(directory, cipher, "wake.v1")
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val ready = AtomicBoolean(true)
        val commandReceived = AtomicBoolean(false)
        val recordings = app.getSystemService(AudioManager::class.java)
        val controller = VoiceSessionController(app, app.settings, scope, {}, {}, {},
            automaticWakeAllowed = ready::get, beforeAutomaticCapture = { true },
            onVerifiedCommand = { commandReceived.set(true) }, profileDirectory = directory)
        suspend fun recording(active: Boolean) = withTimeout(10_000) {
            while (recordings.activeRecordingConfigurations.isNotEmpty() != active) delay(50)
        }
        try {
            SherpaSpeakerVerifier(app.assets, ownerStore).use { verifier ->
                val samples = (1..3).map { wav("fangjun-sr-$it.wav") }
                try { assertTrue(verifier.enroll(samples)) } finally { samples.forEach { it.samples.fill(0) } }
            }
            PersonalizedWakeWordEngine(wakeStore).use { wake ->
                val sample = AudioFrame(ShortArray(16000) { i -> (sin(i * if (i < 8000) 0.1 else 0.2) * 8000).toInt().toShort() }, 16000)
                try { assertTrue(wake.enroll("Aivora", listOf(sample, sample, sample))) } finally { sample.samples.fill(0) }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                controller.refresh()
                withTimeout(10_000) { controller.state.first { it.ownerEnrolled && it.activeWakeName != null } }
                recording(false)
                assertFalse(controller.state.value.foregroundWakeEnabled)
                val otherSession = MicrophoneLease.acquire()
                try {
                    controller.startForegroundWake()
                    delay(400)
                    assertFalse(controller.state.value.capturing)
                    assertTrue(recordings.activeRecordingConfigurations.isEmpty())
                } finally { otherSession.close() }
                recording(true)
                assertTrue(controller.state.value.foregroundWakeEnabled)
                ready.set(false)
                controller.pauseForegroundWake()
                recording(false)
                assertTrue(controller.state.value.foregroundWakeEnabled)
                ready.set(true)
                controller.resumeForegroundWake()
                recording(true)
                controller.stopForegroundWake()
                recording(false)
                assertFalse(controller.state.value.foregroundWakeEnabled)
                assertFalse(commandReceived.get())
                // The shared recorder lease is released after the observed stop.
                withTimeout(5000) {
                    while (runCatching { MicrophoneLease.acquire().close() }.isFailure) delay(20)
                }
            }
        } finally {
            controller.close()
            scopeJob.cancelAndJoin()
            ownerStore.delete(); wakeStore.delete(); directory.delete()
        }
    }
    private fun wav(name: String): AudioFrame {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("speaker/$name").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val size = buffer.getInt(offset + 4)
            if (String(bytes, offset, 4) == "data") return AudioFrame(ShortArray(size / 2) { buffer.getShort(offset + 8 + it * 2) }, 16000)
            offset += 8 + size + (size and 1)
        }
        error("Fixture has no PCM data")
    }
}
