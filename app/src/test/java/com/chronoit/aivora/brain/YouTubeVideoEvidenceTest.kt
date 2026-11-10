package com.chronoit.aivora.brain

import com.chronoit.aivora.automation.*
import org.junit.Assert.*
import org.junit.Test

class YouTubeVideoEvidenceTest {
    private val id = "abcdefghijk"
    private val other = "12345678901"
    private fun session(ids: List<String> = listOf(id), playing: Boolean = true, pkg: String = YouTubeControls.PACKAGE) = VideoSessionEvidence(pkg, playing, ids)
    @Test fun explicitIdentityAndPlayingAreBothRequired() {
        assertTrue(YouTubeVideoEvidence.playing(id, listOf(session())))
        assertTrue(YouTubeVideoEvidence.playing(id, listOf(session(listOf("https://www.youtube.com/watch?v=$id", id)))))
        assertFalse(YouTubeVideoEvidence.playing(id, listOf(session(playing = false))))
        assertFalse(YouTubeVideoEvidence.playing(id, listOf(session(emptyList()))))
        assertFalse(YouTubeVideoEvidence.playing(id, listOf(session(listOf(other)))))
        assertFalse(YouTubeVideoEvidence.playing(id, listOf(session(listOf("A matching-looking title")))))
    }
    @Test fun ambiguityConflictsAndUnrelatedPackagesCannotProvePlayback() {
        assertFalse(YouTubeVideoEvidence.playing(id, listOf(session(), session())))
        assertFalse(YouTubeVideoEvidence.playing(id, listOf(session(listOf(id, other)))))
        assertFalse(YouTubeVideoEvidence.playing(id, listOf(session(listOf(id, "opaque unknown id")))))
        assertFalse(YouTubeVideoEvidence.playing(id, listOf(session(pkg = "other.app"))))
        assertTrue(YouTubeVideoEvidence.playing(id, listOf(session(), session(pkg = "other.app"))))
    }
    @Test fun strictIdentityUrisNeverAcceptRedirectsOrExtraFields() {
        assertEquals(id, YouTubeVideoEvidence.videoId("https://youtu.be/$id"))
        assertEquals(id, YouTubeVideoEvidence.videoId("https://m.youtube.com/watch?v=$id"))
        for (uri in listOf("http://youtu.be/$id", "https://youtube.com.evil/watch?v=$id", "https://youtube.com@evil/watch?v=$id", "https://www.youtube.com/watch?v=$id&v=$other", "https://youtu.be/$id#fragment", "https://youtu.be/$id?x=y", "https://youtu.be:443/$id", "https://www.youtube.com/watch?v=%61bcdefghijk"))
            assertNull(uri, YouTubeVideoEvidence.videoId(uri))
    }
}
