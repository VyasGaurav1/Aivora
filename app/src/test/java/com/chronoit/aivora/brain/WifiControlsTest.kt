package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.automation.*
import com.chronoit.aivora.connectivity.*
import com.chronoit.aivora.data.InternetBehavior
import com.chronoit.aivora.policy.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WifiControlsTest {
    private val pkg = WifiControls.PACKAGE
    private val row = SemanticNode(listOf(0), pkg, "$pkg:id/internet_dialog_title", null, true, true, false, false, "android.widget.TextView", internetLabel = true)
    private val label = SemanticNode(listOf(0, 0, 0), pkg, "$pkg:id/wifi_toggle_title", null, true, true, false, false, "android.widget.TextView", wifiLabel = true)
    private val toggle = SemanticNode(listOf(0, 1, 0), pkg, "$pkg:id/wifi_toggle", null, true, true, true, false, "android.widget.Switch", true, false)
    private val nodes = listOf(row, label, toggle)
    private fun screen(nodes: List<SemanticNode> = this.nodes) = SemanticScreen(pkg, 5, nodes, false)
    @Test fun matchesOnlyTheKnownWifiRowAndExactObservedState() {
        val selected = WifiControls.select(screen(), false)!!
        assertEquals(row, selected.title)
        assertEquals(toggle, selected.toggle)
        assertNull(WifiControls.select(screen(), true))
        assertTrue(WifiControls.sameControl(screen(), selected, screen(), false))
        assertFalse(WifiControls.sameControl(screen(), selected, screen().copy(windowId = 6), false))
        assertFalse(WifiControls.sameControl(screen(), selected, screen(nodes.map { if (it == toggle) it.copy(checked = true) else it }), false))
    }
    @Test fun rejectsOtherAppsPasswordsUnknownOrAmbiguousStructuresAndStaleControls() {
        assertNull(WifiControls.select(screen().copy(packageName = "com.chronoit.aivora"), false))
        assertNull(WifiControls.select(screen().copy(truncated = true), false))
        assertNull(WifiControls.select(screen(nodes + row.copy(path = listOf(9), password = true)), false))
        for (original in nodes) {
            for (replacement in listOf(original.copy(enabled = false), original.copy(visible = false), original.copy(packageName = "other"))) {
                assertNull(WifiControls.select(screen(nodes.map { if (it == original) replacement else it }), false))
            }
        }
        listOf(toggle.copy(checkable = false), toggle.copy(resourceId = "other"), toggle.copy(className = "android.widget.Button"),
            toggle.copy(clickable = false)).forEach { bad -> assertNull(WifiControls.select(screen(nodes - toggle + bad), false)) }
        assertNull(WifiControls.select(screen(nodes - label + label.copy(wifiLabel = false)), false))
        assertNull(WifiControls.select(screen(nodes - row + row.copy(internetLabel = false)), false))
        assertNull(WifiControls.select(screen(nodes + nodes.map { it.copy(path = listOf(1) + it.path.drop(1)) }), false))
    }
    @Test fun wifiIsOfflineTypedStrictlySerializedAndAlwaysRequiresFreshConfirmation() = runTest {
        val action = (CommandParser().parse("turn Wi-Fi on") as ParsedCommand.Plan).value.actions.single()
        assertEquals(Action.SetWifi(true), action)
        assertFalse(action.requiresNetwork())
        assertEquals(action, ActionCodec.decode(ActionCodec.encode(action)))
        assertEquals(Action.SetWifi(false), (CommandParser().parse("switch wifi off") as ParsedCommand.Plan).value.actions.single())
        var calls = 0
        val registry = ActionRegistry(ActionExecutor { calls++; ActionResult(ExecutionStatus.SUCCESS, "test") }, ActionAudit { _, _, _, _ -> }, { ExecutionContext(true, true) })
        val plan = ActionPlan(listOf(action), 1.0, "test")
        assertTrue(registry.dispatch(plan, CommandSource.UNVERIFIED_VOICE) is DispatchResult.Rejected)
        val pending = registry.dispatch(plan, CommandSource.VERIFIED_VOICE) as DispatchResult.NeedsConfirmation
        assertEquals(0, calls)
        registry.confirm(pending.confirmation.token)
        assertEquals(1, calls)
        assertTrue(registry.confirm(pending.confirmation.token) is DispatchResult.Rejected)
    }
    @Test fun onlyTheApprovedCurrentOnlineTaskProvidesTheConnectionSource() {
        var now = 0L
        val orchestrator = ConnectivityOrchestrator { now }
        val plan = ActionPlan(listOf(Action.YouTubeSearch("offline music")), 1.0, "test")
        val pending = orchestrator.begin(plan, CommandSource.VERIFIED_VOICE, NetworkSnapshot(false, false), InternetBehavior.ASK) as NetworkDecision.Ask
        assertNull(orchestrator.approvedSource(pending.token))
        orchestrator.approve(pending.token)
        assertEquals(CommandSource.VERIFIED_VOICE, orchestrator.approvedSource(pending.token))
        assertNull(orchestrator.approvedSource("replacement"))
        now = 60_000
        assertNull(orchestrator.approvedSource(pending.token))
    }
    @Test fun doneButtonRequiresTheWholeKnownPanelAndCannotDismissAnotherDialog() {
        val done = SemanticNode(listOf(8), pkg, "$pkg:id/done_button", null, true, true, true, false, "android.widget.Button", doneLabel = true)
        assertEquals(done, WifiControls.done(screen(nodes + done), false))
        assertNull(WifiControls.done(screen(nodes + done), true))
        assertNull(WifiControls.done(screen(nodes - row + done), false))
        listOf(done.copy(doneLabel = false), done.copy(resourceId = "android:id/button1"), done.copy(visible = false), done.copy(clickable = false), done.copy(password = true)).forEach {
            assertNull(WifiControls.done(screen(nodes + it), false))
        }
        assertNull(WifiControls.done(screen(nodes + done + done.copy(path = listOf(9))), false))
    }
}
