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
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScheduledActionIntegrationTest {
    @Test fun versionOneRemindersSurviveActionSchemaMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "task-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        val encrypted = com.chronoit.aivora.security.FieldCipher(com.chronoit.aivora.security.KeystoreKeys::dataKey).encrypt("Legacy reminder", "task:legacy")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE scheduled_tasks (id TEXT NOT NULL PRIMARY KEY, encryptedText BLOB NOT NULL, dueAt INTEGER NOT NULL, repeatMillis INTEGER NOT NULL, state TEXT NOT NULL, lastDeliveryAt INTEGER NOT NULL)")
            db.execSQL("INSERT INTO scheduled_tasks VALUES (?, ?, ?, ?, ?, ?)", arrayOf("legacy", encrypted, System.currentTimeMillis() + 3_600_000, 0, "PENDING", 0))
            db.version = 1
        }
        val database = Room.databaseBuilder(context, TaskDatabase::class.java, name).addMigrations(TaskDatabase.MIGRATION_1_2, TaskDatabase.MIGRATION_2_3).build()
        try {
            val reminder = AIVORAScheduler(context, database).list().single()
            assertEquals("Legacy reminder", reminder.text)
            assertFalse(reminder.hasAction)
            assertEquals(TaskConditions(), reminder.conditions)
            assertNull(database.tasks().get("legacy")!!.encryptedAction)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun dueActionWaitsForReviewAndCancellationOrReplayCannotExecute() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val name = "action-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, TaskDatabase::class.java, name).build()
        val scheduler = AIVORAScheduler(context, database)
        val ids = mutableListOf<String>()
        var executions = 0
        val executor = ActionExecutor { executions++; ActionResult(ExecutionStatus.SUCCESS, "fixture") }
        suspend fun execute(id: String, action: Action, executor: ActionExecutor) =
            scheduler.executeApproved(id, action, executor, database.tasks().get(id)!!.dueAt)
        try {
            val action = Action.SetVolume(30)
            val task = scheduler.scheduleAction(action, "Set volume to 30%", 3_600_000)
            ids.add(task.id)
            assertTrue(runCatching { scheduler.reviewAction(task.id) }.isFailure)
            val stored = database.tasks().get(task.id)!!
            assertFalse(stored.encryptedAction!!.toString(Charsets.UTF_8).contains("AIVORA_ACTION"))
            database.tasks().update(task.id, "PENDING", System.currentTimeMillis() - 1000, 0)
            assertTrue(scheduler.deliver(task.id))
            assertEquals(0, executions)
            assertEquals("AWAITING_REVIEW", scheduler.list().single().state)
            assertEquals(listOf(action), scheduler.reviewAction(task.id).plan.actions)
            assertEquals(ExecutionStatus.FAILED, execute(task.id, Action.SetVolume(40), executor).status)
            assertEquals(0, executions)
            assertEquals(ExecutionStatus.SUCCESS, execute(task.id, action, executor).status)
            assertEquals(1, executions)
            val interrupted = scheduler.scheduleAction(action, "Interrupted fixture", 3_600_000)
            ids.add(interrupted.id)
            database.tasks().update(interrupted.id, "AWAITING_REVIEW", System.currentTimeMillis() - 1000, 0)
            assertTrue(runCatching { execute(interrupted.id, action, ActionExecutor { error("interrupted") }) }.isFailure)
            assertEquals("STARTED", scheduler.list().first { it.id == interrupted.id }.state)
            assertEquals(ExecutionStatus.CANCELLED, execute(interrupted.id, action, executor).status)
            assertEquals(1, executions)
            assertEquals(ExecutionStatus.CANCELLED, execute(task.id, action, executor).status)
            assertEquals(1, executions)
            val canceled = scheduler.scheduleAction(action, "Canceled fixture", 3_600_000)
            ids.add(canceled.id)
            database.tasks().update(canceled.id, "AWAITING_REVIEW", System.currentTimeMillis() - 1000, 0)
            scheduler.cancel(canceled.id)
            assertEquals(ExecutionStatus.CANCELLED, execute(canceled.id, action, executor).status)
            assertEquals(1, executions)
        } finally { ids.forEach { scheduler.cancel(it) }; database.close(); context.deleteDatabase(name) }
    }
}

