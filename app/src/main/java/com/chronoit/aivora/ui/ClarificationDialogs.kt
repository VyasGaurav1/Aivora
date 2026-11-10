package com.chronoit.aivora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun ClarificationDialogs(model: AssistantViewModel, ui: AssistantUiState, requestMicrophone: () -> Unit) {
    val voice by model.voice.state.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val canReply = settings.handsFreeControls && voice.ownerEnrolled
    ui.networkPromptToken?.let { token -> AlertDialog(onDismissRequest = model::cancel, title = { Text("Internet needed for this task") },
        text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
            Text(model.onlineTaskDescription() ?: "This task expired or changed. Repeat the complete request.")
            Text("Open Android's internet controls? AIVORA will resume this task only after validated connectivity is available and you return within one minute. Silent switching and automatic restoration are unavailable in standard mode.")
            if (canReply) {
                TextButton(onClick = model::readOnlineTask, enabled = !voice.capturing) { Text("Read task and reply by voice") }
            } else {
                Text("After AIVORA reads the task aloud, say “Connect for this task” or “Cancel”.")
                TextButton(onClick = model::readOnlineTask, enabled = !voice.capturing && !voice.speaking) { Text("Read task aloud") }
                TextButton(onClick = requestMicrophone, enabled = !voice.capturing && !voice.speaking) { Text("Read task and reply by voice") }
            }
            if (voice.capturing && !voice.speaking) TextButton(onClick = model::finishRecording) { Text("Finish reply") }
        } },
        confirmButton = { TextButton(onClick = { model.approveOnlineTask(token) }) { Text("Open internet controls") } },
        dismissButton = { TextButton(onClick = model::cancel) { Text("Cancel task") } }) }
    if (ui.contactChoices.isNotEmpty()) AlertDialog(onDismissRequest = model::cancel, title = { Text("Choose a contact and number") },
        text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
            ui.contactChoices.forEachIndexed { index, contact -> TextButton(onClick = { ui.contactChoiceToken?.let { model.chooseContact(index, it) } }) {
                Text("${index + 1}. ${contact.name} · ${contact.label}\n${contact.number}")
            } }
            if (voice.capturing) TextButton(onClick = model::finishRecording) { Text("Finish reply") }
        } }, confirmButton = {
            if (canReply) TextButton(onClick = { model.readChoices() }) { Text("Read choices and reply") }
            else TextButton(onClick = requestMicrophone, enabled = !voice.capturing) { Text("Read choices and reply") }
        }, dismissButton = { TextButton(onClick = model::cancel) { Text("Cancel") } })
    if (ui.documentChoices.isNotEmpty()) AlertDialog(onDismissRequest = model::cancel, title = { Text("Choose a file") },
        text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
            ui.documentChoices.forEachIndexed { index, document -> TextButton(onClick = { ui.documentChoiceToken?.let { model.chooseDocument(index, it) } }) { Text("${index + 1}. ${document.name}\n${document.mimeType} · ${document.size} bytes") } }
            if (voice.capturing) TextButton(onClick = model::finishRecording) { Text("Finish reply") }
        } }, confirmButton = { TextButton(onClick = { model.readChoices() }, enabled = canReply) { Text("Read choices and reply") } }, dismissButton = { TextButton(onClick = model::cancel) { Text("Cancel") } })
}
