package com.chronoit.aivora

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.*
import com.chronoit.aivora.voice.VoiceRuntime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReminderRecoveryIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<AivoraApplication>()
    @Test fun reschedulingAndRepeatedRecoveryDeliverOnlyOneSpeechJob() = runBlocking {
        val reminder = app.scheduler.create("AIVORA restored reminder check", 2000)
        try {
            app.scheduler.restore(reschedule = true)
            app.scheduler.restore()
            withTimeout(60000) { while (app.scheduler.voiceState(reminder.id) != "SPOKEN") delay(200) }
            app.scheduler.restore()
            val jobs = withContext(Dispatchers.IO) { WorkManager.getInstance(app).getWorkInfosByTag("aivora.voice.reminder.${reminder.id}").get(10, TimeUnit.SECONDS) }
            assertEquals("Recovery must preserve the unique occurrence speech job", 1, jobs.size)
        } finally { app.scheduler.cancel(reminder.id) }
    }
    @Test fun cancelledReminderDoesNotSpeakAfterConversationReleases() = runBlocking {
        val ownership = VoiceRuntime.hold(conversation = true)
        val reminder = app.scheduler.create("AIVORA cancellation check", 1500)
        try {
            val manager = app.getSystemService(NotificationManager::class.java)
            withTimeout(15000) { while (manager.activeNotifications.none { it.tag == reminder.id }) delay(100) }
            app.scheduler.cancel(reminder.id)
            ownership.close()
            delay(1500)
            assertNotEquals("SPOKEN", app.scheduler.voiceState(reminder.id))
            assertTrue(manager.activeNotifications.none { it.tag == reminder.id })
        } finally { ownership.close(); app.scheduler.cancel(reminder.id) }
    }
}
