package com.chronoit.aivora.memory

import com.chronoit.aivora.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Bounded encrypted text history. No ambient audio, network client or model training. */
class ConversationMemory(private val memory: AivoraMemory, private val settings: SettingsRepository) {
    data class Turn(val request: String, val answer: String)
    private val lock = Mutex()
    private suspend fun episodes(limit: Int) = memory.recentConversations(limit).filter { it.structuredData == "conversation.v1" }
    suspend fun record(request: String, answer: String): Boolean = lock.withLock {
        if (settings.settings.first().privateMode || request.isBlank() || answer.isBlank()) return@withLock false
        // Do not collect credentials even when the recognizer transcribes them.
        if (Regex("\\b(?:password|passcode|pin|otp|api key|secret key|card number)\\b", RegexOption.IGNORE_CASE).containsMatchIn(request)) return@withLock false
        val saved = memory.remember(JSONObject().put("request", request.take(1600)).put("answer", answer.take(4000)).toString(),
            MemoryType.EPISODIC, MemorySource.CONVERSATION_SUMMARY, structuredData = "conversation.v1")
        if (saved) memory.trimConversations(80)
        saved
    }
    suspend fun repeat(): String? = lock.withLock {
        if (settings.settings.first().privateMode) return@withLock null
        episodes(1).firstOrNull()?.let { JSONObject(it.content).optString("answer").takeIf(String::isNotBlank) }
    }
    suspend fun last(): Turn? = lock.withLock {
        if (settings.settings.first().privateMode) return@withLock null
        episodes(1).firstOrNull()?.let { JSONObject(it.content).let { json -> Turn(json.getString("request"), json.getString("answer")) } }
    }
    suspend fun correct(answer: String, currentRequest: String? = null): Boolean = lock.withLock {
        if (settings.settings.first().privateMode || answer.isBlank()) return@withLock false
        val request = currentRequest ?: episodes(1).firstOrNull()?.let { JSONObject(it.content).getString("request") } ?: return@withLock false
        val key = com.chronoit.aivora.brain.ConversationRouting.normalize(request)
        val old = memory.byType(MemoryType.CORRECTION).filter { it.structuredData == "answer.correction:$key" }
        memory.remember(answer.take(2000), MemoryType.CORRECTION, MemorySource.OWNER_CORRECTION,
            replaces = old.firstOrNull()?.id, structuredData = "answer.correction:$key")
    }
    suspend fun correctedAnswer(request: String): String? = lock.withLock {
        if (settings.settings.first().privateMode) return@withLock null
        val key = com.chronoit.aivora.brain.ConversationRouting.normalize(request)
        memory.byType(MemoryType.CORRECTION).firstOrNull { it.structuredData == "answer.correction:$key" }?.content
    }
    suspend fun recent(): String = lock.withLock {
        if (settings.settings.first().privateMode) return@withLock "Conversation memory is paused in private mode."
        val topics = episodes(3).map { JSONObject(it.content).optString("request").take(180) }
        if (topics.isEmpty()) "I haven't saved any conversations yet." else "Recently you asked: ${topics.joinToString(". ")}."
    }
    suspend fun find(query: String): String? = lock.withLock {
        if (settings.settings.first().privateMode || query.isBlank()) return@withLock null
        val requests = memory.recall(query).filter { it.type == MemoryType.EPISODIC && it.structuredData == "conversation.v1" }
            .take(3).map { JSONObject(it.content).optString("request").take(300) }.distinct()
        requests.takeIf { it.isNotEmpty() }?.joinToString(". ", "You previously said: ")
    }
    suspend fun clear() = lock.withLock { memory.trimConversations(0) }
    companion object {
        fun repeatRequest(text: String) = normalize(text) in setOf("repeat", "repeat that", "repeat it", "say it again", "say that again", "please repeat", "please repeat that", "can you repeat that", "what did you say", "i didn't hear you", "i did not hear you")
        fun historyRequest(text: String) = normalize(text) in setOf("what did we talk about", "what did we discuss", "what did i ask you", "show conversation history", "recall our conversation")
        fun normalize(text: String) = text.trim().lowercase(java.util.Locale.ROOT).trimEnd('.', '?', '!')
    }
}
