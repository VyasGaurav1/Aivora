package com.chronoit.aivora.voice

import org.junit.Assert.*
import org.junit.Test

class MicrophoneLeaseTest {
    @Test fun competingRecordersCannotAcquireAndOldCleanupCannotReleaseNewOwner() {
        val first = MicrophoneLease.acquire()
        try { assertTrue(runCatching { MicrophoneLease.acquire() }.exceptionOrNull() is MicrophoneInUseException) }
        finally { first.close() }
        val second = MicrophoneLease.acquire()
        try {
            first.close()
            assertTrue(runCatching { MicrophoneLease.acquire() }.exceptionOrNull() is MicrophoneInUseException)
        } finally { second.close() }
    }
}
