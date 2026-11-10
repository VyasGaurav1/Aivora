package com.chronoit.aivora

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.chronoit.aivora.brain.*
import com.chronoit.aivora.memory.*
import com.chronoit.aivora.voice.*
import com.chronoit.aivora.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FivePrioritiesIntegrationTest {
    private val app = ApplicationProvider.getApplicationContext<AivoraApplication>()
    @Test fun viewModelRoutesCurrentReplyAndMemoryWithoutAnyWebRequest() = runBlocking {
        val original = app.settings.settings.first()
        val store = androidx.lifecycle.ViewModelStore()
        try {
            app.settings.setPrivateMode(true)
            app.settings.setSpokenReplies(false)
            val model = withContext(Dispatchers.Main) {
                androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))[com.chronoit.aivora.ui.AssistantViewModel::class.java]
            }
            val before = app.webAnswers.requestsMade.get()
            suspend fun command(text: String): String {
                val count = model.ui.value.conversation.size
                withContext(Dispatchers.Main) { model.submit(text) }
                return withTimeout(15000) { model.ui.first { it.conversation.size > count && !it.conversation.last().owner }.conversation.last().text }
            }
            val answer = command("what can you do")
            assertTrue(answer.contains("Clock"))
            assertEquals(answer, command("repeat that"))
            assertTrue(command("make it shorter").length < answer.length)
            assertTrue(command("the correct answer is private fixture").contains("haven't saved"))
            command("what did I tell you about aivoraprivacyfixture")
            assertEquals("Context and memory commands leaked to web routing", before, app.webAnswers.requestsMade.get())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            app.settings.setPrivateMode(original.privateMode)
            app.settings.setSpokenReplies(original.spokenReplies)
        }
    }
    @Test fun explicitCorrectionsAreAppliedLocallyAndPrivateModeBlocksThem() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(app, com.chronoit.aivora.data.AivoraDatabase::class.java).build()
        val privateMode = app.settings.settings.first().privateMode
        try {
            app.settings.setPrivateMode(false)
            val memory = AivoraMemory(database, app.settings, FieldCipher(KeystoreKeys::dataKey))
            val history = ConversationMemory(memory, app.settings)
            history.record("When is my fixture competition?", "Thursday")
            assertTrue(history.correct("Friday"))
            assertEquals("Friday", ConversationMemory(memory, app.settings).correctedAnswer("When is my fixture competition?"))
            assertNull(history.correctedAnswer("What is photosynthesis?"))
            app.settings.setPrivateMode(true)
            assertNull(history.correctedAnswer("When is my fixture competition?"))
            assertFalse(history.correct("Saturday"))
        } finally { app.settings.setPrivateMode(privateMode); database.close() }
    }
    private fun wakeAudio(): Flow<AudioFrame> {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("speech/gnani-wake-command.pcm").use { it.readBytes() }
        val samples = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { ShortArray(it.remaining()).also(it::get) }
        return samples.toList().chunked(1600).map { AudioFrame(it.toShortArray(), 16000) }.asFlow().onEach { delay(100) }.onCompletion { repeat(15) { emit(AudioFrame(ShortArray(1600), 16000)); delay(100) } }
    }
    @Test fun offlineWakeInterruptsActualSpeechPlaybackAndReleasesTheMicrophone() = runBlocking {
        val phone = withContext(Dispatchers.Main) { IndianEnglishSpeechEngine(app) }
        var interrupted = false
        try {
            val completed = withTimeout(30000) {
                SpeechInterruption(app).play(stop = phone::stop, onWake = { interrupted = true }, audio = wakeAudio()) {
                    phone.speak("This is a long spoken answer for the interruption check. ".repeat(12))
                }
            }
            assertTrue("Recorded wake must reach the offline interruption decoder", interrupted)
            assertFalse("Interrupted playback must not be reported as completed", completed)
            assertTrue(MicrophoneLease.isAvailable())
        } finally { phone.close() }
    }
    @Test fun microphoneAndSpeechCanRunTogetherWithoutFalseWakeOnNeutralReply() = runBlocking {
        val phone = withContext(Dispatchers.Main) { IndianEnglishSpeechEngine(app) }
        try {
            val completed = SpeechInterruption(app).play(stop = phone::stop, onWake = {}) {
                phone.speak("Plants use light and water to make food. This is a local speech and microphone check.")
            }
            assertTrue("Neutral playback should not interrupt itself", completed)
            assertTrue(MicrophoneLease.isAvailable())
        } finally { phone.close() }
    }
    @Test fun echoCancellationPreventsOwnWakeNameFromInterruptingReply() = runBlocking {
        val phone = withContext(Dispatchers.Main) { IndianEnglishSpeechEngine(app) }
        try {
            val completed = SpeechInterruption(app).play(stop = phone::stop, onWake = {}) {
                phone.speak("This answer mentions hey Jarvis as an example. This is the assistant speaking, so playback should continue.")
            }
            assertTrue("The assistant must not interrupt itself when its reply mentions the wake name", completed)
        } finally { phone.close() }
    }
    @Test fun currentReplyCanBeRephrasedLocallyWithoutSearching() = runBlocking {
        val evidence = "Green plants use sunlight, water and carbon dioxide to make sugar. Photosynthesis releases oxygen."
        assertNull(com.chronoit.aivora.connectivity.WebQuestionRouting.request("make it simpler", "photosynthesis"))
        val result = app.reasoning.summarize("Explain photosynthesis simply using this previous reply", evidence)
        assertTrue("Local contextual summary unavailable: $result", result is ReasoningResult.Answer)
        assertTrue(GroundedSummary.supported((result as ReasoningResult.Answer).text, evidence))
    }
    @Test fun threeNewWebTopicsUseEvidenceAndValidatedShortAnswers() = runBlocking {
        for ((question, expected) in listOf("What is photosynthesis?" to "plant|light", "What is gravity?" to "force|attract|mass|pull", "Why does rain fall?" to "water|cloud|droplet")) {
            val answer = withContext(Dispatchers.IO) { app.webAnswers.answer(question, false) }
            assertFalse("No reliable answer for $question", answer.isNullOrBlank())
            assertTrue("Answer missed central topic for $question: $answer", Regex(expected, RegexOption.IGNORE_CASE).containsMatchIn(answer!!))
            assertTrue(answer.length < 900)
            assertTrue(app.webAnswers.lastEvidence.isNotEmpty())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("public_web_fixture", "$question -> $answer"); putString("public_evidence_fixture", app.webAnswers.lastEvidence.joinToString("\n") { it.title + ": " + it.text.take(1200) }) })
            if (question == "Why does rain fall?") assertTrue("Rain explanation needs the physical cause: $answer", Regex("gravity|heavy|weight|large enough|heavier", RegexOption.IGNORE_CASE).containsMatchIn(answer))
        }
    }
}
