package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.automation.*
import com.chronoit.aivora.connectivity.*
import com.chronoit.aivora.policy.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MobileDataControlsTest {
    private val target = MobileDataTarget(1, 0, 10, "Test carrier")
    private val pkg = WifiControls.PACKAGE
    private val title = node("$pkg:id/internet_dialog_title").copy(internetLabel = true)
    private val label = node("$pkg:id/mobile_title").copy(mobileLabel = true)
    private val toggle = node("$pkg:id/mobile_toggle", "android.widget.Switch").copy(checkable = true, checked = true, clickable = true)
    private fun node(id: String, kind: String = "android.widget.TextView") = SemanticNode(listOf(id.hashCode()), pkg, id, null, true, true, false, false, kind)
    private fun screen(nodes: List<SemanticNode> = listOf(title, label, toggle)) = SemanticScreen(pkg, 5, nodes, false)
    @Test fun liveBindingRejectsChangedSubscriptionSlotCarrierLabelAndRoamingEnable() {
        val state = MobileDataState(target, true, false)
        assertTrue(MobileDataGuard.matches(target, state, false))
        listOf(target.copy(subscriptionId = 2), target.copy(slotIndex = 1), target.copy(carrierId = 11), target.copy(label = "another SIM")).forEach {
            assertFalse(MobileDataGuard.matches(it, state, true))
        }
        assertFalse(MobileDataGuard.matches(target, null, true))
        assertFalse(MobileDataGuard.matches(target, state.copy(roaming = true), true))
        assertTrue(MobileDataGuard.matches(target, state.copy(roaming = true), false))
        listOf(target.copy(subscriptionId = -1), target.copy(slotIndex = -1), target.copy(label = ""), target.copy(label = "SIM\nother")).forEach { assertFalse(it.valid()) }
    }
    @Test fun onlyExactUniqueKnownMobilePanelStateIsSelectable() {
        assertEquals(toggle, MobileDataControls.select(screen(), true))
        assertNull(MobileDataControls.select(screen(), false))
        assertNull(MobileDataControls.select(screen().copy(packageName = "other"), true))
        assertNull(MobileDataControls.select(screen().copy(truncated = true), true))
        assertNull(MobileDataControls.select(screen(listOf(title, label.copy(mobileLabel = false), toggle)), true))
        for (bad in listOf(toggle.copy(password = true), toggle.copy(enabled = false), toggle.copy(visible = false), toggle.copy(clickable = false),
            toggle.copy(resourceId = "$pkg:id/wifi_toggle"), toggle.copy(checkable = false), toggle.copy(className = "android.widget.Button"))) {
            assertNull(MobileDataControls.select(screen(listOf(title, label, bad)), true))
        }
        assertNull(MobileDataControls.select(screen(listOf(title, label, toggle, toggle.copy(path = listOf(7)))), true))
        assertNull(MobileDataControls.select(screen(listOf(title, label, toggle, toggle.copy(path = listOf(7), checked = false))), true))
        assertNull(MobileDataControls.select(screen(listOf(title, label, toggle, toggle.copy(path = listOf(7), visible = false))), true))
    }
    @Test fun turnOffWarningAndDoneRequireTheirExactCompleteKnownContext() {
        val warningTitle = node("android:id/alertTitle").copy(mobileWarningTitle = true)
        val message = node("android:id/message").copy(mobileWarningMessage = true)
        val off = node("android:id/button1", "android.widget.Button").copy(mobileOffLabel = true, clickable = true)
        assertEquals(off, MobileDataControls.warning(screen(listOf(warningTitle, message, off))))
        assertNull(MobileDataControls.warning(screen(listOf(warningTitle, message.copy(mobileWarningMessage = false), off))))
        assertNull(MobileDataControls.warning(screen(listOf(warningTitle.copy(mobileWarningTitle = false), message, off))))
        assertNull(MobileDataControls.warning(screen(listOf(warningTitle, message, off.copy(mobileOffLabel = false)))))
        assertNull(MobileDataControls.warning(screen(listOf(warningTitle, message, off, off.copy(path = listOf(9))))))
        val done = node("$pkg:id/done_button", "android.widget.Button").copy(doneLabel = true, clickable = true)
        assertEquals(done, MobileDataControls.done(screen(listOf(title, label, toggle, done)), true))
        assertNull(MobileDataControls.done(screen(listOf(title, toggle, done)), true))
        assertNull(MobileDataControls.done(screen(listOf(title, label, toggle, done.copy(password = true))), true))
    }
    @Test fun typedActionIsOfflineStrictlyStoredAndParserDoesNotInventASim() {
        assertEquals(ParsedCommand.MobileData(true), CommandParser().parse("turn mobile data on"))
        assertEquals(ParsedCommand.MobileData(false), CommandParser().parse("switch cellular data off"))
        val action = Action.SetMobileData(true, target)
        assertFalse(action.requiresNetwork())
        assertEquals(RiskLevel.HIGH, action.risk())
        assertEquals(action, ActionCodec.decode(ActionCodec.encode(action)))
        assertNotNull(PlanValidator.error(ActionPlan(listOf(action.copy(target = target.copy(subscriptionId = -1))), 1.0, "test")))
        assertTrue(runCatching { ActionCodec.decode(ActionCodec.encode(action) + "\tZXh0cmE") }.isFailure)
    }
    @Test fun noEffectBeforeDeviceAuthenticationAndRotatedExactPlanConfirmation() = runTest {
        var effects = 0
        val registry = ActionRegistry(ActionExecutor { effects++; ActionResult(ExecutionStatus.SUCCESS, "test") }, ActionAudit { _, _, _, _ -> }, { ExecutionContext(true, true) })
        val plan = ActionPlan(listOf(Action.SetMobileData(true, target)), 1.0, "test")
        assertTrue(registry.dispatch(plan, CommandSource.UNVERIFIED_VOICE) is DispatchResult.Rejected)
        val first = registry.dispatch(plan, CommandSource.VERIFIED_VOICE) as DispatchResult.NeedsAuthentication
        assertTrue(registry.confirm(first.confirmation.token) is DispatchResult.Rejected)
        assertEquals(0, effects)
        val second = registry.dispatch(plan, CommandSource.VERIFIED_VOICE) as DispatchResult.NeedsAuthentication
        val confirmation = registry.authenticate(second.confirmation.token) as DispatchResult.NeedsConfirmation
        assertNotEquals(second.confirmation.token, confirmation.confirmation.token)
        assertEquals(0, effects)
        assertTrue(registry.confirm(confirmation.confirmation.token) is DispatchResult.Executed)
        assertEquals(1, effects)
        assertTrue(registry.confirm(confirmation.confirmation.token) is DispatchResult.Rejected)
    }
}
