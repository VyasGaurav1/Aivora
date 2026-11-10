package com.chronoit.aivora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import com.chronoit.aivora.scheduler.TaskConditions
import com.chronoit.aivora.scheduler.RequiredNetwork

@Composable fun RemindersScreen(model: AssistantViewModel, ui: AssistantUiState, requestNotifications: () -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("10") }
    var repeat by rememberSaveable { mutableStateOf(false) }
    var charging by rememberSaveable { mutableStateOf(false) }
    var networkName by rememberSaveable { mutableStateOf(RequiredNetwork.NONE.name) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(ui.reminderStatus) }
        item { OutlinedButton(onClick = requestNotifications) { Text("Allow reminder notifications") } }
        item { OutlinedTextField(text, { text = it.take(2000) }, label = { Text("Remind me to…") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(6) }, label = { Text("Minutes from now") }, singleLine = true) }
        item { Row { Checkbox(repeat, { repeat = it }); Text("Repeat at this interval (15 minutes minimum)") } }
        item { Row { Checkbox(charging, { charging = it }); Text("Wait until the phone is charging") } }
        item { Text("Network requirement (AIVORA does not turn connectivity on)") }
        item { Column { RequiredNetwork.entries.forEach { network -> Row {
            RadioButton(networkName == network.name, { networkName = network.name })
            Text(when (network) { RequiredNetwork.NONE -> "No network needed"; RequiredNetwork.VALIDATED -> "Validated internet connection"; RequiredNetwork.UNMETERED -> "Validated unmetered connection" })
        } } } }
        item { Button(onClick = { model.createReminder(text, minutes.toLong(), repeat, TaskConditions(charging, RequiredNetwork.valueOf(networkName))) },
            enabled = text.isNotBlank() && (minutes.toLongOrNull() ?: 0) in (if (repeat) 15L else 1L)..525600L) { Text("Save reminder") } }
        item { TextButton(onClick = model::refreshReminders) { Text("Refresh reminders") } }
        if (ui.reminders.isEmpty()) item { Text("No active reminders.") }
        items(ui.reminders, key = { it.id }) { reminder -> Card { Column(Modifier.padding(12.dp)) {
            Text(reminder.text, style = MaterialTheme.typography.titleMedium)
            Text(DateFormat.getDateTimeInstance().format(Date(reminder.dueAt)))
            if (reminder.repeatMillis > 0) Text("Repeats every ${reminder.repeatMillis / 60_000} minutes")
            if (reminder.conditions.charging) Text("Requires charging")
            if (reminder.conditions.network != RequiredNetwork.NONE) Text("Network: ${reminder.conditions.network.name.lowercase()}")
            if (reminder.state == "WAITING_CONDITIONS") Text("Waiting for saved conditions")
            if (reminder.state == "BLOCKED") Text("Waiting for notification access")
            if (reminder.hasAction) Text("Scheduled action · ${when (reminder.state) {
                "AWAITING_REVIEW" -> "ready for owner review"
                "STARTED" -> "execution was interrupted or is in progress; verify its result before creating another task"
                "PAUSED" -> "series paused after an unsuccessful or unverified result; cancel and create a new task after checking it"
                else -> "waiting until due"
            }}")
            if (reminder.hasAction && reminder.state == "AWAITING_REVIEW") TextButton(onClick = { model.reviewScheduledAction(reminder.id) }) { Text("Review action") }
            var cancel by remember { mutableStateOf(false) }
            TextButton(onClick = { cancel = true }) { Text("Cancel reminder") }
            if (cancel) AlertDialog(onDismissRequest = { cancel = false }, title = { Text("Cancel this reminder?") },
                text = { Text(reminder.text) }, confirmButton = { TextButton(onClick = { model.cancelReminder(reminder.id); cancel = false }) { Text("Cancel reminder") } },
                dismissButton = { TextButton(onClick = { cancel = false }) { Text("Keep") } })
        } } }
    }
}
