package com.chronoit.aivora

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronoit.aivora.actions.Action
import com.chronoit.aivora.ui.AssistantViewModel
import com.chronoit.aivora.ui.MainActivity
import com.chronoit.aivora.voice.VerifiedVoiceCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VoiceChoiceIntegrationTest {
    @Test fun capturedChoicePagesPreserveTargetsRejectStaleRepliesAndKeepScheduledActionsScheduled() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as AivoraApplication
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.READ_CONTACTS)
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)
        val original = app.settings.settings.first()
        val beforeTasks = app.scheduler.list().map { it.id }.toSet()
        val name = "AIVORA Choice ${UUID.randomUUID()}"
        var rawId: String? = null
        val store = ViewModelStore()
        try {
            app.settings.setHandsFreeControls(true)
            app.settings.setSpokenReplies(false)
            val operations = arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build())
            (1..5).forEach { number -> operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, "+1555010010$number").withValue(Phone.TYPE, Phone.TYPE_MOBILE).build() }
            rawId = app.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)[0].uri!!.lastPathSegment
            ActivityScenario.launch(MainActivity::class.java).use {
                val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AssistantViewModel::class.java]
                model.foreground(true)
                model.resumed(true)
                withTimeout(10_000) { model.settings.first { it.handsFreeControls && !it.spokenReplies } }
                // These grants inject a verified result at the production callback boundary; this is not acoustic evidence.
                fun voice(text: String) {
                    val clock = { System.nanoTime() / 1_000_000 }
                    model.acceptVoiceCommand(VerifiedVoiceCommand(text, clock(), clock))
                }
                voice("message $name that fixture message")
                val first = withTimeout(10_000) { model.ui.first { it.contactChoices.size == 5 } }
                model.prepareVoiceCapture()
                voice("next choices")
                withTimeout(10_000) { model.ui.first { state -> state.conversation.any { it.text.contains("Choices 4 through 5") } } }
                assertEquals(first.contactChoiceToken, model.ui.value.contactChoiceToken)
                model.prepareVoiceCapture()
                voice("the second contact")
                val plan = withTimeout(10_000) { model.ui.first { it.confirmation != null } }.confirmation!!.plan
                val chosen = plan.actions.single() as Action.DraftMessage
                assertEquals(first.contactChoices[1].number, chosen.phoneNumber)
                assertEquals("fixture message", chosen.body)
                assertEquals(first.contactChoices[1].id, chosen.contactId)
                withTimeout(5_000) { model.voice.state.first { !it.capturing } }
                model.submit("call $name")
                val second = withTimeout(10_000) { model.ui.first { it.contactChoices.size == 5 && it.contactChoiceToken != first.contactChoiceToken } }
                model.prepareVoiceCapture()
                model.submit("message $name that replacement message")
                val replacement = withTimeout(10_000) { model.ui.first { it.contactChoices.size == 5 && it.contactChoiceToken != second.contactChoiceToken } }
                voice("the first contact")
                withTimeout(10_000) { model.ui.first { state -> state.conversation.lastOrNull()?.text?.startsWith("No matching choice") == true } }
                assertEquals(replacement.contactChoiceToken, model.ui.value.contactChoiceToken)
                assertNull(model.ui.value.confirmation)
                model.submit("in 10 minutes call $name")
                val scheduledChoice = withTimeout(10_000) { model.ui.first { it.contactChoices.size == 5 && it.contactChoiceToken != replacement.contactChoiceToken } }
                model.submit("the second contact")
                withTimeout(10_000) { model.ui.first { state -> state.conversation.lastOrNull()?.text?.startsWith("Action scheduled for review") == true } }
                val scheduled = app.scheduler.list().single { it.id !in beforeTasks && it.text.contains(name) }
                assertTrue(scheduled.hasAction)
                assertTrue(scheduled.dueAt > System.currentTimeMillis() + 8 * 60_000)
                assertNull(model.ui.value.confirmation)
                assertNotEquals(replacement.contactChoiceToken, scheduledChoice.contactChoiceToken)
                model.cancel()
            }
        } finally {
            store.clear()
            app.scheduler.list().filter { it.id !in beforeTasks && it.text.contains(name) }.forEach { app.scheduler.cancel(it.id) }
            rawId?.let { app.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI, "${ContactsContract.RawContacts._ID} = ?", arrayOf(it)) }
            app.settings.setHandsFreeControls(original.handsFreeControls)
            app.settings.setSpokenReplies(original.spokenReplies)
            automation.dropShellPermissionIdentity()
        }
    }
}
