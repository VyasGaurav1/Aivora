package com.chronoit.aivora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chronoit.aivora.data.AssistantSettings
import com.chronoit.aivora.platform.CapabilityStatus

@Composable fun SetupScreen(model: AssistantViewModel, ui: AssistantUiState, settings: AssistantSettings, requestPermissions: () -> Unit, requestMicrophone: () -> Unit, requestAssistantRole: () -> Unit) {
    var name by rememberSaveable(settings.identity.assistantDisplayName) { mutableStateOf(settings.identity.assistantDisplayName) }
    var showApps by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Welcome to AIVORA", style = MaterialTheme.typography.headlineLarge) }
        item { Text("Voice recognition, speech and memory work on this phone without internet. Tasks such as online video, messaging delivery and payments may need connectivity and the relevant app.") }
        item { OutlinedTextField(name, { name = it.take(40) }, label = { Text("What would you like to call me?") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Button(onClick = { model.rename(name) }, enabled = name.isNotBlank() && name.none(Char::isISOControl)) { Text("Save assistant name") } }
        item { Text("Permissions", style = MaterialTheme.typography.titleLarge) }
        item { Text("Allow the microphone for voice, contacts for names, camera for payment QR scans and flashlight control, notifications for reminders, phone state for SIM/calling verification, and optional phone-call permission for direct calls. SIM verification does not request subscriber-number or call-log access and does not inspect SIM hardware identifiers. Contact numbers are read only with contacts access for a requested recipient. Android asks you to approve access. You can continue with permissions denied and change them later.") }
        item {
            val microphoneGranted = ui.capabilities.any { it.name == "Microphone" && it.status == CapabilityStatus.AVAILABLE }
            if (microphoneGranted) Text("Microphone access granted")
            else Button(onClick = requestMicrophone) { Text("Allow microphone for voice") }
        }
        item { Button(onClick = requestPermissions) { Text("Review requested permissions") } }
        item { OutlinedButton(onClick = requestAssistantRole) { Text("Choose AIVORA as assistant") } }
        item { Text("Once selected, use your phone’s assistant shortcut while unlocked and speak after AIVORA’s cue.") }
        item {
            Row {
                Switch(checked = settings.spokenReplies, onCheckedChange = model::spokenReplies)
                Text("Speak replies", Modifier.padding(12.dp))
            }
            Text("Hear results aloud using offline speech.")
        }
        item { Text("Optional app control: notification access enables verified media-session controls; AIVORA app-control accessibility access enables a scoped YouTube fallback. These accesses require separate approval in Android settings.") }
        item { OutlinedButton(onClick = { model.submit("notification access settings") }) { Text("Review notification access") } }
        item { OutlinedButton(onClick = { model.submit("app control settings") }) { Text("Review YouTube and Wi-Fi app-control access") } }
        item { WifiControlsSetting(model, settings) }
        item { Button(onClick = model::finishOnboarding, modifier = Modifier.fillMaxWidth()) { Text("Continue to AIVORA") } }
        item { Text("Device capability check", style = MaterialTheme.typography.titleLarge) }
        item { TextButton(onClick = model::refresh) { Text("Recheck capabilities") } }
        items(ui.capabilities, key = { it.name }) { capability -> Card { Column(Modifier.padding(12.dp)) {
            Text(capability.name, style = MaterialTheme.typography.titleMedium)
            Text(capability.status.name.lowercase().replace('_', ' '))
            Text(capability.detail)
        } } }
        item { Text("Found ${ui.discoveredApps.size} launchable apps. This identifies apps AIVORA can open; it does not prove every feature inside them can be controlled.") }
        item { TextButton(onClick = { showApps = !showApps }) { Text(if (showApps) "Hide detected apps" else "Show detected apps") } }
        if (showApps) items(ui.discoveredApps, key = { it.packageName }) { app -> Text("${app.label} · ${app.packageName}") }
        item { Text("Phone-call permission is for optional direct calling. Setup never places a call. Direct calling requires separate opt-in, device authentication and exact confirmation.") }
        item { Text("After setup, tap the microphone on Home and say a command. AIVORA processes it offline and asks for review when needed. Special access and local files are granted in Capabilities and Files.") }
        item { Text("Android may require touch or device credentials for permissions, unlocking and payments. AIVORA cannot bypass your lock screen or payment approval. App control and automatic connectivity are still being built; the current version does not yet provide complete hands-free phone control.") }
    }
}
