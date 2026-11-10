package com.chronoit.aivora

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.automation.AivoraAccessibilityService
import com.chronoit.aivora.connectivity.AndroidNetworkMonitor
import com.chronoit.aivora.data.InternetBehavior
import com.chronoit.aivora.scheduler.TaskDatabase
import com.chronoit.aivora.ui.AssistantViewModel
import com.chronoit.aivora.ui.MainActivity
import com.chronoit.aivora.voice.VerifiedVoiceCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit isolated emulator probe. Injected input does not establish acoustic recognition. */
@RunWith(AndroidJUnit4::class)
class ScheduledConnectionProbe {
    @Test fun savedTaskSurvivesConnectionSetupButCancellationPreventsReview() = runBlocking {
        require(InstrumentationRegistry.getArguments().getString("scheduledConnectionProbe") == "true")
        require(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.VERSION.SDK_INT in setOf(30, 35))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        val original = app.settings.settings.first()
        val services = Settings.Secure.getString(app.contentResolver, "enabled_accessibility_services")
        val access = Settings.Secure.getInt(app.contentResolver, "accessibility_enabled", 0)
        require(services.orEmpty().matches(Regex("[A-Za-z0-9_./:]*")))
        val wifi = app.getSystemService(WifiManager::class.java)
        val originalWifi = wifi.isWifiEnabled
        val data = Settings.Global.getString(app.contentResolver, "mobile_data")
        require(data == "1")
        val database = TaskDatabase.open(app)
        val ids = mutableListOf<String>()
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var model: AssistantViewModel
        var initialized = false
        var stage = "setup"
        try {
            val component = ComponentName(app, AivoraAccessibilityService::class.java)
            val others = services.orEmpty().split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
            shell("settings put secure enabled_accessibility_services ${(others + component.flattenToString()).joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            withTimeout(10000) { while (!AivoraAccessibilityService.connected()) delay(50) }
            app.settings.setWifiControls(true)
            app.settings.setInternetBehavior(InternetBehavior.ASK)
            app.settings.setHandsFreeControls(true)
            app.settings.setSpokenReplies(false)
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AssistantViewModel::class.java] }
            initialized = true
            withTimeout(10000) { model.settings.first { it.wifiControls && it.handsFreeControls && !it.spokenReplies && it.internetBehavior == InternetBehavior.ASK } }
            fun voice(text: String) {
                val clock = { System.nanoTime() / 1_000_000 }
                model.acceptVoiceCommand(VerifiedVoiceCommand(text, clock(), clock))
            }
            for (cancel in listOf(true, false)) {
                stage = "offline cancellation=$cancel"
                shell("svc wifi disable"); shell("svc data disable")
                withTimeout(10000) { while (wifi.isWifiEnabled || AndroidNetworkMonitor(app).snapshot().validated) delay(50) }
                shell("am start -n com.chronoit.aivora/.ui.MainActivity")
                withTimeout(10000) { while (!app.visibility.foreground.value) delay(50) }
                val action = Action.YouTubeSearch("aivora scheduled connection fixture")
                val saved = app.scheduler.scheduleAction(action, "Scheduled connection fixture", 3600000)
                ids += saved.id
                database.tasks().update(saved.id, "PENDING", System.currentTimeMillis() - 1000, 0)
                assertTrue(runCatching { app.scheduler.reviewAction(saved.id) }.isFailure)
                voice("review scheduled action ${saved.id}")
                withTimeout(10000) { model.ui.first { it.networkPromptToken != null } }
                assertEquals("AWAITING_REVIEW", database.tasks().get(saved.id)!!.state)
                assertNull(model.ui.value.confirmation)
                assertTrue(model.onlineTaskDescription()!!.contains(action.query))
                if (cancel) app.scheduler.cancel(saved.id)
                model.prepareVoiceCapture(); voice("connect for this task")
                withTimeout(10000) { model.ui.first { it.confirmation?.plan?.actions == listOf(Action.SetWifi(true)) } }
                model.prepareVoiceCapture(); voice("confirm the action I just reviewed")
                stage = "Wi-Fi helper cancellation=$cancel"
                withTimeout(15000) { while (!wifi.isWifiEnabled) delay(50) }
                // AVD Wi-Fi may have no internet. Harness restoration is distinct from production switching.
                if (!AndroidNetworkMonitor(app).snapshot().validated) shell("svc data enable")
                withTimeout(20000) { while (!AndroidNetworkMonitor(app).snapshot().validated) delay(50) }
                shell("am start -n com.chronoit.aivora/.ui.MainActivity")
                if (cancel) {
                    stage = "cancellation guard"
                    withTimeout(15000) { model.ui.first { state -> state.conversation.any { it.text.startsWith("The saved task or its conditions changed") } } }
                    assertNull(model.ui.value.confirmation)
                    assertEquals("CANCELED", database.tasks().get(saved.id)!!.state)
                    assertFalse(model.ui.value.conversation.any { it.text.startsWith("Requested YouTube search") })
                } else {
                    stage = "scheduled confirmation"
                    withTimeout(15000) { model.ui.first { it.confirmation?.plan?.actions == listOf(action) } }
                    assertEquals("AWAITING_REVIEW", database.tasks().get(saved.id)!!.state)
                    assertFalse(model.ui.value.conversation.any { it.text.startsWith("Requested YouTube search") })
                    model.prepareVoiceCapture(); voice("confirm the action I just reviewed")
                    stage = "confirmed handoff"
                    withTimeout(15000) { model.ui.first { state -> state.conversation.any { it.text.startsWith("Requested YouTube search") } } }
                    assertEquals("HANDLED", database.tasks().get(saved.id)!!.state)
                    withTimeout(5000) { while (shell("dumpsys activity activities").lineSequence().none { ("mResumedActivity" in it || "topResumedActivity" in it) && "com.google.android.youtube/" in it }) delay(50) }
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Scheduled probe stage=$stage state=${if (initialized) model.ui.value.state else null} last=${if (initialized) model.ui.value.conversation.lastOrNull()?.text else null}", failure)
        } finally {
            if (initialized) model.cancel()
            ids.forEach { app.scheduler.cancel(it) }
            database.close()
            shell("am force-stop com.google.android.youtube")
            shell(if (originalWifi) "svc wifi enable" else "svc wifi disable")
            shell("svc data enable")
            shell(if (services.isNullOrBlank()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $services")
            shell("settings put secure accessibility_enabled $access")
            app.settings.setWifiControls(original.wifiControls)
            app.settings.setInternetBehavior(original.internetBehavior)
            app.settings.setHandsFreeControls(original.handsFreeControls)
            app.settings.setSpokenReplies(original.spokenReplies)
            shell("am force-stop com.android.settings")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
