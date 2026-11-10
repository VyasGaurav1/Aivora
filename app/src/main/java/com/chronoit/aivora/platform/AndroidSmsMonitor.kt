package com.chronoit.aivora.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

data class SmsTarget(val subscriptionId: Int, val slotIndex: Int, val carrierId: Int, val label: String) {
    fun valid() = subscriptionId >= 0 && slotIndex >= 0 && carrierId >= -1 && label.isNotBlank() && label.length <= 100 && label.none(Char::isISOControl)
}
class AndroidSmsMonitor(private val context: Context) {
    fun selection(): SmsTarget? {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) || (Build.VERSION.SDK_INT >= 33 && !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))) return null
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Allow SMS SIM verification")
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val phone = context.getSystemService(TelephonyManager::class.java)
        if (!phone.isSmsCapable) return null
        val info = manager.completeActiveSubscriptionInfoList?.singleOrNull() ?: return null
        val id = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (info.subscriptionId != id || info.isOpportunistic) return null
        val target = SmsTarget(id, info.simSlotIndex, info.carrierId, info.displayName?.toString().orEmpty())
        if (!target.valid()) return null
        val pinned = phone.createForSubscriptionId(id)
        if (pinned.simState != TelephonyManager.SIM_STATE_READY || pinned.isNetworkRoaming) return null
        val fresh = manager.completeActiveSubscriptionInfoList?.singleOrNull() ?: return null
        return target.takeIf { !fresh.isOpportunistic && SmsTarget(fresh.subscriptionId, fresh.simSlotIndex, fresh.carrierId, fresh.displayName?.toString().orEmpty()) == it && SubscriptionManager.getDefaultSmsSubscriptionId() == id && pinned.simState == TelephonyManager.SIM_STATE_READY && !pinned.isNetworkRoaming }
    }
}
