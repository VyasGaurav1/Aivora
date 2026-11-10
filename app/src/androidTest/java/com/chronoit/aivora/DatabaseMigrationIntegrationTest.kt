package com.chronoit.aivora

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.data.*
import com.chronoit.aivora.memory.*
import com.chronoit.aivora.security.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationIntegrationTest {
    @Test fun versionOneMemorySurvivesMigrationAndGetsProtectedFtsWithoutPlaintext() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.chronoit.aivora.data.AivoraDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val cipher = FieldCipher(KeystoreKeys::dataKey)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) legacy.execSQL(setup.getString(i))
            legacy.insertOrThrow("memories", null, ContentValues().apply {
                put("id", "legacy-1"); put("type", "SEMANTIC"); put("encryptedContent", cipher.encrypt("Rahul needs the sensitive report", "legacy-1"))
                put("confidence", 1.0); put("source", "EXPLICIT_OWNER_STATEMENT"); put("createdAt", 100L); put("updatedAt", 100L)
                put("lastUsedAt", 0L); put("useCount", 0); put("sensitivity", "PRIVATE"); put("status", "CURRENT")
            })
            legacy.version = 1
        }
        val settings = SettingsRepository(context)
        val original = settings.settings.first()
        val database = Room.databaseBuilder(context, AivoraDatabase::class.java, name).addMigrations(AivoraDatabase.MIGRATION_1_2).build()
        try {
            settings.setPrivateMode(false)
            val memory = AivoraMemory(database, settings, cipher)
            assertEquals(1, memory.recall().size)
            val found = memory.recall("sensitive report")
            val index = SecureSearchIndex(KeystoreKeys::searchKey)
            database.openHelper.readableDatabase.query("SELECT tokens FROM memory_search").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(index.content("Rahul needs the sensitive report"), cursor.getString(0))
            }
            assertEquals("Search query: ${index.query("sensitive report")}", 1, found.size)
            assertEquals("Rahul needs the sensitive report", found.single().content)
            assertEquals("legacy-1", memory.recall("Rahul").single().id)
            database.openHelper.readableDatabase.query("SELECT tokens FROM memory_search").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertFalse(cursor.getString(0).contains("sensitive", true))
            }
            assertTrue(memory.remember("a new structured preference", MemoryType.PREFERENCE, structuredData = "{\"app\":\"org.music\"}"))
            assertEquals("{\"app\":\"org.music\"}", memory.recall("structured preference").single().structuredData)
            memory.forget("legacy-1")
            assertTrue(memory.recall("Rahul").isEmpty())
        } finally { database.close(); context.deleteDatabase(name); settings.setPrivateMode(original.privateMode) }
    }
}
