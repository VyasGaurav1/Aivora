package com.chronoit.aivora.voice

import com.chronoit.aivora.brain.ChoiceKind

data class ChoiceReadback(val page: Int, val prompt: String) {
    companion object {
        const val PAGE_SIZE = 3
        fun create(kind: ChoiceKind, descriptions: List<String>, requestedPage: Int): ChoiceReadback? {
            if (descriptions.isEmpty()) return null
            val page = requestedPage.coerceIn(0, (descriptions.size - 1) / PAGE_SIZE)
            val start = page * PAGE_SIZE
            val end = minOf(start + PAGE_SIZE, descriptions.size)
            val type = when (kind) { ChoiceKind.CONTACT -> "contact"; ChoiceKind.DOCUMENT -> "file"; ChoiceKind.TASK -> "task" }
            val entries = (start until end).joinToString(". ") { index -> "${index + 1}. ${descriptions[index]}" }
            val paging = if (end < descriptions.size) " Say next choices for more." else " This is the last page."
            val previous = if (page > 0) " Say previous choices to go back." else ""
            val prompt = "There are ${descriptions.size} $type choices. Choices ${start + 1} through $end: $entries. Say choose $type number followed by its number, or cancel.$paging$previous"
            return ChoiceReadback(page, prompt).takeIf { prompt.length <= 1200 }
        }
    }
}
