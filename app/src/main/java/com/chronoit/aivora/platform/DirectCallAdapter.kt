package com.chronoit.aivora.platform

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.chronoit.aivora.AivoraApplication
import com.chronoit.aivora.actions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DirectCallAdapter(private val context: Context) {
    suspend fun execute(action: Action.PlaceCall): ActionResult {
        val app = context.applicationContext as AivoraApplication
        if (!app.settings.settings.first().directCalling) return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Enable direct calling in AIVORA settings first.")
        if (listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE).any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
            return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Allow call placement and calling SIM verification in Capabilities.")
        require(PlanValidator.error(ActionPlan(listOf(action), 1.0, "Direct call")) == null)
        val lock = context.getSystemService(KeyguardManager::class.java)
        if (lock.isKeyguardLocked || !app.visibility.foreground.value) return ActionResult(ExecutionStatus.PERMISSION_MISSING, "Return to unlocked AIVORA before requesting a call.")
        if (action.contactId != null && !app.contacts.stillMatches(action.contactId, action.phoneNumber))
            return ActionResult(ExecutionStatus.PERMISSION_MISSING, "The contact changed or contacts access was revoked. Resolve the number again.")
        val monitor = AndroidVoiceCallMonitor(context)
        val initial = monitor.selection()?.takeIf { it.target == action.target }
            ?: return ActionResult(ExecutionStatus.NOT_SUPPORTED, "The calling SIM/account changed, is roaming, unavailable, or another call is active.")
        if (context.getSystemService(TelephonyManager::class.java).isEmergencyNumber(action.phoneNumber))
            return ActionResult(ExecutionStatus.NOT_SUPPORTED, "Use Android's emergency dialer for emergency calls.")
        if (!app.settings.settings.first().directCalling) return ActionResult(ExecutionStatus.CANCELLED, "Direct calling was disabled.")
        // Recheck after the settings suspension as well as at action entry.
        if (action.contactId != null && !app.contacts.stillMatches(action.contactId, action.phoneNumber))
            return ActionResult(ExecutionStatus.PERMISSION_MISSING, "The contact changed before the call request. Resolve the number again.")
        val fresh = monitor.selection()?.takeIf { it.target == action.target && it.account == initial.account }
            ?: return ActionResult(ExecutionStatus.NOT_SUPPORTED, "The calling SIM/account changed before the request.")
        currentCoroutineContext().ensureActive()
        if (lock.isKeyguardLocked || !app.visibility.foreground.value) return ActionResult(ExecutionStatus.CANCELLED, "The device locked or AIVORA left the foreground.")
        context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", action.phoneNumber, null),
            Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, fresh.account)
                if (action.speakerphone) putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            })
        return ActionResult(ExecutionStatus.REQUIRES_USER_INTERACTION, "Requested the call to ${action.contactName ?: action.phoneNumber} through Android Telecom using ${action.target.label}, SIM slot ${action.target.slotIndex + 1}. Connection and answer have not been verified; Android may require further interaction.${if (action.speakerphone) " Speakerphone was requested; the audio route has not been verified." else ""}")
    }
}
