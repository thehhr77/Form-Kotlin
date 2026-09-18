package com.thehhr.form.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thehhr.form.nativeapp.vault.RoutineMath
import com.thehhr.form.nativeapp.vault.VaultCodec
import com.thehhr.form.nativeapp.vault.VaultFiles
import com.thehhr.form.nativeapp.vault.VaultMeal
import com.thehhr.form.nativeapp.vault.VaultRoutine
import com.thehhr.form.nativeapp.vault.VaultRoutineItem
import com.thehhr.form.nativeapp.vault.VaultTrainingLog
import com.thehhr.form.nativeapp.workout.ExerciseRules
import java.math.BigDecimal
import java.util.UUID

internal fun FormState.configWriteBlockReason(): String? = when {
    loading -> "Wait for loading to finish before saving changes."
    vaultBusy -> "A vault operation is in progress. Changes are temporarily disabled."
    reloadRequired -> "Reload from disk before saving further changes."
    vault == null -> "Choose a vault in Settings to save favorites."
    VaultFiles.CONFIG in data.missingFiles || vault?.files?.containsKey(VaultFiles.CONFIG) != true -> "config.md is unavailable. Restore it and reload before saving changes."
    else -> null
}

@Composable
internal fun VaultStatus(state: FormState, onReload: () -> Unit) {
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val diagnostics = (state.data.diagnostics + state.vault?.warnings.orEmpty()).distinct()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.loading || state.vaultBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (state.loading) "Loading Form…" else "Vault operation in progress…", style = MaterialTheme.typography.bodySmall)
        }
        if (state.vault == null && !state.loading && !state.vaultBusy) {
            Text("No vault selected. Choose a folder in Settings to browse saved data.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.reloadRequired) {
            Text("Reload required. Displayed data may be out of date; saving is disabled.", color = MaterialTheme.colorScheme.error)
            if (state.vault != null) OutlinedButton(onClick = onReload, enabled = !state.loading && !state.vaultBusy) { Text("Reload from disk") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.data.missingFiles.isNotEmpty()) {
            Text("Missing files", style = MaterialTheme.typography.titleSmall)
            state.data.missingFiles.sorted().forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("Missing data is not treated as an empty or completed log.", style = MaterialTheme.typography.bodySmall)
        }
        if (diagnostics.isNotEmpty()) {
            Text("Some vault content could not be fully interpreted. These views may be incomplete.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showDiagnostics = !showDiagnostics }) { Text("${if (showDiagnostics) "Hide" else "Show"} diagnostics (${diagnostics.size})") }
            if (showDiagnostics) diagnostics.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun VaultPlansScreen(
    state: FormState,
    onOpenExercise: (Exercise) -> Unit,
    onReload: () -> Unit,
    onSaveRoutine: ((VaultRoutine?, VaultRoutine?, () -> Unit) -> Unit)? = null,
    onSaveMeal: ((VaultMeal?, VaultMeal?, () -> Unit) -> Unit)? = null,
    onSetSchedule: ((Int, String?, String?, () -> Unit) -> Unit)? = null,
    onStartWorkout: ((VaultRoutine) -> Unit)? = null
) {
    var meals by rememberSaveable { mutableStateOf(false) }
    val catalog = remember(state.exercises) { state.exercises.groupBy { it.id } }
    val source = state.vault?.file(VaultFiles.ROUTINES)
    val canRewrite = remember(source) { source?.let { VaultCodec.parseRoutines(it).canRewrite } == true }
    val writeBlock = if (onSaveRoutine == null) "Routine editing is read-only." else routineWriteBlockReason(state, canRewrite)
    val configMissing = state.vault?.file(VaultFiles.CONFIG) == null || VaultFiles.CONFIG in state.data.missingFiles
    val renameDeleteBlock = writeBlock ?: if (configMissing) "Restore config.md and reload before renaming or deleting routines." else null
    var editing by remember(state.vault?.uri) { mutableStateOf<RoutineEditorSession?>(null) }
    var renaming by remember(state.vault?.uri) { mutableStateOf<VaultRoutine?>(null) }
    var deleting by remember(state.vault?.uri) { mutableStateOf<VaultRoutine?>(null) }
    val mealEditor = remember(state.vault?.uri) { MealEditorState() }
    val mealWriteBlock = mealWriteBlockReason(state, onSaveMeal != null)
    val scheduleWriteBlock = if (onSetSchedule == null) "Schedule editing is read-only." else state.configWriteBlockReason()
    var scheduling by remember(state.vault?.uri) { mutableStateOf<ScheduleEditorSession?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !meals, onClick = { meals = false }, label = { Text("Routines") })
            FilterChip(selected = meals, onClick = { meals = true }, label = { Text("Meals") })
        }
        key(meals, state.vault?.uri) {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "status") { VaultStatus(state, onReload) }
                item(key = "heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (meals) "Saved meal library" else "Saved routines", style = MaterialTheme.typography.headlineSmall)
                        Text(if (meals) "Saved meal definitions, not a nutrition diary. Editing or deleting a meal keeps recorded nutrition history." else if (onSaveRoutine == null) "Read-only routine definitions from routines.md." else "Create and edit routines in your vault. Deleting a routine keeps your training log history.")
                        val blockReason = if (meals) mealWriteBlock else writeBlock
                        blockReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (meals) {
                    vaultMealItems(state, mealEditor, mealWriteBlock, onSaveMeal)
                } else {
                    item(key = "schedule") {
                        VaultWeeklySchedule(state, scheduleWriteBlock) { day ->
                            scheduling = ScheduleEditorSession(day, state.data.config.schedule[day])
                        }
                    }
                    if (state.data.routines.isEmpty()) item(key = "empty-routines") {
                        Text(vaultEmptyText(state, VaultFiles.ROUTINES, "routines"))
                    }
                    itemsIndexed(state.data.routines, key = { index, routine -> "routine:$index:${routine.id}" }) { _, routine ->
                        VaultRoutineCard(
                            routine, catalog, writeBlock, renameDeleteBlock, onOpenExercise,
                            onRename = { renaming = routine },
                            onDelete = { deleting = routine },
                            onLiked = { if (writeBlock == null) onSaveRoutine?.invoke(routine, routine.copy(liked = it), {}) },
                            onSecondary = { if (writeBlock == null) onSaveRoutine?.invoke(routine, routine.copy(secondary = it), {}) },
                            onEditItems = { editing = RoutineEditorSession(routine, routine) },
                            onStart = onStartWorkout?.let { callback -> { callback(routine) } }
                        )
                    }
                    if (onSaveRoutine != null) {
                        item(key = "add-routine") {
                            OutlinedButton(onClick = {
                                editing = RoutineEditorSession(null, VaultRoutine(UUID.randomUUID().toString(), ""))
                            }, enabled = writeBlock == null) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add routine") }
                        }
                    }
                }
            }
        }
    }
    MealEditorDialogs(state, mealEditor, mealWriteBlock, onSaveMeal)
    scheduling?.let { session ->
        key(state.vault?.uri, session) {
            ScheduleEditorDialog(
                session, state.data.routines, scheduleWriteBlock, state.error,
                busy = state.loading || state.vaultBusy,
                onDismiss = { scheduling = null },
                onSave = { desired ->
                    if (scheduleWriteBlock == null) onSetSchedule?.invoke(session.day, session.original, desired) {
                        if (scheduling === session) scheduling = null
                    }
                }
            )
        }
    }
    editing?.let { session ->
        key(session) {
            RoutineEditorDialog(
                session.routine, session.original == null, state.data.routines, state.exercises, state.data.trainingLogs, writeBlock,
                onDismiss = { editing = null },
                onSave = { replacement ->
                    if (writeBlock == null) onSaveRoutine?.invoke(session.original, replacement) {
                        if (editing === session) editing = null
                    }
                }
            )
        }
    }
    renaming?.let { original ->
        key(original) {
            TextPromptDialog("Rename routine", "Routine name", original.name, renameDeleteBlock,
                hint = "Weekly schedule assignments pointing at the old name are updated to the new name.",
                validate = { routineNameError(it, state.data.routines, original) },
                onDismiss = { renaming = null }
            ) { name ->
                if (renameDeleteBlock == null) onSaveRoutine?.invoke(original, original.copy(name = name)) {
                    if (renaming === original) renaming = null
                }
            }
        }
    }
    deleting?.let { original ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete routine?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\"${original.name}\" will be removed from routines.md. Recorded training history is kept. Weekly schedule assignments pointing at this routine are cleared automatically.")
                    renameDeleteBlock?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameDeleteBlock == null) onSaveRoutine?.invoke(original, null) {
                        if (deleting === original) deleting = null
                    }
                }, enabled = renameDeleteBlock == null) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

private data class RoutineEditorSession(val original: VaultRoutine?, val routine: VaultRoutine)

private fun routineNameError(value: String, routines: List<VaultRoutine>, original: VaultRoutine?): String? = when {
    value.any { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' } -> "Use a single-line name."
    value.trim().isEmpty() -> "Enter a routine name."
    value.trim().length > 40 -> "Use at most 40 characters."
    routines.any { it.id != original?.id && it.name.trim().equals(value.trim(), true) } -> "A routine with this name already exists."
    else -> null
}

private fun routineWriteBlockReason(state: FormState, canRewrite: Boolean): String? = when {
    state.loading -> "Wait for loading to finish before editing routines."
    state.vaultBusy -> "A vault operation is in progress. Changes are temporarily disabled."
    state.reloadRequired -> "Reload from disk before editing routines."
    state.vault == null -> "Choose a vault in Settings to edit routines."
    VaultFiles.ROUTINES in state.data.missingFiles || state.vault?.file(VaultFiles.ROUTINES) == null -> "routines.md is missing. Restore it and reload before editing."
    !canRewrite -> "routines.md contains content Form cannot safely rewrite. Edit it as text, or resolve the issues and reload."
    else -> null
}

@Composable
private fun TextPromptDialog(title: String, label: String, initialValue: String, blockReason: String?, hint: String? = null, validate: (String) -> String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initialValue) }
    val error = validate(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = blockReason == null, isError = error != null)
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                blockReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { if (validate(value) == null && blockReason == null) onConfirm(value.trim()) }, enabled = error == null && blockReason == null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaultRoutineCard(
    routine: VaultRoutine,
    catalog: Map<String, List<Exercise>>,
    writeBlock: String?,
    renameDeleteBlock: String?,
    onOpenExercise: (Exercise) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onLiked: (Boolean) -> Unit,
    onSecondary: (Boolean) -> Unit,
    onEditItems: () -> Unit,
    onStart: (() -> Unit)? = null
) {
    var expanded by rememberSaveable(routine.id) { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(routine.name.ifBlank { "Unnamed routine" }, style = MaterialTheme.typography.titleLarge)
            Text("${routine.items.size} exercises", style = MaterialTheme.typography.bodyMedium)
            if (routine.liked || routine.secondary) Text(listOfNotNull(if (routine.liked) "Favorite" else null, if (routine.secondary) "Secondary" else null).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide exercises" else "Show exercises") }
                TextButton(onClick = onEditItems, enabled = writeBlock == null) { Text("Edit") }
                if (onStart != null && routine.items.isNotEmpty()) Button(onClick = onStart, enabled = writeBlock == null) { Text("Start workout") }
            }
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(routine.liked, { onLiked(!routine.liked) }, enabled = writeBlock == null, label = { Text("Favorite") }, leadingIcon = { Icon(Icons.Default.Favorite, null) })
                    FilterChip(routine.secondary, { onSecondary(!routine.secondary) }, enabled = writeBlock == null, label = { Text("Secondary") })
                    TextButton(onClick = onRename, enabled = renameDeleteBlock == null) { Text("Rename") }
                    TextButton(onClick = onDelete, enabled = renameDeleteBlock == null) { Text("Delete") }
                }
                renameDeleteBlock?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (routine.items.isEmpty()) Text("No exercise entries available in this routine.")
                routine.items.forEachIndexed { index, item ->
                    val matches = catalog[item.exerciseId].orEmpty()
                    val exercise = matches.singleOrNull()
                    HorizontalDivider()
                    Text("${index + 1}. ${exercise?.name ?: if (matches.isEmpty()) "Unknown exercise (${item.exerciseId})" else "Ambiguous exercise ID (${item.exerciseId})"}", style = MaterialTheme.typography.titleMedium)
                    Text("ID: ${item.exerciseId}", style = MaterialTheme.typography.bodySmall)
                    val timed = item.mode.equals("timed", true)
                    val quantity = if (timed) item.unit?.takeIf { it.isNotBlank() } ?: "time units not specified" else "reps"
                    Text("${item.sets} sets × ${item.reps} $quantity")
                    val modifiers = listOfNotNull(item.mode?.let { "Mode: $it" }, if (!timed) item.unit?.let { "Unit: $it" } else null, if (item.weighted) "Weighted" else null, if (item.unweighted) "Unweighted" else null, item.superset?.let { "Superset: $it" })
                    if (modifiers.isNotEmpty()) Text(modifiers.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    if (exercise != null) {
                        TextButton(onClick = { onOpenExercise(exercise) }) { Text("View exercise") }
                    } else {
                        Text(if (matches.isEmpty()) "This ID is not in the available exercise catalog." else "More than one exercise uses this ID; no exercise was selected automatically.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineEditorDialog(
    routine: VaultRoutine,
    creating: Boolean,
    routines: List<VaultRoutine>,
    exercises: List<Exercise>,
    history: List<VaultTrainingLog>,
    blockReason: String?,
    onDismiss: () -> Unit,
    onSave: (VaultRoutine) -> Unit
) {
    var name by remember { mutableStateOf(routine.name) }
    var items by remember { mutableStateOf(routine.items) }
    var picking by remember { mutableStateOf(false) }
    var linkingFrom by remember { mutableStateOf<String?>(null) }
    var reseedNotes by remember { mutableStateOf(mapOf<String, String>()) }
    val usedIds = items.map { it.exerciseId }.toSet()
    val catalog = remember(exercises) { exercises.groupBy { it.id } }
    val nameError = routineNameError(name, routines, if (creating) null else routine)
    val itemsValid = items.all { it.sets in 1..20 && it.reps in 0..RoutineMath.REPS_EDITOR_CAP } && usedIds.size == items.size
    val capApplies = items.any { it.reps > RoutineMath.REPS_PARSE_CAP }
    val enabled = blockReason == null
    fun exerciseFor(item: VaultRoutineItem): Exercise? = catalog[item.exerciseId]?.singleOrNull()
    fun updateItem(index: Int, transform: (VaultRoutineItem) -> VaultRoutineItem) {
        if (enabled) items = items.mapIndexed { i, item -> if (i == index) transform(item) else item }
    }
    fun changeMode(index: Int, newMode: String?) {
        val current = items.getOrNull(index) ?: return
        val exercise = exerciseFor(current)
        val oldEffective = ExerciseRules.resolvedMode(current.mode, exercise)
        val newEffective = ExerciseRules.resolvedMode(newMode, exercise)
        if (oldEffective == newEffective) {
            updateItem(index) { it.copy(mode = newMode) }
            return
        }
        if (newEffective == "timed") {
            val unit = ExerciseRules.resolvedUnit(null, exercise)
            val reps = RoutineMath.timedPrescription(history, current.exerciseId, unit == "sec")
            val note = if (RoutineMath.lastLoggedDurationMinutes(history, current.exerciseId) == null)
                "No timed history; duration set to the default $reps $unit."
            else "Switched to timed; duration re-derived from the last log: $reps $unit."
            updateItem(index) { it.copy(mode = newMode, unit = unit, reps = reps) }
            reseedNotes = reseedNotes + (current.exerciseId to note)
        } else {
            val reps = RoutineMath.strengthPrescription(history, current.exerciseId, current.reps)
            val note = if (RoutineMath.lastLoggedReps(history, current.exerciseId) == null)
                "No reps history; reps set to $reps."
            else "Switched to reps; prescription re-derived from the last log: $reps reps."
            updateItem(index) { it.copy(mode = newMode, reps = reps) }
            reseedNotes = reseedNotes + (current.exerciseId to note)
        }
    }
    fun changeUnit(index: Int, newUnit: String?) {
        val current = items.getOrNull(index) ?: return
        val exercise = exerciseFor(current)
        val oldEffective = ExerciseRules.resolvedUnit(current.unit, exercise)
        val newEffective = ExerciseRules.resolvedUnit(newUnit, exercise)
        if (ExerciseRules.resolvedMode(current.mode, exercise) != "timed" || oldEffective == newEffective) {
            updateItem(index) { it.copy(unit = newUnit) }
            return
        }
        val reps = RoutineMath.timedPrescription(history, current.exerciseId, newEffective == "sec")
        val unitLabel = if (newEffective == "sec") "sec" else "min"
        val note = if (RoutineMath.lastLoggedDurationMinutes(history, current.exerciseId) == null)
            "No timed history; duration set to the default $reps $unitLabel."
        else "Switched to $unitLabel; duration re-derived from the last log: $reps $unitLabel."
        updateItem(index) { it.copy(unit = newUnit, reps = reps) }
        reseedNotes = reseedNotes + (current.exerciseId to note)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "New routine" else "Edit exercises · ${routine.name.ifBlank { "Unnamed routine" }}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (creating) item(key = "name") {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Routine name") }, singleLine = true, enabled = enabled, isError = nameError != null)
                    }
                    if (items.isEmpty()) item(key = "empty") { Text("No exercises yet. Add one from the catalog.") }
                    itemsIndexed(items, key = { index, item -> if (usedIds.size == items.size) "item:${item.exerciseId}" else "item:$index:${item.exerciseId}" }) { index, item ->
                        val exercise = exerciseFor(item)
                        val timed = ExerciseRules.resolvedMode(item.mode, exercise) == "timed"
                        val partner = item.superset?.let { token -> items.firstOrNull { it.exerciseId != item.exerciseId && it.superset == token } }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${index + 1}. ${exercise?.name ?: item.exerciseId}", style = MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.exerciseId, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = {
                                    if (enabled && index in 1..items.lastIndex) items = items.toMutableList().apply { add(index - 1, removeAt(index)) }
                                }, enabled = enabled && index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                                IconButton(onClick = {
                                    if (enabled && index in 0 until items.lastIndex) items = items.toMutableList().apply { add(index + 1, removeAt(index)) }
                                }, enabled = enabled && index < items.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                                IconButton(onClick = {
                                    if (enabled) {
                                        val superset = items.getOrNull(index)?.superset
                                        items = items.filterIndexed { i, _ -> i != index }.map {
                                            if (superset != null && it.superset == superset) it.copy(superset = null) else it
                                        }
                                    }
                                }, enabled = enabled) { Icon(Icons.Default.Close, "Remove exercise") }
                            }
                            RoutineIntegerControl("Sets", item.sets, 1..20, enabled) { value -> updateItem(index) { it.copy(sets = value) } }
                            RoutineIntegerControl(if (timed) "Duration" else "Reps", item.reps, if (timed) 0..RoutineMath.REPS_PARSE_CAP else 0..RoutineMath.REPS_EDITOR_CAP, enabled) { value ->
                                updateItem(index) { it.copy(reps = value) }
                                reseedNotes = reseedNotes - item.exerciseId
                            }
                            RoutineChoiceControl("Mode", item.mode ?: "default", listOf("default", "reps", "timed"), enabled) { value ->
                                changeMode(index, value.takeUnless { mode -> mode == "default" })
                            }
                            RoutineChoiceControl("Unit", item.unit ?: "default", listOf("default", "sec", "min"), enabled) { value ->
                                changeUnit(index, value.takeUnless { unit -> unit == "default" })
                            }
                            RoutineChoiceControl("Weight", if (item.weighted && item.unweighted) "mixed" else if (item.weighted) "weighted" else if (item.unweighted) "unweighted" else "default", listOf("default", "weighted", "unweighted"), enabled) { value ->
                                updateItem(index) { it.copy(weighted = value == "weighted", unweighted = value == "unweighted") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    partner?.let { "Superset with ${catalog[it.exerciseId]?.singleOrNull()?.name ?: it.exerciseId}" } ?: "Standalone",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = {
                                    when {
                                        linkingFrom == item.exerciseId -> linkingFrom = null
                                        linkingFrom != null -> {
                                            val firstId = requireNotNull(linkingFrom)
                                            linkingFrom = null
                                            items = RoutineMath.pairSuperset(items, firstId, item.exerciseId)
                                        }
                                        item.superset != null -> items = RoutineMath.unlinkSuperset(items, item.exerciseId)
                                        else -> linkingFrom = item.exerciseId
                                    }
                                }, enabled = enabled && (item.superset != null || items.size >= 2)) {
                                    Text(when {
                                        linkingFrom == item.exerciseId -> "Cancel pairing"
                                        linkingFrom != null -> "Pair with selected"
                                        item.superset != null -> "Unpair"
                                        else -> "Pair"
                                    })
                                }
                            }
                            if (partner != null) Text("Pairs are kept as exactly two exercises; removing either member clears the pair.", style = MaterialTheme.typography.bodySmall)
                            reseedNotes[item.exerciseId]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            HorizontalDivider()
                        }
                    }
                }
                linkingFrom?.let { selected ->
                    Text("Pairing: select the exercise to pair with ${catalog[selected]?.singleOrNull()?.name ?: selected}, or tap Cancel pairing.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { picking = true }, enabled = enabled) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add exercise") }
                nameError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!itemsValid) Text("Use unique exercises, sets 1–20 and reps/duration 0–100.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (capApplies) Text("Reps above 60 are saved as 60 (the routines.md parsing cap).", style = MaterialTheme.typography.bodySmall)
                blockReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (enabled && nameError == null && itemsValid) {
                    linkingFrom = null
                    onSave(routine.copy(name = name.trim(), items = items.map { it.copy(reps = RoutineMath.clampForPersistence(it.reps)) }))
                }
            }, enabled = enabled && nameError == null && itemsValid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (picking) {
        var query by remember { mutableStateOf("") }
        val results = remember(query, exercises) {
            val search = query.trim()
            exercises.filter { it.name.contains(search, true) || it.id.contains(search, true) }.sortedBy { it.name }
        }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Add exercise") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search name or ID") }, singleLine = true)
                    if (results.isEmpty()) Text("No matching exercises.")
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        itemsIndexed(results, key = { index, exercise -> "$index:${exercise.id}" }) { _, exercise ->
                            val alreadyUsed = exercise.id in usedIds
                            val ambiguous = catalog[exercise.id].orEmpty().size != 1
                            TextButton(onClick = {
                                if (enabled && items.none { it.exerciseId == exercise.id } && !ambiguous) {
                                    items = items + VaultRoutineItem(exercise.id)
                                    picking = false
                                }
                            }, enabled = enabled && !alreadyUsed && !ambiguous) {
                                Text("${exercise.name} (${exercise.id})${if (alreadyUsed) " (added)" else if (ambiguous) " (ambiguous ID)" else ""}")
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun RoutineIntegerControl(label: String, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: $value", Modifier.weight(1f))
        TextButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = enabled && value > range.first) { Text("−") }
        TextButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = enabled && value < range.last) { Text("+") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineChoiceControl(label: String, value: String, choices: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        choices.forEach { choice ->
            FilterChip(selected = value == choice, onClick = { onChange(choice) }, enabled = enabled, label = { Text(choice) })
        }
    }
}

@Composable
internal fun VaultMealsScreen(
    state: FormState,
    onReload: () -> Unit,
    onSaveMeal: ((VaultMeal?, VaultMeal?, () -> Unit) -> Unit)? = null
) {
    val editor = remember(state.vault?.uri) { MealEditorState() }
    var mealSearch by rememberSaveable(state.vault?.uri) { mutableStateOf("") }
    val writeBlock = mealWriteBlockReason(state, onSaveMeal != null)
    key(state.vault?.uri) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "status") { VaultStatus(state, onReload) }
            item(key = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saved meal library", style = MaterialTheme.typography.headlineSmall)
                    Text("Saved meal definitions from your vault, not a nutrition diary. Editing or deleting a meal keeps recorded nutrition history; these meals do not indicate what you ate today.")
                    writeBlock?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (state.data.meals.isNotEmpty()) item(key = "meal-search") {
                OutlinedTextField(
                    mealSearch, { mealSearch = it }, Modifier.fillMaxWidth(),
                    label = { Text("Search meals") }, singleLine = true
                )
            }
            vaultMealItems(state, editor, writeBlock, onSaveMeal, mealSearch)
        }
    }
    MealEditorDialogs(state, editor, writeBlock, onSaveMeal)
}

private class MealEditorState {
    var editing by mutableStateOf<MealEditorSession?>(null)
    var deleting by mutableStateOf<VaultMeal?>(null)
}

private data class MealEditorSession(val original: VaultMeal?, val meal: VaultMeal)

@Composable
private fun mealWriteBlockReason(state: FormState, editable: Boolean): String? {
    val source = state.vault?.file(VaultFiles.MEALS)
    val canRewrite = remember(source) { source?.let { VaultCodec.parseMeals(it).canRewrite } == true }
    return when {
        !editable -> "Meal editing is read-only."
        state.loading -> "Wait for loading to finish before editing meals."
        state.vaultBusy -> "A vault operation is in progress. Changes are temporarily disabled."
        state.reloadRequired -> "Reload from disk before editing meals."
        state.vault == null -> "Choose a vault in Settings to edit meals."
        VaultFiles.MEALS in state.data.missingFiles || source == null -> "meals.md is missing. Restore it and reload before editing."
        !canRewrite -> "meals.md contains content Form cannot safely rewrite. Edit it as text, or resolve the issues and reload."
        else -> null
    }
}

private fun LazyListScope.vaultMealItems(
    state: FormState,
    editor: MealEditorState,
    writeBlock: String?,
    onSaveMeal: ((VaultMeal?, VaultMeal?, () -> Unit) -> Unit)?,
    query: String = ""
) {
    val trimmed = query.trim()
    val meals = if (trimmed.isEmpty()) state.data.meals else state.data.meals.filter { it.name.contains(trimmed, ignoreCase = true) }
    if (state.data.meals.isEmpty()) item(key = "empty-meals") {
        Text(vaultEmptyText(state, VaultFiles.MEALS, "meals"))
    }
    else if (meals.isEmpty()) item(key = "no-matching-meals") {
        Text("No matching meals.", style = MaterialTheme.typography.bodySmall)
    }
    itemsIndexed(meals, key = { index, meal -> "meal:$index:${meal.id}" }) { _, meal ->
        VaultMealCard(
            meal, writeBlock,
            onEdit = { if (writeBlock == null) editor.editing = MealEditorSession(meal, meal) },
            onDelete = { if (writeBlock == null) editor.deleting = meal },
            onLiked = { if (writeBlock == null) onSaveMeal?.invoke(meal, meal.copy(liked = it), {}) }
        )
    }
    if (onSaveMeal != null) item(key = "add-meal") {
        OutlinedButton(onClick = {
            if (writeBlock == null) editor.editing = MealEditorSession(
                null,
                VaultMeal("m-${UUID.randomUUID()}", "", cals100 = 165.0, p100 = 20.0, c100 = 10.0, f100 = 5.0, liked = true)
            )
        }, enabled = writeBlock == null) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Add meal")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaultMealCard(meal: VaultMeal, writeBlock: String?, onEdit: () -> Unit, onDelete: () -> Unit, onLiked: (Boolean) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(meal.name.ifBlank { "Unnamed meal" }, style = MaterialTheme.typography.titleLarge)
            Text("Default portion: ${meal.defaultGrams.displayNumber()} g")
            Text("Per 100 g", style = MaterialTheme.typography.titleMedium)
            Text("${meal.cals100.displayNumber()} kcal")
            Text("Protein: ${meal.p100.displayNumber()} g\nCarbohydrate: ${meal.c100.displayNumber()} g\nFat: ${meal.f100.displayNumber()} g")
            Text("ID: ${meal.id}", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(meal.liked, { onLiked(!meal.liked) }, enabled = writeBlock == null, label = { Text("Favorite") }, leadingIcon = { Icon(Icons.Default.Favorite, null) })
                TextButton(onClick = onEdit, enabled = writeBlock == null) { Text("Edit") }
                TextButton(onClick = onDelete, enabled = writeBlock == null) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun MealEditorDialogs(
    state: FormState,
    editor: MealEditorState,
    writeBlock: String?,
    onSaveMeal: ((VaultMeal?, VaultMeal?, () -> Unit) -> Unit)?
) {
    val busy = state.loading || state.vaultBusy
    editor.editing?.let { session ->
        key(state.vault?.uri, session) {
            MealEditorDialog(
                session, state.data.meals, writeBlock, state.error, busy,
                onDismiss = { editor.editing = null },
                onSave = { replacement ->
                    if (writeBlock == null) onSaveMeal?.invoke(session.original, replacement) {
                        if (editor.editing === session) editor.editing = null
                    }
                }
            )
        }
    }
    editor.deleting?.let { original ->
        AlertDialog(
            onDismissRequest = { if (!busy) editor.deleting = null },
            title = { Text("Delete meal?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\"${original.name}\" will be removed from meals.md. Recorded nutrition diary history is kept.")
                    writeBlock?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (writeBlock == null) onSaveMeal?.invoke(original, null) {
                        if (editor.deleting === original) editor.deleting = null
                    }
                }, enabled = writeBlock == null) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { editor.deleting = null }, enabled = !busy) { Text("Cancel") } }
        )
    }
}

private fun mealNameError(value: String, meals: List<VaultMeal>, original: VaultMeal?): String? = when {
    value.any { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' } -> "Use a single-line name."
    value.trim().isEmpty() -> "Enter a meal name."
    value.trim().length > 40 -> "Use at most 40 characters."
    meals.any { it.id != original?.id && it.name.trim().equals(value.trim(), true) } -> "A meal with this name already exists."
    else -> null
}

private fun mealNumber(value: String): Double? {
    val text = value.trim()
    return text.takeIf { Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)").matches(it) }?.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun mealKcalText(protein: String, carbohydrate: String, fat: String): String {
    fun value(text: String): Double = mealNumber(text) ?: 0.0
    return FuelMath.kcalFromMacros(value(protein), value(carbohydrate), value(fat)).toLong().toString()
}

@Composable
private fun MealEditorDialog(
    session: MealEditorSession,
    meals: List<VaultMeal>,
    blockReason: String?,
    saveError: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (VaultMeal) -> Unit
) {
    val meal = session.meal
    var name by remember { mutableStateOf(meal.name) }
    var grams by remember { mutableStateOf(meal.defaultGrams.displayNumber()) }
    var calories by remember { mutableStateOf(meal.cals100.displayNumber()) }
    var protein by remember { mutableStateOf(meal.p100.displayNumber()) }
    var carbohydrate by remember { mutableStateOf(meal.c100.displayNumber()) }
    var fat by remember { mutableStateOf(meal.f100.displayNumber()) }
    var liked by remember { mutableStateOf(meal.liked) }
    val nameError = mealNameError(name, meals, session.original)
    val gramsValue = mealNumber(grams)?.takeIf { it in 1.0..5000.0 }
    val proteinValue = mealNumber(protein)?.takeIf { it in 0.0..999.0 }
    val carbohydrateValue = mealNumber(carbohydrate)?.takeIf { it in 0.0..999.0 }
    val fatValue = mealNumber(fat)?.takeIf { it in 0.0..999.0 }
    val valid = nameError == null && gramsValue != null && proteinValue != null && carbohydrateValue != null && fatValue != null
    val enabled = blockReason == null && !busy
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (session.original == null) "New meal" else "Edit meal") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Meal name") }, singleLine = true, enabled = enabled, isError = nameError != null)
                    nameError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                item {
                    MealNumberField("Default portion (g)", grams, enabled, gramsValue == null, "Enter 1–5000 grams.") { grams = it }
                }
                item { Text("All nutrition values below are per 100 g, not per default portion. Changing the portion does not convert these values.", style = MaterialTheme.typography.bodySmall) }
                item {
                    MealNumberField("Calories per 100 g (kcal)", calories, enabled, false, "") { calories = it }
                    Text("Recomputed from protein, carbs and fat when those change; the leading integer of the kcal field is saved (12.9 saves as 12), and an empty, non-numeric, 0 or above-100000 value falls back to the macro energy 4P+4C+9F.", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    MealNumberField("Protein per 100 g (g)", protein, enabled, proteinValue == null, "Enter 0–999; decimals are allowed.") { value ->
                        protein = value
                        calories = mealKcalText(value, carbohydrate, fat)
                    }
                }
                item {
                    MealNumberField("Carbohydrate per 100 g (g)", carbohydrate, enabled, carbohydrateValue == null, "Enter 0–999; decimals are allowed.") { value ->
                        carbohydrate = value
                        calories = mealKcalText(protein, value, fat)
                    }
                }
                item {
                    MealNumberField("Fat per 100 g (g)", fat, enabled, fatValue == null, "Enter 0–999; decimals are allowed.") { value ->
                        fat = value
                        calories = mealKcalText(protein, carbohydrate, value)
                    }
                }
                item { FilterChip(liked, { liked = !liked }, enabled = enabled, label = { Text("Favorite") }, leadingIcon = { Icon(Icons.Default.Favorite, null) }) }
                blockReason?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                saveError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (enabled && valid) {
                    val protein = requireNotNull(proteinValue)
                    val carbohydrate = requireNotNull(carbohydrateValue)
                    val fat = requireNotNull(fatValue)
                    onSave(meal.copy(
                        name = name.trim(), defaultGrams = requireNotNull(gramsValue),
                        cals100 = FuelMath.mealKcalFallback(calories, protein, carbohydrate, fat),
                        p100 = protein, c100 = carbohydrate, f100 = fat, liked = liked
                    ))
                }
            }, enabled = enabled && valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
private fun MealNumberField(label: String, value: String, enabled: Boolean, invalid: Boolean, error: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled, isError = invalid)
        if (invalid) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private data class ScheduleEditorSession(val day: Int, val original: String?)

private val scheduleDayNames = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

@Composable
private fun VaultWeeklySchedule(state: FormState, writeBlock: String?, onEdit: (Int) -> Unit) {
    val weekStart = state.data.config.preferences["week-start"]?.toIntOrNull()?.takeIf { it in 0..6 } ?: 1
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Weekly schedule", style = MaterialTheme.typography.titleLarge)
            writeBlock?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            repeat(7) { offset ->
                val day = (weekStart + offset) % 7
                val savedName = state.data.config.schedule[day]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(scheduleDayNames[day], style = MaterialTheme.typography.titleSmall)
                        Text(savedName ?: "Rest")
                        if (savedName != null && state.data.routines.count { it.name == savedName } != 1) {
                            Text("Unknown or ambiguous routine; saved name preserved.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { if (writeBlock == null) onEdit(day) }, enabled = writeBlock == null) { Text("Change") }
                }
            }
        }
    }
}

@Composable
private fun ScheduleEditorDialog(
    session: ScheduleEditorSession,
    routines: List<VaultRoutine>,
    blockReason: String?,
    saveError: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var desired by remember { mutableStateOf(session.original) }
    val names = remember(routines) {
        routines.filter { routine ->
            routineNameError(routine.name, emptyList(), null) == null && routine.name == routine.name.trim() &&
                routines.count { it.name.trim().equals(routine.name, true) } == 1
        }.map { it.name }.sorted()
    }
    val enabled = blockReason == null && !busy
    val valid = desired == null || desired in names
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Schedule · ${scheduleDayNames[session.day]}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved when opened: ${session.original ?: "Rest"}")
                Text("Choose a routine by its unique name, or Rest. Unchanged days keep their saved assignments.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    item(key = "rest") {
                        FilterChip(desired == null, { desired = null }, enabled = enabled, label = { Text("Rest") })
                    }
                    itemsIndexed(names, key = { _, name -> "routine:$name" }) { _, name ->
                        FilterChip(desired == name, { desired = name }, enabled = enabled, label = { Text(name) })
                    }
                }
                if (!valid) Text("The saved name is unavailable or ambiguous. It is preserved unless you choose a replacement and save.", style = MaterialTheme.typography.bodySmall)
                blockReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { if (enabled && valid) onSave(desired) }, enabled = enabled && valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

private fun vaultEmptyText(state: FormState, file: String, label: String): String = when {
    state.loading || state.vaultBusy -> "Waiting for vault data…"
    state.vault == null -> "Choose a vault in Settings to browse $label."
    state.reloadRequired -> "Reload required before the available $label can be confirmed."
    file in state.data.missingFiles -> "$file is missing; $label are unavailable."
    state.error != null || state.data.diagnostics.isNotEmpty() || state.vault?.warnings?.isNotEmpty() == true -> "No $label are available in this view. Review the vault diagnostics and errors."
    else -> "No saved $label found in $file."
}

private fun Double.displayNumber(): String = if (isFinite()) BigDecimal.valueOf(this).stripTrailingZeros().toPlainString() else "Unavailable"
