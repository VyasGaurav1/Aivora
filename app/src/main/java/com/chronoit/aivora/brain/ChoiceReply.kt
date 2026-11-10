package com.chronoit.aivora.brain

import java.util.Locale

enum class ChoiceKind { CONTACT, DOCUMENT, TASK }
data class ChoiceRequest(val kind: ChoiceKind, val token: String, val count: Int)
data class ChoiceReply(val index: Int? = null, val pageDelta: Int? = null, val kind: ChoiceKind? = null) {
    companion object {
        private val numbers = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
        private val ordinals = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
            "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth", "twentieth")
        private val number = "(#?[0-9]{1,4}|${(numbers + ordinals).distinct().joinToString("|")})"
        private val ordinalFirst = Regex("(?:(?:choose|select|pick) )?(?:the )?$number(?: (contact|file|document|task|option|one))?")
        private val kindFirst = Regex("(?:(?:choose|select|pick) )?(contact|file|document|task|option|number)(?: number)? (?:the )?$number")
        fun parse(text: String): ChoiceReply? {
            val value = text.trim().trimEnd('.', '!', '?').lowercase(Locale.ROOT)
            when (value) {
                "read choices", "repeat choices", "read the choices" -> return ChoiceReply(pageDelta = 0)
                "next choices", "more choices", "next options" -> return ChoiceReply(pageDelta = 1)
                "previous choices", "previous options" -> return ChoiceReply(pageDelta = -1)
            }
            ordinalFirst.matchEntire(value)?.let { return ChoiceReply(index(it.groupValues[1]), kind = kind(it.groupValues[2])) }
            kindFirst.matchEntire(value)?.let { return ChoiceReply(index(it.groupValues[2]), kind = kind(it.groupValues[1])) }
            return null
        }
        private fun index(value: String): Int = value.removePrefix("#").toIntOrNull()?.minus(1)
            ?: numbers.indexOf(value).takeIf { it >= 0 } ?: ordinals.indexOf(value)
        private fun kind(value: String): ChoiceKind? = when (value) { "contact" -> ChoiceKind.CONTACT; "file", "document" -> ChoiceKind.DOCUMENT; "task" -> ChoiceKind.TASK; else -> null }
    }
}
