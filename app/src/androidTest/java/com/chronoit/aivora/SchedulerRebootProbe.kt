package com.chronoit.aivora

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit two-phase probe: tools must reboot the isolated device between invocations. */
@RunWith(AndroidJUnit4::class)
class SchedulerRebootProbe {
    @Test fun persistentReminderAcrossRealReboot() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("rebootPhase")
        assumeTrue("Requires explicit prepare/verify reboot phases", phase != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = (context as AivoraApplication).scheduler
        val fixture = context.getSharedPreferences("aivora_reboot_fixture", Context.MODE_PRIVATE)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        if (phase == "prepare") {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            fixture.getString("id", null)?.let { scheduler.cancel(it) }
            val reminder = scheduler.create("AIVORA reboot fixture", 30_000)
            assertTrue(fixture.edit().putString("id", reminder.id).putInt("boot", boot).commit())
        } else {
            require(phase == "verify")
            val id = requireNotNull(fixture.getString("id", null))
            try {
                assertTrue("The device must actually reboot", boot > fixture.getInt("boot", Int.MAX_VALUE))
                withTimeout(90_000) {
                    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("aivora.reminder.$id")
                        .first { entries -> entries.any { it.state == WorkInfo.State.SUCCEEDED } }
                }
                assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == id })
                assertFalse(scheduler.list().any { it.id == id })
            } finally { scheduler.cancel(id); fixture.edit().clear().commit() }
        }
    }
}
