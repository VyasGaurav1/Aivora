package com.chronoit.aivora.brain

data class LocalDocument(val id: String, val uri: String, val name: String, val mimeType: String, val size: Long, val modifiedAt: Long, val grantUri: String)
interface DocumentDirectory {
    suspend fun find(query: String): List<LocalDocument>
    suspend fun get(id: String): LocalDocument?
    fun readable(document: LocalDocument): Boolean
}
