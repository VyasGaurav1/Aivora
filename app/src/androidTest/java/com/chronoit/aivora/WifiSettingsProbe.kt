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
import com.chronoit.aivora.ui.AssistantViewModel
import com.chronoit.aivora.ui.MainActivity
import com.chronoit.aivora.voice.VerifiedVoiceCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit isolated-emulator probe; excluded from the ordinary suite, not a skipped pass. */
@RunWith(AndroidJUnit4::class)
class WifiSettingsProbe {
    @Test fun actualSettingsSwitchChangesRadioAndApprovedTaskResumesAfterValidation() = runBlocking {
        require(InstrumentationRegistry.getArguments().getString("wifiProbe") == "true")
        require(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        val component = ComponentName(app, AivoraAccessibilityService::class.java)
        val originalServices = Settings.Secure.getString(app.contentResolver, "enabled_accessibility_services")
        val originalAccess = Settings.Secure.getInt(app.contentResolver, "accessibility_enabled", 0)
        require(originalServices.orEmpty().matches(Regex("[A-Za-z0-9_./:]*")))
        val original = app.settings.settings.first()
        val wifi = app.getSystemService(WifiManager::class.java)
        val originalWifi = wifi.isWifiEnabled
        val originalData = Settings.Global.getString(app.contentResolver, "mobile_data")
        require(originalData in setOf("0", "1"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        lateinit var model: AssistantViewModel
        var modelInitialized = false
        try {
            app.settings.setWifiControls(true)
            app.settings.setInternetBehavior(InternetBehavior.ASK)
            app.settings.setHandsFreeControls(true)
            app.settings.setSpokenReplies(false)
            val others = originalServices.orEmpty().split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
            shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services ${others.joinToString(":")}")
            shell("settings put secure enabled_accessibility_services ${(others + component.flattenToString()).joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            withTimeout(10_000) { while (!AivoraAccessibilityService.connected()) delay(50) }
            shell("svc wifi enable")
            withTimeout(10_000) { while (wifi.wifiState != WifiManager.WIFI_STATE_ENABLED) delay(50) }
            val registry = app.foregroundRegistry { app.visibility.foreground.value }
            val review = registry.dispatch(ActionPlan(listOf(Action.SetWifi(false)), 1.0, "Wi-Fi settings emulator probe")) as DispatchResult.NeedsConfirmation
            val off = registry.confirm(review.confirmation.token) as DispatchResult.Executed
            assertEquals(off.results.single().message, ExecutionStatus.SUCCESS, off.results.single().status)
            assertEquals(WifiManager.WIFI_STATE_DISABLED, wifi.wifiState)
            android.util.Log.i("AIVORAWifiProbe", "Verified panel Wi-Fi off against WifiManager.")
            shell("svc data disable")
            withTimeout(10_000) { while (AndroidNetworkMonitor(app).snapshot().validated) delay(50) }
            withTimeout(10_000) { while (!app.visibility.foreground.value) delay(50) }
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AssistantViewModel::class.java] }
            modelInitialized = true
            withTimeout(10_000) { model.settings.first { it.wifiControls && it.internetBehavior == InternetBehavior.ASK && it.handsFreeControls } }
            model.submit("find first aivora wifi fixture on youtube")
            val firstToken = withTimeout(10_000) { model.ui.first { it.networkPromptToken != null } }.networkPromptToken!!
            assertTrue(model.onlineTaskDescription()!!.contains("first aivora wifi fixture"))
            model.prepareVoiceCapture()
            model.submit("find aivora wifi fixture on youtube")
            val secondToken = withTimeout(10_000) { model.ui.first { it.networkPromptToken != null && it.networkPromptToken != firstToken } }.networkPromptToken!!
            assertTrue(model.onlineTaskDescription()!!.contains("aivora wifi fixture"))
            assertFalse(model.onlineTaskDescription()!!.contains("first aivora"))
            fun connectReply() {
                val clock = { System.nanoTime() / 1_000_000 }
                model.acceptVoiceCommand(VerifiedVoiceCommand("connect for this task", clock(), clock))
            }
            connectReply()
            withTimeout(10_000) { model.ui.first { state -> state.conversation.lastOrNull()?.text?.startsWith("No matching connection approval") == true } }
            assertEquals(secondToken, model.ui.value.networkPromptToken)
            assertNull(model.ui.value.confirmation)
            assertEquals(WifiManager.WIFI_STATE_DISABLED, wifi.wifiState)
            android.util.Log.i("AIVORAWifiProbe", "Injected stale connection reply rejected; replacement task and radio unchanged.")
            model.prepareVoiceCapture()
            connectReply()
            val confirmation = withTimeout(10_000) { model.ui.first { it.confirmation?.plan?.actions == listOf(Action.SetWifi(true)) } }.confirmation!!
            assertFalse(AndroidNetworkMonitor(app).snapshot().validated)
            assertEquals(Action.SetWifi(true), confirmation.plan.actions.single())
            model.confirm()
            withTimeout(15_000) { model.ui.first { state -> state.conversation.any { it.text.startsWith("Android reports Wi-Fi enabled") } } }
            assertEquals(WifiManager.WIFI_STATE_ENABLED, wifi.wifiState)
            android.util.Log.i("AIVORAWifiProbe", "Verified confirmed task-helper Wi-Fi on against WifiManager.")
            if (!AndroidNetworkMonitor(app).snapshot().validated) {
                assertFalse(model.ui.value.conversation.any { it.text.startsWith("Requested YouTube search") })
                android.util.Log.i("AIVORAWifiProbe", "Wi-Fi remains unvalidated; original task has not run. Restoring mobile connectivity through the test harness.")
                // The emulator's saved Wi-Fi may have no internet. Test network-resume separately via restored mobile connectivity.
                require(originalData == "1")
                shell("svc data enable")
            }
            withTimeout(20_000) { model.ui.first { state -> state.conversation.any { it.text.startsWith("Requested YouTube search") } } }
            assertEquals(WifiManager.WIFI_STATE_ENABLED, wifi.wifiState)
            assertTrue(AndroidNetworkMonitor(app).snapshot().validated)
            android.util.Log.i("AIVORAWifiProbe", "Validated connectivity observed; original YouTube search handoff reported.")
            withTimeout(5000) {
                while (shell("dumpsys activity activities").lineSequence().none {
                    ("mResumedActivity" in it || "topResumedActivity" in it) && "com.google.android.youtube/" in it
                }) delay(50)
            }
        } finally {
            if (modelInitialized) model.cancel()
            shell("am force-stop com.google.android.youtube")
            shell(if (originalWifi) "svc wifi enable" else "svc wifi disable")
            shell(if (originalData == "1") "svc data enable" else "svc data disable")
            shell(if (originalServices.isNullOrBlank()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $originalServices")
            shell("settings put secure accessibility_enabled $originalAccess")
            app.settings.setWifiControls(original.wifiControls)
            app.settings.setInternetBehavior(original.internetBehavior)
            app.settings.setHandsFreeControls(original.handsFreeControls)
            app.settings.setSpokenReplies(original.spokenReplies)
            shell("input keyevent BACK")
            shell("am force-stop com.android.settings")
            shell("am start -n com.chronoit.aivora/.ui.MainActivity")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
