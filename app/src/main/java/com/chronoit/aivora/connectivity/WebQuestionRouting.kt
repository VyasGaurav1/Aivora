package com.chronoit.aivora.connectivity

/** Questions and topics go to search; unsupported phone actions are never sent as web tasks. */
object WebQuestionRouting {
    fun clarification(text: String): String? = when (text.trim().lowercase(java.util.Locale.ROOT).trimEnd('.', '?', '!')) {
        "mercury" -> "Do you mean Mercury the planet, the chemical element, or something else?"
        "python" -> "Do you mean the Python programming language or the snake?"
        "java" -> "Do you mean Java programming, the island, or coffee?"
        "apple", "jaguar" -> "Which meaning do you want? Please include a few more words."
        else -> null
    }
    fun request(text: String, previous: String?, allowOrdinary: Boolean = true): Pair<String?, Boolean>? {
        val input = text.trim().trimEnd('.', '!', '?')
        val lower = input.lowercase(java.util.Locale.ROOT)
        if (com.chronoit.aivora.brain.ConversationRouting.local(input) != null) return null
        if (lower in setOf("show suggestions", "show me suggestions")) return previous to true
        Regex("(?:search(?: google| the web| web| the internet| internet)?(?: for| about)?|google|look up|find information about|tell me about) (.+)", RegexOption.IGNORE_CASE)
            .matchEntire(input)?.let { return it.groupValues[1] to false }
        Regex("show (?:me )?suggestions (?:for|about) (.+)", RegexOption.IGNORE_CASE).matchEntire(input)?.let { return it.groupValues[1] to true }
        if (Regex("^(?:(?:can|could|would) you |are you able to |please )*(?:send|pay|call|open|turn|enable|disable|delete|install|book|order|play|take|record)\\b", RegexOption.IGNORE_CASE).containsMatchIn(input)) return null
        if (!allowOrdinary || input.isBlank() || input.length > 1600 || input.any(Char::isISOControl)) return null
        if (Regex("^(?:set|create|remind|remember|save|forget|delete|send|pay|call|open|turn|enable|disable|cancel|schedule|wake)\\b", RegexOption.IGNORE_CASE).containsMatchIn(input)) return null
        return input to false
    }
}
