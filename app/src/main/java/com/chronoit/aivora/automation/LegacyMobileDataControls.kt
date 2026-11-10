package com.chronoit.aivora.automation

/** Exact API30 panel row plus independently supplied live SIM label. No generic data switch. */
object LegacyMobileDataControls {
    private const val PACKAGE = WifiControls.SETTINGS_PACKAGE
    private fun inside(node: SemanticNode, parent: SemanticNode) = node.path.size > parent.path.size && node.path.take(parent.path.size) == parent.path
    private fun usable(node: SemanticNode) = node.packageName == PACKAGE && node.enabled && node.visible && !node.password
    private fun SemanticScreen.unique(id: String) = nodes.singleOrNull { it.packageName == PACKAGE && it.resourceId == id }?.takeIf(::usable)
    fun select(screen: SemanticScreen, expectedEnabled: Boolean): SemanticNode? {
        if (screen.packageName != PACKAGE || screen.truncated || screen.nodes.any { it.password }) return null
        screen.nodes.singleOrNull { it.path.isEmpty() }?.takeIf { usable(it) && it.className == "android.widget.FrameLayout" && it.resourceId.isNullOrEmpty() } ?: return null
        screen.unique("$PACKAGE:id/panel_title")?.takeIf { it.internetLabel && it.path.size == 1 && it.className == "android.widget.TextView" } ?: return null
        val list = screen.unique("$PACKAGE:id/panel_parent_layout")?.takeIf { it.path.size == 1 && it.className == "androidx.recyclerview.widget.RecyclerView" } ?: return null
        val label = screen.nodes.singleOrNull { it.packageName == PACKAGE && it.resourceId == "android:id/title" && it.mobileDataLabel }
            ?.takeIf { usable(it) && inside(it, list) && it.className == "android.widget.TextView" } ?: return null
        val row = screen.nodes.singleOrNull { it.resourceId == "$PACKAGE:id/row_view" && label.path.dropLast(1) == it.path }
            ?.takeIf { usable(it) && it.className == "android.widget.LinearLayout" && inside(it, list) } ?: return null
        screen.nodes.singleOrNull { it.resourceId == "android:id/summary" && it.path.dropLast(1) == row.path }
            ?.takeIf { usable(it) && it.mobileLabel && it.className == "android.widget.TextView" } ?: return null
        val slice = screen.nodes.singleOrNull { it.resourceId == "$PACKAGE:id/slice_view" && inside(row, it) && inside(it, list) }
            ?.takeIf { usable(it) && it.className == "android.view.ViewGroup" } ?: return null
        if (row.path.size - slice.path.size != 2) return null
        return screen.nodes.singleOrNull { it.className == "android.widget.Switch" && inside(it, row) }
            ?.takeIf { usable(it) && it.path.dropLast(1) == row.path && it.resourceId.isNullOrEmpty() && it.clickable && it.checkable && it.checked == expectedEnabled }
    }
    fun done(screen: SemanticScreen, expectedEnabled: Boolean): SemanticNode? {
        if (select(screen, expectedEnabled) == null) return null
        return screen.unique("$PACKAGE:id/done")?.takeIf { it.doneLabel && it.path.size == 1 && it.clickable && it.className == "android.widget.Button" }
    }
}
