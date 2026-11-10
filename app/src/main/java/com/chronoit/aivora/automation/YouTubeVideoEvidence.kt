package com.chronoit.aivora.automation

import java.net.URI

data class VideoSessionEvidence(val packageName: String, val playing: Boolean, val identities: List<String>)

/** Reported identity only; titles and artists never establish the requested video. */
object YouTubeVideoEvidence {
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")
    fun videoId(value: String): String? {
        if (idPattern.matches(value)) return value
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return null
        return when (uri.host) {
            "youtu.be" -> uri.rawPath.removePrefix("/").takeIf { uri.rawQuery == null && idPattern.matches(it) }
            "youtube.com", "www.youtube.com", "m.youtube.com" -> {
                if (uri.rawPath != "/watch") null
                else uri.rawQuery?.removePrefix("v=")?.takeIf { uri.rawQuery == "v=$it" && idPattern.matches(it) }
            }
            else -> null
        }
    }
    fun playing(requestedId: String, sessions: List<VideoSessionEvidence>): Boolean {
        if (!idPattern.matches(requestedId)) return false
        val session = sessions.filter { it.packageName == YouTubeControls.PACKAGE }.singleOrNull() ?: return false
        if (!session.playing || session.identities.isEmpty()) return false
        val ids = session.identities.map { videoId(it) ?: return false }
        return ids.all { it == requestedId }
    }
}
