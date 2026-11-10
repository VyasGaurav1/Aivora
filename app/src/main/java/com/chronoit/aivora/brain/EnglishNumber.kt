package com.chronoit.aivora.brain

import java.util.Locale

/** Exact unsigned integer grammar, 0..999999. Callers enforce their own domain limits. */
internal object EnglishNumber {
    private val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    fun parse(text: String): Int? {
        if (text.length > 150 || text.any(Char::isISOControl)) return null
        val value = text.lowercase(Locale.ROOT).trim()
        if (value.matches(Regex("[0-9]{1,6}"))) return value.toInt()
        val words = value.split(Regex("[ -]"))
        if (words.any(String::isEmpty)) return null
        val thousand = words.indexOf("thousand")
        if (thousand < 0) return belowThousand(words)
        if (words.count { it == "thousand" } != 1) return null
        val head = words.take(thousand)
        val coefficient = (if (head == listOf("a")) 1 else belowThousand(head))?.takeIf { it in 1..999 } ?: return null
        val tail = words.drop(thousand + 1)
        if (tail.isEmpty()) return coefficient * 1000
        val rest = belowThousand(tail.dropWhileLeadingAnd())?.takeIf { it in 1..999 } ?: return null
        return coefficient * 1000 + rest
    }

    private fun belowThousand(words: List<String>): Int? {
        val hundred = words.indexOf("hundred")
        if (hundred < 0) return belowHundred(words)
        if (hundred != 1 || words.count { it == "hundred" } != 1) return null
        val coefficient = (if (words[0] == "a") 1 else small.indexOf(words[0])).takeIf { it in 1..9 } ?: return null
        val tail = words.drop(2)
        if (tail.isEmpty()) return coefficient * 100
        val rest = belowHundred(tail.dropWhileLeadingAnd())?.takeIf { it in 1..99 } ?: return null
        return coefficient * 100 + rest
    }

    private fun belowHundred(words: List<String>): Int? {
        if (words.size !in 1..2) return null
        if (words.size == 1) small.indexOf(words[0]).takeIf { it >= 0 }?.let { return it }
        val ten = tens.indexOf(words[0]).takeIf { it >= 0 }?.let { (it + 2) * 10 } ?: return null
        if (words.size == 1) return ten
        return small.indexOf(words[1]).takeIf { it in 1..9 }?.let { ten + it }
    }

    private fun List<String>.dropWhileLeadingAnd() = if (firstOrNull() == "and") drop(1) else this
}
