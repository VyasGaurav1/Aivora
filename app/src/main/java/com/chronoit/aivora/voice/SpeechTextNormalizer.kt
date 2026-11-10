package com.chronoit.aivora.voice

import java.util.Locale

/** Prevent the native lexicon frontend from silently dropping numbers or unfamiliar names. */
class SpeechTextNormalizer(private val words: Set<String>) {
    fun normalize(text: String): String {
        val expanded = Regex("[0-9]+").replace(text) { number(it.value) }.replace("%", " percent ")
        return Regex("[\\p{L}]+(?:'[\\p{L}]+)?|[.,!?:;]|[^\\p{L}\\s]").findAll(expanded.lowercase(Locale.ROOT)).map { match ->
            val word = match.value
            when {
                word in words || word in setOf(".", ",", "!", "?", ":", ";") -> word
                word.all { it in 'a'..'z' || it == '\'' } -> word.filter { it in 'a'..'z' }.map { it.toString() }.filter { it in words }.joinToString(" ").ifBlank { "word" }
                else -> " "
            }
        }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }
    private val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private fun number(value: String): String {
        val n = value.toIntOrNull()
        if (n == null || n > 9999 || (value.startsWith('0') && value.length > 1)) return value.map { small[it.digitToInt()] }.joinToString(" ")
        return when {
            n < 20 -> small[n]
            n < 100 -> tens[n / 10] + if (n % 10 > 0) " ${small[n % 10]}" else ""
            n < 1000 -> "${small[n / 100]} hundred" + if (n % 100 > 0) " ${number((n % 100).toString())}" else ""
            else -> "${small[n / 1000]} thousand" + if (n % 1000 > 0) " ${number((n % 1000).toString())}" else ""
        }
    }
}
