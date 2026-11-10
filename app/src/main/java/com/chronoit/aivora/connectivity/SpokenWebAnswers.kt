package com.chronoit.aivora.connectivity

import android.content.Context
import android.util.Base64
import com.chronoit.aivora.security.FieldCipher
import com.chronoit.aivora.security.KeystoreKeys
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import com.chronoit.aivora.brain.LocalReasoningEngine
import com.chronoit.aivora.brain.LlamaLocalReasoningEngine
import com.chronoit.aivora.brain.ReasoningResult

class WebAnswerException(message: String) : IllegalStateException(message)

internal class SpokenWebAnswers(context: Context, private val summarizer: LocalReasoningEngine = LlamaLocalReasoningEngine(context)) {
    internal val requestsMade = java.util.concurrent.atomic.AtomicLong()
    @Volatile var lastQuestion: String? = null
    @Volatile internal var lastEvidence: List<WebEvidence> = emptyList()
    @Volatile internal var lastSummaryDiagnostic: String? = null
    /** Ephemeral, key-redacted provider diagnostics for explicit acceptance tests only. */
    @Volatile internal var lastFailureDetail: String? = null
    @Volatile private var lastFailureCode: String? = null
    @Volatile private var searchOnlyUntil = 0L
    private val preferences = context.getSharedPreferences("web_answer_settings", Context.MODE_PRIVATE)
    private val cipher = FieldCipher(KeystoreKeys::dataKey)
    private val keyId = "brave_answers_key"

    fun saveKey(value: String): Boolean {
        searchOnlyUntil = 0
        val key = value.trim()
        if (key.isEmpty()) return preferences.edit().remove(keyId).commit()
        if (key.length > 256 || key.any { it !in '!'..'~' }) return false
        val encoded = Base64.encodeToString(cipher.encrypt(key, keyId), Base64.NO_WRAP)
        return preferences.edit().putString(keyId, encoded).commit()
    }

    fun configured(): Boolean = preferences.contains(keyId)

    suspend fun answer(question: String, suggestions: Boolean, onSummarizing: () -> Unit = {}): String? {
        lastEvidence = emptyList()
        val stored = preferences.getString(keyId, null) ?: return null
        val key = cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP), keyId)
        // Always retrieve evidence and validate a local summary; no unchecked Answers-plan bypass.
        return searchExcerpt(question, suggestions, key, onSummarizing)
    }

    private suspend fun searchExcerpt(question: String, suggestions: Boolean, key: String, onSummarizing: () -> Unit): String {
        val query = URLEncoder.encode(question, "UTF-8")
        val base = "https://api.search.brave.com/res/v1/web/search?q=$query&count=5&country=IN&search_lang=en&safesearch=moderate"
        var contextResponse = true
        var fetched = request("https://api.search.brave.com/res/v1/llm/context?q=$query&country=IN&search_lang=en&count=5&maximum_number_of_urls=3&maximum_number_of_tokens=1024&context_threshold_mode=strict&safesearch=moderate", key)
        if (fetched.first in setOf(400, 403, 404)) {
            contextResponse = false
            fetched = request("$base&extra_snippets=true", key)
            if (fetched.first == 400 && lastFailureCode == "OPTION_NOT_IN_PLAN") fetched = request(base, key)
        }
        val (status, response) = fetched
        checkStatus(status)
        val results = if (contextResponse) response?.optJSONObject("grounding")?.optJSONArray("generic") else response?.optJSONObject("web")?.optJSONArray("results")
        fun plain(value: String) = android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
        val sources = (0 until (results?.length() ?: 0)).mapNotNull { index ->
            val item = results?.optJSONObject(index) ?: return@mapNotNull null
            val title = plain(item.optString("title")).take(150)
            val extra = item.optJSONArray(if (contextResponse) "snippets" else "extra_snippets")
            val description = (listOf(plain(item.optString("description"))) + (0 until (extra?.length() ?: 0)).map { plain(extra!!.optString(it)) }).distinct().joinToString(" ").take(2400)
            if (description.isBlank()) null else WebEvidence(title, item.optString("url"), description)
        }
        val selected = WebEvidenceRanking.select(question, sources)
        lastEvidence = selected
        if (selected.isEmpty()) throw WebAnswerException("I couldn't find sufficiently relevant sources for that question. Could you rephrase it?")
        if (suggestions) return selected.joinToString("\n") { "${it.title}. ${it.url}" }.take(4000)
        onSummarizing()
        return when (val summary = summarizer.summarize(question, WebEvidenceRanking.compact(selected, question))) {
            is ReasoningResult.Answer -> summary.text
            is ReasoningResult.Unavailable -> {
                lastSummaryDiagnostic = summary.reason + " Candidate: " + (summarizer as? LlamaLocalReasoningEngine)?.lastSummaryCandidate.orEmpty()
                throw WebAnswerException("I found sources, but couldn't produce a reliable summary. Please ask a shorter or more specific question.")
            }
        }
    }
    private fun checkStatus(status: Int) {
        if (status == 200) return
        throw WebAnswerException(when (status) {
            401 -> "Brave rejected the search key. Please check the key saved in Settings."
            403 -> "This Brave key doesn't have access. Enable a Search plan for the key in your Brave dashboard."
            400 -> if (lastFailureCode == "OPTION_NOT_IN_PLAN") "This feature isn't included in your Brave plan. Enable Search access for the key." else "Brave couldn't process this question. Please rephrase it."
            429 -> "Brave's search limit has been reached. Please try again later or check your account credits."
            else -> "Brave search is unavailable right now. Please try again shortly."
        })
    }
    private fun request(url: String, key: String, body: JSONObject? = null): Pair<Int, JSONObject?> {
        requestsMade.incrementAndGet()
        lastFailureDetail = null
        lastFailureCode = null
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false
            connection.doOutput = body != null
            connection.setRequestProperty("X-Subscription-Token", key)
            connection.setRequestProperty("Content-Type", "application/json")
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val service = if (url.contains("/llm/context")) "context" else if (body == null) "search" else "answer"
            android.util.Log.i("AivoraWeb", "Brave $service response HTTP $status")
            if (status != 200) {
                lastFailureDetail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val buffer = CharArray(4096)
                    val count = reader.read(buffer)
                    val error = if (count > 0) String(buffer, 0, count) else ""
                    if (error.contains(key.take(8))) "Provider error detail omitted because it contains credential data."
                    else error.take(2048)
                }
                lastFailureCode = runCatching { JSONObject(lastFailureDetail.orEmpty()).optJSONObject("error")?.optString("code") }.getOrNull()
                return status to null
            }
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(131_072)
                var offset = 0
                while (offset < buffer.size) {
                    val count = reader.read(buffer, offset, buffer.size - offset)
                    if (count < 0) break
                    offset += count
                }
                if (offset == buffer.size) throw WebAnswerException("The search response was too large. Please ask a shorter question.")
                String(buffer, 0, offset)
            }
            return status to JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }
}
