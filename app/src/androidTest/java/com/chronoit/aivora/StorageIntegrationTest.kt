package com.chronoit.aivora

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.data.*
import com.chronoit.aivora.memory.*
import com.chronoit.aivora.security.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.content.Context
import java.util.UUID
import androidx.test.core.app.ActivityScenario
import com.chronoit.aivora.ui.MainActivity
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.platform.AndroidActionExecutor
import android.media.AudioManager

@RunWith(AndroidJUnit4::class)
class StorageIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun reviewedPersonalFactDeletionRejectsChangedSnapshotAndRemovesHistory() = runBlocking {
        val name = "forget-fact-${UUID.randomUUID()}.db"
        val settings = SettingsRepository(context)
        val original = settings.settings.first().privateMode
        val database = Room.databaseBuilder(context, AivoraDatabase::class.java, name).build()
        val memory = AivoraMemory(database, settings, FieldCipher(KeystoreKeys::dataKey))
        try {
            settings.setPrivateMode(false)
            memory.remember("my favourite colour is blue")
            memory.remember("my favourite colour is green")
            memory.remember("my mother's favourite colour is red")
            val prepared = memory.prepareForgetPersonalFact("favourite colour")!!
            assertEquals(2, prepared.recordCount)
            assertEquals("green", prepared.summary)
            assertEquals(3, database.memoryDao().observeAll().first().size)
            assertTrue(runCatching { (context as AivoraApplication).scheduler.scheduleAction(prepared, "forbidden deletion", 3600000) }.isFailure)
            memory.remember("my favourite colour is gold")
            assertFalse(memory.forgetPersonalFact(prepared))
            assertEquals(4, database.memoryDao().observeAll().first().size)
            val fresh = memory.prepareForgetPersonalFact("favourite colour")!!
            assertEquals(3, fresh.recordCount)
            settings.setPrivateMode(true)
            assertTrue(memory.forgetPersonalFact(fresh))
            assertFalse(memory.forgetPersonalFact(fresh))
            assertNull(memory.prepareForgetPersonalFact("favourite colour"))
            assertTrue(memory.recall("gold").isEmpty())
            assertEquals(1, database.memoryDao().observeAll().first().size)
            assertEquals("You told me your mother's favourite colour: red", memory.answerPersonalFact("mother's favourite colour"))
        } finally { database.close(); context.deleteDatabase(name); settings.setPrivateMode(original) }
    }
    @Test fun personalFactsCorrectAtomicallyAndPrivateModePreservesPreviousAnswer() = runBlocking {
        val name = "facts-${UUID.randomUUID()}.db"
        val settings = SettingsRepository(context)
        val original = settings.settings.first().privateMode
        var database = Room.databaseBuilder(context, AivoraDatabase::class.java, name).build()
        fun memory() = AivoraMemory(database, settings, FieldCipher(KeystoreKeys::dataKey))
        try {
            settings.setPrivateMode(false)
            assertTrue(memory().remember("my favourite colour is blue"))
            assertTrue(memory().remember("my mother's favourite colour is red"))
            assertTrue(memory().remember("my favourite colour is green"))
            assertEquals("You told me your favourite colour: green", memory().answerPersonalFact("favourite colour"))
            assertEquals("You told me your mother's favourite colour: red", memory().answerPersonalFact("mother's favourite colour"))
            val current = memory().recall().single { it.content == "my favourite colour is green" }
            assertEquals(MemorySource.OWNER_CORRECTION, current.source)
            val history = database.memoryDao().observeAll().first()
            assertEquals(1, history.count { it.status == "HISTORICAL" })
            assertNotNull(history.single { it.id == current.id }.supersedes)
            assertFalse(history.any { it.encryptedContent.toString(Charsets.UTF_8).contains("green") })
            settings.setPrivateMode(true)
            assertFalse(memory().remember("my favourite colour is yellow"))
            assertEquals("You told me your favourite colour: green", memory().answerPersonalFact("favourite colour"))
            database.close()
            database = Room.databaseBuilder(context, AivoraDatabase::class.java, name).build()
            assertEquals("You told me your favourite colour: green", memory().answerPersonalFact("favourite colour"))
            memory().forget(current.id)
            assertTrue(memory().answerPersonalFact("favourite colour").startsWith("I don't have"))
            settings.setPrivateMode(false)
            assertTrue(memory().remember("my favourite colour is inferred", source = MemorySource.CONFIRMED_INFERENCE))
            assertTrue(memory().answerPersonalFact("favourite colour").startsWith("I don't have"))
        } finally { database.close(); context.deleteDatabase(name); settings.setPrivateMode(original) }
    }
    @Test fun projectVocabularyCorrectionsPrivacyEncryptionAndRecreation() = runBlocking {
        val name = "vocabulary-${UUID.randomUUID()}.db"
        val settings = SettingsRepository(context)
        val original = settings.settings.first().privateMode
        var database = Room.databaseBuilder(context, AivoraDatabase::class.java, name).build()
        fun memory() = AivoraMemory(database, settings, FieldCipher(KeystoreKeys::dataKey))
        fun alias(id: String) = com.chronoit.aivora.brain.FileAlias.create("Atlas", "blue one", id)
        try {
            settings.setPrivateMode(false)
            var vocabulary = MemoryVocabulary(memory())
            assertTrue(vocabulary.save(alias("first-document")))
            assertTrue(vocabulary.save(alias("correct-document")))
            assertEquals(1, memory().byType(MemoryType.VOCABULARY).size)
            assertNull(vocabulary.lookup("other", "blue one"))
            val stored = database.memoryDao().current(System.currentTimeMillis()).single()
            assertFalse(stored.encryptedStructuredData!!.toString(Charsets.UTF_8).contains("correct-document"))
            settings.setPrivateMode(true)
            assertFalse(vocabulary.save(alias("private-document")))
            database.close()
            database = Room.databaseBuilder(context, AivoraDatabase::class.java, name).build()
            vocabulary = MemoryVocabulary(memory())
            assertEquals("correct-document", vocabulary.lookup("ATLAS", "the blue one")!!.documentId)
            val entry = memory().byType(MemoryType.VOCABULARY).single()
            assertEquals(MemorySource.OWNER_CORRECTION, entry.source)
            memory().forget(entry.id)
            assertNull(vocabulary.lookup("atlas", "blue one"))
        } finally { database.close(); context.deleteDatabase(name); settings.setPrivateMode(original) }
    }
    @Test fun volumeExecutorVerifiesTheActualDeviceState() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val old = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            val result = AndroidActionExecutor(context).execute(Action.SetVolume(40))
            assertEquals(ExecutionStatus.SUCCESS, result.status)
            assertNotNull(result.undo)
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, old, 0) }
    }
    @Test fun launcherSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { assertEquals("com.chronoit.aivora", it.packageName) }
        }
    }
    @Test fun keystoreSurvivesProviderRecreationAndRejectsSwappedRecord() {
        val encrypted = FieldCipher(KeystoreKeys::dataKey).encrypt("private sample", "memory-1")
        assertEquals("private sample", FieldCipher(KeystoreKeys::dataKey).decrypt(encrypted, "memory-1"))
        assertTrue(runCatching { FieldCipher(KeystoreKeys::dataKey).decrypt(encrypted, "memory-2") }.isFailure)
    }
    @Test fun privateModeCorrectionAndDeletionPersistAcrossDatabaseReopen() = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"
        val settings = SettingsRepository(context)
        val original = settings.settings.first()
        var database = Room.databaseBuilder(context, AivoraDatabase::class.java, name).build()
        try {
            settings.setPrivateMode(false)
            var memory = AivoraMemory(database, settings, FieldCipher(KeystoreKeys::dataKey))
            assertTrue(memory.remember("Music app is Alpha", MemoryType.PREFERENCE))
            val first = memory.recall().single()
            assertTrue(memory.remember("Music app is Beta", MemoryType.CORRECTION, MemorySource.OWNER_CORRECTION, first.id))
            assertEquals("Music app is Beta", memory.recall().single().content)
            settings.setPrivateMode(true)
            assertFalse(memory.remember("must never persist"))
            assertEquals(1, memory.recall().size)
            database.close()
            database = Room.databaseBuilder(context, AivoraDatabase::class.java, name).build()
            memory = AivoraMemory(database, settings, FieldCipher(KeystoreKeys::dataKey))
            val current = memory.recall().single()
            assertEquals("Music app is Beta", current.content)
            val bytes = database.memoryDao().current(System.currentTimeMillis()).single().encryptedContent
            assertFalse(bytes.toString(Charsets.UTF_8).contains("Beta"))
            memory.forget(current.id)
            assertTrue(memory.recall().isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
            settings.setPrivateMode(original.privateMode)
        }
    }
    @Test fun settingsRenamePreservesPermanentApplicationIdentity() = runBlocking {
        val settings = SettingsRepository(context)
        val original = settings.settings.first()
        try {
            settings.rename("Friday")
            assertEquals("Friday", settings.settings.first().identity.wakeName)
            assertEquals("com.chronoit.aivora", context.packageName)
            assertEquals("AIVORA", context.applicationInfo.loadLabel(context.packageManager).toString())
        } finally { settings.rename(original.identity.assistantDisplayName) }
    }
}
