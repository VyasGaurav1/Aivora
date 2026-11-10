package com.chronoit.aivora.brain

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** Interprets reminder content separately from its time; never treats stored text as instructions. */
internal object NaturalReminder {
    sealed interface Result {
        data class Ready(val command: String) : Result
        data class NeedsTime(val content: String, val day: String? = null) : Result
    }
    fun parse(text: String, now: LocalDateTime): Result? {
        Regex("remind me (tomorrow\\b.+?|today\\b.+?|at .+?) (?:to|about) (.+)", RegexOption.IGNORE_CASE).matchEntire(text)?.let {
            return absolute(it.groupValues[2], it.groupValues[1], now) ?: Result.NeedsTime(it.groupValues[2])
        }
        val content = Regex("(?:remind me (?:to|about|of)|set (?:a |the )?reminder (?:to|for|about)) (.+)", RegexOption.IGNORE_CASE)
            .matchEntire(text)?.groupValues?.get(1) ?: return null
        Regex("(.+?) (?:in|after) (.+)", RegexOption.IGNORE_CASE).matchEntire(content)?.let {
            val command = "remind me in ${interval(it.groupValues[2])} to ${it.groupValues[1]}"
            if (CommandParser().parse(command) is ParsedCommand.Reminder) return Result.Ready(command)
        }
        Regex("(.+?) (tomorrow\\b.*|today\\b.*|at .+)", RegexOption.IGNORE_CASE).matchEntire(content)?.let {
            val body = it.groupValues[1]
            val expression = it.groupValues[2]
            val hint = Regex("\\b(?:tomorrow|today)(?: (?:morning|afternoon|evening))?\\b", RegexOption.IGNORE_CASE).find(expression)?.value
            return absolute(body, expression, now) ?: Result.NeedsTime(body, hint)
        }
        return Result.NeedsTime(content)
    }
    fun absolute(content: String, expression: String, now: LocalDateTime): Result.Ready? {
        val lower = expression.lowercase(Locale.ROOT)
        val morning = Regex("\\bmorning\\b").containsMatchIn(lower)
        val afternoon = Regex("\\b(?:afternoon|evening)\\b").containsMatchIn(lower)
        var clock = lower.replace(Regex("\\b(?:tomorrow|today|morning|afternoon|evening|in the|at|for)\\b"), " ")
            .replace(Regex(" o['’]?clock"), "").replace(Regex("\\s+"), " ").trim()
        if (!Regex("[ap]\\.? ?m\\.?$").containsMatchIn(clock)) {
            if (morning) clock += " am" else if (afternoon) clock += " pm"
        }
        val time = SpokenClockTime.parse(clock) ?: return null
        if (morning && time.hour >= 12 || afternoon && time.hour < 12) return null
        val explicitTomorrow = Regex("\\btomorrow\\b").containsMatchIn(lower)
        val explicitToday = Regex("\\btoday\\b").containsMatchIn(lower)
        val date = now.toLocalDate().plusDays(if (explicitTomorrow || !explicitToday && !time.isAfter(now.toLocalTime())) 1 else 0)
        val zone = ZoneId.systemDefault()
        val delay = Duration.between(now.atZone(zone), date.atTime(time).atZone(zone)).toMillis()
        if (delay < 1000 || delay > com.chronoit.aivora.scheduler.ScheduleTiming.MAX_DELAY) return null
        return Result.Ready("remind me in ${(delay + 999) / 1000} seconds to $content")
    }
    fun interval(text: String): String = text.replace(Regex("\\bsecs?\\b", RegexOption.IGNORE_CASE), "seconds")
        .replace(Regex("\\bmins?\\b", RegexOption.IGNORE_CASE), "minutes")
        .replace(Regex("\\bhrs?\\b", RegexOption.IGNORE_CASE), "hours")
}
