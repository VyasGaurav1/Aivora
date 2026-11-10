package com.chronoit.aivora.brain

import com.chronoit.aivora.automation.*
import org.junit.Assert.*
import org.junit.Test

class LegacyMobileDataControlsTest {
    private val pkg = WifiControls.SETTINGS_PACKAGE
    private fun node(path: List<Int>, id: String?, type: String = "android.widget.LinearLayout") =
        SemanticNode(path, pkg, id, null, true, true, false, false, type)
    private val root = node(emptyList(), null, "android.widget.FrameLayout")
    private val title = node(listOf(0), "$pkg:id/panel_title", "android.widget.TextView").copy(internetLabel = true)
    private val list = node(listOf(1), "$pkg:id/panel_parent_layout", "androidx.recyclerview.widget.RecyclerView")
    private val slice = node(listOf(1, 1, 0), "$pkg:id/slice_view", "android.view.ViewGroup")
    private val row = node(slice.path + listOf(0, 0), "$pkg:id/row_view")
    private val heading = node(row.path + 0, "android:id/title", "android.widget.TextView").copy(mobileDataLabel = true)
    private val sim = node(row.path + 1, "android:id/summary", "android.widget.TextView").copy(mobileLabel = true)
    private val toggle = node(row.path + 2, null, "android.widget.Switch").copy(clickable = true, checkable = true, checked = true)
    private val done = node(listOf(3), "$pkg:id/done", "android.widget.Button").copy(clickable = true, doneLabel = true)
    private fun screen(nodes: List<SemanticNode> = listOf(root, title, list, slice, row, heading, sim, toggle, done)) = SemanticScreen(pkg, 7, nodes, false)

    @Test fun knownRowRequiresTheIndependentlyMatchedSimAndExpectedState() {
        assertEquals(toggle, MobileDataControls.select(screen(), true))
        assertEquals(done, MobileDataControls.done(screen(), true))
        assertNull(MobileDataControls.select(screen(), false))
        assertNull(MobileDataControls.select(screen(screen().nodes.map { if (it == sim) it.copy(mobileLabel = false) else it }), true))
        assertNull(MobileDataControls.select(screen(screen().nodes.map { if (it == heading) it.copy(mobileDataLabel = false) else it }), true))
    }
    @Test fun hiddenDuplicateSwitchesSummariesAndHeadingsRefuse() {
        listOf(toggle.copy(path = row.path + 3, checked = false), toggle.copy(path = row.path + 4, visible = false),
            sim.copy(path = row.path + 5, mobileLabel = false, visible = false), heading.copy(path = row.path + 6, visible = false)).forEach {
            assertNull(MobileDataControls.select(screen(screen().nodes + it), true))
        }
        assertNull(MobileDataControls.done(screen(screen().nodes + done.copy(path = listOf(4), visible = false)), true))
    }
    @Test fun unknownScreensAncestryAndPasswordsCannotBecomeDataControls() {
        assertNull(MobileDataControls.select(screen().copy(truncated = true), true))
        assertNull(MobileDataControls.select(screen().copy(packageName = "other"), true))
        listOf(sim.copy(path = listOf(9)), toggle.copy(path = listOf(8)), title.copy(internetLabel = false),
            toggle.copy(password = true), toggle.copy(resourceId = "unknown"), toggle.copy(enabled = false)).forEach { changed ->
            val original = when { changed == title.copy(internetLabel = false) -> title; changed.resourceId == "android:id/summary" -> sim; else -> toggle }
            assertNull(MobileDataControls.select(screen(screen().nodes - original + changed), true))
        }
        assertNull(MobileDataControls.select(screen(screen().nodes - slice), true))
    }
    @Test fun legacyPanelDoesNotAuthorizeTheNewerSystemUiWarning() {
        assertNull(MobileDataControls.warning(screen()))
        val fake = node(listOf(5), "android:id/button1", "android.widget.Button").copy(clickable = true, mobileOffLabel = true)
        assertNull(MobileDataControls.warning(screen(screen().nodes + fake)))
        assertNull(MobileDataControls.done(screen(screen().nodes - sim), true))
    }
}
