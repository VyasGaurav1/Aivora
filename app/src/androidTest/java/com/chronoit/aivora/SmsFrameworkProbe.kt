package com.chronoit.aivora

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.telephony.TelephonyManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.actions.Action
import com.chronoit.aivora.platform.AndroidSmsMonitor
import com.chronoit.aivora.ui.AssistantViewModel
import com.chronoit.aivora.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit known-image emulator probe. The virtual modem must remain out of service for every SDK request. */
@RunWith(AndroidJUnit4::class)
class SmsFrameworkProbe {
    @Test fun actualNoServiceCallbackPersistsFailureAndStatusReadDoesNotResend(): Unit = runBlocking {
        require(InstrumentationRegistry.getArguments().getString("smsFrameworkProbe") == "true")
        require(Build.HARDWARE in setOf("ranchu", "goldfish") && "x86_64" in Build.SUPPORTED_ABIS && Build.VERSION.SDK_INT in setOf(30, 35))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        require(shell("getprop ro.kernel.qemu").trim() == "1")
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        for (permission in listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE))
            require(app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        val original = app.settings.settings.first()
        require(shell("cmd connectivity airplane-mode").trim() == "disabled")
        val store = ViewModelStore()
        var requestId: String? = null
        val beforeId = app.smsDatabase.attempts().latest()?.id
        val body = "AIVORA no service fixture ${UUID.randomUUID()}"
        ActivityScenario.launch(MainActivity::class.java).use {
            val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AssistantViewModel::class.java]
            var stage = "setup"
            try {
                require(AndroidSmsMonitor(app).selection() != null)
                withTimeout(10000) { while (app.getSystemService(TelephonyManager::class.java).serviceState?.state != android.telephony.ServiceState.STATE_OUT_OF_SERVICE) delay(50) }
                require(shell("cmd connectivity airplane-mode").trim() == "disabled")
                require(AndroidSmsMonitor(app).selection() != null)
                app.settings.setDirectSms(true); app.settings.setHandsFreeControls(false); app.settings.setSpokenReplies(false)
                withTimeout(10000) { model.settings.first { it.directSms && !it.handsFreeControls && !it.spokenReplies } }
                model.foreground(true); model.resumed(true)
                stage = "authentication review"
                model.submit("text +12025550123 that $body")
                val auth = withTimeout(10000) { model.ui.first { it.authentication != null } }.authentication!!
                assertTrue(auth.plan.actions.single() is Action.SendSms)
                assertEquals(beforeId, app.smsDatabase.attempts().latest()?.id)
                model.authenticated(auth.token) // Injected credential result, not system authentication evidence.
                stage = "exact confirmation"
                withTimeout(10000) { model.ui.first { it.confirmation != null } }
                assertEquals(beforeId, app.smsDatabase.attempts().latest()?.id)
                model.confirm()
                stage = "SDK failure callback"
                withTimeout(20000) {
                    while (true) {
                        val latest = app.smsDatabase.attempts().latest()
                        if (latest != null && latest.id != beforeId) { requestId = latest.id; if (latest.state == "FAILED_REPORTED") break }
                        delay(20)
                    }
                }
                val failed = app.smsDatabase.attempts().get(requestId!!)!!
                assertNotNull(failed.resultCode)
                assertNotEquals(android.app.Activity.RESULT_OK, failed.resultCode)
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("sms_result_code", failed.resultCode.toString()) })
                withTimeout(10000) { model.ui.first { state -> state.conversation.any { it.text.startsWith("Android reported an SMS send failure") } } }
                assertEquals("disabled", shell("cmd connectivity airplane-mode").trim())
                stage = "status question"
                model.submit("SMS status")
                withTimeout(10000) { model.ui.first { state -> state.conversation.lastOrNull()?.text?.let { it.startsWith("Last SMS requested through AIVORA") && body in it && "Android reported a send failure" in it } == true } }
                assertEquals(requestId, app.smsDatabase.attempts().latest()!!.id)
                assertEquals("FAILED_REPORTED", app.smsDatabase.attempts().get(requestId!!)!!.state)
            } catch (failure: Throwable) {
                throw AssertionError("SMS probe stage=$stage state=${model.ui.value.state} last=${model.ui.value.conversation.lastOrNull()?.text}", failure)
            } finally {
                model.cancel(); store.clear()
                // Only this isolated fixture's newly created row can be removed.
                requestId?.let { app.smsDatabase.attempts().delete(it) }
                app.settings.setDirectSms(original.directSms)
                app.settings.setHandsFreeControls(original.handsFreeControls)
                app.settings.setSpokenReplies(original.spokenReplies)
            }
        }
    }
}

