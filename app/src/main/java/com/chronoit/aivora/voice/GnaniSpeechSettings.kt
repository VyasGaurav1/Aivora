package com.chronoit.aivora.voice

import android.content.Context
import android.util.Base64
import com.chronoit.aivora.security.FieldCipher
import com.chronoit.aivora.security.KeystoreKeys

class GnaniSpeechSettings(context: Context) {
    private val preferences = context.getSharedPreferences("gnani_speech", Context.MODE_PRIVATE)
    private val cipher = FieldCipher(KeystoreKeys::dataKey)
    fun configured() = preferences.contains("api_key")
    fun saveKey(value: String): Boolean {
        val key = value.trim()
        if (key.isEmpty()) return preferences.edit().remove("api_key").commit()
        if (key.length > 512 || key.any { it !in '!'..'~' }) return false
        return preferences.edit().putString("api_key", Base64.encodeToString(cipher.encrypt(key, "gnani_api_key"), Base64.NO_WRAP)).commit()
    }
    internal fun key(): String = preferences.getString("api_key", null)?.let {
        cipher.decrypt(Base64.decode(it, Base64.NO_WRAP), "gnani_api_key")
    } ?: error("Add your Gnani API key in AIVORA Settings first.")
    fun language(): String = preferences.getString("language", "indic-auto").orEmpty().takeIf { it in languages } ?: "indic-auto"
    fun setLanguage(code: String) { require(code in languages); preferences.edit().putString("language", code).apply() }
    companion object {
        val languages = linkedMapOf("indic-auto" to "Automatic — all supported languages", "en-IN" to "English (India)",
            "hi-IN" to "Hindi", "bn-IN" to "Bengali", "gu-IN" to "Gujarati", "kn-IN" to "Kannada",
            "ml-IN" to "Malayalam", "mr-IN" to "Marathi", "pa-IN" to "Punjabi", "ta-IN" to "Tamil", "te-IN" to "Telugu")
    }
}
