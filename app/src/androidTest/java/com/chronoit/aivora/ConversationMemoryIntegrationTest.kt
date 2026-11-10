package com.chronoit.aivora

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.data.AivoraDatabase
import com.chronoit.aivora.memory.*
import com.chronoit.aivora.security.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationMemoryIntegrationTest {
    @Test fun savedReplySurvivesClosingAndReopeningTheLocalDatabase() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AivoraApplication>()
        val name = "aivora-conversation-test-${java.util.UUID.randomUUID()}.db"
        val originalPrivate = app.settings.settings.first().privateMode
        var database = Room.databaseBuilder(app, AivoraDatabase::class.java, name).build()
        fun history() = ConversationMemory(AivoraMemory(database, app.settings, FieldCipher(KeystoreKeys::dataKey)), app.settings)
        try {
            app.settings.setPrivateMode(false)
            assertTrue(history().record("public persistence fixture", "A saved reply on this phone"))
            database.close()
            database = Room.databaseBuilder(app, AivoraDatabase::class.java, name).build()
            assertEquals("A saved reply on this phone", history().repeat())
        } finally { app.settings.setPrivateMode(originalPrivate); database.close(); app.deleteDatabase(name) }
    }
    @Test fun threeCancelledPanelsReleaseVoiceOwnershipAndAcceptTheNextTicket() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AivoraApplication>()
        repeat(3) {
            val token = com.chronoit.aivora.voice.VoiceLaunchTickets.store.issue()
            val intent = android.content.Intent(app, com.chronoit.aivora.ui.VoicePanelActivity::class.java)
                .setData(android.net.Uri.parse("aivora://voice/$token"))
            val scenario = androidx.test.core.app.ActivityScenario.launch<com.chronoit.aivora.ui.VoicePanelActivity>(intent)
            try { kotlinx.coroutines.withTimeout(10000) { com.chronoit.aivora.voice.VoiceRuntime.inConversation.first { it } } }
            finally { scenario.close() }
            kotlinx.coroutines.withTimeout(10000) { com.chronoit.aivora.voice.VoiceRuntime.busy.first { !it } }
        }
    }
    @Test fun encryptedHistorySurvivesReaderRecreationIsBoundedAndCanBeCleared() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AivoraApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, AivoraDatabase::class.java).build()
        val originalPrivate = app.settings.settings.first().privateMode
        try {
            app.settings.setPrivateMode(false)
            val memory = AivoraMemory(database, app.settings, FieldCipher(KeystoreKeys::dataKey))
            val history = ConversationMemory(memory, app.settings)
            repeat(82) { assertTrue(history.record("fixture question $it", "fixture answer $it")) }
            assertEquals(80, memory.byType(MemoryType.EPISODIC).size)
            val recreated = ConversationMemory(memory, app.settings)
            assertEquals("fixture answer 81", recreated.repeat())
            assertTrue(recreated.recent().contains("fixture question 81"))
            val encrypted = database.memoryDao().current(System.currentTimeMillis()).first().encryptedContent
            assertFalse(encrypted.toString(Charsets.UTF_8).contains("fixture answer"))
            assertFalse(history.record("My password is secret", "Do not store"))
            app.settings.setPrivateMode(true)
            assertFalse(history.record("private question", "private answer"))
            assertNull(history.repeat())
            app.settings.setPrivateMode(false)
            assertEquals("fixture answer 81", history.repeat())
            history.clear()
            assertNull(history.repeat())
            assertTrue(memory.byType(MemoryType.EPISODIC).isEmpty())
        } finally { app.settings.setPrivateMode(originalPrivate); database.close() }
    }
}
