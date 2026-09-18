package com.thehhr.form.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thehhr.form.nativeapp.vault.*

@Composable
fun TransferScreen(state: FormState, onReload: () -> Unit, onImport: (TransferPreview, () -> Unit) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var clearing by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(VaultKind.ROUTINES) }
    var mode by remember { mutableStateOf(TransferMode.ADD) }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var outputLabel by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<TransferPreview?>(null) }
    var confirming by remember { mutableStateOf<TransferPreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    if (clearing) {
        ClearDataScreen(state, onReload, onImport) { clearing = false }
        return
    }
    val busy = state.loading || state.vaultBusy || state.workoutBusy
    val importBlocked = busy || state.reloadRequired || state.vault == null || state.workout != null || state.workoutLoadFailed
    val codec = remember(state.exercises) { ClipboardCodec(state.exercises.map { it.id }.toSet()) }
    val settingsCodec = remember(state.exercises, state.builtInEquipment) { SettingsClipboardCodec(state.exercises.map { it.id }.toSet(), state.builtInEquipment) }
    val planner = remember(state.builtInExerciseIds, state.builtInEquipment) { SettingsDataPlanner(state.builtInExerciseIds, state.builtInEquipment) }
    val available = remember(state.vault, kind, codec, settingsCodec) {
        runCatching {
            val source = requireNotNull(state.vault?.file(kind.fileName)) { "Choose a vault with ${kind.fileName} first" }
            when (kind) {
                VaultKind.ROUTINES -> codec.readRoutines(source).map { it.id to it.name }
                VaultKind.MEALS -> codec.readMeals(source).map { it.id to it.name }
                VaultKind.TRAINING_LOGS -> settingsCodec.readLogs(source).map { it.id to "${it.date} · ${it.exerciseId}" }
                VaultKind.NUTRITION_DIARY -> settingsCodec.readDiary(source).keys.map { it to it }
                VaultKind.CONFIG -> ConfigCodec.customExerciseRecords(source).map { it.id to it.name }
            }
        }
    }
    fun attempt(action: () -> Unit) {
        try { message = null; action() } catch (error: Exception) { message = error.message ?: "Transfer failed; use manual text" }
    }
    fun clipboard(): ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    BackHandler { if (!busy) onBack() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack, enabled = !busy) { Text("Back to Settings") }
            Text("Import and export", style = MaterialTheme.typography.headlineSmall)
            Text("Web-compatible plain clipboard text; JSON imports are explicitly rejected where native storage cannot retain every field. Invalid or lossy values are errors, never silently dropped.")
            OutlinedButton(onClick = { clearing = true }, enabled = !busy && preview == null) { Text("Clear selected data…") }
            Text("SAF cannot atomically write multiple files. A failed batch may be partially applied. Operation-specific originals and planned outputs are retained in device-private vault-recovery; recovery requires manual file comparison/access. No automatic rollback or destructive retry.", style = MaterialTheme.typography.bodySmall)
            Text("Routine IDs, liked and secondary flags are not exported. Meal liked flags are not exported. Imported records start without these flags; routines receive new IDs. Exercise IDs and meal IDs are retained.", style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(VaultKind.entries) { value ->
                    FilterChip(selected = kind == value, enabled = !busy && preview == null, onClick = {
                        kind = value; selected = null; output = ""; message = null
                    }, label = { Text(if (value == VaultKind.CONFIG) "Custom exercises" else value.heading) })
                }
            }
        }
        item {
            Text("Export loaded snapshot", style = MaterialTheme.typography.titleLarge)
            Text("${state.vault?.folderName ?: "No vault"} · ${kind.fileName}. This is the loaded snapshot, not a fresh disk read. Reload does not refresh an existing import preview or generated export.")
            OutlinedButton(onClick = onReload, enabled = !busy && state.vault != null) { Text("Reload from disk") }
            available.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected == null, { selected = null }, enabled = !busy, label = { Text("All") }) }
                items(available.getOrDefault(emptyList())) { (id, name) ->
                    FilterChip(selected == id, { selected = id }, enabled = !busy, label = { Text(name) })
                }
            }
            Button(enabled = !busy && available.isSuccess && available.getOrDefault(emptyList()).isNotEmpty(), onClick = {
                attempt {
                    val source = requireNotNull(state.vault?.file(kind.fileName))
                    val selection = selected
                    val text = if (kind == VaultKind.ROUTINES) {
                        val records = codec.readRoutines(source).let { records -> if (selection == null) records else records.filter { it.id == selection } }
                        require(records.isNotEmpty()) { "Select an available routine" }
                        codec.exportRoutines(records)
                    } else if (kind == VaultKind.TRAINING_LOGS) {
                        settingsCodec.exportLogs(settingsCodec.readLogs(source).filter { selection == null || it.id == selection })
                    } else if (kind == VaultKind.NUTRITION_DIARY) {
                        settingsCodec.exportDiary(settingsCodec.readDiary(source).filterKeys { selection == null || it == selection })
                    } else if (kind == VaultKind.CONFIG) {
                        settingsCodec.exportCustoms(ConfigCodec.customExerciseRecords(source).filter { selection == null || it.id == selection })
                    } else {
                        val records = codec.readMeals(source).let { records -> if (selection == null) records else records.filter { it.id == selection } }
                        require(records.isNotEmpty()) { "Select an available meal" }
                        codec.exportMeals(records)
                    }
                    require(text.toByteArray(Charsets.UTF_8).size <= ClipboardCodec.MAX_TEXT_BYTES) { "Export exceeds 200 KiB; select a single record" }
                    output = text
                    outputLabel = "${state.vault?.folderName} · ${kind.heading} · loaded snapshot"
                }
            }) { Text("Generate export text") }
            if (output.isNotEmpty()) {
                Text(outputLabel, style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(output, { if (!busy) output = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("Export text · editable / selectable fallback") }, minLines = 3, maxLines = 8)
                OutlinedButton(enabled = !busy, onClick = {
                    attempt {
                        require(output.toByteArray(Charsets.UTF_8).size <= ClipboardCodec.MAX_TEXT_BYTES) { "Clipboard limit is 200 KiB; select smaller text manually" }
                        clipboard().setPrimaryClip(ClipData.newPlainText("Form ${kind.heading}", output))
                        message = "Copied. Manual edits are copied exactly as shown."
                    }
                }) { Text("Copy text") }
            }
        }
        item {
            HorizontalDivider()
            Text("Import ${kind.heading}", style = MaterialTheme.typography.titleLarge)
            Text("Add rejects ID collisions (and library name collisions) rather than skipping records. Diary Add takes the greater water amount per date. Custom Replace removes routine references, likes and logs for removed custom IDs; routine IDs/names and schedule are retained. Empty text cannot clear data.")
            Text("Strict interoperability limits: diary IDs must be safe integers; native UUID diary entries cannot be exported unchanged. Timed seconds must convert exactly to hundredths of a minute; fractional values beyond vault precision are rejected. Custom IDs must be five digits or c- followed by a digit. Use web plain-text exports, not JSON.", style = MaterialTheme.typography.bodySmall)
            if (state.workout != null || state.workoutLoadFailed) Text("Finish or discard the workout draft before importing.", color = MaterialTheme.colorScheme.error)
            if (state.reloadRequired) Text("Reload is required before another write.", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferMode.entries.forEach { value ->
                    FilterChip(mode == value, { mode = value }, enabled = !busy && preview == null, label = { Text(if (value == TransferMode.ADD) "Add" else "Replace") })
                }
            }
            OutlinedTextField(input, { text ->
                if (text.toByteArray(Charsets.UTF_8).size <= ClipboardCodec.MAX_TEXT_BYTES) input = text
                else message = "Text exceeds 200 KiB; previous draft retained."
            }, Modifier.fillMaxWidth(), enabled = !busy && preview == null,
                label = { Text("Paste or type clipboard text (200 KiB maximum)") }, minLines = 4, maxLines = 10)
            OutlinedButton(enabled = !busy && preview == null, onClick = {
                attempt {
                    val clip = requireNotNull(clipboard().primaryClip) { "Clipboard is empty. Paste text manually instead." }
                    require(clip.itemCount == 1) { "Clipboard contains multiple items; paste a single text selection manually" }
                    val text = requireNotNull(clip.getItemAt(0).text) { "Clipboard is not plain text; paste manually" }.toString()
                    require(text.toByteArray(Charsets.UTF_8).size <= ClipboardCodec.MAX_TEXT_BYTES) { "Clipboard exceeds 200 KiB; paste a smaller selection" }
                    input = text
                }
            }) { Text("Read clipboard") }
            Button(enabled = !importBlocked && preview == null, onClick = {
                attempt {
                    val snapshot = requireNotNull(state.vault)
                    if (kind == VaultKind.ROUTINES) ConfigCodec.customExerciseRecords(requireNotNull(snapshot.file(VaultFiles.CONFIG)))
                    preview = if (kind == VaultKind.ROUTINES || kind == VaultKind.MEALS) codec.preview(snapshot, kind, input, mode)
                    else planner.preview(snapshot, kind, input, mode)
                }
            }) { Text("Preview import") }
        }
        preview?.let { captured ->
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${if (captured.mode == TransferMode.ADD) "Add" else "Replace"} preview · ${captured.count} incoming records", style = MaterialTheme.typography.titleMedium)
                        Text("Target: ${captured.kind.fileName}\nVault: ${captured.uri}\nBefore: ${captured.beforeCount}")
                        captured.batch?.summary?.forEach { Text(it) }
                        if (captured.batch == null) Text("After: ${if (captured.mode == TransferMode.ADD) captured.beforeCount + captured.count else captured.count}")
                        captured.batch?.let { Text("Planned files: ${it.desired.keys.joinToString()}") }
                        Text("All parsed records are listed below; none are silently excluded. Parsed IDs and the original file are captured until you explicitly discard this preview. Reload does not rebase it.")
                        captured.names.forEachIndexed { index, name -> Text("${index + 1}. $name") }
                        TextButton(onClick = {
                            if (captured.mode == TransferMode.REPLACE) confirming = captured
                            else onImport(captured) { if (preview === captured) { preview = null; message = "Import saved and verified." } }
                        }, enabled = !importBlocked && captured.batch?.consumed != true) { Text(if (captured.mode == TransferMode.ADD) "Add records" else "Review Replace confirmation") }
                        TextButton(onClick = { preview = null; confirming = null }, enabled = !busy) { Text("Discard preview / edit text") }
                    }
                }
            }
        }
        item {
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    confirming?.let { captured ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirming = null },
            title = { Text("Replace all ${captured.kind.heading}?") },
            text = { Text("Replace ${captured.beforeCount} existing records with ${captured.count} previewed records.\n${captured.uri}\n" + (captured.batch?.summary?.joinToString("\n") ?: "Only ${captured.kind.fileName} is written. Scheduled routines cannot be removed or changed.") + "\nSAF writes are not atomic across files. Partial failure requires reload and manual recovery from retained backups. This confirmation never rebases the preview.") },
            confirmButton = { TextButton(enabled = !importBlocked && captured.batch?.consumed != true, onClick = {
                onImport(captured) { if (preview === captured) { preview = null; confirming = null; message = "Replacement saved and verified." } }
            }) { Text("Replace") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirming = null }) { Text("Cancel") } }
        )
    }
}
