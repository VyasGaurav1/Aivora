package com.chronoit.aivora

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.automation.AivoraAccessibilityService
import com.chronoit.aivora.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit isolated API30 radio probe; no transactions, voice grants or privileged production setters. */
@RunWith(AndroidJUnit4::class)
class LegacyWifiProbe {
    @Test fun nativeLegacyPanelChangesWifiAndReturnsToAivora(): Unit = runBlocking {
        require(InstrumentationRegistry.getArguments().getString("legacyWifiProbe") == "true")
        require(Build.VERSION.SDK_INT == 30 && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = automation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
        val component = ComponentName(app, AivoraAccessibilityService::class.java)
        val services = Settings.Secure.getString(app.contentResolver, "enabled_accessibility_services")
        val access = Settings.Secure.getInt(app.contentResolver, "accessibility_enabled", 0)
        require(services.orEmpty().matches(Regex("[A-Za-z0-9_./:]*")))
        val originalOptIn = app.settings.settings.first().wifiControls
        val wifi = app.getSystemService(WifiManager::class.java)
        val originalWifi = wifi.isWifiEnabled
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        try {
            app.settings.setWifiControls(true)
            val others = services.orEmpty().split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
            shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services ${others.joinToString(":")}")
            shell("settings put secure enabled_accessibility_services ${(others + component.flattenToString()).joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            withTimeout(10000) { while (!AivoraAccessibilityService.connected()) delay(50) }
            shell("svc wifi enable")
            withTimeout(10000) { while (wifi.wifiState != WifiManager.WIFI_STATE_ENABLED) delay(50) }
            val registry = app.foregroundRegistry { app.visibility.foreground.value }
            for (enabled in listOf(false, true)) {
                val before = wifi.wifiState
                val review = registry.dispatch(ActionPlan(listOf(Action.SetWifi(enabled)), 1.0, "Legacy Wi-Fi fixture")) as DispatchResult.NeedsConfirmation
                assertEquals(before, wifi.wifiState)
                val result = registry.confirm(review.confirmation.token) as DispatchResult.Executed
                assertEquals(result.results.single().message, ExecutionStatus.SUCCESS, result.results.single().status)
                assertEquals(if (enabled) WifiManager.WIFI_STATE_ENABLED else WifiManager.WIFI_STATE_DISABLED, wifi.wifiState)
                withTimeout(5000) { while (!app.visibility.foreground.value) delay(50) }
                android.util.Log.i("AIVORALegacyWifiProbe", "Verified API30 panel Wi-Fi ${if (enabled) "on" else "off"}, radio state and return to AIVORA.")
            }
        } finally {
            app.settings.setWifiControls(originalOptIn)
            shell("svc wifi ${if (originalWifi) "enable" else "disable"}")
            shell(if (services.isNullOrBlank()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $services")
            shell("settings put secure accessibility_enabled $access")
            shell("am force-stop com.android.settings")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
