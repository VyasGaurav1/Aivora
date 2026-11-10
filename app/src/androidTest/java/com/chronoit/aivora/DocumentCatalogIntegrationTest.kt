package com.chronoit.aivora

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.data.*
import com.chronoit.aivora.security.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentCatalogIntegrationTest {
    @Test fun persistedGrantCatalogSearchMissingFilePrivacyAndRevocationAreEnforced() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
        val name = "documents-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, AivoraDatabase::class.java, name).build()
        val settings = SettingsRepository(context)
        val original = settings.settings.first()
        val index = SecureSearchIndex(KeystoreKeys::searchKey)
        val catalog = LocalDocuments(context, database, settings, FieldCipher(KeystoreKeys::dataKey), index,
            localProvider = { it.authority == TestDocumentsProvider.AUTHORITY })
        try {
            context.contentResolver.call(root, "reset", null, null)
            context.contentResolver.call(root, "grant", null, null)
            val productionPolicy = LocalDocuments(context, database, settings, FieldCipher(KeystoreKeys::dataKey), index)
            assertTrue(runCatching { productionPolicy.addGrant(root) }.exceptionOrNull() is IllegalArgumentException)
            settings.setPrivateMode(true)
            assertTrue(runCatching { catalog.addGrant(root) }.isFailure)
            assertTrue(database.documentDao().all().isEmpty())
            settings.setPrivateMode(false)
            val scan = catalog.addGrant(root)
            assertEquals(2, scan.indexed)
            assertFalse(scan.truncated)
            val report = catalog.find("final report").single()
            assertEquals("FinalReport.pdf", report.name)
            assertEquals(report.id, catalog.find("final rep").single().id)
            assertTrue(catalog.readable(report))
            assertNull(productionPolicy.authorized(report.uri))
            val encrypted = database.documentDao().get(report.id)!!.encryptedMetadata
            assertFalse(encrypted.toString(Charsets.UTF_8).contains("FinalReport"))
            val recreated = LocalDocuments(context, database, settings, FieldCipher(KeystoreKeys::dataKey), SecureSearchIndex(KeystoreKeys::searchKey), { it.authority == TestDocumentsProvider.AUTHORITY })
            assertEquals(report.id, recreated.find("report").single().id)
            context.contentResolver.call(root, "remove-report", null, null)
            assertFalse(catalog.readable(report))
            val notes = catalog.find("notes").single()
            catalog.revokeGrant(notes)
            assertTrue(catalog.find("").isEmpty())
            assertNull(catalog.authorized(report.uri))
        } finally {
            runCatching { context.contentResolver.releasePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.contentResolver.call(root, "reset", null, null)
            settings.setPrivateMode(original.privateMode)
            database.close(); context.deleteDatabase(name)
        }
    }
}
