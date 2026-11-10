package com.chronoit.aivora.automation

object MobileDataControls {
    private const val PACKAGE = WifiControls.PACKAGE
    private fun valid(screen: SemanticScreen) = screen.packageName == PACKAGE && !screen.truncated && screen.nodes.none { it.password }
    private fun node(screen: SemanticScreen, id: String, condition: (SemanticNode) -> Boolean) = screen.nodes
        .filter { it.packageName == PACKAGE && it.resourceId == id }.singleOrNull()
        ?.takeIf { it.enabled && it.visible && !it.password && condition(it) }
    fun select(screen: SemanticScreen, expectedEnabled: Boolean): SemanticNode? {
        if (screen.packageName == WifiControls.SETTINGS_PACKAGE) return LegacyMobileDataControls.select(screen, expectedEnabled)
        if (!valid(screen)) return null
        node(screen, "$PACKAGE:id/internet_dialog_title") { it.internetLabel } ?: return null
        node(screen, "$PACKAGE:id/mobile_title") { it.mobileLabel } ?: return null
        return node(screen, "$PACKAGE:id/mobile_toggle") { it.className == "android.widget.Switch" && it.checkable && it.checked == expectedEnabled && it.clickable }
    }
    fun warning(screen: SemanticScreen): SemanticNode? {
        if (!valid(screen)) return null
        node(screen, "android:id/alertTitle") { it.mobileWarningTitle } ?: return null
        node(screen, "android:id/message") { it.mobileWarningMessage } ?: return null
        return node(screen, "android:id/button1") { it.mobileOffLabel && it.clickable && it.className == "android.widget.Button" }
    }
    fun done(screen: SemanticScreen, expectedEnabled: Boolean): SemanticNode? {
        if (screen.packageName == WifiControls.SETTINGS_PACKAGE) return LegacyMobileDataControls.done(screen, expectedEnabled)
        if (select(screen, expectedEnabled) == null) return null
        return node(screen, "$PACKAGE:id/done_button") { it.doneLabel && it.clickable && it.className == "android.widget.Button" }
    }
}
