package com.chronoit.aivora.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.net.Uri
import android.os.Build
import androidx.work.*

/** AlarmManager wakeup plus persistent WorkManager fallback; neither executes protected actions. */
internal class ReminderTiming(private val context: Context) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    fun preciseAllowed() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    private fun intent(id: String) = Intent(context, ReminderDueReceiver::class.java)
        .setData(Uri.parse("aivora://reminder-due/$id")).putExtra("taskId", id)
    fun schedule(id: String, due: Long) {
        val pending = PendingIntent.getBroadcast(context, 0, intent(id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (preciseAllowed()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(due, System.currentTimeMillis() + 1000), pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(due, System.currentTimeMillis() + 1000), pending)
        } catch (_: SecurityException) { /* Persistent WorkManager remains scheduled if special access changes. */ }
    }
    fun cancel(id: String) {
        PendingIntent.getBroadcast(context, 0, intent(id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            alarms.cancel(it); it.cancel()
        }
    }
}

class ReminderDueReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("taskId")?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess } ?: return
        WorkManager.getInstance(context).enqueueUniqueWork("aivora.reminder.alarm.$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf("taskId" to id))
                .apply { if (Build.VERSION.SDK_INT >= 31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }.build())
    }
}
