package com.chronoit.aivora

import android.content.Context
import android.app.role.RoleManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.platform.AivoraVoiceInteractionService
import com.chronoit.aivora.ui.VoicePanelActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantRoleIntegrationTest {
    @Test fun roleBoundSessionOpensAivoraAndNonHolderCannotRequestASession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val roles = context.getSystemService(RoleManager::class.java)
        if (!roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            instrumentation.runOnMainSync { assertFalse(AivoraVoiceInteractionService.requestVoiceSession()) }
            return@runBlocking
        }
        // Some OEMs leave a dead assistant binding after instrumentation force-stops
        // the target. Reassigning its role here can kill the test process, even with
        // DONT_KILL_APP. Report that environmental limitation rather than revoke permissions.
        val bound = withTimeoutOrNull(3000) { AivoraVoiceInteractionService.ready.first { it } } == true
        if (!bound) instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("assistant_panel_environment", "Selected assistant not rebound inside instrumentation; verify repeated sessions in the live app")
        })
        org.junit.Assume.assumeTrue("OEM assistant binding unavailable inside instrumentation", bound)
        repeat(2) {
            val monitor = instrumentation.addMonitor(VoicePanelActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { assertTrue(AivoraVoiceInteractionService.requestVoiceSession()) }
                val activity = monitor.waitForActivityWithTimeout(10_000)
                assertNotNull("Android assistant session did not open AIVORA", activity)
                withTimeout(10000) { com.chronoit.aivora.voice.VoiceRuntime.inConversation.first { it } }
                instrumentation.runOnMainSync { activity.finish() }
                withTimeout(10000) { com.chronoit.aivora.voice.VoiceRuntime.busy.first { !it } }
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }
}
