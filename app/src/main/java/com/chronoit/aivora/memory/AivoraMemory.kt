package com.chronoit.aivora.memory

import androidx.room.withTransaction
import com.chronoit.aivora.data.*
import com.chronoit.aivora.security.FieldCipher
import com.chronoit.aivora.security.SecureSearchIndex
import com.chronoit.aivora.security.KeystoreKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import com.chronoit.aivora.brain.PersonalFact
import com.chronoit.aivora.actions.Action

enum class MemoryType { WORKING, EPISODIC, SEMANTIC, VOCABULARY, PREFERENCE, HABIT, PROCEDURAL, RELATIONSHIP, CORRECTION, ROUTINE }
enum class MemorySource { EXPLICIT_OWNER_STATEMENT, OWNER_CORRECTION, CONFIRMED_INFERENCE, OBSERVED_PATTERN, CONVERSATION_SUMMARY, SYSTEM_EVENT }
data class Memory(val id: String, val type: MemoryType, val content: String, val source: MemorySource, val confidence: Double, val updatedAt: Long, val status: String, val structuredData: String? = null)

class AivoraMemory(private val database: AivoraDatabase, private val settings: SettingsRepository, private val cipher: FieldCipher,
    private val searchIndex: SecureSearchIndex = SecureSearchIndex(KeystoreKeys::searchKey)) {
    private val dao = database.memoryDao()
    private val indexMutex = Mutex()
    suspend fun remember(content: String, type: MemoryType = MemoryType.SEMANTIC, source: MemorySource = MemorySource.EXPLICIT_OWNER_STATEMENT, replaces: String? = null, structuredData: String? = null): Boolean = PrivacyGate.mutex.withLock {
        require(content.isNotBlank() && content.length <= 10_000)
        require(structuredData == null || structuredData.length <= 50_000)
        if (settings.settings.first().privateMode) return@withLock false
        val fact = if (type == MemoryType.SEMANTIC && structuredData == null && replaces == null && source in ownerSources) PersonalFact.parse(content) else null
        val previousFacts = if (fact == null) emptyList() else factCandidates(fact.subject).filter { PersonalFact.parse(it.content)?.key == fact.key }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = MemoryEntity(id, type.name, cipher.encrypt(content, id),
            if (source in setOf(MemorySource.EXPLICIT_OWNER_STATEMENT, MemorySource.OWNER_CORRECTION)) 1.0 else 0.7,
            (if (previousFacts.isNotEmpty()) MemorySource.OWNER_CORRECTION else source).name, now, now, supersedes = replaces ?: previousFacts.firstOrNull()?.id, encryptedStructuredData = structuredData?.let { cipher.encrypt(it, "$id:structure") })
        database.withTransaction {
            replaces?.let { dao.retire(it) }
            previousFacts.forEach { dao.retire(it.id) }
            dao.insert(entity)
            dao.index(MemorySearchEntity(memoryId = id, tokens = searchIndex.content(content)))
        }
        true
    }
    suspend fun recall(query: String = ""): List<Memory> {
        if (query.isBlank()) return dao.current(System.currentTimeMillis()).take(100).map(::decode)
        ensureIndex()
        val match = searchIndex.query(query) ?: return emptyList()
        return dao.search(match, System.currentTimeMillis()).map(::decode)
    }
    suspend fun byType(type: MemoryType): List<Memory> = dao.byType(type.name, System.currentTimeMillis()).map(::decode)
    suspend fun recentConversations(limit: Int): List<Memory> = dao.conversations(limit.coerceIn(1, 80)).map(::decode)
    suspend fun trimConversations(keep: Int) = database.withTransaction {
        require(keep in 0..80)
        dao.oldConversations(keep).forEach { dao.deleteIndex(it); dao.delete(it) }
    }
    suspend fun latestRememberedText(): String? = PrivacyGate.mutex.withLock {
        if (settings.settings.first().privateMode) return@withLock null
        dao.latestRemembered(System.currentTimeMillis())?.let(::decode)?.content
    }
    private val ownerSources = setOf(MemorySource.EXPLICIT_OWNER_STATEMENT, MemorySource.OWNER_CORRECTION)
    private suspend fun factCandidates(subject: String): List<Memory> {
        ensureIndex()
        val match = searchIndex.query(PersonalFact.normalize(subject)) ?: return emptyList()
        return dao.personalFactCandidates(match, System.currentTimeMillis()).map(::decode)
            .filter { it.source in ownerSources && it.confidence == 1.0 && it.structuredData == null }
    }
    suspend fun answerPersonalFact(subject: String): String = PersonalFact.answer(subject, factCandidates(subject).map { it.content })
    private suspend fun deletionRecords(subject: String): List<Memory> {
        val query = searchIndex.query(PersonalFact.normalize(subject)) ?: return emptyList()
        return dao.personalFactDeletionCandidates(query).distinctBy { it.id }.map(::decode).filter {
            it.source in ownerSources && it.confidence == 1.0 && it.structuredData == null && PersonalFact.parse(it.content)?.key == PersonalFact.normalize(subject)
        }
    }
    private fun deletionAction(subject: String, records: List<Memory>): Action.ForgetPersonalFact? {
        if (records.isEmpty()) return null
        val summary = records.filter { it.status == "CURRENT" }.mapNotNull { PersonalFact.parse(it.content)?.value }.distinct().joinToString("; ")
            .ifEmpty { "No current value; historical records only" }
        require(summary.length <= 2000) { "These facts need a narrower on-screen memory review." }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        records.sortedBy { it.id }.forEach { record ->
            listOf(record.id, record.status, record.source.name, record.updatedAt.toString(), record.content).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
        }
        return Action.ForgetPersonalFact(subject, summary, records.size, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    suspend fun prepareForgetPersonalFact(subject: String): Action.ForgetPersonalFact? = PrivacyGate.mutex.withLock {
        ensureIndex()
        deletionAction(subject, deletionRecords(subject))
    }
    suspend fun forgetPersonalFact(action: Action.ForgetPersonalFact): Boolean = PrivacyGate.mutex.withLock {
        ensureIndex()
        database.withTransaction {
            val records = deletionRecords(action.subject)
            if (deletionAction(action.subject, records) != action) return@withTransaction false
            records.forEach { dao.deleteIndex(it.id); dao.delete(it.id) }
            check(deletionRecords(action.subject).isEmpty())
            true
        }
    }
    suspend fun forget(id: String) = database.withTransaction { dao.deleteIndex(id); dao.delete(id) }
    suspend fun forgetAll() = database.withTransaction { dao.deleteAllIndex(); dao.deleteAll() }
    private fun decode(entity: MemoryEntity) = Memory(entity.id, MemoryType.valueOf(entity.type), cipher.decrypt(entity.encryptedContent, entity.id),
        MemorySource.valueOf(entity.source), entity.confidence, entity.updatedAt, entity.status,
        entity.encryptedStructuredData?.let { cipher.decrypt(it, "${entity.id}:structure") })
    private suspend fun ensureIndex() = indexMutex.withLock {
        // Migration cannot access Keystore plaintext. Backfill old records locally on first search.
        while (true) {
            val records = dao.unindexed()
            if (records.isEmpty()) break
            database.withTransaction { records.forEach { entry ->
                dao.deleteIndex(entry.id)
                dao.index(MemorySearchEntity(memoryId = entry.id, tokens = searchIndex.content(cipher.decrypt(entry.encryptedContent, entry.id))))
            } }
        }
    }
}
