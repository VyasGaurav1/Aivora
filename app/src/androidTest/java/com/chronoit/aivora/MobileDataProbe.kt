package com.chronoit.aivora

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.automation.*
import com.chronoit.aivora.connectivity.*
import com.chronoit.aivora.data.InternetBehavior
import com.chronoit.aivora.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Adapter/state-routing evidence only. Credential success below is injected, never a real auth claim. */
@RunWith(AndroidJUnit4::class)
class MobileDataProbe {
    @Test fun actualSingleSimDataControlsAndAuthenticatedTaskRouting(): Unit = runBlocking {
        require(InstrumentationRegistry.getArguments().getString("mobileDataProbe") == "true")
        require(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = automation.executeShellCommand(command).use { descriptor -> ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() } }
        val original = app.settings.settings.first()
        val originalWifi = app.getSystemService(WifiManager::class.java).isWifiEnabled
        val originalData = Settings.Global.getString(app.contentResolver, "mobile_data")
        require(originalData in setOf("0", "1"))
        val originalServices = Settings.Secure.getString(app.contentResolver, "enabled_accessibility_services")
        val originalAccess = Settings.Secure.getInt(app.contentResolver, "accessibility_enabled", 0)
        require(originalServices.orEmpty().matches(Regex("[A-Za-z0-9_./:]*")))
        val component = ComponentName(app, AivoraAccessibilityService::class.java)
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.grantRuntimePermission(app.packageName, Manifest.permission.READ_PHONE_STATE)
        val monitor = AndroidMobileDataMonitor(app)
        lateinit var model: AssistantViewModel
        var initialized = false
        try {
            app.settings.setMobileDataControls(true)
            app.settings.setWifiControls(false)
            app.settings.setInternetBehavior(InternetBehavior.ENABLE_WHEN_REQUIRED)
            app.settings.setHandsFreeControls(false)
            app.settings.setSpokenReplies(false)
            val others = originalServices.orEmpty().split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
            shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services ${others.joinToString(":")}")
            shell("settings put secure enabled_accessibility_services ${(others + component.flattenToString()).joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            withTimeout(10_000) { while (!AivoraAccessibilityService.connected()) delay(50) }
            shell("svc data enable")
            withTimeout(10_000) { while (monitor.snapshot()?.enabled != true) delay(50) }
            val target = monitor.snapshot()!!.target
            val adapter = MobileDataAutomationAdapter(app)
            val changed = adapter.execute(Action.SetMobileData(false, target.copy(label = "changed fixture SIM")))
            assertEquals(ExecutionStatus.NOT_SUPPORTED, changed.status)
            assertTrue(monitor.snapshot()!!.enabled)
            val off = adapter.execute(Action.SetMobileData(false, target))
            assertEquals(off.message, ExecutionStatus.SUCCESS, off.status)
            assertFalse(monitor.snapshot()!!.enabled)
            android.util.Log.i("AIVORAMobileProbe", if (Build.VERSION.SDK_INT == 30) "Adapter verified legacy-panel mobile-data setting off for the same SIM; this panel has no off warning." else "Adapter verified mobile-data setting off for same SIM, including known System UI warning.")
            shell("svc wifi disable")
            withTimeout(10_000) { while (AndroidNetworkMonitor(app).snapshot().validated) delay(50) }
            withTimeout(10_000) { while (!app.visibility.foreground.value) delay(50) }
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AssistantViewModel::class.java] }
            initialized = true
            withTimeout(10_000) { model.settings.first { it.mobileDataControls && !it.wifiControls && it.internetBehavior == InternetBehavior.ENABLE_WHEN_REQUIRED } }
            model.submit("find aivora mobile fixture on youtube")
            val auth = withTimeout(10_000) { model.ui.first { it.authentication != null } }.authentication!!
            assertEquals(Action.SetMobileData(true, target), auth.plan.actions.single())
            assertFalse(monitor.snapshot()!!.enabled)
            android.util.Log.i("AIVORAMobileProbe", "Production task route requested device authentication before any data-setting effect.")
            // Simulate the trusted system callback to test routing. No physical PIN/biometric is supplied or claimed.
            model.authenticated(auth.token)
            val confirmation = withTimeout(10_000) { model.ui.first { it.confirmation != null } }.confirmation!!
            assertNotEquals(auth.token, confirmation.token)
            assertFalse(monitor.snapshot()!!.enabled)
            model.confirm()
            val clientInstalled = app.packageManager.getLaunchIntentForPackage("com.google.android.youtube") != null
            val expectedHandoff = if (clientInstalled) "Requested YouTube search" else "YouTube is not installed."
            withTimeout(20_000) { model.ui.first { state -> state.conversation.any { it.text.startsWith(expectedHandoff) } } }
            assertEquals(target, monitor.snapshot()!!.target)
            assertTrue(monitor.snapshot()!!.enabled)
            val connectivity = app.getSystemService(ConnectivityManager::class.java)
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)!!
            assertTrue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            assertTrue(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            android.util.Log.i("AIVORAMobileProbe", "After injected authentication and exact confirmation, same-SIM setting enabled; validated cellular network resumed original YouTube ${if (clientInstalled) "app" else "browser"} handoff.")
        } finally {
            if (initialized) model.cancel()
            shell("am force-stop com.google.android.youtube")
            shell(if (originalWifi) "svc wifi enable" else "svc wifi disable")
            shell(if (originalData == "1") "svc data enable" else "svc data disable")
            shell(if (originalServices.isNullOrBlank()) "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $originalServices")
            shell("settings put secure accessibility_enabled $originalAccess")
            app.settings.setMobileDataControls(original.mobileDataControls)
            app.settings.setWifiControls(original.wifiControls)
            app.settings.setInternetBehavior(original.internetBehavior)
            app.settings.setHandsFreeControls(original.handsFreeControls)
            app.settings.setSpokenReplies(original.spokenReplies)
            shell("input keyevent BACK"); shell("am force-stop com.android.settings")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
