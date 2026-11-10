package com.chronoit.aivora.automation

enum class PlaybackControl { PLAY, PAUSE }
data class SemanticNode(val path: List<Int>, val packageName: String, val resourceId: String?, val description: String?,
    val enabled: Boolean, val visible: Boolean, val clickable: Boolean, val password: Boolean,
    val className: String? = null, val checkable: Boolean = false, val checked: Boolean = false, val wifiLabel: Boolean = false, val internetLabel: Boolean = false, val doneLabel: Boolean = false,
    val mobileLabel: Boolean = false, val mobileWarningTitle: Boolean = false, val mobileWarningMessage: Boolean = false, val mobileOffLabel: Boolean = false, val mobileDataLabel: Boolean = false)
data class SemanticScreen(val packageName: String, val windowId: Int, val nodes: List<SemanticNode>, val truncated: Boolean)

/** Centralized, conservative selectors for the current YouTube playback button. */
object YouTubeControls {
    const val PACKAGE = "com.google.android.youtube"
    private const val PLAY_PAUSE_ID = "$PACKAGE:id/player_control_play_pause_replay_button"
    private fun labels(operation: PlaybackControl) = if (operation == PlaybackControl.PLAY) setOf("Play video", "Play Video") else setOf("Pause video", "Pause Video")
    fun select(screen: SemanticScreen, operation: PlaybackControl): SemanticNode? {
        if (screen.packageName != PACKAGE || screen.truncated || screen.nodes.any { it.password }) return null
        return screen.nodes.filter { it.packageName == PACKAGE && it.resourceId == PLAY_PAUSE_ID && it.description in labels(operation) &&
            it.enabled && it.visible && it.clickable && !it.password }.singleOrNull()
    }
    fun sameControl(before: SemanticScreen, selected: SemanticNode, fresh: SemanticScreen, operation: PlaybackControl): Boolean =
        before.windowId == fresh.windowId && select(fresh, operation) == selected
    fun changed(screen: SemanticScreen, operation: PlaybackControl) = select(screen, if (operation == PlaybackControl.PLAY) PlaybackControl.PAUSE else PlaybackControl.PLAY) != null
}
