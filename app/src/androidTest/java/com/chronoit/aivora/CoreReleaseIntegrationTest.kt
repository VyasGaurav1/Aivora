package com.chronoit.aivora

import android.app.NotificationManager
import android.app.Notification
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.brain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the installed owner key without exposing it, and only a scoped reminder fixture. */
@RunWith(AndroidJUnit4::class)
class CoreReleaseIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<AivoraApplication>()
    @Test fun localModelClassifiesReleaseCapabilitiesWithoutExecutingActions() = runBlocking {
        for ((request, expected) in listOf(
            "Tell me the things you are able to help with" to "CAPABILITIES",
            "Please transfer thirty rupees to my friend" to "UNSUPPORTED",
            "I need an alarm to wake me up" to "ALARM"
        )) {
            assertEquals("Unexpected routing for public fixture: $request", expected, app.reasoning.classifyRequest(request))
        }
    }
    @Test fun wakePhraseCanBeRecognizedInTwoSuccessiveSessions() = runBlocking {
        val client = com.chronoit.aivora.voice.GnaniSpeechClient(app)
        assertTrue(client.configured())
        try {
            val data = InstrumentationRegistry.getInstrumentation().context.assets.open("speech/gnani-wake-command.pcm").use { it.readBytes() }
            val samples = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { buffer -> ShortArray(buffer.remaining()).also { buffer.get(it) } }
            repeat(2) {
                val frames = samples.toList().chunked(1600).map { com.chronoit.aivora.voice.AudioFrame(it.toShortArray(), 16000) }.asFlow().onEach { delay(100) }
                val heard = withTimeout(20000) { client.listen(wakeOnly = true, suppliedAudio = frames) }
                assertTrue("Repeated synthetic wake was not recognized", com.chronoit.aivora.voice.wakeTextMatches(heard.orEmpty(), "Aivora"))
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("core_repeated_wake", "Two successive recorded wake sessions passed") })
        } finally { client.close() }
    }
    @Test fun recordedAudioQueryIsRecognizedSummarizedAndSpoken() = runBlocking {
        val client = com.chronoit.aivora.voice.GnaniSpeechClient(app)
        assertTrue("Gnani key must be configured for the audio pipeline check", client.configured())
        try {
            val data = InstrumentationRegistry.getInstrumentation().context.assets.open("speech/gnani-photosynthesis-command.pcm").use { it.readBytes() }
            val samples = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { buffer -> ShortArray(buffer.remaining()).also { buffer.get(it) } }
            val frames = samples.toList().chunked(1600).map { com.chronoit.aivora.voice.AudioFrame(it.toShortArray(), 16000) }.asFlow().onEach { delay(100) }
            var promptPresented = false
            var ready = false
            val transcript = client.listen(suppliedAudio = frames,
                beforeAudio = { promptPresented = true },
                onReady = { assertTrue(promptPresented); ready = true })
            assertTrue("Speech connection must be ready before supplied command audio", ready)
            assertNotNull("Gnani returned no fixture transcript", transcript)
            val question = com.chronoit.aivora.connectivity.WebQuestionRouting.request(transcript!!, null)?.first
            assertTrue("Expected the technical term in the recognized query", question.orEmpty().replace(" ", "").contains("photosynthesis", true))
            val answer = withContext(Dispatchers.IO) { app.webAnswers.answer(question!!, false) }
            assertFalse(answer.isNullOrBlank())
            com.chronoit.aivora.voice.VoiceRuntime.speak { client.speak(answer!!, com.chronoit.aivora.voice.SpeechOptions()) }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("core_audio_fixture_transcript", transcript)
                putString("core_audio_fixture_summary", answer)
                putString("core_audio_pipeline", "Synthetic recorded PCM -> live Gnani STT -> Brave evidence -> local summary -> live Gnani audio playback completed")
            })
        } finally { client.close() }
    }
    @Test fun stagedSummaryModelImportsIntoPrivateStorage() = runBlocking {
        val staged = app.getExternalFilesDir(null)?.let { java.io.File(it, com.chronoit.aivora.brain.LlamaLocalReasoningEngine.MODEL) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("model_staging_path", staged?.absolutePath)
            putBoolean("model_staging_exists", staged?.exists() == true)
            putBoolean("model_staging_readable", staged?.canRead() == true)
            putLong("model_staging_bytes", staged?.length() ?: 0)
        })
        val file = withContext(Dispatchers.IO) { com.chronoit.aivora.brain.LlamaLocalReasoningEngine(app).prepareModel() }
        assertEquals(com.chronoit.aivora.brain.LlamaLocalReasoningEngine.MODEL_BYTES, file.length())
    }
    @Test fun encryptedMemoryResolvesContextualThreeDayReminder() = runBlocking {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(app, com.chronoit.aivora.data.AivoraDatabase::class.java).build()
        try {
            val memory = com.chronoit.aivora.memory.AivoraMemory(database, app.settings,
                com.chronoit.aivora.security.FieldCipher(com.chronoit.aivora.security.KeystoreKeys::dataKey))
            val privateMode = app.settings.settings.first().privateMode
            val content = "I am participating in the AIVORA acceptance competition"
            val saved = memory.remember(content)
            assertEquals(!privateMode, saved)
            if (!privateMode) {
                assertEquals(content, memory.latestRememberedText())
                val reminder = CommandParser().parse("remind me after three days for this") as ParsedCommand.Reminder
                assertNull(reminder.text)
                assertEquals(259200000L, reminder.delayMillis)
                assertEquals(content, reminder.text ?: memory.latestRememberedText())
            } else assertNull(memory.latestRememberedText())
        } finally { database.close() }
    }
    @Test fun configuredBraveReturnsPhotosynthesisAnswer() = runBlocking {
        assertTrue("Save the Brave key in the installed app first", app.webAnswers.configured())
        val answer = try { withContext(Dispatchers.IO) { app.webAnswers.answer("What is photosynthesis?", false) } }
        catch (error: Exception) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("core_web_fixture_error", app.webAnswers.lastFailureDetail.orEmpty()) })
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("core_summary_diagnostic", app.webAnswers.lastSummaryDiagnostic)
                putString("core_summary_evidence", com.chronoit.aivora.connectivity.WebEvidenceRanking.compact(app.webAnswers.lastEvidence))
                putLong("core_cached_model_bytes", java.io.File(app.filesDir, "aivora_reasoning/qwen3-0.6b-q4_0.gguf").length())
            })
            throw error
        }
        assertFalse("Brave returned no answer", answer.isNullOrBlank())
        assertTrue("Expected an answer about plants or photosynthesis", answer!!.contains("photosynthesis", true) || answer.contains("plant", true))
        assertFalse("Expected a summary rather than a result readout", answer.startsWith("Here is a web search result"))
        assertTrue("Expected an explanation involving light or energy", answer.contains("light", true) || answer.contains("energy", true))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("core_web", "Live Brave answer received; ${answer.length} characters") })
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("core_summary_fixture", answer); putString("core_summary_sources", app.webAnswers.lastEvidence.joinToString { it.url }) })
    }
    @Test fun naturalReminderReachesActualWorkManagerNotification() = runBlocking {
        assertTrue("AIVORA reminder notifications must be allowed", app.scheduler.notificationsAllowed())
        val turn = StarterVoiceDialogue().next("remind me about the AIVORA acceptance check in five seconds") as StarterVoiceDialogue.Turn.Command
        val command = CommandParser().parse(turn.text) as ParsedCommand.Reminder
        val reminder = app.scheduler.create(command.text!!, command.delayMillis)
        try {
            val manager = app.getSystemService(NotificationManager::class.java)
            val posted = withTimeoutOrNull(60000) {
                while (true) {
                    val found = manager.activeNotifications.firstOrNull { it.tag == reminder.id }
                    if (found != null) return@withTimeoutOrNull found
                    delay(200)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            assertNotNull("WorkManager did not deliver the reminder within the acceptance window", posted)
            assertEquals(command.text, posted!!.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("core_reminder", "Actual scheduled reminder notification observed") })
        } finally { app.scheduler.cancel(reminder.id) }
    }
    @Test fun reminderIsSpokenAfterItsNotification() = runBlocking {
        assertTrue(app.scheduler.notificationsAllowed())
        val reminder = app.scheduler.create("AIVORA spoken reminder test", 1500)
        try {
            val spoken = withTimeoutOrNull(120000) {
                while (app.scheduler.voiceState(reminder.id) != "SPOKEN") delay(200)
                true
            } ?: false
            assertTrue("Reminder speech did not complete", spoken)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("core_spoken_reminder", "Persisted speech job completed actual audio playback") })
        } finally { app.scheduler.cancel(reminder.id) }
    }
}
