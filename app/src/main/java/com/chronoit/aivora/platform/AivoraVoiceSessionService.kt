package com.chronoit.aivora.platform

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.chronoit.aivora.ui.VoicePanelActivity
import com.chronoit.aivora.voice.VoiceLaunchTickets
import android.net.Uri

class AivoraVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            android.util.Log.i("AivoraWake", "Android assistant session shown")
            if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                val ticket = VoiceLaunchTickets.store.issue(starterCommand = args?.getBoolean("aivora_background_wake") != true)
                startAssistantActivity(Intent(this@AivoraVoiceSessionService, VoicePanelActivity::class.java).setData(Uri.parse("aivora://voice/$ticket")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                android.util.Log.i("AivoraWake", "Voice panel launch requested")
            }
            hide()
        }
    }
}
