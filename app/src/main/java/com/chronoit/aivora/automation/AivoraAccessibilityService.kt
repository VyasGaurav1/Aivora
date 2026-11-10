package com.chronoit.aivora.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import com.chronoit.aivora.actions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class AivoraAccessibilityService : AccessibilityService() {
    private var interruptionVersion = 0L
    override fun onServiceConnected() {
        if (android.os.Build.VERSION.SDK_INT == 30) serviceInfo = serviceInfo.apply {
            packageNames = (packageNames.orEmpty().toList() + WifiControls.SETTINGS_PACKAGE).distinct().toTypedArray()
        }
        active = this
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* No continuous capture or logging. */ }
    override fun onInterrupt() { interruptionVersion++ }
    override fun onUnbind(intent: Intent?): Boolean { interruptionVersion++; if (active === this) active = null; return super.onUnbind(intent) }
    override fun onDestroy() { interruptionVersion++; if (active === this) active = null; super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun activeRoot(): AccessibilityNodeInfo? = rootInActiveWindow
    @Suppress("DEPRECATION")
    private fun screen(expectedPackage: String = YouTubeControls.PACKAGE, mobileLabel: String? = null): SemanticScreen? {
        val root = activeRoot() ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != expectedPackage || pkg !in (setOf(YouTubeControls.PACKAGE, WifiControls.PACKAGE) + if (android.os.Build.VERSION.SDK_INT == 30) setOf(WifiControls.SETTINGS_PACKAGE) else emptySet())) { root.recycle(); return null }
        val window = root.windowId
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, List<Int>>>()
        queue.add(root to emptyList())
        val nodes = mutableListOf<SemanticNode>()
        var truncated = false
        val deadline = android.os.SystemClock.uptimeMillis() + 500
        try {
            while (queue.isNotEmpty()) {
                if (android.os.SystemClock.uptimeMillis() > deadline) { truncated = true; break }
                val (node, path) = queue.removeFirst()
                try {
                    if (nodes.size >= 500 || path.size > 24) { truncated = true; continue }
                    nodes.add(SemanticNode(path, node.packageName?.toString().orEmpty(), node.viewIdResourceName,
                        if (node.isPassword || pkg in setOf(WifiControls.PACKAGE, WifiControls.SETTINGS_PACKAGE)) null else node.contentDescription?.takeIf { it.length <= 256 }?.toString(),
                        node.isEnabled, node.isVisibleToUser, node.isClickable, node.isPassword, node.className?.toString(), node.isCheckable, node.isChecked,
                        !node.isPassword && ((pkg == WifiControls.PACKAGE && node.viewIdResourceName == "$pkg:id/wifi_toggle_title") || (pkg == WifiControls.SETTINGS_PACKAGE && node.viewIdResourceName == "android:id/title")) && node.text?.toString() in setOf("Wi-Fi", "Wi‑Fi"),
                        !node.isPassword && ((pkg == WifiControls.PACKAGE && node.viewIdResourceName == "$pkg:id/internet_dialog_title" && node.text?.toString() == "Internet") || (pkg == WifiControls.SETTINGS_PACKAGE && node.viewIdResourceName == "$pkg:id/panel_title" && node.text?.toString() == "Internet Connectivity")),
                        !node.isPassword && ((pkg == WifiControls.PACKAGE && node.viewIdResourceName == "$pkg:id/done_button") || (pkg == WifiControls.SETTINGS_PACKAGE && node.viewIdResourceName == "$pkg:id/done")) && node.text?.toString() == "Done",
                        mobileLabel = !node.isPassword && mobileLabel != null && ((pkg == WifiControls.PACKAGE && node.viewIdResourceName == "$pkg:id/mobile_title") || (pkg == WifiControls.SETTINGS_PACKAGE && node.viewIdResourceName == "android:id/summary")) && node.text?.toString() == mobileLabel,
                        mobileWarningTitle = !node.isPassword && mobileLabel != null && pkg == WifiControls.PACKAGE && node.viewIdResourceName == "android:id/alertTitle" && node.text?.toString() == "Turn off mobile data?",
                        mobileWarningMessage = !node.isPassword && mobileLabel != null && pkg == WifiControls.PACKAGE && node.viewIdResourceName == "android:id/message" && node.text?.toString() == "You won’t have access to data or the internet through $mobileLabel. Internet will only be available via Wi-Fi.",
                        mobileOffLabel = !node.isPassword && mobileLabel != null && pkg == WifiControls.PACKAGE && node.viewIdResourceName == "android:id/button1" && node.text?.toString() == "Turn off",
                        mobileDataLabel = !node.isPassword && pkg == WifiControls.SETTINGS_PACKAGE && node.viewIdResourceName == "android:id/title" && node.text?.toString() == "Mobile data"))
                    if (!node.isPassword) for (index in 0 until node.childCount) {
                        if (queue.size + nodes.size >= 500 || path.size >= 24) { truncated = true; break }
                        node.getChild(index)?.let { queue.add(it to path + index) }
                    }
                } finally { node.recycle() }
            }
        } finally { queue.forEach { it.first.recycle() } }
        return SemanticScreen(pkg, window, nodes, truncated)
    }
    @Suppress("DEPRECATION")
    private fun click(screen: SemanticScreen, selected: SemanticNode, operation: PlaybackControl): Boolean {
        val fresh = this.screen() ?: return false
        if (!YouTubeControls.sameControl(screen, selected, fresh, operation)) return false
        var node = activeRoot() ?: return false
        try {
            if (node.packageName?.toString() != YouTubeControls.PACKAGE || node.windowId != fresh.windowId) return false
            for (index in selected.path) {
                val next = node.getChild(index) ?: return false
                node.recycle(); node = next
            }
            if (!node.refresh() || node.packageName?.toString() != selected.packageName || node.viewIdResourceName != selected.resourceId ||
                node.contentDescription?.toString() != selected.description || !node.isEnabled || !node.isVisibleToUser || !node.isClickable || node.isPassword) return false
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally { node.recycle() }
    }
    private suspend fun control(operation: PlaybackControl): ActionResult {
        val version = interruptionVersion
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Unlock the device before using app controls.")
        val before = screen() ?: return ActionResult(ExecutionStatus.NOT_SUPPORTED, "YouTube is not the active accessible app. No control was pressed.")
        val selected = YouTubeControls.select(before, operation)
            ?: return ActionResult(ExecutionStatus.NOT_SUPPORTED, "The expected unique YouTube control is unavailable. No control was pressed.")
        currentCoroutineContext().ensureActive()
        if (version != interruptionVersion) return ActionResult(ExecutionStatus.CANCELLED, "App controls were interrupted before the request.")
        if (!click(before, selected, operation)) return ActionResult(ExecutionStatus.FAILED, "YouTube changed before the action. No verified control action was completed.")
        val changed = withTimeoutOrNull(2500) {
            while (true) {
                if (version != interruptionVersion) return@withTimeoutOrNull false
                val current = screen() ?: return@withTimeoutOrNull false
                if (YouTubeControls.changed(current, operation)) return@withTimeoutOrNull true
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        if (version != interruptionVersion) return ActionResult(ExecutionStatus.CANCELLED, "App-control verification was interrupted. A control request may already have been sent.")
        return ActionResult(ExecutionStatus.PARTIAL, if (changed) "YouTube's control state changed. Playback has not yet been independently verified." else "The control request was sent, but YouTube's resulting state could not be verified.")
    }
    @Suppress("DEPRECATION")
    private fun clickWifi(expectedEnabled: Boolean): Boolean {
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return false
        val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val expectedState = if (expectedEnabled) android.net.wifi.WifiManager.WIFI_STATE_ENABLED else android.net.wifi.WifiManager.WIFI_STATE_DISABLED
        if (wifi.wifiState != expectedState) return false
        val before = screen(internetPanelPackage()) ?: return false
        val selected = WifiControls.select(before, expectedEnabled) ?: return false
        val fresh = screen(internetPanelPackage()) ?: return false
        if (!WifiControls.sameControl(before, selected, fresh, expectedEnabled)) return false
        var node = activeRoot() ?: return false
        try {
            if (node.packageName?.toString() != internetPanelPackage() || node.windowId != fresh.windowId) return false
            for (index in selected.toggle.path) {
                val next = node.getChild(index) ?: return false
                node.recycle(); node = next
            }
            if (!node.refresh() || node.packageName?.toString() != internetPanelPackage() || node.viewIdResourceName != selected.toggle.resourceId ||
                node.className?.toString() != "android.widget.Switch" || !node.isCheckable || node.isChecked != expectedEnabled ||
                !node.isEnabled || !node.isVisibleToUser || !node.isClickable || node.isPassword || wifi.wifiState != expectedState) return false
            if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked || wifi.wifiState != expectedState) return false
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally { node.recycle() }
    }
    companion object {
        private fun internetPanelPackage() = if (android.os.Build.VERSION.SDK_INT == 30) WifiControls.SETTINGS_PACKAGE else WifiControls.PACKAGE
        @Volatile private var active: AivoraAccessibilityService? = null
        fun connected() = active != null
        private enum class MobilePhase { TOGGLE, WARNING, DONE }
        private fun mobileNode(screen: SemanticScreen, action: Action.SetMobileData, phase: MobilePhase): SemanticNode? = when (phase) {
            MobilePhase.TOGGLE -> MobileDataControls.select(screen, !action.enabled)
            MobilePhase.WARNING -> if (!action.enabled) MobileDataControls.warning(screen) else null
            MobilePhase.DONE -> MobileDataControls.done(screen, action.enabled)
        }
        suspend fun hasMobileControl(action: Action.SetMobileData, expectedEnabled: Boolean): Boolean = withContext(Dispatchers.Main) {
            active?.screen(internetPanelPackage(), action.target.label)?.let { MobileDataControls.select(it, expectedEnabled) } != null
        }
        suspend fun hasMobileOffWarning(action: Action.SetMobileData): Boolean = withContext(Dispatchers.Main) {
            !action.enabled && active?.screen(internetPanelPackage(), action.target.label)?.let(MobileDataControls::warning) != null
        }
        suspend fun requestMobile(action: Action.SetMobileData) = mobileClick(action, MobilePhase.TOGGLE)
        suspend fun confirmMobileOff(action: Action.SetMobileData) = mobileClick(action, MobilePhase.WARNING)
        suspend fun dismissMobilePanel(action: Action.SetMobileData) = mobileClick(action, MobilePhase.DONE)
        @Suppress("DEPRECATION")
        private suspend fun mobileClick(action: Action.SetMobileData, phase: MobilePhase): Boolean = withContext(Dispatchers.Main) {
            val service = active ?: return@withContext false
            val version = service.interruptionVersion
            if (!(service.application as com.chronoit.aivora.AivoraApplication).settings.settings.first().mobileDataControls || active !== service || version != service.interruptionVersion)
                return@withContext false
            val monitor = com.chronoit.aivora.connectivity.AndroidMobileDataMonitor(service)
            val expected = if (phase == MobilePhase.DONE) action.enabled else !action.enabled
            fun permitted(): Boolean {
                if (service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return false
                val current = monitor.snapshot()
                return com.chronoit.aivora.connectivity.MobileDataGuard.matches(action.target, current, action.enabled) && current?.enabled == expected
            }
            if (!permitted()) return@withContext false
            val before = service.screen(internetPanelPackage(), action.target.label) ?: return@withContext false
            val selected = mobileNode(before, action, phase) ?: return@withContext false
            val fresh = service.screen(internetPanelPackage(), action.target.label) ?: return@withContext false
            if (fresh.windowId != before.windowId || mobileNode(fresh, action, phase) != selected) return@withContext false
            var node = service.activeRoot() ?: return@withContext false
            try {
                if (node.packageName?.toString() != internetPanelPackage() || node.windowId != before.windowId) return@withContext false
                for (index in selected.path) { val next = node.getChild(index) ?: return@withContext false; node.recycle(); node = next }
                if (!node.refresh() || node.packageName?.toString() != internetPanelPackage() || node.viewIdResourceName != selected.resourceId ||
                    node.className?.toString() != selected.className || !node.isVisibleToUser || !node.isEnabled || !node.isClickable || node.isPassword) return@withContext false
                if (phase == MobilePhase.TOGGLE && (!node.isCheckable || node.isChecked != expected)) return@withContext false
                if (phase == MobilePhase.WARNING && node.text?.toString() != "Turn off") return@withContext false
                if (phase == MobilePhase.DONE && node.text?.toString() != "Done") return@withContext false
                currentCoroutineContext().ensureActive()
                if (!permitted()) return@withContext false
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally { node.recycle() }
        }
        suspend fun hasWifiControl(expectedEnabled: Boolean): Boolean = withContext(Dispatchers.Main) {
            active?.screen(internetPanelPackage())?.let { WifiControls.select(it, expectedEnabled) } != null
        }
        @Suppress("DEPRECATION")
        suspend fun dismissWifiPanel(expectedEnabled: Boolean): Boolean = withContext(Dispatchers.Main) {
            val service = active ?: return@withContext false
            val version = service.interruptionVersion
            if (!(service.application as com.chronoit.aivora.AivoraApplication).settings.settings.first().wifiControls || active !== service ||
                service.interruptionVersion != version) return@withContext false
            if (service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return@withContext false
            val before = service.screen(internetPanelPackage()) ?: return@withContext false
            val done = WifiControls.done(before, expectedEnabled) ?: return@withContext false
            val fresh = service.screen(internetPanelPackage()) ?: return@withContext false
            if (fresh.windowId != before.windowId || WifiControls.done(fresh, expectedEnabled) != done) return@withContext false
            var node = service.activeRoot() ?: return@withContext false
            try {
                if (node.packageName?.toString() != internetPanelPackage() || node.windowId != before.windowId) return@withContext false
                for (index in done.path) { val next = node.getChild(index) ?: return@withContext false; node.recycle(); node = next }
                if (!node.refresh() || node.viewIdResourceName != done.resourceId || node.packageName?.toString() != internetPanelPackage() ||
                    node.className?.toString() != done.className || !node.isEnabled || !node.isVisibleToUser || !node.isClickable || node.isPassword ||
                    node.text?.toString() != "Done" || service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return@withContext false
                currentCoroutineContext().ensureActive()
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally { node.recycle() }
        }
        suspend fun requestWifi(expectedEnabled: Boolean): ActionResult = withContext(Dispatchers.Main) {
            val service = active ?: return@withContext ActionResult(ExecutionStatus.PERMISSION_MISSING, "Enable AIVORA app controls in Android accessibility settings first.")
            val version = service.interruptionVersion
            if (!(service.application as com.chronoit.aivora.AivoraApplication).settings.settings.first().wifiControls)
                return@withContext ActionResult(ExecutionStatus.PERMISSION_MISSING, "Supported Wi-Fi settings controls are disabled in AIVORA.")
            currentCoroutineContext().ensureActive()
            if (active !== service || service.interruptionVersion != version)
                return@withContext ActionResult(ExecutionStatus.CANCELLED, "Wi-Fi controls were interrupted before the request.")
            if (service.clickWifi(expectedEnabled)) ActionResult(ExecutionStatus.PARTIAL, "Android's Wi-Fi switch was requested; its radio state still needs verification.")
            else ActionResult(ExecutionStatus.NOT_SUPPORTED, "The expected Wi-Fi switch is unavailable or changed. No verified Wi-Fi request was completed.")
        }
        @Suppress("DEPRECATION")
        suspend fun hasYouTubeWindow(): Boolean = withContext(Dispatchers.Main) {
            val root = active?.rootInActiveWindow ?: return@withContext false
            try { root.packageName?.toString() == YouTubeControls.PACKAGE } finally { root.recycle() }
        }
        suspend fun youtube(operation: PlaybackControl): ActionResult = withContext(Dispatchers.Main) {
            active?.control(operation) ?: ActionResult(ExecutionStatus.PERMISSION_MISSING, "Enable AIVORA app controls in Android accessibility settings first.")
        }
    }
}
