package com.chronoit.aivora

import android.Manifest
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.scheduler.*
import com.chronoit.aivora.actions.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import androidx.work.WorkManager
import androidx.work.NetworkType
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

@RunWith(AndroidJUnit4::class)
class TaskConditionsIntegrationTest {
    @Test fun chargingIsPersistedAndRecheckedAtDeliveryReviewAndExecution() = runBlocking {
        assumeTrue("Battery simulation is restricted to an emulator", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        fun battery(arguments: String) {
            automation.executeShellCommand("dumpsys battery $arguments").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        val name = "conditions-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, TaskDatabase::class.java, name).build()
        var scheduler = AIVORAScheduler(context, database)
        var id: String? = null
        suspend fun awaitCharging(expected: Boolean) = withTimeout(5000) {
            while (scheduler.conditionSnapshot().charging != expected) kotlinx.coroutines.delay(20)
        }
        try {
            battery("unplug -f"); battery("set -f status 3")
            awaitCharging(false)
            assertFalse(scheduler.conditionSnapshot().charging)
            val task = scheduler.scheduleAction(Action.SetVolume(30), "Charging fixture", 3_600_000, TaskConditions(charging = true))
            id = task.id
            val workInfo = withTimeout(10_000) {
                WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("aivora.reminder.${task.id}").first { it.isNotEmpty() }.single()
            }
            assertTrue(workInfo.constraints.requiresCharging())
            assertEquals(NetworkType.NOT_REQUIRED, workInfo.constraints.requiredNetworkType)
            assertFalse(database.tasks().get(task.id)!!.encryptedConditions!!.toString(Charsets.UTF_8).contains("AIVORA_CONDITIONS"))
            database.tasks().update(task.id, "PENDING", System.currentTimeMillis() - 1000, 0)
            assertFalse(scheduler.deliver(task.id))
            assertEquals("WAITING_CONDITIONS", scheduler.list().single().state)
            database.close()
            database = Room.databaseBuilder(context, TaskDatabase::class.java, name).build()
            scheduler = AIVORAScheduler(context, database)
            assertTrue(scheduler.list().single().conditions.charging)
            battery("set -f ac 1"); battery("set -f status 2")
            awaitCharging(true)
            assertTrue(scheduler.conditionSnapshot().charging)
            assertTrue(scheduler.deliver(task.id))
            val review = scheduler.reviewAction(task.id)
            val action = review.plan.actions.single()
            battery("unplug -f"); battery("set -f status 3")
            awaitCharging(false)
            assertTrue(runCatching { scheduler.reviewAction(task.id) }.isFailure)
            var executed = false
            val result = scheduler.executeApproved(task.id, action, ActionExecutor { executed = true; ActionResult(ExecutionStatus.SUCCESS, "fixture") }, review.dueAt)
            assertEquals(ExecutionStatus.FAILED, result.status)
            assertFalse(executed)
            assertEquals("AWAITING_REVIEW", scheduler.list().single().state)
        } finally {
            battery("reset -f")
            id?.let { scheduler.cancel(it) }
            database.close(); context.deleteDatabase(name)
        }
    }
}
