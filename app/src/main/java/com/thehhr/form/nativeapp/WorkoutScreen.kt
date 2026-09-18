package com.thehhr.form.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thehhr.form.nativeapp.workout.ExerciseRules
import com.thehhr.form.nativeapp.workout.WorkoutExerciseState
import com.thehhr.form.nativeapp.workout.WorkoutSetRow
import java.math.BigDecimal
import kotlinx.coroutines.delay

@Composable
internal fun ActiveWorkoutScreen(
    state: FormState,
    onRow: (Int, Int, WorkoutSetRow) -> Unit,
    onIndex: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onPause: () -> Unit,
    onRest: (Int) -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onReload: () -> Unit,
    onAddSet: (Int) -> Unit = {},
    onRemoveSet: (Int) -> Unit = {},
    onMarkAllDone: (Int) -> Unit = {},
    onUndoLast: (Int) -> Unit = {},
    onToggleSecondary: (String) -> Unit = {},
    onOpenExercise: (String) -> Unit = {}
) {
    val session = state.workout
    val busy = state.loading || state.vaultBusy || state.workoutBusy
    val blocked = busy || state.reloadRequired || state.vault == null
    val discardBlocked = busy || state.vault == null || session?.finishing == true
    var confirmingFinish by rememberSaveable(state.vault?.uri, session?.id) { mutableStateOf(false) }
    var confirmingDiscard by rememberSaveable(state.vault?.uri, session?.id) { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "status") { VaultStatus(state) { if (!busy) onReload() } }
        if (state.workoutBusy) item(key = "workout-busy") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Workout operation in progress…", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (session != null) state.workoutError?.let { error ->
            item(key = "workout-error") { Text(error, color = MaterialTheme.colorScheme.error) }
        }
        if (session == null) {
            item(key = "no-session") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No workout in progress.")
                    state.workoutError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                        if (!state.workoutLoadFailed) {
                            Text("Return and start the workout again, or reload the vault to recover.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.workoutLoadFailed) {
                        Text("The saved workout draft could not be read. Reload to try again, or confirm discarding the unreadable draft.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.workoutLoadFailed || state.workoutError != null) {
                        OutlinedButton(onClick = { if (!busy) onReload() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Reload vault") }
                    }
                    if (state.workoutLoadFailed) {
                        OutlinedButton(onClick = { if (!discardBlocked) confirmingDiscard = true }, enabled = !discardBlocked, modifier = Modifier.fillMaxWidth()) { Text("Discard unreadable draft") }
                    }
                }
            }
        } else {
            val editBlocked = blocked || session.finishing
            item(key = "session-date") {
                Text("Original workout date: ${session.date}", style = MaterialTheme.typography.bodyMedium)
            }
            if (session.finishing) item(key = "finishing") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This workout is finishing. Editing, navigation, pause, rest and discard are locked until saving completes.", style = MaterialTheme.typography.bodyMedium)
                    Text(if (busy) "Saving is in progress. Please wait." else if (state.reloadRequired) "Reload the vault, then retry finish to complete saving." else "Retry finish to complete saving.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { if (!blocked) onFinish() }, enabled = !blocked, modifier = Modifier.fillMaxWidth()) { Text("Retry finish") }
                }
            }
            val current = session.exercises.getOrNull(session.currentIndex)
            if (current == null) {
                item(key = "invalid-session") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (session.finishing) "The saved workout is invalid at the current exercise. Reload the vault before retrying finish." else "The saved workout is invalid at the current exercise. Reload it, or discard it and start a new workout.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { if (!busy) onReload() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Reload vault") }
                        OutlinedButton(onClick = { if (!discardBlocked) confirmingDiscard = true }, enabled = !discardBlocked, modifier = Modifier.fillMaxWidth()) { Text("Discard workout") }
                    }
                }
            } else {
                val timed = current.mode.equals("timed", true)
                val partnerIndex = current.superset?.let { superset ->
                    val members = session.exercises.indices.filter { index ->
                        val exercise = session.exercises[index]
                        exercise.superset == superset && !exercise.skipped
                    }
                    if (members.size == 2 && session.currentIndex in members) members.single { it != session.currentIndex } else null
                }
                val nextIncomplete = ExerciseRules.firstIncompleteIndex(session).takeIf { it >= 0 }
                val completedSets = ExerciseRules.completedSetCount(session)
                val completedExercises = ExerciseRules.completedExerciseCount(session)
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(session.routineName, style = MaterialTheme.typography.headlineSmall)
                        Text("Exercise ${session.currentIndex + 1} of ${session.exercises.size}", style = MaterialTheme.typography.bodyMedium)
                        Text(current.exerciseName, style = MaterialTheme.typography.titleLarge)
                        val details = listOfNotNull(
                            current.mode?.let { "Mode: $it" },
                            current.unit?.let { "Unit: $it" },
                            if (current.weighted) "Weighted" else null,
                            if (current.unweighted) "Unweighted" else null,
                            current.superset?.let { "Superset: $it" }
                        )
                        if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onOpenExercise(current.exerciseId) }, modifier = Modifier.fillMaxWidth()) {
                            Text("View exercise details")
                        }
                        if (session.paused && !session.finishing) Text("Workout paused. Sets keep their state and the rest timer keeps running; resume to continue.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Text("Records $completedSets completed sets from $completedExercises completed exercises. Skipped exercises and unmarked sets are excluded.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                session.restDeadline?.let { deadline ->
                    item(key = "rest") { WorkoutRestBar(deadline, editBlocked || session.paused, onRest) }
                }
                item(key = "navigation") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { if (!editBlocked) onIndex(session.currentIndex - 1) }, enabled = !editBlocked && session.currentIndex > 0, modifier = Modifier.weight(1f)) { Text("Previous") }
                            OutlinedButton(onClick = { if (!editBlocked) onIndex(session.currentIndex + 1) }, enabled = !editBlocked && session.currentIndex < session.exercises.lastIndex, modifier = Modifier.weight(1f)) { Text("Next") }
                        }
                        partnerIndex?.let { index ->
                            OutlinedButton(onClick = { if (!editBlocked) onIndex(index) }, enabled = !editBlocked, modifier = Modifier.fillMaxWidth()) {
                                Text("Jump to superset partner: ${session.exercises[index].exerciseName}")
                            }
                        }
                        if (nextIncomplete != null && nextIncomplete != session.currentIndex) {
                            OutlinedButton(onClick = { if (!editBlocked) onIndex(nextIncomplete) }, enabled = !editBlocked, modifier = Modifier.fillMaxWidth()) {
                                Text("Jump to next incomplete: ${session.exercises[nextIncomplete].exerciseName}")
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (!editBlocked) onPause() }, enabled = !editBlocked) { Text(if (session.finishing) "Pause unavailable" else if (session.paused) "Resume" else "Pause") }
                            FilterChip(current.skipped, { if (!editBlocked && !session.paused) onSkip(session.currentIndex) }, enabled = !editBlocked && !session.paused, label = { Text(if (current.skipped) "Skipped · undo skip" else "Skip exercise") })
                        }
                    }
                }
                item(key = "sets-heading") { Text("Sets", style = MaterialTheme.typography.titleMedium) }
                if (current.skipped) item(key = "skipped-note") {
                    Text("This exercise is skipped. Its sets are locked and excluded from the finished log.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                itemsIndexed(current.sets, key = { setIndex, _ -> "set:${current.exerciseId}:$setIndex" }) { setIndex, row ->
                    WorkoutSetEditor(session.id, current, row, setIndex, session.currentIndex, session.paused, editBlocked, onRow)
                }
                item(key = "set-actions:${current.exerciseId}") {
                    val setActionsEnabled = !editBlocked && !session.paused && !current.skipped
                    val allDone = current.sets.isNotEmpty() && current.sets.all { it.done }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { if (setActionsEnabled) onAddSet(session.currentIndex) },
                                enabled = setActionsEnabled && current.sets.size < ExerciseRules.LIMIT_SETS,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (timed) "Add interval" else "Add set") }
                            OutlinedButton(
                                onClick = { if (setActionsEnabled) onRemoveSet(session.currentIndex) },
                                enabled = setActionsEnabled && current.sets.size > 1,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (timed) "Remove interval" else "Remove set") }
                        }
                        Button(
                            onClick = { if (setActionsEnabled && !allDone) onMarkAllDone(session.currentIndex) else if (setActionsEnabled) onUndoLast(session.currentIndex) },
                            enabled = setActionsEnabled && current.sets.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (allDone) "Undo last completed" else "Mark all done") }
                    }
                }
                val secondaryRoutines = state.data.routines.filter { it.secondary && it.items.isNotEmpty() && it.id != session.routineId }
                if (secondaryRoutines.isNotEmpty() && !session.finishing) item(key = "secondary-routines") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Secondary routines", style = MaterialTheme.typography.titleMedium)
                        Text("Toggle routines to append their exercises to this workout. Existing sets are kept.", style = MaterialTheme.typography.bodySmall)
                        secondaryRoutines.forEach { routine ->
                            FilterChip(
                                selected = routine.id in session.secondaryIds,
                                onClick = { if (!editBlocked) onToggleSecondary(routine.id) },
                                enabled = !editBlocked,
                                label = { Text(routine.name) }
                            )
                        }
                    }
                }
                item(key = "finish") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!session.finishing) {
                            Button(onClick = { if (!blocked && completedSets > 0) confirmingFinish = true }, enabled = !blocked && completedSets > 0, modifier = Modifier.fillMaxWidth()) { Text("Finish workout") }
                            if (completedSets == 0) {
                                Text("Mark at least one set done to finish. Skipped exercises cannot provide completed sets.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OutlinedButton(onClick = { if (!discardBlocked) confirmingDiscard = true }, enabled = !discardBlocked, modifier = Modifier.fillMaxWidth()) { Text("Discard workout") }
                        if (!session.finishing) Text("Discarding deletes the draft without saving anything to the training log.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (session != null && !session.finishing && confirmingFinish) {
        val completedSets = ExerciseRules.completedSetCount(session)
        AlertDialog(
            onDismissRequest = { if (!busy) confirmingFinish = false },
            title = { Text("Finish workout?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\"${session.routineName}\" records $completedSets completed sets. Skipped exercises and unmarked sets are not saved to the training log.")
                    state.workoutError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!blocked && completedSets > 0) {
                        confirmingFinish = false
                        onFinish()
                    }
                }, enabled = !blocked && completedSets > 0) { Text("Finish") }
            },
            dismissButton = { TextButton(onClick = { confirmingFinish = false }, enabled = !busy) { Text("Cancel") } }
        )
    }
    if ((session != null || state.workoutLoadFailed) && session?.finishing != true && confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmingDiscard = false },
            title = { Text(if (session == null) "Discard unreadable draft?" else "Discard workout?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (session == null) "The unreadable workout draft for this vault will be permanently deleted without saving it to the training log. It cannot be recovered. Existing training history is kept." else "Only the \"${session.routineName}\" draft will be deleted and its live-saved records for this session will be removed. Unsaved completed sets are lost and the draft cannot be recovered. Existing training history is kept.")
                    state.workoutError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!discardBlocked) {
                        confirmingDiscard = false
                        onDiscard()
                    }
                }, enabled = !discardBlocked) { Text(if (session == null) "Discard unreadable draft" else "Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }, enabled = !busy) { Text("Cancel") } }
        )
    }
}

@Composable
private fun WorkoutRestBar(deadline: Long, busy: Boolean, onRest: (Int) -> Unit) {
    var now by remember(deadline) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (System.currentTimeMillis() < deadline) {
            delay(1000)
            now = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()
    }
    val remainingMillis = (deadline - now).coerceAtLeast(0L)
    val remaining = remainingMillis / 1000 + if (remainingMillis % 1000 > 0) 1 else 0
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (remaining > 0) "Rest · $remaining s remaining" else "Rest finished", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { if (!busy && remaining > 0) onRest(30) }, enabled = !busy && remaining > 0) { Text("+30 s") }
                TextButton(onClick = { if (!busy) onRest(0) }, enabled = !busy) { Text("Skip rest") }
            }
            Text("The rest timer keeps counting down while the app is closed.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private class WorkoutSetBuffers(row: WorkoutSetRow) {
    var weight by mutableStateOf(workoutDecimal(row.weight))
    var reps by mutableStateOf(row.reps.toString())
    var minutes by mutableStateOf(workoutDecimal(row.durationMin))
    var distance by mutableStateOf(workoutDecimal(row.distanceKm))
}

@Composable
private fun WorkoutSetEditor(
    sessionId: String,
    exercise: WorkoutExerciseState,
    row: WorkoutSetRow,
    setIndex: Int,
    exerciseIndex: Int,
    paused: Boolean,
    busy: Boolean,
    onRow: (Int, Int, WorkoutSetRow) -> Unit
) {
    val timed = exercise.mode.equals("timed", true)
    val seconds = exercise.unit.equals("sec", true)
    val durationLabel = if (seconds) "Seconds" else "Minutes"
    val editable = !busy && !paused && !exercise.skipped && !row.done
    val buffers = remember(sessionId, exercise.exerciseId, setIndex) { WorkoutSetBuffers(row) }
    val weightValue = workoutNumber(buffers.weight)?.takeIf { it in 0.0..2000.0 }
    val repsValue = workoutNumber(buffers.reps, integer = true)?.toInt()?.takeIf { it in 1..100 }
    val minutesValue = workoutNumber(buffers.minutes)?.takeIf { it in 0.0..600.0 }
    val distanceValue = workoutNumber(buffers.distance)?.takeIf { it in 0.0..500.0 }
    val valuesValid = if (timed) minutesValue != null && distanceValue != null else repsValue != null && (!exercise.weighted || weightValue != null)
    LaunchedEffect(row.weight) {
        if (workoutNumber(buffers.weight) != row.weight) buffers.weight = workoutDecimal(row.weight)
    }
    LaunchedEffect(row.reps) {
        if (workoutNumber(buffers.reps, integer = true) != row.reps.toDouble()) buffers.reps = row.reps.toString()
    }
    LaunchedEffect(row.durationMin) {
        if (workoutNumber(buffers.minutes) != row.durationMin) buffers.minutes = workoutDecimal(row.durationMin)
    }
    LaunchedEffect(row.distanceKm) {
        if (workoutNumber(buffers.distance) != row.distanceKm) buffers.distance = workoutDecimal(row.distanceKm)
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Set ${setIndex + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(if (row.done) "Done" else "To do", style = MaterialTheme.typography.bodySmall)
                Checkbox(
                    checked = row.done,
                    onCheckedChange = { done ->
                        if (!busy && !paused && !exercise.skipped && (!done || valuesValid)) {
                            val replacement = when {
                                !done -> row.copy(done = false)
                                timed -> row.copy(durationMin = requireNotNull(minutesValue), distanceKm = requireNotNull(distanceValue), done = true)
                                else -> row.copy(weight = if (exercise.weighted) requireNotNull(weightValue) else row.weight, reps = requireNotNull(repsValue), done = true)
                            }
                            onRow(exerciseIndex, setIndex, replacement)
                        }
                    },
                    enabled = !busy && !paused && !exercise.skipped && (row.done || valuesValid)
                )
            }
            if (timed) {
                WorkoutNumberField(durationLabel, buffers.minutes, editable, minutesValue == null, if (seconds) "Enter 0–600 seconds." else "Enter 0–600 minutes.") { text ->
                    buffers.minutes = text
                    val parsed = workoutNumber(text)?.takeIf { it in 0.0..600.0 }
                    if (editable && parsed != null && parsed != row.durationMin) onRow(exerciseIndex, setIndex, row.copy(durationMin = parsed))
                }
                WorkoutNumberField("Distance (km)", buffers.distance, editable, distanceValue == null, "Enter 0–500 km.") { text ->
                    buffers.distance = text
                    val parsed = workoutNumber(text)?.takeIf { it in 0.0..500.0 }
                    if (editable && parsed != null && parsed != row.distanceKm) onRow(exerciseIndex, setIndex, row.copy(distanceKm = parsed))
                }
            } else {
                if (exercise.weighted) {
                    WorkoutNumberField("Weight (kg)", buffers.weight, editable, weightValue == null, "Enter 0–2000 kg.") { text ->
                        buffers.weight = text
                        val parsed = workoutNumber(text)?.takeIf { it in 0.0..2000.0 }
                        if (editable && parsed != null && parsed != row.weight) onRow(exerciseIndex, setIndex, row.copy(weight = parsed))
                    }
                }
                WorkoutNumberField("Reps", buffers.reps, editable, repsValue == null, "Enter 1–100 reps.") { text ->
                    buffers.reps = text
                    val parsed = workoutNumber(text, integer = true)?.toInt()?.takeIf { it in 1..100 }
                    if (editable && parsed != null && parsed != row.reps) onRow(exerciseIndex, setIndex, row.copy(reps = parsed))
                }
            }
            if (!editable) {
                Text(
                    when {
                        busy -> "Wait for the workout operation to finish before editing sets."
                        paused -> "Resume the workout to edit this set."
                        exercise.skipped -> "This exercise is skipped; its sets are excluded from the training log."
                        else -> "Set recorded. Uncheck done to edit it again."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun WorkoutNumberField(label: String, value: String, enabled: Boolean, invalid: Boolean, error: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value, { if (enabled) onChange(it) }, Modifier.fillMaxWidth(), label = { Text(label) },
            singleLine = true, enabled = enabled, isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = if (label == "Reps") KeyboardType.Number else KeyboardType.Decimal)
        )
        if (invalid && enabled) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private fun workoutDecimal(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private fun workoutNumber(value: String, integer: Boolean = false): Double? {
    val text = value.trim()
    val pattern = if (integer) Regex("[0-9]+") else Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
    return text.takeIf { pattern.matches(it) }?.toDoubleOrNull()?.takeIf { it.isFinite() }
}
