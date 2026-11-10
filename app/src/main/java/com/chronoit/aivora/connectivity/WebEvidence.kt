package com.chronoit.aivora.connectivity

import java.net.URI

data class WebEvidence(val title: String, val url: String, val text: String)
object WebEvidenceRanking {
    fun select(question: String, sources: List<WebEvidence>): List<WebEvidence> {
        val stop = setOf("what", "who", "why", "how", "when", "where", "the", "are", "does", "can", "explain", "about", "search", "web", "for", "tell", "please", "possible")
        val terms = Regex("[a-z]{3,}").findAll(question.lowercase(java.util.Locale.ROOT)).map { it.value }.filter { it !in stop }.toSet()
        return sources.mapIndexedNotNull { index, source ->
            val host = runCatching { URI(source.url).host.lowercase(java.util.Locale.ROOT) }.getOrNull() ?: return@mapIndexedNotNull null
            val text = (source.title + " " + source.text).lowercase(java.util.Locale.ROOT)
            val words = Regex("[a-z]{3,}").findAll(text).map { it.value.removeSuffix("s") }.toSet()
            val matches = terms.count { it.removeSuffix("s") in words }
            if (Regex("ignore (?:all |the )?(?:previous|prior) instructions|reveal (?:the |your )?(?:system prompt|api key)").containsMatchIn(text)) return@mapIndexedNotNull null
            if (source.text.length < 60 || terms.isNotEmpty() && matches == 0) return@mapIndexedNotNull null
            val authority = when {
                host.endsWith(".edu") || host.endsWith(".gov") || host.endsWith(".ac.in") -> 12
                host == "education.nationalgeographic.org" -> 10
                host == "britannica.com" || host.endsWith(".britannica.com") -> 8
                host == "wikipedia.org" || host.endsWith(".wikipedia.org") -> 4
                else -> 0
            }
            val causalQuestion = Regex("^(?:why|how)\\b", RegexOption.IGNORE_CASE).containsMatchIn(question.trim())
            val directExplanation = causalQuestion && Regex("\\b(?:cause|causes|why|how)\\b", RegexOption.IGNORE_CASE).containsMatchIn(source.title)
            source to (matches * 5 + authority + (if (directExplanation) 10 else 0) - index)
        }.sortedByDescending { it.second }.map { it.first }.distinctBy { runCatching { URI(it.url).host.removePrefix("www.") }.getOrNull() }.take(3)
    }
    fun compact(sources: List<WebEvidence>, question: String = ""): String = sources.take(2).mapIndexed { index, item ->
        val sentences = item.text.split(Regex("(?<=[.!?])\\s+"))
        val causal = question.trim().startsWith("why", true) || question.trim().startsWith("how", true)
        val ranked = if (causal) sentences.withIndex().maxByOrNull {
            Regex("\\b(?:because|when|cause|causes|due to|enough|therefore|result|results)\\b", RegexOption.IGNORE_CASE).findAll(it.value).count()
        }?.index ?: 0 else 0
        val focus = if (ranked > 0) (listOf(sentences.first()) + sentences.drop(ranked).take(2)).distinct().joinToString(" ") else item.text
        val prefix = focus.take(640)
        val end = Regex("[.!?](?: |$)").findAll(prefix).lastOrNull()?.range?.last
        val complete = if (end != null && end > 80) prefix.take(end + 1) else prefix
        "Source ${index + 1}: ${item.title.take(80)}\n$complete"
    }.joinToString("\n").take(1600)
}
