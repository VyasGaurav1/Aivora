package com.chronoit.aivora.brain

import java.time.LocalTime
import java.util.Locale

/** Exact clock fields only. Without AM/PM, hours use the existing 24-hour convention. */
internal object SpokenClockTime {
    data class Prefix(val time: LocalTime, val remainder: String)
    private val suffix = Regex("([ap])(?:[.]? ?m[.]?)$")
    private val numeric = Regex("([0-9]{1,2})(?::([0-9]{2}))?")
    private val vocabulary = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen " +
        "twenty thirty forty fifty sixty seventy eighty ninety hundred thousand and oh o a p m am pm").split(' ').toSet()

    fun parse(text: String): LocalTime? {
        if (text.length > 80 || text.any(Char::isISOControl)) return null
        var value = text.trim().lowercase(Locale.ROOT)
        val meridiem = suffix.find(value)
        val period = meridiem?.groupValues?.get(1)
        if (meridiem != null) value = value.substring(0, meridiem.range.first).trim()
        fun time(hour: Int, minute: Int): LocalTime? {
            if (minute !in 0..59 || hour !in (if (period == null) 0..23 else 1..12)) return null
            return LocalTime.of(if (period == null) hour else hour % 12 + if (period == "p") 12 else 0, minute)
        }
        numeric.matchEntire(value)?.let {
            return time(it.groupValues[1].toInt(), it.groupValues[2].toIntOrNull() ?: 0)
        }
        val words = value.split(' ')
        val candidates = mutableSetOf<LocalTime>()
        EnglishNumber.parse(value)?.let {
            // A complete cardinal that is an invalid hour must not become a shorter hour plus minutes.
            val wholeHour = time(it, 0) ?: return null
            candidates.add(wholeHour)
        }
        for (split in 1 until words.size) {
            val hour = EnglishNumber.parse(words.take(split).joinToString(" ")) ?: continue
            val tail = words.drop(split)
            val minute = if (tail.size == 2 && tail[0] in setOf("oh", "o", "zero")) {
                EnglishNumber.parse(tail[1])?.takeIf { it in 0..9 }
            } else EnglishNumber.parse(tail.joinToString(" "))
            if (minute != null) time(hour, minute)?.let(candidates::add)
        }
        return candidates.singleOrNull()
    }

    fun prefix(text: String): Prefix? {
        val tokens = Regex("\\S+").findAll(text).toList()
        val count = tokens.takeWhile { token ->
            val value = token.value.lowercase(Locale.ROOT)
            value.matches(Regex("[ap][.]?m[.]?")) || value.matches(Regex("[0-9]{1,3}(?::[0-9]{1,3})?(?:[ap][.]?m[.]?)?")) ||
                value.trimEnd('.').split('-').all { it in vocabulary }
        }.size
        if (count == 0 || count == tokens.size) return null
        val boundary = tokens[count].range.first
        val time = parse(text.substring(0, boundary).trimEnd()) ?: return null
        return Prefix(time, text.substring(boundary))
    }
}
