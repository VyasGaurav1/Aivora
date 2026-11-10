package com.chronoit.aivora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.chronoit.aivora.data.AssistantSettings
import com.chronoit.aivora.data.InternetBehavior

@Composable internal fun WifiControlsSetting(model: AssistantViewModel, settings: AssistantSettings) {
    Row {
        Checkbox(settings.wifiControls, model::wifiControls)
        Text("Allow supported Wi-Fi settings controls")
    }
    Text("Optional: with AIVORA app controls enabled, a confirmed request can operate the known Wi-Fi switch in the Android Internet panel. Unknown layouts and network/password selection remain manual.")
}

@Composable fun InternetSettings(model: AssistantViewModel, settings: AssistantSettings) {
    Text("Internet for requested tasks", style = MaterialTheme.typography.titleMedium)
    WifiControlsSetting(model, settings)
    Row {
        Checkbox(settings.mobileDataControls, model::mobileDataControls)
        Text("Allow supported mobile-data controls")
    }
    Text("Mobile data may incur carrier charges. Requires SIM verification, device authentication and exact-action confirmation. Only one unchanged default data SIM is supported; data is not enabled while roaming. Android's user setting and actual internet availability are verified separately.")
    InternetBehavior.entries.forEach { behavior -> Row {
        RadioButton(settings.internetBehavior == behavior, { model.internetBehavior(behavior) })
        Text(when (behavior) {
            InternetBehavior.LEAVE_UNCHANGED -> "Leave current connectivity unchanged"
            InternetBehavior.ASK -> "Ask before opening internet controls"
            InternetBehavior.ENABLE_WHEN_REQUIRED -> "Open Android internet controls when needed"
            InternetBehavior.RESTORE_AFTERWARD -> "Ask to connect and request restoration afterward"
        })
    } }
    Text("Offline tasks do not need connectivity. An approved online task can request opted-in Wi-Fi when off, or opted-in mobile data for the verified SIM with authentication. User settings and validated internet are checked separately. Silent radio APIs, SIM switching and automatic restoration remain unavailable.")
}
