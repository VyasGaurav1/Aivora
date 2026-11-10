package com.chronoit.aivora.automation

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import com.chronoit.aivora.AivoraApplication
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.connectivity.*
import com.chronoit.aivora.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MobileDataAutomationAdapter(private val context: Context) {
    suspend fun execute(action: Action.SetMobileData): ActionResult {
        val app = context.applicationContext as AivoraApplication
        if (!app.settings.settings.first().mobileDataControls) return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Enable supported mobile-data controls in AIVORA internet settings first.")
        val monitor = AndroidMobileDataMonitor(context)
        val before = monitor.snapshot()
        if (!MobileDataGuard.matches(action.target, before, action.enabled)) return ActionResult(ExecutionStatus.NOT_SUPPORTED, "The data SIM changed, is ambiguous/unavailable, or is roaming. Resolve it in Android settings before repeating the request.")
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Unlock the device before changing mobile data.")
        if (before!!.enabled == action.enabled) return ActionResult(ExecutionStatus.SUCCESS, "Android reports this SIM's mobile-data user setting already ${if (action.enabled) "enabled" else "disabled"}.")
        if (!AivoraAccessibilityService.connected()) return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Enable AIVORA app controls in Android accessibility settings first.")
        val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).setPackage(WifiControls.SETTINGS_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = context.packageManager.resolveActivity(intent, 0)?.activityInfo
        if (target == null || target.packageName != WifiControls.SETTINGS_PACKAGE || target.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0)
            return ActionResult(ExecutionStatus.NOT_SUPPORTED, "The supported Android Internet panel is unavailable.")
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.Main) { context.startActivity(intent) }
        val ready = withTimeoutOrNull(4000) {
            while (!AivoraAccessibilityService.hasMobileControl(action, before.enabled)) {
                if (keyguard.isKeyguardLocked || !MobileDataGuard.matches(action.target, monitor.snapshot(), action.enabled)) return@withTimeoutOrNull false
                delay(50)
            }
            true
        } == true
        if (!ready || !AivoraAccessibilityService.requestMobile(action)) return ActionResult(ExecutionStatus.NOT_SUPPORTED, "The expected data SIM/control is unavailable or changed. No verified mobile-data change was completed.")
        if (!action.enabled) {
            withTimeoutOrNull(2000) {
                while (monitor.snapshot()?.enabled == true) {
                    if (AivoraAccessibilityService.hasMobileOffWarning(action)) { AivoraAccessibilityService.confirmMobileOff(action); break }
                    delay(50)
                }
            }
        }
        val applied = withTimeoutOrNull(5000) {
            while (true) {
                val state = monitor.snapshot()
                if (!MobileDataGuard.matches(action.target, state, action.enabled) || keyguard.isKeyguardLocked) return@withTimeoutOrNull false
                if (state!!.enabled == action.enabled) return@withTimeoutOrNull true
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true
        if (!applied) return ActionResult(ExecutionStatus.PARTIAL, "A mobile-data control was requested, but its user setting could not be verified for the same SIM.")
        val dismissed = withTimeoutOrNull(2500) {
            while (!AivoraAccessibilityService.hasMobileControl(action, action.enabled)) { if (keyguard.isKeyguardLocked) return@withTimeoutOrNull false; delay(50) }
            AivoraAccessibilityService.dismissMobilePanel(action)
        } == true
        if (dismissed && !keyguard.isKeyguardLocked) withContext(Dispatchers.Main) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        val final = monitor.snapshot()
        if (!MobileDataGuard.matches(action.target, final, action.enabled) || final?.enabled != action.enabled)
            return ActionResult(ExecutionStatus.PARTIAL, "The data SIM or setting changed again before completion. Check Android settings.")
        return ActionResult(ExecutionStatus.SUCCESS, "Android reports mobile-data user setting ${if (action.enabled) "enabled" else "disabled"} for ${action.target.label}, SIM slot ${action.target.slotIndex + 1}. Internet availability is checked separately.")
    }
}
