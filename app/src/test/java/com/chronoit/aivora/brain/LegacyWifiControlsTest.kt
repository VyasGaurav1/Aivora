package com.chronoit.aivora.brain

import com.chronoit.aivora.automation.*
import org.junit.Assert.*
import org.junit.Test

class LegacyWifiControlsTest {
    private val pkg = WifiControls.SETTINGS_PACKAGE
    private fun node(path: List<Int>, id: String?, type: String = "android.widget.LinearLayout") =
        SemanticNode(path, pkg, id, null, true, true, false, false, type)
    private val panel = node(emptyList(), null, "android.widget.FrameLayout")
    private val title = node(listOf(0), "$pkg:id/panel_title", "android.widget.TextView").copy(internetLabel = true)
    private val list = node(listOf(1), "$pkg:id/panel_parent_layout", "androidx.recyclerview.widget.RecyclerView")
    private val frame = node(listOf(1, 0, 0), "$pkg:id/slice_view", "android.view.ViewGroup")
    private val row = node(listOf(1, 0, 0, 0, 0), "$pkg:id/row_view")
    private val label = node(row.path + 0, "android:id/title", "android.widget.TextView").copy(wifiLabel = true)
    private val toggle = node(row.path + 1, null, "android.widget.Switch").copy(clickable = true, checkable = true, checked = true)
    private val done = node(listOf(3), "$pkg:id/done", "android.widget.Button").copy(clickable = true, doneLabel = true)
    private fun screen(nodes: List<SemanticNode> = listOf(panel, title, list, frame, row, label, toggle, done)) = SemanticScreen(pkg, 7, nodes, false)

    @Test fun knownLegacyPanelSelectsOnlyTheSwitchInTheWifiRow() {
        assertEquals(toggle, WifiControls.select(screen(), true)!!.toggle)
        assertEquals(done, WifiControls.done(screen(), true))
        assertNull(WifiControls.select(screen(), false))
        val other = toggle.copy(path = listOf(0, 3, 1, 0, 0), checked = false)
        assertEquals(toggle, WifiControls.select(screen(screen().nodes + other), true)!!.toggle)
    }
    @Test fun unknownTitlesPagesPasswordsAndTruncatedTreesRefuse() {
        assertNull(WifiControls.select(screen().copy(packageName = "other"), true))
        assertNull(WifiControls.select(screen().copy(truncated = true), true))
        listOf(title.copy(internetLabel = false), label.copy(wifiLabel = false), toggle.copy(password = true),
            toggle.copy(resourceId = "unknown"), toggle.copy(enabled = false), toggle.copy(visible = false)).forEach { changed ->
            assertNull(WifiControls.select(screen(screen().nodes.map { if (it.path == changed.path) changed else it }), true))
        }
        assertNull(WifiControls.select(screen(screen().nodes - panel), true))
    }
    @Test fun hiddenDuplicateOrOppositeStateControlsNeverDisambiguateByVisibility() {
        listOf(toggle.copy(path = toggle.path + 1, visible = false), toggle.copy(path = toggle.path + 1, checked = false),
            label.copy(path = listOf(0, 8), visible = false), title.copy(path = listOf(0, 9), visible = false)).forEach {
            assertNull(WifiControls.select(screen(screen().nodes + it), true))
        }
        assertNull(WifiControls.done(screen(screen().nodes + done.copy(path = listOf(0, 9), visible = false)), true))
    }
    @Test fun rowAncestryAndFreshWindowIdentityAreRequired() {
        assertNull(WifiControls.select(screen(screen().nodes.map { if (it == toggle) it.copy(path = listOf(0, 4)) else it }), true))
        assertNull(WifiControls.select(screen(screen().nodes - frame), true))
        val selected = WifiControls.select(screen(), true)!!
        assertTrue(WifiControls.sameControl(screen(), selected, screen(), true))
        assertFalse(WifiControls.sameControl(screen(), selected, screen().copy(windowId = 8), true))
        assertFalse(WifiControls.sameControl(screen(), selected, screen(screen().nodes.map { if (it == toggle) it.copy(checked = false) else it }), true))
    }
}
