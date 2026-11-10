package com.chronoit.aivora

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chronoit.aivora.actions.Action
import com.chronoit.aivora.data.SmsDatabase
import com.chronoit.aivora.platform.*
import com.chronoit.aivora.security.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic status receipts only. No SMS permission, radio request or actual submission. */
@RunWith(AndroidJUnit4::class)
class SmsJournalIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val action = Action.SendSms("+12025550123", "Journal fixture not transmitted", SmsTarget(1, 0, 1, "Fixture SIM"))
    @Test fun encryptedRequestsSurviveReopenAndFirstTerminalResultWins(): Unit = runBlocking {
        assertNotEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.SEND_SMS))
        val name = "sms-journal-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, SmsDatabase::class.java, name).build()
        fun journal() = SmsJournal(database, FieldCipher(KeystoreKeys::dataKey))
        try {
            val id = journal().begin(action)
            val stored = database.attempts().get(id)!!
            assertEquals("REQUESTED", stored.state)
            assertFalse(stored.encryptedAction.toString(Charsets.UTF_8).contains(action.body))
            assertTrue(journal().latestDescription().contains("Submission is uncertain"))
            database.close()
            database = Room.databaseBuilder(context, SmsDatabase::class.java, name).build()
            assertEquals("REQUESTED", database.attempts().get(id)!!.state)
            journal().unknown(id)
            assertTrue(journal().sentResult(id, Activity.RESULT_OK))
            journal().unknown(id)
            assertFalse(journal().sentResult(id, 5))
            assertEquals("SUBMITTED", database.attempts().get(id)!!.state)
            assertTrue(journal().latestDescription().contains("Recipient delivery is unverified"))
            val stopped = journal().begin(action)
            journal().notRequested(stopped)
            assertFalse(journal().sentResult(stopped, Activity.RESULT_OK))
            assertEquals("NOT_REQUESTED", database.attempts().get(stopped)!!.state)
            val failed = journal().begin(action)
            assertTrue(journal().sentResult(failed, 5))
            assertFalse(journal().sentResult(failed, Activity.RESULT_OK))
            assertEquals("FAILED_REPORTED", database.attempts().get(failed)!!.state)
            val rolledBack = UUID.randomUUID().toString()
            database.attempts().insert(stored.copy(id = rolledBack, createdAt = 1))
            assertEquals(rolledBack, database.attempts().latest()!!.id)
            database.attempts().delete(rolledBack)
            database.attempts().insert(stored.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis() + 10000))
            assertTrue(runCatching { journal().latestDescription() }.isFailure)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun privateSentReceiverPersistsReceiptWithoutLiveObserver(): Unit = runBlocking {
        val app = context as AivoraApplication
        assertNotEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.SEND_SMS))
        val id = app.smsJournal.begin(action)
        var receipt: PendingIntent? = null
        try {
            // No SmsSentResults.register: represents the original in-process observer being absent.
            receipt = PendingIntent.getBroadcast(app, 0, Intent(app, SmsSentReceiver::class.java).setAction("${app.packageName}.SMS_SENT.$id"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            receipt.send(app, Activity.RESULT_OK, null)
            withTimeout(10000) { while (app.smsDatabase.attempts().get(id)?.state != "SUBMITTED") delay(20) }
            assertFalse(app.smsJournal.sentResult(id, 5))
            assertTrue(app.smsJournal.latestDescription().contains(action.body))
            assertTrue(app.smsJournal.latestDescription().contains("Recipient delivery is unverified"))
        } finally { receipt?.cancel(); app.smsDatabase.attempts().delete(id) }
    }
}
