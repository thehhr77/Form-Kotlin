package com.thehhr.form.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thehhr.form.nativeapp.vault.*

@Composable
fun ClearDataScreen(state: FormState, onReload: () -> Unit, onImport: (TransferPreview, () -> Unit) -> Unit, onBack: () -> Unit) {
    var selected by remember { mutableStateOf(emptySet<ClearCategory>()) }
    var preview by remember { mutableStateOf<TransferPreview?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val busy = state.loading || state.vaultBusy || state.workoutBusy
    val blocked = busy || state.reloadRequired || state.vault == null || state.workout != null || state.workoutLoadFailed
    BackHandler { if (!busy) onBack() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack, enabled = !busy) { Text("Back to transfers") }
            Text("Clear selected data", style = MaterialTheme.typography.headlineSmall)
            Text("Profile, targets, unrelated preferences and vault files are preserved. Clearing custom exercises keeps training history; replacing custom exercises removes history for removed custom IDs.")
            Text("SAF cannot atomically update multiple files. Partial failure requires Reload and manual recovery from operation-specific device-private vault-recovery backups. Originals and planned outputs are retained; no automatic rollback or destructive retry.")
            OutlinedButton(onClick = onReload, enabled = !busy && state.vault != null) { Text("Reload from disk") }
            if (state.workout != null || state.workoutLoadFailed) Text("Finish or discard the active, paused, finishing or unreadable workout first.")
            if (state.reloadRequired) Text("Reload is required before writing.", color = MaterialTheme.colorScheme.error)
        }
        item {
            ClearCategory.entries.forEach { category ->
                Row {
                    Checkbox(category in selected, { checked -> selected = if (checked) selected + category else selected - category }, enabled = !busy && preview == null)
                    Text(category.label, Modifier.padding(top = 12.dp))
                }
            }
            TextButton(onClick = { selected = if (selected.size == ClearCategory.entries.size) emptySet() else ClearCategory.entries.toSet() }, enabled = !busy && preview == null) { Text("Select / deselect all") }
            Button(enabled = !blocked && preview == null && selected.isNotEmpty(), onClick = {
                try {
                    message = null
                    preview = SettingsDataPlanner(state.builtInExerciseIds, state.builtInEquipment).clear(requireNotNull(state.vault), selected)
                } catch (error: Exception) { message = error.message }
            }) { Text("Preview clear") }
        }
        preview?.let { captured ->
            item {
                Text("Captured preview · ${captured.count} categories", style = MaterialTheme.typography.titleLarge)
                captured.batch?.summary?.forEach { Text(it) }
                Text("Files to change: ${captured.batch?.desired?.keys?.joinToString()}")
                Text("Vault: ${captured.uri}. Reload never rebases this preview.")
                Button(onClick = { confirming = true }, enabled = !blocked && captured.batch?.consumed != true) { Text("Review deletion confirmation") }
                TextButton(onClick = { preview = null; confirming = false }, enabled = !busy) { Text("Discard preview / change selection") }
            }
        }
        item {
            message?.let { Text(it) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirming) preview?.let { captured ->
        AlertDialog(onDismissRequest = { if (!busy) confirming = false }, title = { Text("Delete selected data?") },
            text = { Text(captured.batch?.summary?.joinToString("\n").orEmpty() + "\nChanges may span multiple files and are not atomic. Recovery backups require manual access. This confirmation uses only the captured preview.") },
            confirmButton = { TextButton(enabled = !blocked && captured.batch?.consumed != true, onClick = {
                onImport(captured) { preview = null; confirming = false; message = "Selected data cleared and verified." }
            }) { Text("Delete selected data") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirming = false }) { Text("Cancel") } })
    }
}
