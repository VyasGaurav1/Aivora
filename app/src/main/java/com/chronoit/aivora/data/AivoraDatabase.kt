package com.chronoit.aivora.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "memories", indices = [Index("type"), Index("status")])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val type: String,
    val encryptedContent: ByteArray,
    val confidence: Double,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long = 0,
    val useCount: Int = 0,
    val sensitivity: String = "PRIVATE",
    val expiresAt: Long? = null,
    val status: String = "CURRENT",
    val supersedes: String? = null,
    val encryptedStructuredData: ByteArray? = null,
)

@Fts4
@Entity(tableName = "memory_search")
data class MemorySearchEntity(@PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Int = 0, val memoryId: String, val tokens: String)

@Entity(tableName = "documents")
data class DocumentEntity(@PrimaryKey val id: String, val encryptedMetadata: ByteArray, val updatedAt: Long)

@Fts4
@Entity(tableName = "document_search")
data class DocumentSearchEntity(@PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Int = 0, val documentId: String, val tokens: String)

@Entity(tableName = "audit_events")
data class AuditEvent(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val actionType: String,
    val result: String,
    val authorization: String,
    val confirmed: Boolean,
)

@Dao interface MemoryDao {
    @Query("SELECT memories.* FROM memories JOIN memory_search ON memories.id = memory_search.memoryId WHERE memory_search MATCH :query AND type = 'SEMANTIC' ORDER BY memories.id")
    suspend fun personalFactDeletionCandidates(query: String): List<MemoryEntity>
    @Query("SELECT memories.* FROM memories JOIN memory_search ON memories.id = memory_search.memoryId WHERE memory_search MATCH :query AND type = 'SEMANTIC' AND status = 'CURRENT' AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY updatedAt DESC")
    suspend fun personalFactCandidates(query: String, now: Long): List<MemoryEntity>
    @Query("SELECT memories.* FROM memories JOIN memory_search ON memories.id = memory_search.memoryId WHERE memory_search MATCH :query AND status = 'CURRENT' AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY updatedAt DESC LIMIT 100")
    suspend fun search(query: String, now: Long): List<MemoryEntity>
    @Query("SELECT memories.* FROM memories LEFT JOIN memory_search ON memories.id = memory_search.memoryId WHERE memory_search.rowid IS NULL LIMIT 100")
    suspend fun unindexed(): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE type = :type AND status = 'CURRENT' AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY updatedAt DESC, rowid DESC")
    suspend fun byType(type: String, now: Long): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE type = 'EPISODIC' AND source = 'CONVERSATION_SUMMARY' AND status = 'CURRENT' ORDER BY updatedAt DESC, rowid DESC LIMIT :limit")
    suspend fun conversations(limit: Int): List<MemoryEntity>
    @Query("SELECT id FROM memories WHERE type = 'EPISODIC' AND source = 'CONVERSATION_SUMMARY' AND status = 'CURRENT' ORDER BY updatedAt DESC, rowid DESC LIMIT -1 OFFSET :keep")
    suspend fun oldConversations(keep: Int): List<String>
    @Query("SELECT * FROM memories WHERE type = 'SEMANTIC' AND status = 'CURRENT' AND source IN ('EXPLICIT_OWNER_STATEMENT', 'OWNER_CORRECTION') AND encryptedStructuredData IS NULL AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY updatedAt DESC, rowid DESC LIMIT 1")
    suspend fun latestRemembered(now: Long): MemoryEntity?
    @Insert suspend fun index(entry: MemorySearchEntity)
    @Query("DELETE FROM memory_search WHERE memoryId = :id") suspend fun deleteIndex(id: String)
    @Query("DELETE FROM memory_search") suspend fun deleteAllIndex()
    @Query("SELECT * FROM memories WHERE status = 'CURRENT' AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY updatedAt DESC")
    suspend fun current(now: Long): List<MemoryEntity>
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC") fun observeAll(): Flow<List<MemoryEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(memory: MemoryEntity)
    @Query("UPDATE memories SET status = 'HISTORICAL' WHERE id = :id") suspend fun retire(id: String)
    @Query("UPDATE memories SET lastUsedAt = :now, useCount = useCount + 1 WHERE id = :id") suspend fun markUsed(id: String, now: Long)
    @Query("DELETE FROM memories WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM memories") suspend fun deleteAll()
}

@Dao interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY updatedAt DESC LIMIT 500") suspend fun all(): List<DocumentEntity>
    @Query("SELECT * FROM documents WHERE id = :id") suspend fun get(id: String): DocumentEntity?
    @Query("SELECT documents.* FROM documents JOIN document_search ON documents.id = document_search.documentId WHERE document_search MATCH :query ORDER BY updatedAt DESC LIMIT 100")
    suspend fun search(query: String): List<DocumentEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(entry: DocumentEntity)
    @Insert suspend fun index(entry: DocumentSearchEntity)
    @Query("DELETE FROM document_search WHERE documentId = :id") suspend fun deleteIndex(id: String)
    @Query("DELETE FROM document_search") suspend fun deleteAllIndex()
    @Query("DELETE FROM documents WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM documents") suspend fun deleteAll()
}

@Dao interface AuditDao {
    @Insert suspend fun insert(event: AuditEvent)
    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC LIMIT 200") fun observe(): Flow<List<AuditEvent>>
    @Query("DELETE FROM audit_events WHERE id NOT IN (SELECT id FROM audit_events ORDER BY timestamp DESC LIMIT 1000)") suspend fun prune()
}

@Database(entities = [MemoryEntity::class, AuditEvent::class, MemorySearchEntity::class, DocumentEntity::class, DocumentSearchEntity::class], version = 2, exportSchema = true)
abstract class AivoraDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun auditDao(): AuditDao
    abstract fun documentDao(): DocumentDao
    companion object {
        fun open(context: Context): AivoraDatabase = Room.databaseBuilder(
            context.applicationContext, AivoraDatabase::class.java, "aivora.db",
        ).addMigrations(MIGRATION_1_2).build()
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN encryptedStructuredData BLOB")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memory_search USING FTS4(memoryId TEXT NOT NULL, tokens TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS documents (id TEXT NOT NULL PRIMARY KEY, encryptedMetadata BLOB NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS document_search USING FTS4(documentId TEXT NOT NULL, tokens TEXT NOT NULL)")
            }
        }
    }
}
