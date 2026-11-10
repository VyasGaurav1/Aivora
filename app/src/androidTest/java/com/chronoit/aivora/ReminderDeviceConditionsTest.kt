package com.chronoit.aivora

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.os.PowerManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderDeviceConditionsTest {
    private val app = ApplicationProvider.getApplicationContext<AivoraApplication>()
    private fun shell(command: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
    @Test fun spokenReminderRunsWithScreenOffAndBatterySaverEnabled() = runBlocking {
        val power = app.getSystemService(PowerManager::class.java)
        val originalSaver = power.isPowerSaveMode
        val plugged = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra("plugged", 0) != 0
        val reminder = app.scheduler.create("AIVORA screen off battery saver reminder check", 5000)
        try {
            if (plugged) shell("dumpsys battery unplug")
            shell("cmd power set-mode 1")
            withTimeout(10000) { while (!power.isPowerSaveMode) delay(100) }
            shell("cmd power sleep")
            withTimeout(10000) { while (power.isInteractive) delay(100) }
            withTimeout(90000) { while (app.scheduler.voiceState(reminder.id) != "SPOKEN") delay(200) }
            assertFalse(power.isInteractive)
            assertTrue(power.isPowerSaveMode)
        } finally {
            app.scheduler.cancel(reminder.id)
            shell("cmd power set-mode ${if (originalSaver) 1 else 0}")
            if (plugged) shell("dumpsys battery reset")
            shell("cmd power wakeup")
        }
    }
    private fun fixture() = java.io.File(app.getExternalFilesDir(null), "diagnostics/aivora-reboot-fixture.txt")
    @Test fun stageReminderForRealReboot() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("real_reboot_phase") == "stage")
        val reminder = app.scheduler.create("AIVORA reboot recovery reminder check", 45000)
        fixture().apply { parentFile?.mkdirs(); writeText(reminder.id) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("reboot_fixture", reminder.id) })
    }
    @Test fun verifyAndCleanReminderAfterRealReboot() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("real_reboot_phase") == "verify")
        val file = fixture()
        assertTrue("No staged restart reminder", file.exists())
        val id = file.readText().trim()
        try {
            withTimeout(120000) { while (app.scheduler.voiceState(id) != "SPOKEN") delay(200) }
        } finally { app.scheduler.cancel(id); file.delete() }
    }
}
