package com.chronoit.aivora.platform

import com.chronoit.aivora.actions.*
import com.chronoit.aivora.data.*
import com.chronoit.aivora.security.FieldCipher
import java.util.UUID

class SmsJournal(private val database: SmsDatabase, private val cipher: FieldCipher) {
    private val dao = database.attempts()
    suspend fun begin(action: Action.SendSms): String {
        val id = UUID.randomUUID().toString()
        dao.insert(SmsAttempt(id, cipher.encrypt(ActionCodec.encode(action), "sms:$id:action"), System.currentTimeMillis()))
        return id
    }
    suspend fun unknown(id: String) { dao.uncertainOrNotRequested(id, "UNKNOWN") }
    suspend fun notRequested(id: String) { dao.uncertainOrNotRequested(id, "NOT_REQUESTED") }
    suspend fun sentResult(id: String, code: Int): Boolean = dao.sentResult(id, if (code == -1) "SUBMITTED" else "FAILED_REPORTED", code) == 1
    suspend fun latestDescription(): String {
        val record = dao.latest() ?: return "No direct SMS request has been recorded by AIVORA."
        val action = ActionCodec.decode(cipher.decrypt(record.encryptedAction, "sms:${record.id}:action")) as? Action.SendSms
            ?: error("Invalid SMS journal payload")
        val status = when (record.state) {
            "SUBMITTED" -> "Android reported submission. Recipient delivery is unverified."
            "FAILED_REPORTED" -> "Android reported a send failure. Check your messaging app before retrying; delivery is unverified."
            "NOT_REQUESTED" -> "The request stopped before SMS submission was attempted."
            else -> "Submission is uncertain. Check your messaging app before resending; delivery is unverified."
        }
        return "Last SMS requested through AIVORA, to ${action.contactName ?: action.phoneNumber} (${action.phoneNumber}). Message: ${action.body}. $status"
    }
}
