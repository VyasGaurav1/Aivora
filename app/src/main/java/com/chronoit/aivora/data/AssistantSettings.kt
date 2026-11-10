package com.chronoit.aivora.data

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.chronoit.aivora.ProductIdentity
import com.chronoit.aivora.voice.AssistantIdentity
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock

private val Context.settingsStore by preferencesDataStore(
    name = "aivora_settings",
    produceMigrations = { context -> listOf(SharedPreferencesMigration(context, "assistant_identity")) },
)

enum class InternetBehavior { LEAVE_UNCHANGED, ASK, ENABLE_WHEN_REQUIRED, RESTORE_AFTERWARD }

data class AssistantSettings(
    val identity: AssistantIdentity = AssistantIdentity(),
    val privateMode: Boolean = false,
    val internetBehavior: InternetBehavior = InternetBehavior.ASK,
    val onboardingComplete: Boolean = false,
    val spokenReplies: Boolean = false,
    val speechSpeed: Float = 1f,
    val speechVoice: Int = 0,
    val handsFreeControls: Boolean = false,
    val wifiControls: Boolean = false,
    val mobileDataControls: Boolean = false,
    val directCalling: Boolean = false,
    val directSms: Boolean = false,
    val localReasoning: Boolean = true,
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore
    private val displayName = stringPreferencesKey("display_name")
    private val wakeName = stringPreferencesKey("wake_name")
    private val privateMode = booleanPreferencesKey("private_mode")
    private val internetBehavior = stringPreferencesKey("internet_behavior")
    private val onboardingComplete = booleanPreferencesKey("onboarding_complete")
    private val spokenReplies = booleanPreferencesKey("spoken_replies")
    private val speechSpeed = floatPreferencesKey("speech_speed")
    private val speechVoice = intPreferencesKey("speech_voice")
    private val handsFreeControls = booleanPreferencesKey("hands_free_controls")
    private val wifiControls = booleanPreferencesKey("wifi_controls")
    private val mobileDataControls = booleanPreferencesKey("mobile_data_controls")
    private val directCalling = booleanPreferencesKey("direct_calling")
    private val directSms = booleanPreferencesKey("direct_sms")
    private val localReasoning = booleanPreferencesKey("local_reasoning")
    val settings = store.data.map { values ->
        val identity = runCatching { AssistantIdentity(
            values[displayName] ?: ProductIdentity.DEFAULT_ASSISTANT_NAME,
            values[wakeName] ?: ProductIdentity.DEFAULT_ASSISTANT_NAME,
        ) }.getOrDefault(AssistantIdentity())
        AssistantSettings(identity, values[privateMode] ?: false,
            InternetBehavior.entries.find { it.name == values[internetBehavior] } ?: InternetBehavior.ASK,
            values[onboardingComplete] ?: false, values[spokenReplies] ?: false,
            (values[speechSpeed] ?: 1f).let { if (it.isFinite()) it.coerceIn(0.5f, 2f) else 1f },
            (values[speechVoice] ?: 0).coerceIn(0, 3), values[handsFreeControls] ?: false, values[wifiControls] ?: false, values[mobileDataControls] ?: false, values[directCalling] ?: false, values[directSms] ?: false, values[localReasoning] ?: true)
    }
    suspend fun rename(name: String) {
        val identity = AssistantIdentity(name.trim(), name.trim())
        store.edit { it[displayName] = identity.assistantDisplayName; it[wakeName] = identity.wakeName }
    }
    suspend fun setPrivateMode(enabled: Boolean) { PrivacyGate.mutex.withLock { store.edit { it[privateMode] = enabled } } }
    suspend fun setInternetBehavior(value: InternetBehavior) { store.edit { it[internetBehavior] = value.name } }
    suspend fun finishOnboarding() { store.edit { it[onboardingComplete] = true } }
    suspend fun setSpokenReplies(value: Boolean) { store.edit { it[spokenReplies] = value } }
    suspend fun setHandsFreeControls(value: Boolean) { store.edit { it[handsFreeControls] = value } }
    suspend fun setWifiControls(value: Boolean) { store.edit { it[wifiControls] = value } }
    suspend fun setMobileDataControls(value: Boolean) { store.edit { it[mobileDataControls] = value } }
    suspend fun setDirectCalling(value: Boolean) { store.edit { it[directCalling] = value } }
    suspend fun setDirectSms(value: Boolean) { store.edit { it[directSms] = value } }
    suspend fun setLocalReasoning(value: Boolean) { store.edit { it[localReasoning] = value } }
    suspend fun setSpeechOptions(speed: Float, voice: Int) {
        require(speed.isFinite() && speed in 0.5f..2f && voice in 0..3)
        store.edit { it[speechSpeed] = speed; it[speechVoice] = voice }
    }
}
