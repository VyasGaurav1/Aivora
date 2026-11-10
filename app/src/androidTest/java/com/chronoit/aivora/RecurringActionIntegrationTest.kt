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
class RecurringActionIntegrationTest {
    @Test fun occurrencesNeedFreshReviewAndUncertainResultsPauseTheSeries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val name = "recurring-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, TaskDatabase::class.java, name).build()
        val scheduler = AIVORAScheduler(context, database)
        val ids = mutableListOf<String>()
        val action = Action.SetVolume(30)
        var executions = 0
        val executor = ActionExecutor { executions++; ActionResult(ExecutionStatus.SUCCESS, "fixture") }
        try {
            val series = scheduler.scheduleAction(action, "Recurring fixture", 3_600_000, repeatMillis = ScheduleTiming.MIN_REPEAT)
            ids.add(series.id)
            val firstDue = System.currentTimeMillis() - 2 * ScheduleTiming.MIN_REPEAT - 1000
            database.tasks().update(series.id, "PENDING", firstDue, 0)
            assertTrue(scheduler.deliver(series.id))
            assertEquals(0, executions)
            val first = scheduler.reviewAction(series.id)
            assertEquals(firstDue, first.dueAt)
            assertEquals(ExecutionStatus.SUCCESS, scheduler.executeApproved(series.id, action, executor, first.dueAt).status)
            assertEquals(1, executions)
            val next = scheduler.list().single()
            assertEquals("PENDING", next.state)
            assertTrue(next.dueAt > System.currentTimeMillis())
            assertEquals(0L, (next.dueAt - firstDue) % ScheduleTiming.MIN_REPEAT)
            assertTrue(runCatching { scheduler.reviewAction(series.id) }.isFailure)
            // Advance the persisted occurrence to a due state without a wall-clock sleep.
            val secondDue = System.currentTimeMillis() - 1000
            database.tasks().update(series.id, "AWAITING_REVIEW", secondDue, firstDue)
            assertEquals(ExecutionStatus.CANCELLED, scheduler.executeApproved(series.id, action, executor, first.dueAt).status)
            assertEquals(1, executions)
            val second = scheduler.reviewAction(series.id)
            assertEquals(secondDue, second.dueAt)
            assertNotEquals(first.plan.planId, second.plan.planId)
            assertEquals(ExecutionStatus.PARTIAL, scheduler.executeApproved(series.id, action, ActionExecutor {
                executions++; ActionResult(ExecutionStatus.PARTIAL, "Unverified fixture")
            }, second.dueAt).status)
            assertEquals(2, executions)
            assertEquals("PAUSED", scheduler.list().single().state)
            assertTrue(runCatching { scheduler.reviewAction(series.id) }.isFailure)
            assertEquals(ExecutionStatus.CANCELLED, scheduler.executeApproved(series.id, action, executor, second.dueAt).status)
            assertEquals(2, executions)
            scheduler.cancel(series.id)
            assertTrue(scheduler.list().isEmpty())
        } finally { ids.forEach { scheduler.cancel(it) }; database.close(); context.deleteDatabase(name) }
    }
}
