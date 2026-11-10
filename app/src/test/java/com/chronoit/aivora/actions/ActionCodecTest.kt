package com.chronoit.aivora.actions

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class ActionCodecTest {
    @Test fun tomorrowKeepsAbsoluteTimeAndRejectsAmbiguousDstTimes() {
        val now = java.time.ZonedDateTime.parse("2026-11-02T15:00:00+05:30[Asia/Kolkata]")
        val parser = com.chronoit.aivora.brain.CommandParser { now }
        val scheduled = parser.parse("tomorrow at 9 send Rahul FinalReport.pdf") as com.chronoit.aivora.brain.ParsedCommand.Schedule
        assertEquals(now.toLocalDate().plusDays(1).atTime(9, 0).atZone(now.zone).toInstant().toEpochMilli(), scheduled.dueAtMillis)
        assertEquals(com.chronoit.aivora.brain.ParsedCommand.FileReference("FinalReport.pdf", com.chronoit.aivora.brain.FileOperation.SHARE, "Rahul"), parser.parse(scheduled.command))
        val dst = com.chronoit.aivora.brain.CommandParser { java.time.ZonedDateTime.parse("2026-10-31T12:00:00-04:00[America/New_York]") }
        assertTrue(dst.parse("tomorrow at 1:30 volume 30") is com.chronoit.aivora.brain.ParsedCommand.Clarify)
    }
    @Test fun everySupportedActionRoundTripsWithoutExecutionAuthority() {
        val actions = listOf(Action.Flashlight(true), Action.SetVolume(30), Action.SetBrightness(40), Action.OpenApp("org.editor.app"),
            Action.YouTubeSearch("birds & trees"), Action.YouTubeVideo("dQw4w9WgXcQ"),
            Action.YouTubePlayback(com.chronoit.aivora.automation.PlaybackControl.PLAY), Action.YouTubePlayback(com.chronoit.aivora.automation.PlaybackControl.PAUSE),
            Action.Media(MediaOperation.PAUSE), Action.Dial("5550199", "12", "Rahul"), Action.DraftMessage("5550199", "Hello\nworld", "12", "Rahul"),
            Action.Alarm(LocalTime.of(9, 30), "Morning"), Action.OpenSettings(SettingsPanel.INTERNET), Action.Camera, Action.ReadNotifications,
            Action.FindFile("report"), Action.OpenFile("content://files/report", "application/pdf", "Report.pdf"),
            Action.ShareFile("content://files/report", "application/pdf", "Rahul", "Report.pdf"))
        actions.forEach { assertEquals(it, ActionCodec.decode(ActionCodec.encode(it))) }
    }
    @Test fun malformedUnknownAndExtraParametersFailClosed() {
        val encoded = ActionCodec.encode(Action.SetVolume(30))
        listOf(encoded + "\textra", encoded.replace("AIVORA_ACTION_1", "AIVORA_ACTION_2"), "shell rm anything", encoded.replace("MzA", "MTAx")).forEach {
            assertTrue(it, runCatching { ActionCodec.decode(it) }.isFailure)
        }
        assertEquals(com.chronoit.aivora.brain.ParsedCommand.Schedule(60_000, "volume 30"), com.chronoit.aivora.brain.CommandParser().parse("in 1 minute volume 30"))
    }
}
