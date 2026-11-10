package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.connectivity.*
import com.chronoit.aivora.data.InternetBehavior
import org.junit.Assert.*
import org.junit.Test

class VoiceNetworkGateTest {
    private var now = 100L
    private val gate = VoiceNetworkGate { now }
    private val review = NetworkReview("task", ActionPlan(listOf(Action.YouTubeSearch("birds")), 1.0, "test"), CommandSource.FOREGROUND_OWNER, 1000)
    @Test fun acceptsOnlyOneVerifiedReplyForTheExactCapturedTaskAndDeadline() {
        for (source in CommandSource.entries) {
            gate.arm(review)
            assertEquals(if (source == CommandSource.VERIFIED_VOICE) "task" else null, gate.consume(source, review))
            assertNull(gate.consume(CommandSource.VERIFIED_VOICE, review))
        }
        listOf(review.copy(token = "new"), review.copy(plan = review.plan.copy(planId = "changed")), review.copy(source = CommandSource.VERIFIED_VOICE), review.copy(expiresAt = 2000), null).forEach {
            gate.arm(review); assertNull(gate.consume(CommandSource.VERIFIED_VOICE, it))
            assertNull(gate.consume(CommandSource.VERIFIED_VOICE, review))
        }
    }
    @Test fun aReplacementTaskCannotUseAnOlderConnectionReply() {
        val orchestrator = ConnectivityOrchestrator { now }
        val first = orchestrator.begin(review.plan, CommandSource.VERIFIED_VOICE, NetworkSnapshot(false, false), InternetBehavior.ASK) as NetworkDecision.Ask
        gate.arm(orchestrator.review(first.token))
        val replacementPlan = ActionPlan(listOf(Action.YouTubeSearch("replacement")), 1.0, "test")
        val second = orchestrator.begin(replacementPlan, CommandSource.FOREGROUND_OWNER, NetworkSnapshot(false, false), InternetBehavior.ASK) as NetworkDecision.Ask
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, orchestrator.review(second.token)))
        assertNull(orchestrator.approvedSource(second.token))
        assertNull(orchestrator.resume(NetworkSnapshot(true, true), second.token))
        gate.arm(orchestrator.review(second.token))
        val token = gate.consume(CommandSource.VERIFIED_VOICE, orchestrator.review(second.token))!!
        assertTrue(orchestrator.approve(token) is NetworkDecision.OpenControls)
        val run = orchestrator.resume(NetworkSnapshot(true, true), second.token) as NetworkDecision.Run
        assertEquals(replacementPlan, run.plan)
        assertEquals(CommandSource.FOREGROUND_OWNER, run.source)
        assertNull(orchestrator.resume(NetworkSnapshot(true, true), second.token))
    }
    @Test fun expiryCancellationAndUnverifiedReviewNeverOpenConnectionControls() {
        gate.arm(review); now = 1000
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, review))
        gate.arm(review); now = 100
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, review))
        gate.arm(review); gate.clear()
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, review))
        gate.arm(review.copy(source = CommandSource.UNVERIFIED_VOICE))
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, review.copy(source = CommandSource.UNVERIFIED_VOICE)))
        val orchestrator = ConnectivityOrchestrator { now }
        val ask = orchestrator.begin(review.plan, CommandSource.VERIFIED_VOICE, NetworkSnapshot(false), InternetBehavior.ASK) as NetworkDecision.Ask
        gate.arm(orchestrator.review(ask.token))
        now += 60_000
        assertNull(orchestrator.review(ask.token))
        assertNull(gate.consume(CommandSource.VERIFIED_VOICE, orchestrator.review(ask.token)))
    }
    @Test fun reviewPreservesTheImmutableOriginalPlanWithoutEncodingTrustInIt() {
        val actions = mutableListOf<Action>(Action.YouTubeSearch("original"))
        val plan = ActionPlan(actions, 1.0, "test")
        val orchestrator = ConnectivityOrchestrator { now }
        val ask = orchestrator.begin(plan, CommandSource.VERIFIED_VOICE, NetworkSnapshot(false), InternetBehavior.ASK) as NetworkDecision.Ask
        actions.clear()
        val snapshot = orchestrator.review(ask.token)!!
        assertEquals(listOf(Action.YouTubeSearch("original")), snapshot.plan.actions)
        assertEquals(plan.planId, snapshot.plan.planId)
        assertEquals(CommandSource.VERIFIED_VOICE, snapshot.source)
        assertEquals(now + 60_000, snapshot.expiresAt)
        assertNull(orchestrator.review("wrong"))
        orchestrator.cancel()
        assertNull(orchestrator.review(ask.token))
    }
}
