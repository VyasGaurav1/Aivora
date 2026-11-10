package com.chronoit.aivora.memory

import com.chronoit.aivora.brain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemoryVocabulary(private val memory: AivoraMemory) : ReferenceVocabulary {
    private val mutex = Mutex()
    private suspend fun entries() = memory.byType(MemoryType.VOCABULARY).mapNotNull { entry ->
        entry.structuredData?.let(FileAlias::decode)?.let { entry to it }
    }
    override suspend fun lookup(scope: String, phrase: String): FileAlias? = entries().firstOrNull {
        it.second.scope == LearnedPhrase.key(scope) && it.second.phrase == normalizeReference(phrase).removePrefix("the ")
    }?.second
    override suspend fun save(alias: FileAlias): Boolean = mutex.withLock {
        val checked = FileAlias.create(alias.scope, alias.phrase, alias.documentId)
        val previous = entries().firstOrNull { it.second.scope == checked.scope && it.second.phrase == checked.phrase }?.first
        memory.remember("In project ${checked.scope}, ${checked.phrase} refers to a selected local file.", MemoryType.VOCABULARY,
            if (previous == null) MemorySource.EXPLICIT_OWNER_STATEMENT else MemorySource.OWNER_CORRECTION,
            replaces = previous?.id, structuredData = checked.encode())
    }
}
