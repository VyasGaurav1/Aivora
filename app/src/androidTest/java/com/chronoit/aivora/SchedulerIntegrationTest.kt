package com.chronoit.aivora

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.scheduler.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import androidx.work.WorkManager
import androidx.work.WorkInfo
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

@RunWith(AndroidJUnit4::class)
class SchedulerIntegrationTest {
    @Test fun actualPersistentWorkerPostsReminder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val scheduler = (context as AivoraApplication).scheduler
        val reminder = scheduler.create("AIVORA worker fixture", 1000)
        try {
            withTimeout(60_000) {
                WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("aivora.reminder.${reminder.id}")
                    .first { entries -> entries.any { it.state == WorkInfo.State.SUCCEEDED } }
            }
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == reminder.id })
            assertFalse(scheduler.list().any { it.id == reminder.id })
        } finally { scheduler.cancel(reminder.id) }
    }
    @Test fun encryptedDefinitionsReopenDeliverVerifyCancelAndAdvanceRecurrences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val name = "scheduler-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, TaskDatabase::class.java, name).build()
        val ids = mutableListOf<String>()
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            var scheduler = AIVORAScheduler(context, database)
            val reminder = scheduler.create("private reminder sample", 3_600_000)
            ids.add(reminder.id)
            assertFalse(database.tasks().get(reminder.id)!!.encryptedText.toString(Charsets.UTF_8).contains("private reminder"))
            assertFalse(scheduler.deliver(reminder.id))
            database.close()
            database = Room.databaseBuilder(context, TaskDatabase::class.java, name).build()
            scheduler = AIVORAScheduler(context, database)
            assertEquals("private reminder sample", scheduler.list().single().text)
            database.tasks().update(reminder.id, "PENDING", System.currentTimeMillis() - 1000, 0)
            assertTrue(scheduler.deliver(reminder.id))
            assertEquals("DELIVERED", database.tasks().get(reminder.id)!!.state)
            assertTrue(manager.activeNotifications.any { it.tag == reminder.id })
            val deliveredAt = database.tasks().get(reminder.id)!!.lastDeliveryAt
            assertTrue(scheduler.deliver(reminder.id))
            assertEquals(deliveredAt, database.tasks().get(reminder.id)!!.lastDeliveryAt)
            val recurring = scheduler.create("recurring sample", 3_600_000, ScheduleTiming.MIN_REPEAT)
            ids.add(recurring.id)
            database.tasks().update(recurring.id, "PENDING", System.currentTimeMillis() - 3 * ScheduleTiming.MIN_REPEAT, 0)
            assertTrue(scheduler.deliver(recurring.id))
            assertTrue(database.tasks().get(recurring.id)!!.dueAt > System.currentTimeMillis())
            scheduler.cancel(recurring.id)
            assertTrue(scheduler.deliver(recurring.id))
            assertEquals("CANCELED", database.tasks().get(recurring.id)!!.state)
            // NotificationManager cancellation is asynchronous, including on API30.
            withTimeout(2000) {
                while (manager.activeNotifications.any { it.tag == recurring.id }) kotlinx.coroutines.delay(20)
            }
            assertFalse(manager.activeNotifications.any { it.tag == recurring.id })
        } finally {
            ids.forEach { AIVORAScheduler(context, database).cancel(it) }
            database.close(); context.deleteDatabase(name)
        }
    }
}

