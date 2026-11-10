package com.chronoit.aivora.brain

data class FileAlias(val scope: String, val phrase: String, val documentId: String) {
    fun encode() = listOf("AIVORA_FILE_ALIAS_1", scope, phrase, documentId).joinToString("\n")
    companion object {
        fun create(scope: String, phrase: String, documentId: String): FileAlias {
            require(listOf(scope, phrase, documentId).all { it.isNotBlank() && it.length <= 200 && '\n' !in it && '\r' !in it })
            require(normalizeReference(phrase).removePrefix("the ") !in setOf("it", "that", "file"))
            return FileAlias(LearnedPhrase.key(scope), normalizeReference(phrase).removePrefix("the "), documentId)
        }
        fun decode(value: String): FileAlias? = runCatching {
            val parts = value.split('\n'); require(parts.size == 4 && parts[0] == "AIVORA_FILE_ALIAS_1")
            create(parts[1], parts[2], parts[3])
        }.getOrNull()
    }
}

interface ReferenceVocabulary {
    suspend fun lookup(scope: String, phrase: String): FileAlias?
    suspend fun save(alias: FileAlias): Boolean
}
