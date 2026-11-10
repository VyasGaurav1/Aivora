package com.chronoit.aivora

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.scheduler.TaskDatabase
import com.chronoit.aivora.ui.AssistantViewModel
import com.chronoit.aivora.ui.MainActivity
import com.chronoit.aivora.voice.VerifiedVoiceCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Injected voice provenance, not acoustic recognition. No messages/calls are sent. */
@RunWith(AndroidJUnit4::class)
class VoiceScheduledReviewIntegrationTest {
    @Test fun numberedDueTaskReplySelectsOnlyItsReviewedOccurrence(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val original = app.settings.settings.first()
        val audio = app.getSystemService(AudioManager::class.java)
        val oldVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val database = TaskDatabase.open(app)
        val store = ViewModelStore()
        val ids = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use {
            val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AssistantViewModel::class.java]
            fun voice(text: String) { val clock = { System.nanoTime() / 1_000_000 }; model.acceptVoiceCommand(VerifiedVoiceCommand(text, clock(), clock)) }
            try {
                app.settings.setHandsFreeControls(true); app.settings.setSpokenReplies(false)
                withTimeout(10000) { model.settings.first { it.handsFreeControls && !it.spokenReplies } }
                model.foreground(true); model.resumed(true)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                val initial = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                for ((index, volume) in listOf(10, 0).withIndex()) {
                    val saved = app.scheduler.scheduleAction(Action.SetVolume(volume), "Numbered due fixture $index", 3600000)
                    ids += saved.id
                    database.tasks().update(saved.id, "PENDING", System.currentTimeMillis() - 2000 + index * 1000, 0)
                }
                voice("review due action")
                withTimeout(10000) { model.ui.first { state -> state.conversation.any { it.text.contains("2 task choices") } } }
                assertNull(model.ui.value.confirmation)
                voice("choose task number two")
                withTimeout(10000) { model.ui.first { state -> state.conversation.lastOrNull()?.text?.startsWith("No matching choice") == true } }
                assertNull(model.ui.value.confirmation)
                model.prepareVoiceCapture(); voice("choose task number two")
                withTimeout(10000) { model.ui.first { it.confirmation?.plan?.actions == listOf(Action.SetVolume(0)) } }
                assertEquals(initial, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                assertEquals("PENDING", database.tasks().get(ids[0])!!.state)
                model.prepareVoiceCapture(); voice("confirm the action I just reviewed")
                withTimeout(10000) { model.ui.first { state -> state.conversation.any { it.text.startsWith("Media volume set to 0%") } } }
                assertEquals("HANDLED", database.tasks().get(ids[1])!!.state)
                assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            } finally {
                model.cancel(); store.clear()
                ids.forEach { app.scheduler.cancel(it) }
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0)
                app.settings.setHandsFreeControls(original.handsFreeControls)
                app.settings.setSpokenReplies(original.spokenReplies)
                database.close()
            }
        }
    }
    @Test fun duePendingActionRequiresFreshVoiceConfirmationAndCancellationStillWins(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val original = app.settings.settings.first()
        val audio = app.getSystemService(AudioManager::class.java)
        val oldVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val database = TaskDatabase.open(app)
        val ids = mutableListOf<String>()
        val store = ViewModelStore()
        ActivityScenario.launch(MainActivity::class.java).use {
            val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AssistantViewModel::class.java]
            fun voice(text: String) { val clock = { System.nanoTime() / 1_000_000 }; model.acceptVoiceCommand(VerifiedVoiceCommand(text, clock(), clock)) }
            try {
                app.settings.setHandsFreeControls(true)
                app.settings.setSpokenReplies(false)
                withTimeout(10000) { model.settings.first { it.handsFreeControls && !it.spokenReplies } }
                model.foreground(true); model.resumed(true)
                for (cancel in listOf(true, false)) {
                    val task = app.scheduler.scheduleAction(Action.SetVolume(0), "AIVORA due voice fixture", 3_600_000)
                    ids += task.id
                    // No notification worker or permission is needed for an explicit foreground due review.
                    database.tasks().update(task.id, "PENDING", System.currentTimeMillis() - 1000, 0)
                    voice("review scheduled action ${task.id}")
                    val review = withTimeout(10000) { model.ui.first { it.confirmation?.plan?.actions == listOf(Action.SetVolume(0)) } }.confirmation!!
                    assertEquals("AWAITING_REVIEW", database.tasks().get(task.id)!!.state)
                    val due = database.tasks().get(task.id)!!.dueAt
                    assertTrue(runCatching { app.scheduler.reviewAction(task.id, expectedDueAt = due + 1) }.isFailure)
                    assertTrue(app.scheduler.reviewStillValid(task.id, due, review.plan))
                    assertFalse(app.scheduler.reviewStillValid(task.id, due + 1, review.plan))
                    assertFalse(app.scheduler.reviewStillValid(task.id, due, ActionPlan(listOf(Action.SetVolume(10)), 1.0, "changed")))
                    assertEquals(oldVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    if (cancel) {
                        app.scheduler.cancel(task.id)
                        assertFalse(app.scheduler.reviewStillValid(task.id, due, review.plan))
                    }
                    model.prepareVoiceCapture()
                    voice("confirm the action I just reviewed")
                    withTimeout(10000) { model.ui.first { it.confirmation == null && it.state != com.chronoit.aivora.ui.AssistantState.EXECUTING } }
                    if (cancel) {
                        assertEquals("CANCELED", database.tasks().get(task.id)!!.state)
                        assertEquals(oldVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    } else {
                        withTimeout(10000) { while (database.tasks().get(task.id)!!.state != "HANDLED") delay(20) }
                        withTimeout(10000) { model.ui.first { state -> state.conversation.any { it.text.startsWith("Media volume set to 0%") } } }
                        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    }
                }
            } finally {
                model.cancel(); store.clear()
                ids.forEach { app.scheduler.cancel(it) }
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0)
                app.settings.setHandsFreeControls(original.handsFreeControls)
                app.settings.setSpokenReplies(original.spokenReplies)
                database.close()
            }
        }
    }
}
