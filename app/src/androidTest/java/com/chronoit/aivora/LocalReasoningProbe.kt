package com.chronoit.aivora

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.brain.*
import com.chronoit.aivora.ui.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit resource-heavy native inference, excluded from ordinary suites. No mocked model output. */
@RunWith(AndroidJUnit4::class)
class LocalReasoningProbe {
    @Test fun bundledNativeModelAnswersAndCancellationReleasesOwnership(): Unit = runBlocking {
        require(InstrumentationRegistry.getArguments().getString("reasoningProbe") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(MainActivity::class.java).use {
            val engine = LlamaLocalReasoningEngine(context)
            val first = withTimeout(90000) { engine.answer("What is two plus two? Answer with the number.") }
            assertTrue(first.toString(), first is ReasoningResult.Answer)
            assertTrue((first as ReasoningResult.Answer).text, Regex("\\b(?:4|four)\\b", RegexOption.IGNORE_CASE).containsMatchIn(first.text))
            assertEquals(0, engine.nativePhase())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("native_reasoning_fixture_reply", first.text) })
            val job = launch(Dispatchers.Default) { engine.answer("Explain how clouds form in detail.") }
            withTimeout(90000) { while (engine.nativePhase() < 2 && job.isActive) delay(10) }
            assertTrue("Cancellation must occur during native decoding", job.isActive && engine.nativePhase() >= 2)
            withTimeout(10000) { job.cancelAndJoin() }
            assertEquals(0, engine.nativePhase())
            val after = withTimeout(90000) { engine.answer("What is two plus two? Answer with the number.") }
            assertTrue(after.toString(), after is ReasoningResult.Answer)
            assertTrue(Regex("\\b(?:4|four)\\b", RegexOption.IGNORE_CASE).containsMatchIn((after as ReasoningResult.Answer).text))
            assertEquals(0, engine.nativePhase())
            val science = withTimeout(90000) { engine.answer("Why do we see lightning before hearing thunder? Answer in one sentence.") }
            assertTrue(science.toString(), science is ReasoningResult.Answer)
            val explanation = (science as ReasoningResult.Answer).text
            assertTrue(explanation, explanation.contains("light", true) && explanation.contains("sound", true) && explanation.contains("faster", true))
            assertEquals(0, engine.nativePhase())
        }
    }
}
