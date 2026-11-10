package com.chronoit.aivora.scheduler

import android.content.Context

/** Only opaque task IDs, delivery timestamps and completion status; reminder content stays encrypted in Room. */
internal class VoiceReminderJournal(context: Context) {
    private val preferences = context.getSharedPreferences("aivora_reminder_voice", Context.MODE_PRIVATE)
    val enabledSince: Long = synchronized(lock) {
        preferences.getLong("enabled_since", 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also {
            check(preferences.edit().putLong("enabled_since", it).commit())
        }
    }
    fun status(id: String, delivery: Long): String = preferences.getString("$id:$delivery", "PENDING").orEmpty()
    fun complete(id: String, delivery: Long, state: String = "SPOKEN") {
        check(preferences.edit().putString("$id:$delivery", state).commit())
    }
    companion object { private val lock = Any() }
}
