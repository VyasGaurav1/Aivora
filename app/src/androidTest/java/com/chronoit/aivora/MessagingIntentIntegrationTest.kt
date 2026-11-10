package com.chronoit.aivora

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.automation.WhatsAppDraftAdapter
import com.chronoit.aivora.platform.AndroidActionExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessagingIntentIntegrationTest {
    @Test fun officialLinkPreservesRecipientAndTextWithoutQueryInjectionOrBrowserFallback() {
        val body = "Meet at 5? &phone=999\nनमस्ते + hello"
        for (app in MessagingApp.entries) {
            val intent = WhatsAppDraftAdapter.intent(Action.DraftAppMessage(app, "+91 (98765) 43210", body))
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(app.packageName, intent.`package`)
            val uri = intent.data!!
            assertEquals("https", uri.scheme)
            assertEquals("wa.me", uri.host)
            assertEquals(listOf("919876543210"), uri.pathSegments)
            assertEquals(setOf("text"), uri.queryParameterNames)
            assertEquals(body, uri.getQueryParameter("text"))
            assertNull(intent.component)
        }
        assertTrue(runCatching { WhatsAppDraftAdapter.intent(Action.DraftAppMessage(MessagingApp.WHATSAPP, "5550199", "Hello")) }.isFailure)
    }
    @Test fun missingNamedClientRefusesWithoutSendingOrFallingBack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val missing = MessagingApp.entries.filter { context.packageManager.getLaunchIntentForPackage(it.packageName) == null }
        assertTrue("This fixture requires at least one unavailable named messaging client", missing.isNotEmpty())
        for (app in missing) {
            val result = AndroidActionExecutor(context).execute(Action.DraftAppMessage(app, "+15550199001", "AIVORA fixture, never sent"))
            assertEquals(ExecutionStatus.NOT_SUPPORTED, result.status)
            assertTrue(result.message.contains("No message"))
        }
    }
}
