package com.chronoit.aivora.connectivity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.os.Build

/** Public live subscription metadata only; no phone number or hardware subscriber identifier. */
class AndroidMobileDataMonitor(private val context: Context) {
    fun snapshot(): MobileDataState? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return null
        if (Build.VERSION.SDK_INT >= 33 && (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA) ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION))) return null
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Phone-state permission is required for SIM verification")
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val rootPhone = context.getSystemService(TelephonyManager::class.java)
        fun active() = subscriptions.completeActiveSubscriptionInfoList
        val info = active()?.singleOrNull() ?: return null
        val defaultId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (info.subscriptionId != defaultId || info.isOpportunistic) return null
        val target = MobileDataTarget(info.subscriptionId, info.simSlotIndex, info.carrierId, info.displayName?.toString().orEmpty())
        if (!target.valid()) return null
        val phone = rootPhone.createForSubscriptionId(defaultId)
        if (phone.simState != TelephonyManager.SIM_STATE_READY) return null
        val result = MobileDataState(target, phone.isDataEnabled, phone.isNetworkRoaming)
        if (SubscriptionManager.getDefaultDataSubscriptionId() != defaultId) return null
        val current = active()?.singleOrNull() ?: return null
        return result.takeIf { !current.isOpportunistic && phone.simState == TelephonyManager.SIM_STATE_READY && SubscriptionManager.getDefaultDataSubscriptionId() == defaultId &&
            MobileDataTarget(current.subscriptionId, current.simSlotIndex, current.carrierId, current.displayName?.toString().orEmpty()) == target }
    }
}
