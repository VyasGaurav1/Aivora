package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.connectivity.*
import com.chronoit.aivora.data.InternetBehavior
import org.junit.Assert.*
import org.junit.Test

class ConnectivityOrchestratorTest {
    private var time = 0L
    private val orchestrator = ConnectivityOrchestrator { time }
    private val offline = NetworkSnapshot(false, false)
    private val online = NetworkSnapshot(true, true)
    private fun plan(action: Action) = ActionPlan(listOf(action), 1.0, "test")
    @Test fun expiredApprovalCannotClearAReplacementTask() {
        fun waiting(query: String) = (orchestrator.begin(plan(Action.YouTubeSearch(query)),
            CommandSource.FOREGROUND_OWNER, offline, InternetBehavior.ENABLE_WHEN_REQUIRED) as NetworkDecision.OpenControls).token
        val first = waiting("first")
        time = 59_999
        assertFalse(orchestrator.expireApproved(first))
        val second = waiting("second")
        time = 60_000
        assertFalse(orchestrator.expireApproved(first))
        assertEquals(second, orchestrator.token())
        time = 119_999
        assertTrue(orchestrator.expireApproved(second))
        assertFalse(orchestrator.expireApproved(second))
        assertNull(orchestrator.resume(online, second))
    }
    @Test fun offlineTasksNeverAskForNetworkAndOnlinePolicyDoesNotMutateConnectivity() {
        assertTrue(orchestrator.begin(plan(Action.SetVolume(30)), CommandSource.FOREGROUND_OWNER, offline, InternetBehavior.ASK) is NetworkDecision.Run)
        assertTrue(orchestrator.begin(plan(Action.YouTubeSearch("birds")), CommandSource.FOREGROUND_OWNER, offline, InternetBehavior.LEAVE_UNCHANGED) is NetworkDecision.Wait)
        assertNull(orchestrator.token())
        assertTrue(orchestrator.begin(plan(Action.YouTubeSearch("birds")), CommandSource.FOREGROUND_OWNER, online, InternetBehavior.ASK) is NetworkDecision.Run)
    }
    @Test fun approvalValidationProvenanceSnapshotExpiryAndSingleUseAreEnforced() {
        val actions = mutableListOf<Action>(Action.YouTubeSearch("birds"))
        val pending = ActionPlan(actions, 1.0, "test")
        val ask = orchestrator.begin(pending, CommandSource.VERIFIED_VOICE, offline, InternetBehavior.ASK) as NetworkDecision.Ask
        actions.clear()
        assertNull(orchestrator.resume(online, ask.token))
        assertTrue(orchestrator.approve("forged") is NetworkDecision.Wait)
        assertEquals(offline, orchestrator.previousSnapshot())
        assertTrue(orchestrator.approve(ask.token) is NetworkDecision.OpenControls)
        assertNull(orchestrator.resume(offline, ask.token))
        val run = orchestrator.resume(online, ask.token) as NetworkDecision.Run
        assertEquals(listOf(Action.YouTubeSearch("birds")), run.plan.actions)
        assertEquals(CommandSource.VERIFIED_VOICE, run.source)
        assertNull(orchestrator.resume(online, ask.token))
        orchestrator.begin(plan(Action.YouTubeSearch("birds")), CommandSource.FOREGROUND_OWNER, offline, InternetBehavior.ENABLE_WHEN_REQUIRED)
        time += 60_000
        assertNull(orchestrator.resume(online, ask.token))
        assertNull(orchestrator.token())
    }
    @Test fun replacementCancellationRestoreRequestAndUnverifiedVoiceFailSafely() {
        val first = orchestrator.begin(plan(Action.YouTubeSearch("first")), CommandSource.FOREGROUND_OWNER, offline, InternetBehavior.ASK) as NetworkDecision.Ask
        val second = orchestrator.begin(plan(Action.YouTubeSearch("second")), CommandSource.FOREGROUND_OWNER, offline, InternetBehavior.RESTORE_AFTERWARD) as NetworkDecision.Ask
        assertTrue(orchestrator.approve(first.token) is NetworkDecision.Wait)
        orchestrator.approve(second.token)
        assertNull(orchestrator.resume(online, first.token))
        assertEquals(second.token, orchestrator.token())
        assertTrue((orchestrator.resume(online, second.token) as NetworkDecision.Run).restoreRequested)
        orchestrator.begin(plan(Action.YouTubeSearch("third")), CommandSource.FOREGROUND_OWNER, offline, InternetBehavior.ENABLE_WHEN_REQUIRED)
        orchestrator.cancel()
        assertNull(orchestrator.resume(online, second.token))
        assertTrue(orchestrator.begin(plan(Action.YouTubeSearch("third")), CommandSource.UNVERIFIED_VOICE, online, InternetBehavior.ENABLE_WHEN_REQUIRED) is NetworkDecision.Wait)
    }
    @Test fun statusPreservesApprovalAndReportsExpiry() {
        assertEquals(ParsedCommand.ConnectivityStatus, CommandParser().parse("why is my online task waiting"))
        val task = plan(Action.YouTubeSearch("birds"))
        val ask = orchestrator.begin(task, CommandSource.VERIFIED_VOICE, offline, InternetBehavior.ASK) as NetworkDecision.Ask
        assertTrue(orchestrator.status(online).contains("waiting for your connection approval"))
        val mobileStatus = orchestrator.status(NetworkSnapshot(true, false, NetworkRoute.MOBILE))
        assertTrue(mobileStatus.contains("internet access over mobile data"))
        assertTrue(mobileStatus.contains("Wi-Fi is off"))
        assertTrue(mobileStatus.contains("waiting for your connection approval"))
        assertEquals(ask.token, orchestrator.token())
        assertNull(orchestrator.resume(online, ask.token))

        orchestrator.approve(ask.token)
        assertTrue(orchestrator.status(offline).contains("waiting for an internet connection"))
        assertTrue(orchestrator.status(online).contains("connection is ready"))
        assertEquals(task, (orchestrator.resume(online, ask.token) as NetworkDecision.Run).plan)

        orchestrator.begin(task, CommandSource.VERIFIED_VOICE, offline, InternetBehavior.ASK)
        time += 60_000
        assertTrue(orchestrator.status(offline).contains("No online task is waiting"))
        assertNull(orchestrator.token())
    }
    @Test fun youtubeCommandsUseTypedBoundedActionsAndStrictVideoIdentifiers() {
        val parser = CommandParser()
        assertEquals(Action.YouTubeSearch("calm music"), (parser.parse("play calm music on YouTube") as ParsedCommand.Plan).value.actions.single())
        assertEquals(Action.YouTubeVideo("dQw4w9WgXcQ"), (parser.parse("play https://youtu.be/dQw4w9WgXcQ") as ParsedCommand.Plan).value.actions.single())
        assertTrue(parser.parse("play https://youtube.com.evil.test/watch?v=dQw4w9WgXcQ") is ParsedCommand.Clarify)
        assertTrue(PlanValidator.error(plan(Action.YouTubeVideo("../malicious"))) != null)
        val action = Action.YouTubeSearch("birds & trees")
        assertEquals(action, ActionCodec.decode(ActionCodec.encode(action)))
        assertTrue(action.requiresNetwork())
        assertFalse(Action.DraftMessage("5550199", "hello").requiresNetwork())
    }
}
