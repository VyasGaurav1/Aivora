package com.chronoit.aivora.brain

import java.text.Normalizer
import java.util.Locale

data class PersonalFact(val subject: String, val value: String) {
    val key: String get() = normalize(subject)
    companion object {
        private val statement = Regex("my ([\\p{L}\\p{N}][\\p{L}\\p{N} '\u2019-]{0,119}?) (?:is|are) (.+)", RegexOption.IGNORE_CASE)
        fun normalize(subject: String): String = Normalizer.normalize(subject, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
        fun parse(content: String): PersonalFact? {
            if (content.length > 2200 || content.any(Char::isISOControl)) return null
            val match = statement.matchEntire(content.trim()) ?: return null
            return PersonalFact(match.groupValues[1].trim(), match.groupValues[2].trim()).takeIf { it.value.isNotEmpty() && it.value.length <= 2000 }
        }
        fun answer(subject: String, statements: List<String>): String {
            val matches = statements.mapNotNull(::parse).filter { it.key == normalize(subject) }
            if (matches.isEmpty()) return "I don't have a saved personal fact about your $subject. You can say remember that my $subject is, followed by the value."
            if (matches.map { it.value }.distinct().size != 1) return "I have conflicting saved values for your $subject. Tell me the current value with remember that my $subject is, followed by the value."
            return "You told me your ${matches.first().subject}: ${matches.first().value}"
        }
    }
}
