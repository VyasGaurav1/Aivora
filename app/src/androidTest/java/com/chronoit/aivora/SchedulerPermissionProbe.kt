package com.chronoit.aivora

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.scheduler.TaskDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Permission is changed between invocations because Android may kill an app when revoking it. */
@RunWith(AndroidJUnit4::class)
class SchedulerPermissionProbe {
    @Test fun deniedThenRestoredPermission() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("permissionPhase")
        assumeTrue("Requires external permission changes", phase != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = (context as AivoraApplication).scheduler
        val fixture = context.getSharedPreferences("aivora_permission_fixture", Context.MODE_PRIVATE)
        if (phase == "denied") {
            assertFalse(scheduler.notificationsAllowed())
            val reminder = scheduler.create("AIVORA denied fixture", 3_600_000)
            assertTrue(fixture.edit().putString("id", reminder.id).commit())
            val database = TaskDatabase.open(context)
            try { database.tasks().update(reminder.id, "PENDING", System.currentTimeMillis() - 1000, 0) } finally { database.close() }
            assertFalse(scheduler.deliver(reminder.id))
            assertEquals("BLOCKED", scheduler.list().first { it.id == reminder.id }.state)
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == reminder.id })
        } else {
            require(phase == "restored")
            val id = requireNotNull(fixture.getString("id", null))
            try {
                assertTrue(scheduler.notificationsAllowed())
                assertTrue(scheduler.deliver(id))
                assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == id })
                assertFalse(scheduler.list().any { it.id == id })
            } finally { scheduler.cancel(id); fixture.edit().clear().commit() }
        }
    }
}
