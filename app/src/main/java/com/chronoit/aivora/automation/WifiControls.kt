package com.chronoit.aivora.automation

data class WifiControl(val toggle: SemanticNode, val label: SemanticNode, val title: SemanticNode)

/** Known System UI or API30 Internet panel only; no generic Settings-page, SSID or coordinate fallback. */
object WifiControls {
    const val PACKAGE = "com.android.systemui"
    const val SETTINGS_PACKAGE = "com.android.settings"
    fun select(screen: SemanticScreen, expectedEnabled: Boolean): WifiControl? {
        if (screen.packageName == SETTINGS_PACKAGE) return LegacyWifiControls.select(screen, expectedEnabled)
        if (screen.packageName != PACKAGE || screen.truncated || screen.nodes.any { it.password }) return null
        fun valid(node: SemanticNode) = node.packageName == PACKAGE && node.enabled && node.visible && !node.password
        val title = screen.nodes.singleOrNull { valid(it) && it.resourceId == "$PACKAGE:id/internet_dialog_title" && it.internetLabel } ?: return null
        val label = screen.nodes.singleOrNull { valid(it) && it.resourceId == "$PACKAGE:id/wifi_toggle_title" && it.wifiLabel } ?: return null
        val toggle = screen.nodes.singleOrNull { valid(it) && it.resourceId == "$PACKAGE:id/wifi_toggle" && it.clickable &&
            it.className == "android.widget.Switch" && it.checkable && it.checked == expectedEnabled } ?: return null
        return WifiControl(toggle, label, title)
    }
    fun sameControl(before: SemanticScreen, selected: WifiControl, fresh: SemanticScreen, expectedEnabled: Boolean): Boolean =
        before.windowId == fresh.windowId && select(fresh, expectedEnabled) == selected
    fun done(screen: SemanticScreen, expectedEnabled: Boolean): SemanticNode? {
        if (screen.packageName == SETTINGS_PACKAGE) return LegacyWifiControls.done(screen, expectedEnabled)
        if (select(screen, expectedEnabled) == null) return null
        return screen.nodes.singleOrNull { it.packageName == PACKAGE && it.resourceId == "$PACKAGE:id/done_button" && it.doneLabel &&
            it.enabled && it.visible && it.clickable && !it.password && it.className == "android.widget.Button" }
    }
}
