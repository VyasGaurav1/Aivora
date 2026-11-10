package com.chronoit.aivora.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundWakeLoopTest {
    @Test fun waitsForReadinessAndReturnsAfterEachBoundedCaptureWithoutOverlap() = runTest {
        var ready = false
        var starts = 0
        var active = 0
        var maximumActive = 0
        var stopped = false
        var finish = CompletableDeferred<Unit>()
        val loop = ForegroundWakeLoop(backgroundScope, { ready }, { ready }, {
            backgroundScope.launch {
                starts++; active++; maximumActive = maxOf(active, maximumActive)
                try { finish.await() } finally { active-- }
            }
        }, { stopped = true }, StandardTestDispatcher(testScheduler))
        loop.start(); loop.start()
        runCurrent()
        assertEquals(0, starts)
        ready = true
        advanceTimeBy(250); runCurrent()
        assertEquals(1, starts)
        finish.complete(Unit)
        advanceTimeBy(100); runCurrent()
        finish = CompletableDeferred()
        advanceTimeBy(250); runCurrent()
        assertEquals(2, starts)
        assertEquals(1, maximumActive)
        loop.stop(); runCurrent()
        assertTrue(stopped)
        assertEquals(0, active)
        advanceTimeBy(5000); runCurrent()
        assertEquals(2, starts)
    }

    @Test fun losingForegroundOrStartingWorkCancelsOnlyItsOwnCaptureThenResumes() = runTest {
        var ready = true
        var starts = 0
        var canceled = 0
        val unrelatedReply = backgroundScope.launch { awaitCancellation() }
        val loop = ForegroundWakeLoop(backgroundScope, { ready }, { ready }, {
            backgroundScope.launch {
                starts++
                try { awaitCancellation() } finally { canceled++ }
            }
        }, {}, StandardTestDispatcher(testScheduler))
        loop.start(); runCurrent()
        assertEquals(1, starts)
        ready = false
        advanceTimeBy(100); runCurrent()
        assertEquals(1, canceled)
        assertTrue(unrelatedReply.isActive)
        advanceTimeBy(2000); runCurrent()
        assertEquals(1, starts)
        ready = true
        advanceTimeBy(250); runCurrent()
        assertEquals(2, starts)
        loop.pause(); runCurrent()
        assertEquals(2, canceled)
        loop.stop(); runCurrent()
        assertTrue(unrelatedReply.isActive)
    }

    @Test fun stopAndImmediateRestartCannotLetAnOldSessionDisableTheNewOne() = runTest {
        var stopped = 0
        var starts = 0
        val loop = ForegroundWakeLoop(backgroundScope, { true }, { true }, {
            backgroundScope.launch { starts++; awaitCancellation() }
        }, { stopped++ }, StandardTestDispatcher(testScheduler))
        loop.start(); runCurrent()
        loop.stop(); loop.start(); runCurrent()
        assertEquals(1, stopped)
        assertEquals(2, starts)
        loop.stop(); runCurrent()
        assertEquals(2, stopped)
    }

    @Test fun aPausedActivitySuspendsReadinessPollingUntilAnExplicitResume() = runTest {
        var checks = 0
        var starts = 0
        val loop = ForegroundWakeLoop(backgroundScope, { checks++; true }, { true }, {
            backgroundScope.launch { starts++; awaitCancellation() }
        }, {}, StandardTestDispatcher(testScheduler))
        loop.start(); runCurrent()
        assertEquals(1, starts)
        loop.pause(); runCurrent()
        advanceTimeBy(1000); runCurrent()
        val pausedChecks = checks
        advanceTimeBy(60_000); runCurrent()
        assertEquals(pausedChecks, checks)
        assertEquals(1, starts)
        loop.resume(); runCurrent()
        assertEquals(2, starts)
        loop.stop(); runCurrent()
    }
}
