package com.chronoit.aivora.automation

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.net.Uri
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.platform.AivoraNotificationListener
import kotlinx.coroutines.*

class YouTubeAutomationAdapter(private val context: Context) {
    private fun hasNotificationAccess(): Boolean = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(context, AivoraNotificationListener::class.java))

    fun playbackStatus(): String {
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return "Unlock the device to check YouTube playback."
        if (!hasNotificationAccess()) return "Enable notification access to check YouTube playback."
        val current = sessions()
        if (current.size > 1) return "Multiple YouTube media sessions are active."
        val state = current.singleOrNull()?.playbackState?.state
        return when (state) {
            PlaybackState.STATE_PLAYING -> "YouTube reports playback is playing."
            PlaybackState.STATE_PAUSED -> "YouTube reports playback is paused."
            PlaybackState.STATE_BUFFERING -> "YouTube reports playback is buffering."
            PlaybackState.STATE_STOPPED -> "YouTube reports playback is stopped."
            PlaybackState.STATE_ERROR -> "YouTube reports a playback error."
            else -> "AIVORA could not verify the current YouTube playback state."
        }
    }

    suspend fun openVideo(videoId: String): ActionResult {
        require(YouTubeVideoEvidence.videoId(videoId) == videoId)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Unlock the device before opening YouTube.")
        val uri = Uri.Builder().scheme("https").authority("www.youtube.com").appendPath("watch").appendQueryParameter("v", videoId).build()
        try {
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(YouTubeControls.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: android.content.ActivityNotFoundException) {
            return ActionResult(ExecutionStatus.NOT_SUPPORTED, "YouTube could not open the requested video.")
        }
        if (!hasNotificationAccess())
            return ActionResult(ExecutionStatus.REQUIRES_USER_INTERACTION, "Requested the YouTube video in the app. Notification access is needed to verify matching media-session playback.")
        val verified = withTimeoutOrNull(10_000) {
            while (!keyguard.isKeyguardLocked) {
                val evidence = sessions().map { session ->
                    val metadata = session.metadata
                    VideoSessionEvidence(session.packageName, session.playbackState?.state == PlaybackState.STATE_PLAYING,
                        listOf(MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_MEDIA_URI)
                            .mapNotNull { metadata?.getString(it)?.takeIf(String::isNotBlank) })
                }
                if (YouTubeVideoEvidence.playing(videoId, evidence)) return@withTimeoutOrNull true
                delay(100)
            }
            false
        } ?: false
        return if (verified && !keyguard.isKeyguardLocked) ActionResult(ExecutionStatus.SUCCESS, "YouTube's media session reports the requested video playing. Audibility and ad-free content are not verified.")
            else ActionResult(ExecutionStatus.REQUIRES_USER_INTERACTION, "Requested the YouTube video in the app. Matching video playback could not be verified; review YouTube for any required interaction.")
    }
    private fun sessions() = try {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(context, AivoraNotificationListener::class.java))
            .filter { it.packageName == YouTubeControls.PACKAGE }
    } catch (_: SecurityException) { emptyList() }
    private fun desired(operation: PlaybackControl) = if (operation == PlaybackControl.PLAY) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
    private suspend fun verified(operation: PlaybackControl, token: MediaSession.Token): Boolean = withTimeoutOrNull(2500) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        while (!keyguard.isKeyguardLocked && hasNotificationAccess()) {
            val current = sessions().singleOrNull()
            if (current?.sessionToken != token) return@withTimeoutOrNull false
            if (current.playbackState?.state == desired(operation)) return@withTimeoutOrNull true
            delay(50)
        }
        false
    } ?: false
    suspend fun execute(operation: PlaybackControl): ActionResult {
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Unlock the device before controlling YouTube.")
        val sessions = sessions()
        if (sessions.size > 1) return ActionResult(ExecutionStatus.NOT_SUPPORTED, "Multiple YouTube playback sessions are active. Choose the intended video first.")
        if (sessions.size == 1) {
            val session = sessions.single()
            if (session.playbackState?.state != desired(operation)) {
                currentCoroutineContext().ensureActive()
                if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                    return ActionResult(ExecutionStatus.PERMISSION_MISSING, "The device locked before the playback request.")
                if (operation == PlaybackControl.PLAY) session.transportControls.play() else session.transportControls.pause()
            }
            return if (verified(operation, session.sessionToken)) ActionResult(ExecutionStatus.SUCCESS, "YouTube reports playback ${if (operation == PlaybackControl.PLAY) "playing" else "paused"}.")
                else ActionResult(ExecutionStatus.PARTIAL, "The YouTube media-session request was sent, but its result could not be verified.")
        }
        if (!AivoraAccessibilityService.connected()) return ActionResult(ExecutionStatus.PERMISSION_MISSING,
            "Enable notification access for verified media-session controls, or enable AIVORA app controls in accessibility settings for the scoped fallback.")
        val launch = context.packageManager.getLaunchIntentForPackage(YouTubeControls.PACKAGE)
            ?: return ActionResult(ExecutionStatus.NOT_SUPPORTED, "YouTube is not installed.")
        withContext(Dispatchers.Main) { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        val ready = withTimeoutOrNull(2500) {
            while (!AivoraAccessibilityService.hasYouTubeWindow()) delay(50)
            true
        } ?: false
        if (!ready) return ActionResult(ExecutionStatus.NOT_SUPPORTED, "YouTube did not expose the expected foreground window. No control was pressed.")
        return AivoraAccessibilityService.youtube(operation)
    }
}
