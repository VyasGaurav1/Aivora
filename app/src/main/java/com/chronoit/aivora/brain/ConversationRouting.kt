package com.chronoit.aivora.brain

/** Resolve conversational references before deciding whether any new information is needed. */
object ConversationRouting {
    enum class Local { REPEAT, HISTORY, EXPLAIN, SIMPLIFY, SHORTEN, EXAMPLE, CORRECT, REJECT, APPROVE, MEMORY }
    fun normalize(text: String) = text.trim().lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
    fun local(text: String): Local? {
        val t = normalize(text).replace(Regex("^(?:please |can you |could you )+"), "")
        return when {
            Regex("^what (?:was|is) (?:my|your|our) (?:last|previous) (?:reply|answer)$").matches(t) -> Local.REPEAT
            Regex("^what (?:was|is) (?:my|our) (?:last|previous) question$").matches(t) -> Local.HISTORY
            Regex("^(?:what (?:did|have) i (?:tell|told|say|said)|what (?:was|is) (?:my|your|our) (?:last|previous) (?:question|reply|answer)|(?:check|access|recall|look (?:at|in)|search) (?:my|our|your) (?:saved )?memor)").containsMatchIn(t) -> Local.MEMORY
            Regex("^(?:explain|elaborate on|clarify|expand on|tell me (?:more )?about) (?:that|it|this|your (?:last )?(?:answer|reply)|the previous (?:answer|reply))(?: again| further| for me)?$").matches(t) -> Local.EXPLAIN
            t in setOf("repeat", "repeat that", "repeat it", "say it again", "say that again", "repeat your answer", "what did you say", "i didn't hear you", "i did not hear you") -> Local.REPEAT
            t in setOf("what did we talk about", "what did we discuss", "what did i ask you", "show conversation history", "recall our conversation") -> Local.HISTORY
            t in setOf("tell me more", "explain more", "explain that", "explain it", "what does that mean", "why is that", "explain your answer") -> Local.EXPLAIN
            t in setOf("explain it simply", "explain it more simply", "make it simpler", "simplify that", "use simpler words") -> Local.SIMPLIFY
            t in setOf("make it shorter", "keep it short", "keep answers short", "shorter answer", "summarize that", "summarise that") -> Local.SHORTEN
            t in setOf("give me an example", "give an example", "an example please") -> Local.EXAMPLE
            t.startsWith("the correct answer is ") || t.startsWith("actually ") || t.startsWith("correction ") -> Local.CORRECT
            t in setOf("that was wrong", "that is wrong", "that's wrong", "that was not helpful", "wrong answer") -> Local.REJECT
            t in setOf("that was helpful", "that is right", "that's right", "correct answer") -> Local.APPROVE
            t in setOf("what do you remember", "what do you remember about me", "what have you saved", "show my memories", "access my memory") -> Local.MEMORY
            else -> null
        }
    }
    fun correction(text: String): String = text.trim().replace(Regex("^(?:the correct answer is|actually|correction)\\s+", RegexOption.IGNORE_CASE), "").take(2000)
    fun memoryQuery(text: String): String = Regex("(?:about|regarding|for) (.+?)[.!?]?$", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.takeUnless { normalize(it) == "me" }.orEmpty()
    fun explicitSearch(text: String) = Regex("^(?:search\\b|google\\b|look up\\b|find information about\\b)", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())
}
