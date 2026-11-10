package com.chronoit.aivora.brain

/** Bounded working memory for an unresolved share request; contains no execution credentials. */
class ClarificationManager(private val clock: () -> Long) {
    data class Answer(val recipient: String, val phrase: String, val filename: String)
    private data class Request(val reference: String, val recipient: String?, val project: String?, val expires: Long)
    private var pending: Request? = null
    fun ask(reference: String, recipient: String?, project: String?): String {
        pending = Request(reference, recipient, project, clock() + 120_000)
        return "Who do you mean by '${recipient ?: "the recipient"}', and what is '$reference'? " +
            if (project == null) "Select a project with 'use project' before teaching a file reference."
            else "For example: Rahul. In this project, blue one means FinalReport.pdf."
    }
    fun answer(text: String, project: String?): Answer? {
        val request = pending ?: return null
        pending = null
        if (clock() >= request.expires || project == null || request.project != project || text.length > 1000) return null
        val match = Regex("(.{1,100}?)\\.\\s+In this project,\\s+(.{1,200}?) means (?:file )?(.{1,200}?)\\.?$", RegexOption.IGNORE_CASE).matchEntire(text.trim()) ?: return null
        fun key(value: String) = normalizeReference(value).removePrefix("the ")
        if (key(match.groupValues[2]) != key(request.reference)) return null
        return Answer(match.groupValues[1].trim(), match.groupValues[2].trim(), match.groupValues[3].trim())
    }
    fun clear() { pending = null }
}
