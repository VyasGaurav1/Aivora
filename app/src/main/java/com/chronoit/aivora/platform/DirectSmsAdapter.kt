package com.chronoit.aivora.platform

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import com.chronoit.aivora.AivoraApplication
import com.chronoit.aivora.actions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DirectSmsAdapter(private val context: Context) {
    @Suppress("DEPRECATION")
    suspend fun execute(action: Action.SendSms): ActionResult {
        val app = context.applicationContext as AivoraApplication
        if (!app.settings.settings.first().directSms) return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Enable direct SMS in AIVORA settings first.")
        if (listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE).any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
            return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Allow SMS sending and SMS SIM verification in Capabilities.")
        require(PlanValidator.error(ActionPlan(listOf(action), 1.0, "Direct SMS")) == null)
        val lock = context.getSystemService(KeyguardManager::class.java)
        fun foreground() = !lock.isKeyguardLocked && app.visibility.foreground.value
        if (!foreground()) return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Return to unlocked AIVORA before sending SMS.")
        if (action.contactId != null && !app.contacts.stillMatches(action.contactId, action.phoneNumber)) return ActionResult(ExecutionStatus.CANCELLED, "The contact changed. Resolve the recipient again.")
        val monitor = AndroidSmsMonitor(context)
        if (monitor.selection() != action.target) return ActionResult(ExecutionStatus.NOT_SUPPORTED, "The default SMS SIM changed, is unavailable or roaming.")
        val sms = SmsManager.getSmsManagerForSubscriptionId(action.target.subscriptionId)
        if (sms.divideMessage(action.body) != arrayListOf(action.body)) return ActionResult(ExecutionStatus.NOT_SUPPORTED, "This message needs multiple SMS parts. Use a shorter message or request a draft; nothing was sent.")
        if (!app.settings.settings.first().directSms) return ActionResult(ExecutionStatus.CANCELLED, "Direct SMS was disabled.")
        if (action.contactId != null && !app.contacts.stillMatches(action.contactId, action.phoneNumber)) return ActionResult(ExecutionStatus.CANCELLED, "The recipient changed before sending. Resolve the number again.")
        if (monitor.selection() != action.target || !foreground()) return ActionResult(ExecutionStatus.CANCELLED, "The SMS SIM or foreground device state changed before sending.")
        currentCoroutineContext().ensureActive()
        val journal = app.smsJournal
        val id = journal.begin(action)
        var attempted = false
        var receipt: PendingIntent? = null
        try {
            val (_, result) = SmsSentResults.register(id)
            receipt = PendingIntent.getBroadcast(context, 0, Intent(context, SmsSentReceiver::class.java).setAction("${context.packageName}.SMS_SENT.$id"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            if (!app.settings.settings.first().directSms ||
                (action.contactId != null && !app.contacts.stillMatches(action.contactId, action.phoneNumber)) ||
                monitor.selection() != action.target || !foreground()) {
                journal.notRequested(id)
                return ActionResult(ExecutionStatus.CANCELLED, "The SMS request stopped before submission because its settings, recipient, SIM or device state changed.")
            }
            currentCoroutineContext().ensureActive()
            attempted = true
            sms.sendTextMessage(action.phoneNumber, null, action.body, receipt, null)
            val code = withTimeoutOrNull(15000) { result.await() }
            if (code == null) journal.unknown(id)
            return when (code) {
                Activity.RESULT_OK -> ActionResult(ExecutionStatus.SUCCESS, "Android reports the SMS submitted. Recipient delivery has not been verified.")
                null -> ActionResult(ExecutionStatus.PARTIAL, "SMS submission is uncertain. No sent result arrived; check your messaging app before resending. Delivery is unverified.")
                else -> ActionResult(ExecutionStatus.FAILED, "Android reported an SMS send failure. Check your SIM, service and messaging app before retrying. Delivery is unverified.")
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { if (attempted) journal.unknown(id) else journal.notRequested(id) } }
            throw cancelled
        }
        catch (_: Exception) {
            withContext(NonCancellable) { runCatching { if (attempted) journal.unknown(id) else journal.notRequested(id) } }
            return if (attempted) ActionResult(ExecutionStatus.PARTIAL, "The SMS request could not be verified. Check your messaging app before resending; delivery is unverified.")
                else ActionResult(ExecutionStatus.FAILED, "The SMS stopped before submission was requested.")
        } finally {
            if (!attempted) receipt?.cancel()
            SmsSentResults.remove(id)
        }
    }
}
