package com.chronoit.aivora.voice

import com.chronoit.aivora.actions.CommandSource
import com.chronoit.aivora.connectivity.NetworkReview

/** Connection permission belongs to the task reviewed before capture, not a later online request. */
class VoiceNetworkGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var captured: NetworkReview? = null
    @Synchronized fun arm(review: NetworkReview?) {
        captured = review?.takeIf { it.expiresAt > clock() && it.source != CommandSource.UNVERIFIED_VOICE }
    }
    @Synchronized fun clear() { captured = null }
    @Synchronized fun consume(source: CommandSource, current: NetworkReview?): String? {
        val review = captured
        captured = null
        return review?.takeIf { source == CommandSource.VERIFIED_VOICE && current != null && it.token == current.token &&
            it.plan.planId == current.plan.planId && it.source == current.source && it.expiresAt == current.expiresAt && it.expiresAt > clock() }?.token
    }
}
