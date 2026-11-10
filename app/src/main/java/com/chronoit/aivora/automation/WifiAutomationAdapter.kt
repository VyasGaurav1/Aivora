package com.chronoit.aivora.automation

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.wifi.WifiManager
import android.provider.Settings
import com.chronoit.aivora.AivoraApplication
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** User-visible system Settings interaction, independently checked against the actual radio. */
class WifiAutomationAdapter(private val context: Context) {
    suspend fun execute(enabled: Boolean): ActionResult {
        val app = context.applicationContext as AivoraApplication
        if (!app.settings.settings.first().wifiControls) return ActionResult(ExecutionStatus.PERMISSION_MISSING,
            "Enable supported Wi-Fi settings controls in AIVORA's internet settings first.")
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Unlock the device before changing Wi-Fi.")
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val desired = if (enabled) WifiManager.WIFI_STATE_ENABLED else WifiManager.WIFI_STATE_DISABLED
        val before = wifi.wifiState
        if (before == desired) return ActionResult(ExecutionStatus.SUCCESS, "Android reports Wi-Fi already ${if (enabled) "enabled" else "disabled"}.")
        if (before != WifiManager.WIFI_STATE_ENABLED && before != WifiManager.WIFI_STATE_DISABLED)
            return ActionResult(ExecutionStatus.PARTIAL, "Wi-Fi is changing or its state is unavailable. Wait, then repeat the request.")
        if (!AivoraAccessibilityService.connected()) return ActionResult(ExecutionStatus.PERMISSION_MISSING,
            "Enable AIVORA app controls in Android accessibility settings for supported Wi-Fi controls.")
        val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).setPackage(WifiControls.SETTINGS_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = context.packageManager.resolveActivity(intent, 0)?.activityInfo
        if (target == null || target.packageName != WifiControls.SETTINGS_PACKAGE ||
            target.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0)
            return ActionResult(ExecutionStatus.NOT_SUPPORTED, "The supported Android Wi-Fi settings app is unavailable.")
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.Main) { context.startActivity(intent) }
        val ready = withTimeoutOrNull(4000) {
            while (!AivoraAccessibilityService.hasWifiControl(!enabled)) {
                if (keyguard.isKeyguardLocked || wifi.wifiState != before) return@withTimeoutOrNull false
                delay(50)
            }
            true
        } ?: false
        if (!ready) return ActionResult(ExecutionStatus.NOT_SUPPORTED, "Android did not expose the supported Wi-Fi control. Change connectivity in its settings, then return to AIVORA.")
        if (!app.settings.settings.first().wifiControls || keyguard.isKeyguardLocked)
            return ActionResult(ExecutionStatus.CANCELLED, "Wi-Fi controls were disabled or the device locked. No switch was requested.")
        val requested = AivoraAccessibilityService.requestWifi(!enabled)
        if (requested.status != ExecutionStatus.PARTIAL) return requested
        val applied = withTimeoutOrNull(5000) {
            while (wifi.wifiState != desired) {
                if (keyguard.isKeyguardLocked || !AivoraAccessibilityService.connected()) return@withTimeoutOrNull false
                delay(50)
            }
            true
        } ?: false
        if (!applied) return ActionResult(ExecutionStatus.PARTIAL, "The Wi-Fi switch was requested, but Android's resulting radio state could not be verified.")
        val dismissed = withTimeoutOrNull(2500) {
            while (!AivoraAccessibilityService.hasWifiControl(enabled)) { if (keyguard.isKeyguardLocked) return@withTimeoutOrNull false; delay(50) }
            AivoraAccessibilityService.dismissWifiPanel(enabled)
        } == true
        if (!keyguard.isKeyguardLocked && dismissed) withContext(Dispatchers.Main) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        if (wifi.wifiState != desired) return ActionResult(ExecutionStatus.PARTIAL, "Wi-Fi changed again before completion. Check its current state before repeating the request.")
        return ActionResult(ExecutionStatus.SUCCESS, "Android reports Wi-Fi ${if (enabled) "enabled" else "disabled"}. Internet availability is checked separately.")
    }
}
