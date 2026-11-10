package com.chronoit.aivora.platform

import android.service.voice.VoiceInteractionService
import android.content.ComponentName
import android.os.Bundle
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** System-managed assistant entry. No microphone or inference work runs while idle. */
class AivoraVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); active = WeakReference(this); mutableReady.value = true }
    override fun onShutdown() { if (active?.get() === this) { active = null; mutableReady.value = false }; super.onShutdown() }
    override fun onDestroy() { if (active?.get() === this) { active = null; mutableReady.value = false }; super.onDestroy() }
    companion object {
        @Volatile private var active: WeakReference<AivoraVoiceInteractionService>? = null
        private val mutableReady = MutableStateFlow(false)
        val ready = mutableReady.asStateFlow()
        /** System-managed assistant session, not an unrestricted background activity launch. */
        fun requestVoiceSession(): Boolean {
            val service = active?.get() ?: return false
            if (!isActiveService(service, ComponentName(service, AivoraVoiceInteractionService::class.java))) return false
            return try { service.showSession(Bundle().apply { putBoolean("aivora_background_wake", true) }, 0); true } catch (_: Exception) { false }
        }
    }
}
