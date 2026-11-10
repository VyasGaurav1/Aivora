package com.chronoit.aivora.scheduler

import android.content.Context
import com.chronoit.aivora.voice.*
import kotlinx.coroutines.*

internal class ReminderSpeaker(private val context: Context) {
    suspend fun speak(text: String, stillCurrent: suspend () -> Boolean = { true }): Boolean {
        var completed = false
        VoiceRuntime.speakWhenIdle {
            if (!stillCurrent()) return@speakWhenIdle
            // Saved reminders never leave the device for cloud synthesis.
            val phone = withContext(Dispatchers.Main.immediate) { IndianEnglishSpeechEngine(context) }
            try { withTimeout(60000) {
                completed = SpeechInterruption(context).play(stop = phone::stop, onWake = {
                    if (!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
                        com.chronoit.aivora.platform.AivoraVoiceInteractionService.requestVoiceSession()
                }) { phone.speak("Reminder. $text") }
            } }
            finally { phone.close() }
            android.util.Log.i("AivoraReminder", if (completed) "Spoken reminder playback completed" else "Reminder speech interrupted by wake word")
        }
        return completed
    }
}
