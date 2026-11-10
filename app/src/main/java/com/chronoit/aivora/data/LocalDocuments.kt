package com.chronoit.aivora.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.chronoit.aivora.security.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import kotlinx.coroutines.sync.withLock
import com.chronoit.aivora.brain.*

data class DocumentScan(val indexed: Int, val truncated: Boolean = false, val unavailableRoots: Int = 0)

class LocalDocuments(
    private val context: Context,
    private val database: AivoraDatabase,
    private val settings: SettingsRepository,
    private val cipher: FieldCipher,
    private val search: SecureSearchIndex,
    private val localProvider: (Uri) -> Boolean = { it.scheme == "content" && it.authority in LOCAL_AUTHORITIES },
) : DocumentDirectory {
    private val dao = database.documentDao()
    suspend fun addGrant(uri: Uri): DocumentScan {
        require(localProvider(uri)) { "Choose a local Android storage provider. Cloud providers are not indexed offline." }
        check(!settings.settings.first().privateMode) { "Private mode does not save a new document catalog." }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return scan(uri)
    }
    suspend fun refresh(): DocumentScan {
        check(!settings.settings.first().privateMode) { "Private mode does not update the persistent catalog." }
        var indexed = 0; var truncated = false; var unavailable = 0
        for (grant in context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && localProvider(it.uri) }) {
            try { val result = scan(grant.uri); indexed += result.indexed; truncated = truncated || result.truncated }
            catch (_: SecurityException) { unavailable++ }
            catch (_: java.io.FileNotFoundException) { unavailable++ }
        }
        return DocumentScan(indexed, truncated, unavailable)
    }
    override suspend fun find(query: String): List<LocalDocument> {
        require(query.length <= 200)
        val entries = if (query.isBlank()) dao.all() else search.fileQuery(query)?.let { dao.search(it) } ?: emptyList()
        return entries.map(::decode).filter { hasGrant(it) }
    }
    override suspend fun get(id: String): LocalDocument? = dao.get(id)?.let(::decode)?.takeIf(::hasGrant)
    suspend fun authorized(uri: String): LocalDocument? = dao.get(search.key("document:$uri"))?.let(::decode)?.takeIf { it.uri == uri && hasGrant(it) }
    override fun readable(document: LocalDocument): Boolean {
        if (!hasGrant(document)) return false
        return try { context.contentResolver.openFileDescriptor(Uri.parse(document.uri), "r")?.use { true } ?: false }
        catch (_: java.io.FileNotFoundException) { false }
        catch (_: SecurityException) { false }
    }
    suspend fun remove(id: String) = database.withTransaction { dao.deleteIndex(id); dao.delete(id) }
    suspend fun revokeGrant(document: LocalDocument) {
        context.contentResolver.releasePersistableUriPermission(Uri.parse(document.grantUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // A folder grant may cover multiple rows; permission checks hide all revoked rows immediately.
        remove(document.id)
    }
    private fun hasGrant(document: LocalDocument) = localProvider(Uri.parse(document.uri)) && context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == document.grantUri }
    private suspend fun scan(grant: Uri): DocumentScan {
        val tree = DocumentsContract.isTreeUri(grant)
        val rootId = if (tree) DocumentsContract.getTreeDocumentId(grant) else DocumentsContract.getDocumentId(grant)
        val queue = ArrayDeque<Pair<String, Int>>().apply { add(rootId to 0) }
        val visited = mutableSetOf<String>()
        var indexed = 0; var examined = 0; var truncated = false
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (examined >= 5000) { truncated = true; break }
            val (id, depth) = queue.removeFirst()
            if (!visited.add(id)) continue
            val documentUri = if (tree) DocumentsContract.buildDocumentUriUsingTree(grant, id) else grant
            val metadata = read(documentUri, grant) ?: continue
            examined++
            if (metadata.mimeType == DocumentsContract.Document.MIME_TYPE_DIR && tree) {
                if (depth >= 16) { truncated = true; continue }
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(grant, id)
                context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (queue.size + examined >= 5000) { truncated = true; break }
                        queue.add(cursor.getString(0) to depth + 1)
                    }
                }
            } else if (metadata.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
                val saved = PrivacyGate.mutex.withLock {
                    if (settings.settings.first().privateMode) return@withLock false
                    database.withTransaction {
                    dao.save(DocumentEntity(metadata.id, cipher.encrypt(encode(metadata), metadata.id), metadata.modifiedAt))
                    dao.deleteIndex(metadata.id)
                    dao.index(DocumentSearchEntity(documentId = metadata.id, tokens = search.fileContent(metadata.name)))
                    }
                    true
                }
                if (!saved) break
                indexed++
            }
        }
        return DocumentScan(indexed, truncated)
    }
    private fun read(uri: Uri, grant: Uri): LocalDocument? {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            LocalDocument(search.key("document:$uri"), uri.toString(), cursor.getString(0).orEmpty().take(512), cursor.getString(1) ?: "application/octet-stream",
                if (cursor.isNull(2)) -1 else cursor.getLong(2), if (cursor.isNull(3)) 0 else cursor.getLong(3), grant.toString())
        }
    }
    private fun encode(document: LocalDocument) = JSONObject().put("uri", document.uri).put("name", document.name).put("mime", document.mimeType)
        .put("size", document.size).put("modified", document.modifiedAt).put("grant", document.grantUri).toString()
    private fun decode(entity: DocumentEntity): LocalDocument = JSONObject(cipher.decrypt(entity.encryptedMetadata, entity.id)).let {
        LocalDocument(entity.id, it.getString("uri"), it.getString("name"), it.getString("mime"), it.getLong("size"), it.getLong("modified"), it.getString("grant")) }
    companion object {
        private val LOCAL_AUTHORITIES = setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents", "com.android.providers.media.documents")
    }
}
