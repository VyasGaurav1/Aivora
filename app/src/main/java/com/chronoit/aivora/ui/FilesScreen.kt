package com.chronoit.aivora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chronoit.aivora.brain.FileOperation

@Composable fun FilesScreen(model: AssistantViewModel, ui: AssistantUiState, pickFile: () -> Unit, pickFolder: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(ui.fileStatus) }
        item { Text("Only files and folders you select are indexed. Metadata stays encrypted on this phone. Sharing requires device authentication and final confirmation.") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = pickFile) { Text("Add file") }
            OutlinedButton(onClick = pickFolder) { Text("Add folder") }
        } }
        item { TextButton(onClick = model::refreshDocumentCatalog) { Text("Refresh permitted files") } }
        if (ui.documents.isEmpty()) item { Text("No permitted local files are indexed.") }
        items(ui.documents, key = { it.id }) { document -> Card { Column(Modifier.padding(12.dp)) {
            Text(document.name, style = MaterialTheme.typography.titleMedium)
            Text(document.mimeType, style = MaterialTheme.typography.labelSmall)
            Row {
                TextButton(onClick = { model.actOnFile(document.id, FileOperation.OPEN) }) { Text("Open") }
                TextButton(onClick = { model.actOnFile(document.id, FileOperation.SHARE) }) { Text("Share") }
            }
            var revoke by remember { mutableStateOf(false) }
            TextButton(onClick = { revoke = true }) { Text("Remove access") }
            if (revoke) AlertDialog(onDismissRequest = { revoke = false }, title = { Text("Remove file access?") },
                text = { Text("For a folder grant, this removes access to every indexed file in that folder. Your files are not deleted.") },
                confirmButton = { TextButton(onClick = { model.revokeDocumentGrant(document.id); revoke = false }) { Text("Remove access") } },
                dismissButton = { TextButton(onClick = { revoke = false }) { Text("Keep") } })
        } } }
    }
}
