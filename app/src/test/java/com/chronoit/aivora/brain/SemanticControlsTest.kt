package com.chronoit.aivora.brain

import com.chronoit.aivora.automation.*
import com.chronoit.aivora.actions.*
import org.junit.Assert.*
import org.junit.Test

class SemanticControlsTest {
    private val play = SemanticNode(listOf(0, 1), YouTubeControls.PACKAGE, "${YouTubeControls.PACKAGE}:id/player_control_play_pause_replay_button", "Play video", true, true, true, false)
    private fun screen(nodes: List<SemanticNode> = listOf(play)) = SemanticScreen(YouTubeControls.PACKAGE, 5, nodes, false)
    @Test fun exactUniqueControlIsSelectedAndOppositeStateVerifiesUiChangeOnly() {
        assertEquals(play, YouTubeControls.select(screen(), PlaybackControl.PLAY))
        assertNull(YouTubeControls.select(screen(), PlaybackControl.PAUSE))
        assertTrue(YouTubeControls.changed(screen(listOf(play.copy(description = "Pause video"))), PlaybackControl.PLAY))
        assertFalse(YouTubeControls.changed(screen(), PlaybackControl.PLAY))
    }
    @Test fun ambiguityWrongAppPasswordsHiddenDisabledAndTruncatedTreesFailClosed() {
        val invalid = listOf(play.copy(packageName = "com.chronoit.aivora"), play.copy(password = true), play.copy(visible = false),
            play.copy(enabled = false), play.copy(clickable = false), play.copy(resourceId = "other"), play.copy(description = "Play ad"), play.copy(description = "Reproduire"))
        invalid.forEach { assertNull(YouTubeControls.select(screen(listOf(it)), PlaybackControl.PLAY)) }
        assertNull(YouTubeControls.select(screen(listOf(play, play.copy(path = listOf(0, 2)))), PlaybackControl.PLAY))
        assertNull(YouTubeControls.select(screen().copy(packageName = "com.chronoit.aivora"), PlaybackControl.PLAY))
        assertNull(YouTubeControls.select(screen().copy(truncated = true), PlaybackControl.PLAY))
        assertNull(YouTubeControls.select(screen(listOf(play, play.copy(path = listOf(3), resourceId = "password", password = true))), PlaybackControl.PLAY))
    }
    @Test fun changedWindowOrControlPreventsAStaleClick() {
        val before = screen()
        assertTrue(YouTubeControls.sameControl(before, play, before, PlaybackControl.PLAY))
        assertFalse(YouTubeControls.sameControl(before, play, before.copy(windowId = 6), PlaybackControl.PLAY))
        assertFalse(YouTubeControls.sameControl(before, play, screen(listOf(play.copy(path = listOf(2)))), PlaybackControl.PLAY))
        assertFalse(YouTubeControls.sameControl(before, play, screen(listOf(play.copy(description = "Pause video"))), PlaybackControl.PLAY))
    }
    @Test fun playbackCommandsAreTypedAndWorkWithoutRequiringInternet() {
        val action = Action.YouTubePlayback(PlaybackControl.PAUSE)
        assertEquals(action, (CommandParser().parse("pause YouTube") as ParsedCommand.Plan).value.actions.single())
        assertEquals(Action.YouTubePlayback(PlaybackControl.PLAY), (CommandParser().parse("resume YouTube") as ParsedCommand.Plan).value.actions.single())
        assertEquals(action, ActionCodec.decode(ActionCodec.encode(action)))
        assertFalse(action.requiresNetwork())
    }
}
