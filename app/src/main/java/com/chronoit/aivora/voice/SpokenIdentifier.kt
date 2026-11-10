package com.chronoit.aivora.voice

/** Explicit character spelling, independent of prose normalization. Unsupported text fails closed. */
object SpokenIdentifier {
    private val letters = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november oscar papa quebec romeo sierra tango uniform victor whiskey".split(' ') + listOf("x ray", "yankee", "zulu")
    private val digits = "zero one two three four five six seven eight nine".split(' ')
    private val punctuation = mapOf(32 to "space", 33 to "exclamation mark", 34 to "double quote", 35 to "hash", 36 to "dollar",
        37 to "percent", 38 to "ampersand", 39 to "apostrophe", 40 to "left parenthesis", 41 to "right parenthesis", 42 to "asterisk",
        43 to "plus", 44 to "comma", 45 to "hyphen", 46 to "dot", 47 to "slash", 58 to "colon", 59 to "semi colon",
        60 to "less than", 61 to "equals", 62 to "greater than", 63 to "question mark", 64 to "at", 91 to "left square bracket",
        92 to "backslash", 93 to "right square bracket", 94 to "up arrow", 95 to "underscore", 96 to "grave accent",
        123 to "left brace", 124 to "vertical bar", 125 to "right brace", 126 to "wave sign")
    fun render(value: String): String? {
        if (value.length > 2048 || value.any { it.code !in 32..126 }) return null
        return value.map { char -> when (char) {
            in 'a'..'z' -> letters[char - 'a']
            in 'A'..'Z' -> "capital " + letters[char - 'A']
            in '0'..'9' -> digits[char - '0']
            else -> requireNotNull(punctuation[char.code])
        } }.joinToString(" ")
    }
}
