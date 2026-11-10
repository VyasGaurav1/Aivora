package com.chronoit.aivora.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*

/** Enqueue bounded recovery after restart/clock changes; no microphone or speech in the receiver. */
class ScheduleRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in actions) return
        WorkManager.getInstance(context).enqueueUniqueWork("aivora.scheduler.system-recovery", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderRecoveryWorker>().setInputData(workDataOf("reschedule" to true)).build())
    }
    companion object { val actions = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED) }
}
