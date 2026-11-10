package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class CommandParserTest {
    private val parser = CommandParser()
    private fun action(text: String) = (parser.parse(text) as ParsedCommand.Plan).value.actions.single()
    @Test fun youtubePlaybackQuestionsReadStatusWithoutStartingPlayback() {
        assertEquals(ParsedCommand.YouTubeStatus, parser.parse("Is YouTube playing?"))
        assertEquals(ParsedCommand.YouTubeStatus, parser.parse("YouTube status"))
    }
    @Test fun replyDiscoverySelectsAnAppWithoutCreatingASendAction() {
        assertEquals(ParsedCommand.ListReplyNotifications(MessagingApp.WHATSAPP), parser.parse("List WhatsApp reply notifications"))
        assertEquals(ParsedCommand.ListReplyNotifications(MessagingApp.WHATSAPP_BUSINESS), parser.parse("List WhatsApp Business reply notifications"))
    }
    @Test fun notificationReplyKeepsTheExplicitAppTitleAndBody() {
        assertEquals(
            ParsedCommand.NotificationReply(MessagingApp.WHATSAPP_BUSINESS, "Priya", "I'll be there at 6!"),
            parser.parse("Reply to WhatsApp Business notification from Priya saying I'll be there at 6!")
        )
    }
    @Test fun permissionQuestionsUseLiveCapabilityRouting() {
        assertEquals(ParsedCommand.PermissionStatus, parser.parse("What permissions are missing?"))
        assertEquals(ParsedCommand.PermissionStatus, parser.parse("check permissions"))
        val settings = Action.OpenSettings(SettingsPanel.APP_PERMISSIONS)
        assertEquals(settings, action("open app permissions"))
        assertEquals(settings, ActionCodec.decode(ActionCodec.encode(settings)))
        val brightness = Action.OpenSettings(SettingsPanel.BRIGHTNESS_ACCESS)
        assertEquals(brightness, action("open brightness permission"))
        assertEquals(brightness, ActionCodec.decode(ActionCodec.encode(brightness)))
    }
    @Test fun parsesExplicitControls() {
        assertEquals(Action.Flashlight(true), action("Turn on the flashlight"))
        assertEquals(Action.SetVolume(40), action("volume 40%"))
        assertEquals(Action.SetBrightness(30), action("set brightness to 30 percent"))
        assertEquals(Action.Alarm(LocalTime.of(19, 30), "AIVORA alarm"), action("set alarm for 7:30 PM"))
        assertEquals(Action.Media(MediaOperation.PAUSE), action("pause music"))
    }
    @Test fun rejectsAmbiguityAndInvalidParameters() {
        listOf("volume 101", "alarm 25:00", "alarm 0 am", "delete all my photos").forEach {
            assertTrue(it, parser.parse(it) is ParsedCommand.Clarify)
        }
    }
    @Test fun emitsTypedPersonalReferencesForTheDialogueResolver() {
        assertEquals(ParsedCommand.CallPerson("Mom"), parser.parse("call Mom"))
        assertEquals(ParsedCommand.MessagePerson("Rahul", "I'll arrive soon"), parser.parse("message Rahul that I'll arrive soon"))
        assertEquals(ParsedCommand.FileReference("it", FileOperation.SHARE, "Rahul"), parser.parse("send it to Rahul"))
        assertEquals(ParsedCommand.FileReference("the blue one", FileOperation.SHARE, "him"), parser.parse("send him the blue one"))
    }
    @Test fun resolvesOnlyUniqueInstalledApp() {
        val apps = listOf(LaunchableApp("Music", "org.music.one"), LaunchableApp("Music", "org.music.two"))
        assertTrue(parser.parse("open Music", apps) is ParsedCommand.Clarify)
        val result = parser.parse("open org.music.one", apps) as ParsedCommand.Plan
        assertEquals(Action.OpenApp("org.music.one"), result.value.actions.single())
    }
    @Test fun keepsTeachingTextAndNameCase() {
        assertEquals(ParsedCommand.Rename("Friday"), parser.parse("call you Friday"))
        assertEquals(ParsedCommand.Remember("Rahul is my friend"), parser.parse("remember that Rahul is my friend"))
        assertEquals(ParsedCommand.PrivateMode(true), parser.parse("Private mode"))
        assertEquals(ParsedCommand.Cancel, parser.parse("never mind"))
    }
    @Test fun acceptsNaturalRadioWordOrderWithoutChoosingASimOrBypassingPolicy() {
        listOf("turn on mobile data", "enable cellular data", "switch the mobile data on", "Turn on the mobile data!").forEach {
            assertEquals(it, ParsedCommand.MobileData(true), parser.parse(it))
        }
        listOf("turn off the cellular data", "disable mobile data", "switch mobile data off").forEach {
            assertEquals(it, ParsedCommand.MobileData(false), parser.parse(it))
        }
        listOf("turn on wifi", "enable the wi-fi", "switch wi-fi on").forEach {
            assertEquals(it, Action.SetWifi(true), action(it))
        }
        assertEquals(Action.SetWifi(false), action("disable wifi"))
        assertEquals(RiskLevel.MEDIUM, action("turn on wifi").risk())
        listOf("don't turn on wifi", "enable mobile data on SIM two", "turn mobile data on and off",
            "turn on mobile data then send money", "disable wifi except at home").forEach {
            assertTrue(it, parser.parse(it) is ParsedCommand.Clarify)
        }
        assertEquals(ParsedCommand.MessagePerson("Rahul", "turn on mobile data"), parser.parse("message Rahul that turn on mobile data"))
    }
    @Test fun acceptsSpokenWholePercentagesAndRejectsUnboundedOrMixedValues() {
        mapOf("zero" to 0, "nineteen" to 19, "twenty" to 20, "twenty five" to 25,
            "forty-two" to 42, "ninety nine" to 99, "one hundred" to 100, "a hundred" to 100).forEach { (word, value) ->
            assertEquals(Action.SetVolume(value), action("set volume to $word percent"))
            assertEquals(Action.SetBrightness(value), action("brightness $word"))
        }
        assertEquals(Action.SetVolume(0), action("volume 0%"))
        assertEquals(Action.SetBrightness(100), action("brightness 100 percent"))
        listOf("minus one", "-1", "one hundred one", "one hundred and one", "101", "one thousand", "twenty zero",
            "twenty ten", "twenty 5", "2 five", "50.5", "fifty and pause", "twenty--five", "fifty fifty").forEach {
            assertTrue(it, parser.parse("volume $it percent") is ParsedCommand.Clarify)
        }
        assertEquals(ParsedCommand.MessagePerson("Rahul", "brightness fifty percent"), parser.parse("message Rahul that brightness fifty percent"))
    }
    @Test fun spokenValuesInLearnedRoutinesReparseIntoTheUsualValidatedPlan() {
        val phrase = LearnedPhrase.create("quiet setup", "volume twenty five then disable wifi")
        val plan = phrase.plan(parser, emptyList())!!
        assertEquals(listOf(Action.SetVolume(25), Action.SetWifi(false)), plan.actions)
        assertNull(PlanValidator.error(plan))
        assertEquals(plan.actions, LearnedPhrase.decode(phrase.encode())!!.plan(parser, emptyList())!!.actions)
        assertNull(LearnedPhrase.create("invalid", "volume one thousand").plan(parser, emptyList()))
    }

}
