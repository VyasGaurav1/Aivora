package com.chronoit.aivora.brain

sealed interface ReasoningResult {
    data class Answer(val text: String) : ReasoningResult
    data class Unavailable(val reason: String) : ReasoningResult
}
interface LocalReasoningEngine {
    suspend fun classifyRequest(text: String): String? = null
    suspend fun answer(question: String): ReasoningResult
    suspend fun summarize(question: String, evidence: String): ReasoningResult = ReasoningResult.Unavailable("Local summarization is unavailable.")
}

internal class ReasoningFollowUp(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var previous: Pair<String, Long>? = null

    fun instruction(text: String): String? =
        when (text.trim().trimEnd('.', '?', '!').lowercase(java.util.Locale.ROOT)) {
            "explain more", "tell me more" -> "Explain in more detail"
            "explain it more simply", "make it simpler" -> "Explain in simpler words"
            "give me an example" -> "Explain with one concrete example"
            else -> null
        }

    fun resolve(instruction: String): Pair<String, String>? {
        val (topic, savedAt) = previous ?: return null
        if (clock() - savedAt >= 120_000) return null
        val question = "$instruction: $topic"
        return if (ReasoningQuestions.eligible(question)) question to topic else null
    }

    fun remember(topic: String) { previous = topic to clock() }
    fun clear() { previous = null }
}
object ReasoningQuestions {
    private val completionClaim = Regex("\\b(?:i|we)(?:['’]ve| have)?\\s+(?:successfully\\s+|just\\s+)?(?:sent|called|paid|unlocked|enrolled|authenticated|executed|turned|changed|deleted|opened|completed)\\b|\\b(?:enrollment|authentication|recognition|payment|message|call|transfer)\\s+(?:was|is|has been)\\s+(?:successful|completed|verified|sent|delivered)\\b", RegexOption.IGNORE_CASE)
    fun safeAnswer(text: String): Boolean = !completionClaim.containsMatchIn(text)
    fun eligible(text: String): Boolean = text.length in 3..1600 && text.none { it.isISOControl() && it != '\n' } &&
        Regex("(?:what|why|how|who|where|when|explain|tell me about)\\b.+", RegexOption.IGNORE_CASE).matches(text.trim())
    fun prompt(question: String): String {
        require(eligible(question))
        val safe = question.replace("<|", "< |")
        return "<|im_start|>system\nYou are AIVORA. Answer briefly in English as JSON with a reply string. No personal memory or device state is provided. You cannot perform phone actions. Never invent facts, action results, or verification.\n<|im_end|>\n<|im_start|>user\n$safe /no_think\n<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n"
    }
}
