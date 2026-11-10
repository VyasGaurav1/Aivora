package com.chronoit.aivora.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chronoit.aivora.AivoraApplication
import kotlinx.coroutines.*

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefix = "${context.packageName}.SMS_SENT."
        val action = intent.action ?: return
        if (!action.startsWith(prefix)) return
        val id = action.removePrefix(prefix)
        if (!id.matches(Regex("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}"))) return
        val code = resultCode
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if ((context.applicationContext as AivoraApplication).smsJournal.sentResult(id, code)) SmsSentResults.complete(id, code)
            } catch (_: Exception) {
                // A missing durable result remains uncertain; never manufacture a sent outcome.
                android.util.Log.w("AIVORA", "SMS status could not be stored; outcome remains uncertain.")
            } finally { pending.finish() }
        }
    }
}
