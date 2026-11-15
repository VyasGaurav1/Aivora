package com.chronoit.aivora.ui



import androidx.compose.foundation.layout.*

import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.text.input.PasswordVisualTransformation

import androidx.compose.ui.unit.dp

import androidx.compose.ui.platform.LocalUriHandler

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.chronoit.aivora.data.AssistantSettings

import com.chronoit.aivora.platform.BackgroundWakeService

import com.chronoit.aivora.platform.CapabilityStatus

import com.chronoit.aivora.voice.EnrollmentKind

import kotlinx.coroutines.delay



/** Compact entry point for the first release. The full assistant engines remain shared. */

@Composable internal fun StarterScreen(

    model: AssistantViewModel,

    ui: AssistantUiState,

    settings: AssistantSettings,

    requestPermissions: () -> Unit,

    openAppPermissions: () -> Unit,

    requestAssistantRole: () -> Unit,

    enroll: (EnrollmentKind) -> Unit,

    startWake: () -> Unit,

    stopWake: () -> Unit,

    record: () -> Unit,

) {

    val voice by model.voice.state.collectAsStateWithLifecycle()

    val wake by BackgroundWakeService.state.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }

    var showReminders by remember { mutableStateOf(false) }

    val setup = !settings.onboardingComplete || showSettings

    Surface(Modifier.fillMaxSize()) {

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {

                Text("AIVORA", style = MaterialTheme.typography.headlineMedium)

                if (settings.onboardingComplete) TextButton(onClick = { showSettings = !showSettings; showReminders = false }) {

                    Text(if (setup) "Done" else "Settings")

                }

            }

            when {

                setup -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    val microphone = ui.capabilities.any { it.name == "Microphone" && it.status == CapabilityStatus.AVAILABLE }

                    val notifications = ui.capabilities.any { it.name == "Reminder notifications" && it.status == CapabilityStatus.AVAILABLE }

                    val assistant = ui.capabilities.any { it.name == "Assistant role" && it.status == CapabilityStatus.AVAILABLE }

                    Text(if (settings.onboardingComplete) "Voice settings" else "Get started", style = MaterialTheme.typography.titleLarge)

                    if (!microphone || !notifications) {

                        Text("Allow microphone access for voice and notifications for reminders and wake listening.")

                        OutlinedButton(onClick = requestPermissions) { Text("Allow access") }

                        TextButton(onClick = openAppPermissions) { Text("Open Android app settings") }

                    }

                    Text(if (assistant) "Default assistant: AIVORA" else "Choose AIVORA as your default assistant")

                    if (!assistant) {

                        Text("Open Android settings, tap Digital assistant app, then select AIVORA and return here.")

                        OutlinedButton(onClick = requestAssistantRole) { Text("Open assistant settings") }

                    }

                    HorizontalDivider()

                    Text("Reminder timing", style = MaterialTheme.typography.titleMedium)
                    Text(if (ui.preciseReminders) "Precise reminder access is enabled." else "Allow Alarms & reminders access for better timing while the phone is idle. Without it, Android may delay reminders.")
                    if (!ui.preciseReminders) TextButton(onClick = model::openReminderTimingSettings) { Text("Allow precise reminders") }
                    Text("Say Hey Jarvis or Hey Aivora while I speak to interrupt, then wait for Go ahead before your next request.")
                    HorizontalDivider()
                    Text("Local memory", style = MaterialTheme.typography.titleMedium)
                    Text("The latest 80 command/reply pairs stay encrypted on this phone. No ambient recordings or model training. Saved-memory readbacks use offline speech.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Remember conversations", Modifier.weight(1f))
                        Switch(checked = !settings.privateMode, onCheckedChange = { model.privateMode(!it) })
                    }
                    TextButton(onClick = { model.submit("what did we discuss") }) { Text("Recall recent conversations") }
                    TextButton(onClick = { model.submit("forget conversation history") }) { Text("Clear conversation history") }
                    HorizontalDivider()
                    Text("Wake listening", style = MaterialTheme.typography.titleMedium)

                    Text(if (model.gnaniConfigured()) "Voice provider: Gnani (online, optional)" else "Voice provider: on-device (offline)")

                    Text("Keep your phone unlocked. Say Hey Jarvis or Hey Aivora, wait for the greeting, then speak. Say Goodbye when finished.")

                    if (wake.running) OutlinedButton(onClick = stopWake) { Text("Stop listening") }

                    else Button(onClick = startWake, enabled = microphone && notifications && assistant) { Text("Start listening") }

                    Text(wake.message)

                    TextButton(onClick = model::testWakeGreeting, enabled = !voice.speaking) { Text("Test Indian English voice") }

                    HorizontalDivider()

                    var showOptional by remember { mutableStateOf(false) }

                    TextButton(onClick = { showOptional = !showOptional }) { Text(if (showOptional) "Hide optional services" else "Optional speech and search services") }

                    if (showOptional) {

                    GnaniSpeechSetup(model)

                    HorizontalDivider()

                    Text("Web answers", style = MaterialTheme.typography.titleMedium)

                    Text(if (model.webSearchConfigured()) "Search key saved" else "Web answers are optional — offline answers work without a key")

                    val uriHandler = LocalUriHandler.current

                    Text("In Brave’s dashboard, activate the Answers plan, then create a key under API Keys. Paste it below.")

                    TextButton(onClick = { uriHandler.openUri("https://api-dashboard.search.brave.com/") }) { Text("Get a Brave API key") }

                    var key by remember { mutableStateOf("") }

                    OutlinedTextField(key, { key = it.take(256) }, singleLine = true,

                        label = { Text("Brave Search API key") }, visualTransformation = PasswordVisualTransformation())

                    Row {

                        Button(onClick = { model.saveWebSearchKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }

                        TextButton(onClick = { model.saveWebSearchKey(""); key = "" }) { Text("Remove key") }

                    }

                    }

                    if (!settings.onboardingComplete) Button(onClick = model::finishOnboarding) { Text("Finish setup") }

                }

                showReminders -> {

                    TextButton(onClick = { showReminders = false }) { Text("Back") }

                    val reminders = ui.reminders.filterNot { it.hasAction }

                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        if (reminders.isEmpty()) item { Text("No saved reminders.") }

                        items(reminders, key = { it.id }) { reminder ->

                            Text(reminder.text, style = MaterialTheme.typography.titleMedium)

                            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(reminder.dueAt)))

                        }

                    }

                }

                else -> {

                    Spacer(Modifier.weight(1f))

                    Text(if (voice.speaking) "Speaking" else if (voice.capturing) if (voice.inputReady) "Listening..." else "Getting microphone ready..." else ui.state.statusLabel(),

                        style = MaterialTheme.typography.headlineMedium)

                    if (ui.state in setOf(AssistantState.UNDERSTANDING, AssistantState.SEARCHING, AssistantState.SUMMARIZING, AssistantState.EXECUTING)) LinearProgressIndicator(Modifier.fillMaxWidth())

                    Text("Reminders, alarms, and web answers.")

                    LiveTranscript(ui.voiceDraft, voice.capturing && !voice.speaking)

                    if (!voice.capturing && !voice.speaking) Text(voice.message, maxLines = 3)

                    Button(onClick = if (voice.capturing) model::finishRecording else record, enabled = !voice.speaking) {

                        Text(if (voice.capturing) "Finish" else "Speak")

                    }

                    ui.conversation.lastOrNull { !it.owner }?.let { Text(it.text, maxLines = 3) }

                    Spacer(Modifier.weight(1f))

                    TextButton(onClick = { showReminders = true }) { Text("Reminders") }

                }

            }

        }

    }

    StarterSuggestions(ui, model::dismissWebSuggestions)

}



@Composable private fun GnaniSpeechSetup(model: AssistantViewModel) {

    var key by remember { mutableStateOf("") }

    var language by remember { mutableStateOf(model.gnaniLanguage()) }

    var showLanguages by remember { mutableStateOf(false) }

    Text(if (model.gnaniConfigured()) "Gnani speech key saved" else "Connect Gnani speech", style = MaterialTheme.typography.titleMedium)

    Text("Save a key to use Gnani for wake listening, commands and replies. Without a key, AIVORA uses your phone’s recognizer. Gnani supports 10 languages and requires internet and credits; enabled wake listening streams audio to Gnani.")

    OutlinedTextField(key, { key = it.take(512) }, singleLine = true,

        label = { Text("Gnani API key") }, visualTransformation = PasswordVisualTransformation())

    Row {

        Button(onClick = { model.saveGnaniKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save speech key") }

        if (model.gnaniConfigured()) TextButton(onClick = { model.saveGnaniKey(""); key = "" }) { Text("Remove") }

    }

    Box {

        OutlinedButton(onClick = { showLanguages = true }) { Text(com.chronoit.aivora.voice.GnaniSpeechSettings.languages.getValue(language)) }

        DropdownMenu(expanded = showLanguages, onDismissRequest = { showLanguages = false }) {

            com.chronoit.aivora.voice.GnaniSpeechSettings.languages.forEach { (code, label) ->

                DropdownMenuItem(text = { Text(label) }, onClick = { model.setGnaniLanguage(code); language = code; showLanguages = false })

            }

        }

    }

    Text("Transcripts appear after each recognized speech segment. Reminder and alarm command understanding currently uses English; other languages can be transcribed and spoken.")

}



@Composable internal fun StarterVoicePanel(model: AssistantViewModel, ui: AssistantUiState, onClose: () -> Unit) {

    val voice by model.voice.state.collectAsStateWithLifecycle()

    var captureStarted by remember { mutableStateOf(false) }

    LaunchedEffect(voice.capturing, voice.speaking, ui.state, ui.webSuggestions, ui.conversationRunning) {

        if (voice.capturing || voice.speaking) captureStarted = true

        if (captureStarted && !voice.capturing && !voice.speaking &&

            ui.state == AssistantState.IDLE && ui.webSuggestions == null && !ui.conversationRunning) {

            delay(1800)

            onClose()

        }

    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {

        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {

            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                Text("AIVORA", style = MaterialTheme.typography.titleLarge)

                Text(when { voice.speaking -> "Speaking"; voice.capturing -> if (voice.inputReady) "Listening..." else "Getting microphone ready..."; else -> ui.state.statusLabel() })

                LiveTranscript(ui.voiceDraft, voice.capturing && !voice.speaking)

                if (!voice.capturing && !voice.speaking) Text(voice.message, maxLines = 3)

                ui.conversation.lastOrNull { !it.owner }?.let { Text(it.text, maxLines = 3) }

                Row {

                    if (voice.capturing && !voice.speaking) TextButton(onClick = model::finishRecording) { Text("Finish") }

                    TextButton(onClick = onClose) { Text("Close") }

                }

            }

        }

    }

    StarterSuggestions(ui, model::dismissWebSuggestions)

}



@Composable private fun LiveTranscript(text: String, listening: Boolean) {

    if (text.isNotBlank() || listening) {

        Text(if (text.isBlank()) "Say your request…" else text,

            style = MaterialTheme.typography.bodyLarge, maxLines = 6,

            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)

    }

}



@Composable private fun StarterSuggestions(ui: AssistantUiState, dismiss: () -> Unit) {

    ui.webSuggestions?.let { suggestions ->

        AlertDialog(onDismissRequest = dismiss, title = { Text("Web suggestions") },

            text = { Text(suggestions, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },

            confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })

    }

}



class VoicePanelActivity : MainActivity() {

    override val voicePanelOnly = true

}

