package com.chronoit.aivora.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** The account stays opaque and transient; only public subscription metadata enters an action. */
class VoiceCallSelection(val target: VoiceCallTarget, internal val account: PhoneAccountHandle)
class AndroidVoiceCallMonitor(private val context: Context) {
    @Suppress("DEPRECATION")
    fun selection(): VoiceCallSelection? {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return null
        if (Build.VERSION.SDK_INT >= 33 && (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING) || !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION))) return null
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Allow calling SIM verification")
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val phone = context.getSystemService(TelephonyManager::class.java)
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
        if (!phone.isVoiceCapable || telecom.isInCall) return null
        val info = subscriptions.completeActiveSubscriptionInfoList?.singleOrNull() ?: return null
        val id = SubscriptionManager.getDefaultVoiceSubscriptionId()
        if (info.subscriptionId != id || info.isOpportunistic) return null
        val target = VoiceCallTarget(id, info.simSlotIndex, info.carrierId, info.displayName?.toString().orEmpty())
        if (!target.valid()) return null
        val account = telecom.callCapablePhoneAccounts.singleOrNull() ?: return null
        // Enabled accounts only. Pinning below rejects accounts without a matching cellular subscription.
        val pinned = phone.createForPhoneAccountHandle(account) ?: return null
        if (pinned.subscriptionId != id || pinned.simState != TelephonyManager.SIM_STATE_READY || pinned.isNetworkRoaming) return null
        val current = subscriptions.completeActiveSubscriptionInfoList?.singleOrNull() ?: return null
        return VoiceCallSelection(target, account).takeIf { !current.isOpportunistic &&
            VoiceCallTarget(current.subscriptionId, current.simSlotIndex, current.carrierId, current.displayName?.toString().orEmpty()) == target &&
            SubscriptionManager.getDefaultVoiceSubscriptionId() == id && telecom.callCapablePhoneAccounts == listOf(account) &&
            pinned.simState == TelephonyManager.SIM_STATE_READY && !pinned.isNetworkRoaming && !telecom.isInCall }
    }
}
