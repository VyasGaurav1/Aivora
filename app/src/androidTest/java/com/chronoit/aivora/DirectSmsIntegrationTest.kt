package com.chronoit.aivora

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.platform.*
import com.chronoit.aivora.ui.AssistantViewModel
import com.chronoit.aivora.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Refusal/review tests only: SEND_SMS must be absent and no send is attempted. */
@RunWith(AndroidJUnit4::class)
class DirectSmsIntegrationTest {
    @Test fun explicitDraftOnlyReviewAndSavedActionPreserveIntent(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        assertNotEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.SEND_SMS))
        val original = app.settings.settings.first()
        val before = app.smsDatabase.attempts().latest()?.id
        val store = ViewModelStore()
        val database = com.chronoit.aivora.scheduler.TaskDatabase.open(app)
        var taskId: String? = null
        ActivityScenario.launch(MainActivity::class.java).use {
            val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AssistantViewModel::class.java]
            try {
                app.settings.setDirectSms(true); app.settings.setSpokenReplies(false); app.settings.setHandsFreeControls(false)
                withTimeout(10000) { model.settings.first { it.directSms && !it.spokenReplies && !it.handsFreeControls } }
                model.foreground(true); model.resumed(true)
                model.submit("draft SMS to +12025550123 that Fixture not sent")
                val review = withTimeout(10000) { model.ui.first { it.confirmation != null } }.confirmation!!
                val expected = Action.DraftMessage("+12025550123", "Fixture not sent", draftOnly = true)
                assertEquals(listOf(expected), review.plan.actions)
                assertNull(model.ui.value.authentication)
                assertFalse(model.ui.value.conversation.any { it.text.contains("Direct SMS is unavailable") })
                val saved = app.scheduler.scheduleAction(expected, "Explicit draft fixture", 3600000)
                taskId = saved.id
                database.tasks().update(saved.id, "PENDING", System.currentTimeMillis() - 1000, 0)
                assertEquals(listOf(expected), app.scheduler.reviewAction(saved.id).plan.actions)
                assertEquals(before, app.smsDatabase.attempts().latest()?.id)
            } finally {
                model.cancel(); store.clear()
                taskId?.let { app.scheduler.cancel(it) }
                database.close()
                app.settings.setDirectSms(original.directSms)
                app.settings.setSpokenReplies(original.spokenReplies)
                app.settings.setHandsFreeControls(original.handsFreeControls)
            }
        }
    }
    @Test fun optInAndMissingPermissionRefuseBeforeAnySubmission() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        assertNotEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.SEND_SMS))
        val original = app.settings.settings.first().directSms
        val action = Action.SendSms("+12025550123", "Fixture not sent", SmsTarget(1, 0, 1, "Fixture SIM"))
        try {
            app.settings.setDirectSms(false)
            assertEquals(ExecutionStatus.PERMISSION_MISSING, DirectSmsAdapter(app).execute(action).status)
            app.settings.setDirectSms(true)
            assertEquals(ExecutionStatus.PERMISSION_MISSING, DirectSmsAdapter(app).execute(action).status)
            assertFalse(app.packageManager.getReceiverInfo(ComponentName(app, SmsSentReceiver::class.java), 0).exported)
            assertTrue(runCatching { app.scheduler.scheduleAction(action, "forbidden SMS schedule", 3600000) }.isFailure)
        } finally { app.settings.setDirectSms(original) }
    }
    @Test fun unavailableSendingPreservesDraftReviewAndNeverRequestsSendAuthentication(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        assertNotEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.SEND_SMS))
        val original = app.settings.settings.first()
        val store = ViewModelStore()
        ActivityScenario.launch(MainActivity::class.java).use {
            val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AssistantViewModel::class.java]
            try {
                app.settings.setDirectSms(true); app.settings.setSpokenReplies(false); app.settings.setHandsFreeControls(false)
                withTimeout(10000) { model.settings.first { it.directSms && !it.spokenReplies && !it.handsFreeControls } }
                model.foreground(true); model.resumed(true)
                model.submit("text +12025550123 that Fixture not sent")
                val review = withTimeout(10000) { model.ui.first { it.confirmation != null } }.confirmation!!
                assertEquals(listOf(Action.DraftMessage("+12025550123", "Fixture not sent")), review.plan.actions)
                assertNull(model.ui.value.authentication)
                assertTrue(model.ui.value.conversation.any { it.text.contains("draft-only") })
            } finally {
                model.cancel(); store.clear()
                app.settings.setDirectSms(original.directSms)
                app.settings.setSpokenReplies(original.spokenReplies)
                app.settings.setHandsFreeControls(original.handsFreeControls)
            }
        }
    }
}
