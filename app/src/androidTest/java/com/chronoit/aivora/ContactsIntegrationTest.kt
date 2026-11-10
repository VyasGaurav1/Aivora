package com.chronoit.aivora

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.brain.*
import com.chronoit.aivora.platform.ContactsDirectory
import com.chronoit.aivora.platform.AndroidActionExecutor
import com.chronoit.aivora.actions.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContactsIntegrationTest {
    @Test fun systemContactsResolveMultipleNumbersAndExecutionRechecksChangedRecipients() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
        val name = "AIVORA Fixture ${UUID.randomUUID()}"
        var rawId: String? = null
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)
        try {
            val operations = arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, "5550199").withValue(Phone.TYPE, Phone.TYPE_MOBILE).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, "5550188").withValue(Phone.TYPE, Phone.TYPE_WORK).build(),
            )
            rawId = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)[0].uri!!.lastPathSegment
            val directory = ContactsDirectory(context)
            val candidates = directory.search(name)
            assertEquals(2, candidates.size)
            assertTrue(ContactResolver.resolve(name, candidates) is ContactResolution.Choose)
            val selected = candidates.first { it.number == "5550199" }
            assertTrue(directory.stillMatches(selected.id, selected.number))
            context.contentResolver.delete(ContactsContract.Data.CONTENT_URI, "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${Phone.NUMBER} = ?",
                arrayOf(rawId, Phone.CONTENT_ITEM_TYPE, selected.number))
            assertFalse(directory.stillMatches(selected.id, selected.number))
            val result = AndroidActionExecutor(context).execute(Action.Dial(selected.number, selected.id, selected.name))
            assertEquals(ExecutionStatus.PERMISSION_MISSING, result.status)
            assertEquals(1, directory.search(name).size)
        } finally {
            rawId?.let { context.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI, "${ContactsContract.RawContacts._ID} = ?", arrayOf(it)) }
            automation.dropShellPermissionIdentity()
        }
    }
}
