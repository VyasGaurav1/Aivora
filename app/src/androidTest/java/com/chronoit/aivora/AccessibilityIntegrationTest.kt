package com.chronoit.aivora

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.automation.*
import com.chronoit.aivora.actions.ExecutionStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityIntegrationTest {
    @Test fun wifiControlRequiresItsSeparateOptInBeforeAnySettingsHandoff() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val original = app.settings.settings.first()
        val wifi = app.getSystemService(android.net.wifi.WifiManager::class.java)
        val before = wifi.isWifiEnabled
        try {
            app.settings.setWifiControls(false)
            val result = WifiAutomationAdapter(app).execute(!before)
            assertEquals(ExecutionStatus.PERMISSION_MISSING, result.status)
            assertEquals(before, wifi.isWifiEnabled)
        } finally { app.settings.setWifiControls(original.wifiControls) }
    }
    @Test fun systemBindingIsProtectedAndDeclaredScopeHasNoGestureCapability() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, AivoraAccessibilityService::class.java)
        val service = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission)
        val installed = context.getSystemService(AccessibilityManager::class.java).installedAccessibilityServiceList
            .single { ComponentName.unflattenFromString(it.id) == component }
        assertArrayEquals(arrayOf(YouTubeControls.PACKAGE, WifiControls.PACKAGE), installed.packageNames)
        assertTrue(installed.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0)
        assertEquals(0, installed.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)
        assertTrue(installed.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0)
    }
    @Test fun missingServiceOrAnUnrelatedForegroundAppCannotBeClicked() = runBlocking {
        ActivityScenario.launch(com.chronoit.aivora.ui.MainActivity::class.java).use {
            if (InstrumentationRegistry.getArguments().getString("verifyBoundService") == "true") {
                val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                val context = ApplicationProvider.getApplicationContext<Context>()
                val component = ComponentName(context, AivoraAccessibilityService::class.java)
                val enabled = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty()
                require(enabled.matches(Regex("[A-Za-z0-9_./:]*")))
                val others = enabled.split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
                fun shell(command: String) { automation.executeShellCommand(command).use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> input.readBytes() }
                } }
                // Instrumentation force-stops the app. Rebind the service after its new process starts.
                shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services ${others.joinToString(":")}")
                shell("settings put secure enabled_accessibility_services ${(others + component.flattenToString()).joinToString(":")}")
                shell("settings put secure accessibility_enabled 1")
                withTimeout(10_000) { while (!AivoraAccessibilityService.connected()) kotlinx.coroutines.delay(50) }
            }
            assertFalse(AivoraAccessibilityService.hasYouTubeWindow())
            val connected = AivoraAccessibilityService.connected()
            val result = AivoraAccessibilityService.youtube(PlaybackControl.PAUSE)
            assertEquals(if (connected) ExecutionStatus.NOT_SUPPORTED else ExecutionStatus.PERMISSION_MISSING, result.status)
        }
    }
}
