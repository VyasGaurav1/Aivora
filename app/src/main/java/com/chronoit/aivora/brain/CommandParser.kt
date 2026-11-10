package com.chronoit.aivora.brain

import com.chronoit.aivora.actions.*
import java.util.Locale

data class LaunchableApp(val label: String, val packageName: String)
sealed interface ParsedCommand {
    data class Plan(val value: ActionPlan) : ParsedCommand
    data class Clarify(val question: String) : ParsedCommand
    data class Rename(val name: String) : ParsedCommand
    data class PrivateMode(val enabled: Boolean) : ParsedCommand
    data class Remember(val content: String) : ParsedCommand
    data class Recall(val query: String) : ParsedCommand
    data class PersonalFactQuestion(val subject: String) : ParsedCommand
    data class ForgetPersonalFact(val subject: String) : ParsedCommand
    data class TeachPhrase(val phrase: String, val definition: String) : ParsedCommand
    data class ProjectContext(val name: String) : ParsedCommand
    data class TeachFileAlias(val phrase: String, val file: String) : ParsedCommand
    data class Reminder(val text: String?, val delayMillis: Long, val repeatMillis: Long = 0) : ParsedCommand
    data class ReviewScheduledAction(val id: String? = null, val next: Boolean = false) : ParsedCommand
    data object ListReminders : ParsedCommand
    data object SmsStatus : ParsedCommand
    data object YouTubeStatus : ParsedCommand
    data object PermissionStatus : ParsedCommand
    data class NotificationReply(val app: MessagingApp, val title: String, val body: String) : ParsedCommand
    data class ListReplyNotifications(val app: MessagingApp) : ParsedCommand
    data class CancelReminder(val id: String) : ParsedCommand
    data class Schedule(val delayMillis: Long, val command: String, val dueAtMillis: Long? = null, val repeatMillis: Long = 0) : ParsedCommand
    data class Conditioned(val conditions: com.chronoit.aivora.scheduler.TaskConditions, val command: String) : ParsedCommand
    data object ConnectivityStatus : ParsedCommand
    data object ConnectPendingTask : ParsedCommand
    data class MobileData(val enabled: Boolean) : ParsedCommand
    data object ConfirmAction : ParsedCommand
    data class CallPerson(val reference: String, val speakerphone: Boolean = false) : ParsedCommand
    data class MessagePerson(val reference: String, val body: String, val app: MessagingApp? = null, val draftOnly: Boolean = false) : ParsedCommand
    data class FileReference(val reference: String, val operation: FileOperation, val recipient: String? = null) : ParsedCommand
    data object Cancel : ParsedCommand
    data object InspectMemory : ParsedCommand
}

class CommandParser(private val now: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now() }) {
    fun parse(text: String, apps: List<LaunchableApp> = emptyList()): ParsedCommand {
        val raw = text.trim()
        Regex("reply to (whatsapp business|whatsapp) notification from (.{1,120}?) saying (.{1,500})", RegexOption.IGNORE_CASE)
            .matchEntire(raw)?.let { match ->
                val app = if (match.groupValues[1].equals("WhatsApp Business", true)) MessagingApp.WHATSAPP_BUSINESS else MessagingApp.WHATSAPP
                return ParsedCommand.NotificationReply(app, match.groupValues[2].trim(), match.groupValues[3].trim())
            }
        val command = raw.lowercase(Locale.ROOT).trimEnd('.', '!', '?')
        fun plan(action: Action) = ParsedCommand.Plan(ActionPlan(listOf(action), 1.0, "Recognized explicit command"))
        Regex("(?:review payment|pay) (?:([a-z0-9. -]{1,150}) (?:rupees?|rs) from )?(upi://[^ ]+) using (gpay|google pay|phonepe|phonepay)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            val requestedAmount = it.groupValues[1]
            val requestedPaise = if (requestedAmount.isEmpty()) null else {
                val amount = if (requestedAmount.matches(Regex("[0-9]{1,10}(?:[.][0-9]{1,2})?"))) {
                    requestedAmount.toBigDecimal()
                } else {
                    EnglishNumber.parse(requestedAmount)?.toBigDecimal()
                        ?: return ParsedCommand.Clarify("Give an exact amount in rupees.")
                }
                amount.movePointRight(2).longValueExact().takeIf { paise -> paise > 0 }
                    ?: return ParsedCommand.Clarify("Give a positive amount in rupees.")
            }
            val request = PaymentRequest.parse(it.groupValues[2], requestedPaise)
                ?: return ParsedCommand.Clarify("Use an INR merchant UPI link with recipient, name, merchant code and reference. Specify an exact amount if the link omits it.")
            if (requestedPaise != null && requestedPaise != request.amountPaise) {
                return ParsedCommand.Clarify("The UPI link specifies ${request.amount} rupees, which differs from the amount you requested. Use a link with the matching amount.")
            }
            val appName = it.groupValues[3]
            val app = if (appName.equals("gpay", true) || appName.equals("google pay", true)) PaymentApp.GOOGLE_PAY else PaymentApp.PHONEPE
            return plan(Action.PaymentHandoff(app, request))
        }
        radioState(command, "(?:mobile|cellular) data")?.let { return ParsedCommand.MobileData(it) }
        radioState(command, "wi-?fi")?.let { return plan(Action.SetWifi(it)) }
        if (command in setOf("stop", "cancel", "never mind")) return ParsedCommand.Cancel
        if (command in setOf("confirm action", "approve action", "confirm the action i just reviewed", "i approve the action you just described")) return ParsedCommand.ConfirmAction
        if (command in setOf("pause youtube", "pause video on youtube")) return plan(Action.YouTubePlayback(com.chronoit.aivora.automation.PlaybackControl.PAUSE))
        if (command in setOf("resume youtube", "play youtube", "resume video on youtube")) return plan(Action.YouTubePlayback(com.chronoit.aivora.automation.PlaybackControl.PLAY))
        if (command in setOf("connection status", "internet status", "why is my online task waiting")) return ParsedCommand.ConnectivityStatus
        if (command in setOf("connect for this task", "enable internet for this task")) return ParsedCommand.ConnectPendingTask
        if (command in setOf("sms status", "what is the status of my last sms", "did my last sms send")) return ParsedCommand.SmsStatus
        if (command in setOf("youtube status", "is youtube playing", "is youtube paused")) return ParsedCommand.YouTubeStatus
        Regex("(?:open|play) https://(?:youtu\\.be/|(?:www\\.|m\\.)?youtube\\.com/watch\\?v=)([A-Za-z0-9_-]{11})", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            return plan(Action.YouTubeVideo(it.groupValues[1]))
        }
        Regex("(?:search(?: for)?|find|play) (.+?) on youtube", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            return if (it.groupValues[1].length <= 300) plan(Action.YouTubeSearch(it.groupValues[1])) else ParsedCommand.Clarify("Use a YouTube query of at most 300 characters.")
        }
        Regex("every (.+?) (minute|hour|day)s?,? (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            if (it.groupValues[3].startsWith("and ", ignoreCase = true))
                return ParsedCommand.Clarify("Use a single time unit for the delay, such as sixty-five minutes.")
            val multiplier = when (it.groupValues[2].lowercase(Locale.ROOT)) { "minute" -> 60_000L; "hour" -> 3_600_000L; else -> 86_400_000L }
            val count = EnglishNumber.parse(it.groupValues[1]) ?: return ParsedCommand.Clarify("Use a whole number for the recurring interval.")
            val interval = count.toLong() * multiplier
            return if (interval in com.chronoit.aivora.scheduler.ScheduleTiming.MIN_REPEAT..com.chronoit.aivora.scheduler.ScheduleTiming.MAX_DELAY)
                ParsedCommand.Schedule(interval, it.groupValues[3], repeatMillis = interval)
                else ParsedCommand.Clarify("Recurring actions need an interval from 15 minutes to 365 days.")
        }
        Regex("when (charging|online|on unmetered network|charging and online|charging and on unmetered network), (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            val condition = it.groupValues[1].lowercase(Locale.ROOT)
            val network = when {
                "unmetered" in condition -> com.chronoit.aivora.scheduler.RequiredNetwork.UNMETERED
                "online" in condition -> com.chronoit.aivora.scheduler.RequiredNetwork.VALIDATED
                else -> com.chronoit.aivora.scheduler.RequiredNetwork.NONE
            }
            return ParsedCommand.Conditioned(com.chronoit.aivora.scheduler.TaskConditions("charging" in condition, network), it.groupValues[2])
        }
        Regex("tomorrow at (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            val clock = SpokenClockTime.prefix(it.groupValues[1])
                ?: return ParsedCommand.Clarify("Choose a valid, unambiguous clock time and an action for tomorrow.")
            val current = now()
            val local = current.toLocalDate().plusDays(1).atTime(clock.time)
            if (current.zone.rules.getValidOffsets(local).size != 1) return ParsedCommand.Clarify("That local time is ambiguous or unavailable because of a clock change. Choose another time.")
            val due = local.atZone(current.zone)
            return ParsedCommand.Schedule(java.time.Duration.between(current, due).toMillis(), clock.remainder, due.toInstant().toEpochMilli())
        }
        Regex("in (.+?) (minute|hour|day)s?,? (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            if (it.groupValues[3].startsWith("and ", ignoreCase = true))
                return ParsedCommand.Clarify("Use a single time unit for the delay, such as sixty-five minutes.")
            val multiplier = when (it.groupValues[2].lowercase(Locale.ROOT)) { "minute" -> 60_000L; "hour" -> 3_600_000L; else -> 86_400_000L }
            val count = EnglishNumber.parse(it.groupValues[1]) ?: return ParsedCommand.Clarify("Use a whole number for the delay.")
            val delay = count.toLong() * multiplier
            return if (delay in 1000..com.chronoit.aivora.scheduler.ScheduleTiming.MAX_DELAY) ParsedCommand.Schedule(delay, it.groupValues[3])
                else ParsedCommand.Clarify("Choose a delay from one minute to 365 days.")
        }
        if (command == "review due action") return ParsedCommand.ReviewScheduledAction()
        if (command in setOf("review next due action", "review next scheduled action")) return ParsedCommand.ReviewScheduledAction(next = true)
        Regex("review (?:scheduled )?action ([a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12})").matchEntire(command)?.let {
            return ParsedCommand.ReviewScheduledAction(it.groupValues[1])
        }
        if (command in setOf("list reminders", "show reminders")) return ParsedCommand.ListReminders
        Regex("cancel reminder ([a-f0-9-]{36})", RegexOption.IGNORE_CASE).matchEntire(raw)?.let { return ParsedCommand.CancelReminder(it.groupValues[1]) }
        Regex("remind me (in|after|every) (.+?) *(second|minute|hour|day)s?(?: to (.+)| (?:for|about) (?:this|that))?", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            val multiplier = when (it.groupValues[3].lowercase(Locale.ROOT)) { "second" -> 1000L; "minute" -> 60_000L; "hour" -> 3_600_000L; else -> 86_400_000L }
            val count = EnglishNumber.parse(it.groupValues[2]) ?: return ParsedCommand.Clarify("Use a whole number for the reminder interval.")
            val delay = count.toLong() * multiplier
            val repeat = if (it.groupValues[1].equals("every", true)) delay else 0L
            if (delay !in 1000..com.chronoit.aivora.scheduler.ScheduleTiming.MAX_DELAY || (repeat > 0 && repeat < com.chronoit.aivora.scheduler.ScheduleTiming.MIN_REPEAT))
                return ParsedCommand.Clarify("Use a positive delay of at most 365 days; repeating reminders need at least 15 minutes.")
            return ParsedCommand.Reminder(it.groupValues[4].ifBlank { null }, delay, repeat)
        }
        if (command in setOf("private mode", "private mode on")) return ParsedCommand.PrivateMode(true)
        if (command in setOf("private mode off", "stop private mode")) return ParsedCommand.PrivateMode(false)
        if (command in setOf("inspect memory", "what have you learned about me", "show memory")) return ParsedCommand.InspectMemory
        Regex("use project (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let { return ParsedCommand.ProjectContext(it.groupValues[1]) }
        Regex("(.+?) means file (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let { return ParsedCommand.TeachFileAlias(it.groupValues[1], it.groupValues[2]) }
        Regex("when I say (.+?), (?:do|it means) (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            return ParsedCommand.TeachPhrase(it.groupValues[1], it.groupValues[2])
        }
        Regex("(?:call you|your name is|from now on i'm calling you) (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let { return ParsedCommand.Rename(it.groupValues[1]) }
        Regex("(?:what is|what are|what's|who is|what about) my ([\\p{L}\\p{N}][\\p{L}\\p{N} '\u2019-]{0,119})", RegexOption.IGNORE_CASE).matchEntire(raw.trimEnd('?', '.', '!'))?.let { return ParsedCommand.PersonalFactQuestion(it.groupValues[1].trim()) }
        Regex("forget (?:what I told you about )?my ([\\p{L}\\p{N}][\\p{L}\\p{N} '\u2019-]{0,119})", RegexOption.IGNORE_CASE).matchEntire(raw.trimEnd('?', '.', '!'))?.let { return ParsedCommand.ForgetPersonalFact(it.groupValues[1].trim()) }
        Regex("remember (?:that )?(.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let { return ParsedCommand.Remember(it.groupValues[1]) }
        Regex("(?:what do you remember about|recall) (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let { return ParsedCommand.Recall(it.groupValues[1]) }
        when (command) {
            "flashlight on", "torch on", "turn on flashlight", "turn on the flashlight" -> return plan(Action.Flashlight(true))
            "flashlight off", "torch off", "turn off flashlight", "turn off the flashlight" -> return plan(Action.Flashlight(false))
            "play music", "resume music", "play", "resume" -> return plan(Action.Media(MediaOperation.PLAY))
            "pause music", "pause" -> return plan(Action.Media(MediaOperation.PAUSE))
            "next track", "next song" -> return plan(Action.Media(MediaOperation.NEXT))
            "previous track", "previous song" -> return plan(Action.Media(MediaOperation.PREVIOUS))
            "open settings" -> return plan(Action.OpenSettings(SettingsPanel.GENERAL))
            "check permissions", "what permissions are missing" -> return ParsedCommand.PermissionStatus
            "open security settings", "screen lock settings" -> return plan(Action.OpenSettings(SettingsPanel.DEVICE_SECURITY))
            "assistant role settings", "open default apps" -> return plan(Action.OpenSettings(SettingsPanel.DEFAULT_APPS))
            "open app permissions", "open permission settings" -> return plan(Action.OpenSettings(SettingsPanel.APP_PERMISSIONS))
            "open brightness permission", "brightness permission settings" -> return plan(Action.OpenSettings(SettingsPanel.BRIGHTNESS_ACCESS))
            "notification access settings" -> return plan(Action.OpenSettings(SettingsPanel.NOTIFICATION_ACCESS))
            "accessibility settings", "app control settings" -> return plan(Action.OpenSettings(SettingsPanel.ACCESSIBILITY))
            "internet" -> return plan(Action.OpenSettings(SettingsPanel.INTERNET))
            "bluetooth on", "bluetooth off", "open bluetooth settings" -> return plan(Action.OpenSettings(SettingsPanel.BLUETOOTH))
            "open camera", "take a photo" -> return plan(Action.Camera)
            "read notifications", "read recent notifications" -> return plan(Action.ReadNotifications)
            "list whatsapp reply notifications" -> return ParsedCommand.ListReplyNotifications(MessagingApp.WHATSAPP)
            "list whatsapp business reply notifications" -> return ParsedCommand.ListReplyNotifications(MessagingApp.WHATSAPP_BUSINESS)
        }
        Regex("draft (?:an? )?(?:sms|text|message)(?: to)? (.+?) (?:that|saying|:) (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            val recipient = it.groupValues[1].trim(); val body = it.groupValues[2].trim()
            if (recipient.contains(Regex("\\s+on\\s+", RegexOption.IGNORE_CASE))) return ParsedCommand.Clarify("Use this draft command for SMS without another messaging-app name.")
            return if (recipient.matches(Regex("\\+?[0-9 ()-]{3,30}"))) plan(Action.DraftMessage(recipient, body, draftOnly = true))
                else ParsedCommand.MessagePerson(recipient, body, draftOnly = true)
        }
        Regex("(?:message|text) (.+?) on (whatsapp(?: business)?) (?:that|saying|:) (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            val recipient = it.groupValues[1].trim()
            val app = if (it.groupValues[2].equals("whatsapp business", true)) MessagingApp.WHATSAPP_BUSINESS else MessagingApp.WHATSAPP
            val body = it.groupValues[3].trim()
            return if (recipient.matches(Regex("\\+?[0-9 ()-]{3,30}"))) {
                if (internationalPhone(recipient) == null) ParsedCommand.Clarify("Use the full international number including '+' and country code for ${app.displayName}.")
                else plan(Action.DraftAppMessage(app, recipient, body))
            } else ParsedCommand.MessagePerson(recipient, body, app)
        }
        Regex("(?:message|text) (.+?) (?:that|saying|:) (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            val recipient = it.groupValues[1].trim(); val body = it.groupValues[2].trim()
            if (recipient.contains(Regex("\\s+on\\s+", RegexOption.IGNORE_CASE))) return ParsedCommand.Clarify("That messaging app is not supported yet. Specify WhatsApp or WhatsApp Business, or omit the app for an SMS draft.")
            return if (recipient.matches(Regex("\\+?[0-9 ()-]{3,30}"))) plan(Action.DraftMessage(recipient, body)) else ParsedCommand.MessagePerson(recipient, body)
        }
        Regex("(?:send|share) (.+?) to (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            return ParsedCommand.FileReference(it.groupValues[1], FileOperation.SHARE, it.groupValues[2])
        }
        Regex("(?:send|share) (.+?) (the .+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            return ParsedCommand.FileReference(it.groupValues[2], FileOperation.SHARE, it.groupValues[1])
        }
        Regex("(?:send|share) (.+?) ([^\\s]+\\.[a-zA-Z0-9]{1,10})", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            return ParsedCommand.FileReference(it.groupValues[2], FileOperation.SHARE, it.groupValues[1])
        }
        Regex("(?:open file|open document|share file|share document) (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            return ParsedCommand.FileReference(it.groupValues[1], if (command.startsWith("share")) FileOperation.SHARE else FileOperation.OPEN)
        }
        if (command in setOf("open it", "open that", "share it", "share that")) return ParsedCommand.FileReference("it", if (command.startsWith("share")) FileOperation.SHARE else FileOperation.OPEN)
        Regex("(?:set )?(volume|brightness)(?: to)? (.+?)(?:%| percent)?").matchEntire(command)?.let {
            val percent = EnglishNumber.parse(it.groupValues[2])?.takeIf { value -> value in 0..100 }
                ?: return ParsedCommand.Clarify("Choose a whole percentage from 0 to 100.")
            return plan(if (it.groupValues[1] == "volume") Action.SetVolume(percent) else Action.SetBrightness(percent))
        }
        Regex("(?:set )?alarm(?: for)? (.+)").matchEntire(command)?.let {
            val time = SpokenClockTime.parse(it.groupValues[1])
                ?: return ParsedCommand.Clarify("What valid, unambiguous clock time should I use for the alarm?")
            return plan(Action.Alarm(time, "AIVORA alarm"))
        }
        if (command.startsWith("open ")) {
            val name = raw.substring(5).trim()
            val matches = apps.filter { it.label.equals(name, true) || it.packageName.equals(name, true) }
            return when (matches.size) {
                1 -> plan(Action.OpenApp(matches.single().packageName))
                0 -> ParsedCommand.Clarify("I couldn't find an installed app named '$name'. Use its exact launcher name.")
                else -> ParsedCommand.Clarify("More than one app has that name. Use its package name to choose.")
            }
        }
        Regex("call (.+?)( on (?:speaker(?:phone)?|loudspeaker))?", RegexOption.IGNORE_CASE).matchEntire(raw.trimEnd('.', '!', '?'))?.let {
            val number = it.groupValues[1].trim()
            val speakerphone = it.groupValues[2].isNotEmpty()
            if (!speakerphone && Regex(" on (?:speaker(?:phone)?|loudspeaker)\\b", RegexOption.IGNORE_CASE).containsMatchIn(number))
                return ParsedCommand.Clarify("Use call name or number on speaker for a speakerphone request.")
            return if (number.matches(Regex("\\+?[0-9 ()-]{3,30}"))) plan(Action.Dial(number, speakerphone = speakerphone))
            else ParsedCommand.CallPerson(number, speakerphone)
        }
        Regex("(?:find|search for) (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let { return plan(Action.FindFile(it.groupValues[1])) }
        return ParsedCommand.Clarify("I don't yet understand that command. Please explain what you want me to do.")
    }
    private fun radioState(command: String, target: String): Boolean? {
        Regex("(?:turn |switch )?(?:the )?$target (on|off)").matchEntire(command)?.let { return it.groupValues[1] == "on" }
        Regex("(?:turn|switch) (on|off) (?:the )?$target").matchEntire(command)?.let { return it.groupValues[1] == "on" }
        Regex("(enable|disable) (?:the )?$target").matchEntire(command)?.let { return it.groupValues[1] == "enable" }
        return null
    }

}
