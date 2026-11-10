package com.chronoit.aivora.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReviewSpeechTest {
    @Test fun anAlreadyExpiredOrReplacedReviewNeverSpeaks() = runTest {
        var spoken = false
        assertFalse(ReviewSpeech.present({ false }) { spoken = true })
        assertFalse(spoken)
    }
    @Test fun expiryOrReplacementDuringSpeechPreventsReplyCapture() = runTest {
        var current = true
        var captured = false
        val completed = ReviewSpeech.present({ current }) { current = false }
        if (completed) captured = true
        assertFalse(completed)
        assertFalse(captured)
        assertTrue(ReviewSpeech.present({ true }) { })
    }
    @Test fun interruptedOrFailedSpeechCannotBecomeACompletedReview() = runTest {
        val interrupted = runCatching { ReviewSpeech.present({ true }) { throw CancellationException("stopped") } }
        assertTrue(interrupted.exceptionOrNull() is CancellationException)
        val failed = runCatching { ReviewSpeech.present({ true }) { error("output failed") } }
        assertTrue(failed.isFailure)
    }
}
