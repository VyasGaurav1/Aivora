package com.chronoit.aivora

import android.Manifest
import android.content.Context
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import java.util.UUID
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.telecom.TelecomManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.actions.*
import com.chronoit.aivora.platform.AndroidVoiceCallMonitor
import com.chronoit.aivora.ui.AssistantViewModel
import com.chronoit.aivora.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit known-image emulator probe only. Never run on physical phones or claim PSTN connection. */
@RunWith(AndroidJUnit4::class)
class DirectCallProbe {
    @Test fun confirmedRequestReachesEmulatedTelecomAndIsNotReportedConnected(): Unit = runBlocking {
        require(InstrumentationRegistry.getArguments().getString("directCallProbe") == "true")
        require(Build.HARDWARE in setOf("ranchu", "goldfish") && "x86_64" in Build.SUPPORTED_ABIS && Build.VERSION.SDK_INT in setOf(30, 35))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun shell(command: String): String = automation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
        require(shell("getprop ro.kernel.qemu").trim() == "1")
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val telecom = app.getSystemService(TelecomManager::class.java)
        val original = app.settings.settings.first()
        automation.grantRuntimePermission(app.packageName, Manifest.permission.READ_PHONE_STATE)
        automation.grantRuntimePermission(app.packageName, Manifest.permission.CALL_PHONE)
        automation.grantRuntimePermission(app.packageName, Manifest.permission.READ_CONTACTS)
        require(!telecom.isInCall)
        val target = requireNotNull(AndroidVoiceCallMonitor(app).selection()) { "Known emulator must expose one ready default voice SIM/account" }.target
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var model: AssistantViewModel
        var initialized = false
        var rawId: String? = null
        var phoneUri: android.net.Uri? = null
        val name = "AIVORA calling fixture ${UUID.randomUUID()}"
        fun <T> fixtureWrite(block: () -> T): T {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)
            try { return block() } finally { automation.dropShellPermissionIdentity() }
        }
        try {
            fixtureWrite {
                val inserted = app.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build(),
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, "+12025550123").withValue(Phone.TYPE, Phone.TYPE_MOBILE).build()
                ))
                rawId = inserted[0].uri!!.lastPathSegment
                phoneUri = inserted[2].uri!!
            }
            val contact = app.contacts.search(name).single()
            assertEquals("+12025550123", contact.number)
            app.settings.setDirectCalling(true)
            app.settings.setSpokenReplies(false)
            app.settings.setHandsFreeControls(false)
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AssistantViewModel::class.java] }
            initialized = true
            withTimeout(10000) { model.settings.first { it.directCalling && !it.spokenReplies && !it.handsFreeControls } }
            withTimeout(10000) { while (!app.visibility.foreground.value) delay(50) }
            suspend fun requestReview(): Confirmation {
                model.submit("call $name on speaker")
                val auth = withTimeout(10000) { model.ui.first { it.authentication != null } }.authentication!!
                assertEquals(Action.PlaceCall("+12025550123", target, contact.id, contact.name, speakerphone = true), auth.plan.actions.single())
                assertFalse(telecom.isInCall)
                // Trusted callback injection verifies routing only, not real Android credential acceptance.
                model.authenticated(auth.token)
                val review = withTimeout(10000) { model.ui.first { it.confirmation != null } }.confirmation!!
                assertNotEquals(auth.token, review.token)
                assertFalse(telecom.isInCall)
                return review
            }
            val stale = requestReview()
            fixtureWrite { assertEquals(1, app.contentResolver.update(phoneUri!!, ContentValues().apply { put(Phone.NUMBER, "+12025550124") }, null, null)) }
            model.confirm()
            withTimeout(10000) { model.ui.first { state -> state.conversation.any { it.text.contains("contact changed", ignoreCase = true) } } }
            assertFalse(telecom.isInCall)
            android.util.Log.i("AIVORADirectCallProbe", "Contact number changed after review; confirmation was refused before an emulated call.")
            fixtureWrite { assertEquals(1, app.contentResolver.update(phoneUri!!, ContentValues().apply { put(Phone.NUMBER, "+12025550123") }, null, null)) }
            val fresh = requestReview()
            assertNotEquals(stale.token, fresh.token)
            model.confirm()
            val result = withTimeout(15000) { model.ui.first { state -> state.conversation.any { it.text.startsWith("Requested the call") } } }
            assertTrue(result.conversation.last().text.contains("have not been verified"))
            withTimeout(10000) { while (!telecom.isInCall) delay(50) }
            assertNull(AndroidVoiceCallMonitor(app).selection())
            val audio = app.getSystemService(AudioManager::class.java)
            @Suppress("DEPRECATION")
            fun speakerReported() = if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else audio.isSpeakerphoneOn
            withTimeout(10000) { while (!speakerReported()) delay(50) }
            android.util.Log.i("AIVORADirectCallProbe", "Android reports the speaker communication route for the explicit speakerphone request. This does not prove physical audibility.")
            android.util.Log.i("AIVORADirectCallProbe", "After injected authentication and exact confirmation, Telecom observed an emulated call; active-call guard refused another selection. No carrier connection or answer is proven.")
        } finally {
            shell("input keyevent KEYCODE_ENDCALL")
            withTimeout(10000) { while (telecom.isInCall) delay(50) }
            if (initialized) model.cancel()
            app.settings.setDirectCalling(original.directCalling)
            app.settings.setSpokenReplies(original.spokenReplies)
            app.settings.setHandsFreeControls(original.handsFreeControls)
            rawId?.let { id -> fixtureWrite { app.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI, "${ContactsContract.RawContacts._ID} = ?", arrayOf(id)) } }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
