package com.chronoit.aivora.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceRuntimeTest {
    @Test fun nestedOwnershipAndDoubleCloseDoNotResumeWakeTooEarly() {
        val conversation = VoiceRuntime.hold(conversation = true)
        val capture = VoiceRuntime.hold()
        conversation.close(); conversation.close()
        assertTrue(VoiceRuntime.busy.value)
        assertFalse(VoiceRuntime.inConversation.value)
        capture.close()
        assertFalse(VoiceRuntime.busy.value)
    }
    @Test fun reminderSpeechWaitsForConversationAndReleasesOnFailure() = runBlocking {
        val conversation = VoiceRuntime.hold(conversation = true)
        val started = CompletableDeferred<Unit>()
        val worker = launch { VoiceRuntime.speakWhenIdle { started.complete(Unit) } }
        yield()
        assertFalse(started.isCompleted)
        conversation.close()
        withTimeout(1000) { worker.join() }
        assertTrue(started.isCompleted)
        try { VoiceRuntime.speak { error("fixture") } } catch (_: IllegalStateException) { }
        assertFalse(VoiceRuntime.busy.value)
    }
}
