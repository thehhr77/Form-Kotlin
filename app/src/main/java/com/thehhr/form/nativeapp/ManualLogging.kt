package com.thehhr.form.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thehhr.form.nativeapp.vault.VaultRoutineItem
import com.thehhr.form.nativeapp.vault.VaultTrainingLog
import com.thehhr.form.nativeapp.workout.ExerciseRules
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

class ManualLoggingSession(initial: ManualDraft) {
    var draft by mutableStateOf(initial)
}

object ManualLogging {
    private val DECIMAL = Regex("[0-9]+(?:\\.[0-9]+)?")
    private const val REPS_MESSAGE = "Enter whole reps between 1 and 100."
    private const val WEIGHT_MESSAGE = "Enter weight between 0 and 2000 kg with at most one decimal place."
    private const val DURATION_SEC_MESSAGE = "Enter a duration between 0 and 36000 seconds with at most two decimal places."
    private const val DURATION_SEC_EXACT_MESSAGE =
        "Seconds must convert exactly to hundredths of a minute; use a whole multiple of 0.6 seconds or switch the unit to minutes."
    private const val DURATION_MIN_MESSAGE = "Enter a duration between 0 and 600 minutes with at most two decimal places."
    private const val DISTANCE_MESSAGE = "Enter a distance between 0 and 500 km with at most one decimal place."

    fun manualLogId(createdAtMillis: Long, exerciseId: String): String =
        ExerciseRules.logId(createdAtMillis, exerciseId)

    fun latestLogFor(exerciseId: String, logs: List<VaultTrainingLog>): VaultTrainingLog? =
        ExerciseRules.latestLogFor(exerciseId, logs)

    fun historyFor(exerciseId: String, logs: List<VaultTrainingLog>): List<VaultTrainingLog> =
        logs.withIndex()
            .filter { it.value.exerciseId == exerciseId }
            .sortedWith(compareByDescending<IndexedValue<VaultTrainingLog>> { it.value.date }.thenByDescending { it.index })
            .map { it.value }

    fun personalRecord(exerciseId: String, logs: List<VaultTrainingLog>): PersonalRecord? {
        var best: PersonalRecord? = null
        for (log in logs) {
            if (log.exerciseId != exerciseId || log.timed) continue
            val weights = log.setWeights.filter { it > 0.0 }.ifEmpty {
                listOfNotNull(log.weight?.takeIf { it > 0.0 })
            }
            for (weight in weights) {
                if (best == null || weight > best.weight) best = PersonalRecord(weight, log.date, log.id)
            }
        }
        return best
    }

    fun formatPR(record: PersonalRecord): String = "PR: ${oneDecimal(record.weight)} kg"

    fun formatLog(log: VaultTrainingLog): String {
        return if (log.timed) {
            val intervals = log.intervals ?: maxOf(log.setDurations.size, log.setDistances.size, 1)
            val duration = ExerciseRules.round2(log.setDurations.sum())
            val distance = ExerciseRules.round1(log.setDistances.sum())
            listOfNotNull(
                if (intervals > 0) if (intervals == 1) "1 interval" else "$intervals intervals" else null,
                if (duration > 0.0) "${oneDecimal(duration)} min" else null,
                if (distance > 0.0) "${oneDecimal(distance)} km" else null
            ).joinToString(" · ").ifEmpty { "Timed entry" }
        } else {
            val reps = log.setReps.ifEmpty { listOf(log.reps) }
            val weights = log.setWeights.filter { it > 0.0 }.ifEmpty { listOfNotNull(log.weight?.takeIf { it > 0.0 }) }
            val weightText = if (weights.isEmpty()) "" else " × ${weightRange(weights, " kg")}"
            "${log.sets} sets × ${intRange(reps, " reps")}$weightText"
        }
    }

    fun seedDraft(exercise: Exercise, history: List<VaultTrainingLog>, today: String): ManualDraft =
        if (ExerciseRules.resolvedMode(null, exercise) == "timed") {
            seedTimed(exercise, history, today, "timed")
        } else {
            seedStrength(exercise, history, today)
        }

    fun withMode(draft: ManualDraft, exercise: Exercise, history: List<VaultTrainingLog>, mode: String): ManualDraft {
        require(mode == "reps" || mode == "timed") { "Choose a logging mode" }
        return when {
            mode == draft.mode -> draft
            mode == "timed" -> seedTimed(exercise, history, draft.date, "timed")
            else -> seedStrength(exercise, history, draft.date)
        }
    }

    fun withUnit(draft: ManualDraft, unit: String): ManualDraft {
        require(draft.mode == "timed") { "Duration units only apply to timed entries" }
        require(unit == "min" || unit == "sec") { "Choose a duration unit" }
        return draft.copy(unit = unit)
    }

    fun withDate(draft: ManualDraft, date: String): ManualDraft = draft.copy(date = date)

    fun withNotes(draft: ManualDraft, notes: String): ManualDraft = draft.copy(notes = notes)

    fun withRow(draft: ManualDraft, index: Int, row: ManualSetRow): ManualDraft {
        require(index in draft.rows.indices) { "Invalid set index" }
        return draft.copy(rows = draft.rows.mapIndexed { position, existing -> if (position == index) row else existing })
    }

    fun addRow(draft: ManualDraft): ManualDraft? {
        if (draft.rows.size >= ExerciseRules.LIMIT_SETS) return null
        val template = draft.rows.lastOrNull() ?: return null
        return draft.copy(rows = draft.rows + template)
    }

    fun removeRow(draft: ManualDraft): ManualDraft? {
        if (draft.rows.size <= 1) return null
        return draft.copy(rows = draft.rows.dropLast(1))
    }

    fun dateError(raw: String, today: String): String? {
        val value = raw.trim()
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) return "Use an ISO date (YYYY-MM-DD)."
        val parsed = runCatching { LocalDate.parse(value) }.getOrNull() ?: return "Enter a valid calendar date."
        if (parsed.toString() != value) return "Enter a valid calendar date."
        if (value > today) return "Future dates cannot be logged. Choose today or an earlier date."
        return null
    }

    fun repsError(raw: String): String? =
        runCatching { requireReps(raw) }.exceptionOrNull()?.message

    fun weightError(raw: String): String? =
        runCatching { requireDecimal(raw, ExerciseRules.LIMIT_WEIGHT, 1, WEIGHT_MESSAGE) }.exceptionOrNull()?.message

    fun durationError(raw: String, unit: String): String? =
        runCatching { storedMinutes(raw, unit) }.exceptionOrNull()?.message

    fun distanceError(raw: String): String? =
        runCatching { requireDecimal(raw, ExerciseRules.LIMIT_DISTANCE, 1, DISTANCE_MESSAGE) }.exceptionOrNull()?.message

    fun notesError(raw: String): String? = when {
        raw.trim().length > 160 -> "Use at most 160 characters of notes."
        raw.trim().any { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' || (it.isISOControl() && it != '\t') } ->
            "Use single-line notes without control characters."
        else -> null
    }

    fun rowError(mode: String, unit: String, weighted: Boolean, row: ManualSetRow): String? = runCatching {
        if (mode == "timed") {
            storedMinutes(row.duration, unit)
            requireDecimal(row.distance, ExerciseRules.LIMIT_DISTANCE, 1, DISTANCE_MESSAGE)
        } else {
            requireReps(row.reps)
            if (weighted) requireDecimal(row.weight, ExerciseRules.LIMIT_WEIGHT, 1, WEIGHT_MESSAGE)
        }
    }.exceptionOrNull()?.message

    fun buildLog(exercise: Exercise, draft: ManualDraft, today: String, createdAtMillis: Long): VaultTrainingLog {
        require(draft.exerciseId == exercise.id) { "This entry belongs to another exercise" }
        require(exercise.name.isNotBlank() && exercise.name == exercise.name.trim() &&
            exercise.name.none { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' }) {
            "Exercise name must be trimmed single-line text"
        }
        dateError(draft.date, today)?.let { throw IllegalArgumentException(it) }
        notesError(draft.notes)?.let { throw IllegalArgumentException(it) }
        val notes = draft.notes.trim()
        require(draft.mode == "reps" || draft.mode == "timed") { "Choose a logging mode" }
        val rows = draft.rows
        require(rows.size in 1..ExerciseRules.LIMIT_SETS) { "Log between 1 and ${ExerciseRules.LIMIT_SETS} sets" }
        val id = manualLogId(createdAtMillis, exercise.id)
        return if (draft.mode == "timed") {
            require(draft.unit == "min" || draft.unit == "sec") { "Choose a duration unit" }
            val durations = rows.map { storedMinutes(it.duration, draft.unit) }
            val distances = rows.map { requireDecimal(it.distance, ExerciseRules.LIMIT_DISTANCE, 1, DISTANCE_MESSAGE) }
            val hasDuration = durations.any { it > 0.0 }
            val hasDistance = distances.any { it > 0.0 }
            if (!hasDuration && !hasDistance) throw IllegalArgumentException("Add a duration or a distance")
            VaultTrainingLog(
                id = id,
                exerciseId = exercise.id,
                date = draft.date,
                intervals = rows.size,
                setDurations = if (hasDuration) durations else emptyList(),
                setDistances = if (hasDistance) distances else emptyList(),
                durUnit = draft.unit,
                notes = notes,
                exerciseLabel = "\"${exercise.name}\""
            )
        } else {
            val weighted = ExerciseRules.resolvedWeighted(VaultRoutineItem(exerciseId = exercise.id), exercise)
            val reps = rows.map { requireReps(it.reps) }
            val weights = rows.map { requireDecimal(it.weight, ExerciseRules.LIMIT_WEIGHT, 1, WEIGHT_MESSAGE) }
            if (!weighted) require(weights.all { it == 0.0 }) { "This exercise does not track weight" }
            val tracked = weights.filter { it > 0.0 }
            VaultTrainingLog(
                id = id,
                exerciseId = exercise.id,
                date = draft.date,
                sets = rows.size,
                reps = reps.first(),
                weight = tracked.lastOrNull(),
                setWeights = if (weighted && tracked.isNotEmpty()) weights else emptyList(),
                setReps = reps,
                notes = notes,
                exerciseLabel = "\"${exercise.name}\""
            )
        }
    }

    fun deletionTarget(history: List<VaultTrainingLog>, logId: String): VaultTrainingLog =
        history.firstOrNull { it.id == logId }
            ?: throw IllegalArgumentException("This log entry no longer exists. Reload the vault before deleting.")

    fun blockReason(state: FormState): String? = when {
        state.vault == null -> "Choose a vault before changing training history."
        state.loading || state.vaultBusy || state.workoutBusy -> "Wait for the current operation to finish."
        state.reloadRequired -> "Reload the vault before changing training history."
        state.workout?.finishing == true -> "This workout is finishing. Complete saving or reload before changing training history."
        state.workoutPaused -> "Finish or discard the paused workout before changing training history."
        state.workout != null -> "Finish or discard the active workout before changing training history."
        state.workoutLoadFailed -> "The saved workout draft could not be read. Reload, or discard the unreadable draft, before changing training history."
        else -> null
    }

    private fun seedTimed(exercise: Exercise, history: List<VaultTrainingLog>, today: String, mode: String): ManualDraft {
        val timedLogs = history.withIndex().filter { it.value.exerciseId == exercise.id && it.value.timed }
        val latest = timedLogs.maxWithOrNull(compareBy({ it.value.date }, { it.index }))?.value
        val unit = timedLogs.filter { it.value.durUnit == "min" || it.value.durUnit == "sec" }
            .maxWithOrNull(compareBy({ it.value.date }, { it.index }))?.value?.durUnit
            ?: ExerciseRules.resolvedUnit(null, exercise)
        val seconds = unit == "sec"
        val lastMinutes = latest?.setDurations?.lastOrNull()
        val durationText = when {
            lastMinutes == null -> if (seconds) "10" else "1"
            seconds -> manualDecimal(maxOf(10.0, ExerciseRules.roundHalfUp(lastMinutes * 60.0)))
            else -> manualDecimal(ExerciseRules.round2(maxOf(1.0, lastMinutes)))
        }
        val distanceText = manualDecimal(ExerciseRules.round1(latest?.setDistances?.lastOrNull() ?: 0.0))
        val count = (latest?.intervals ?: 1).coerceIn(1, ExerciseRules.LIMIT_SETS)
        val rows = List(count) { ManualSetRow(duration = durationText, distance = distanceText) }
        return ManualDraft(exercise.id, today, mode, unit, rows)
    }

    private fun seedStrength(exercise: Exercise, history: List<VaultTrainingLog>, today: String): ManualDraft {
        val strengthLogs = history.withIndex().filter { it.value.exerciseId == exercise.id && !it.value.timed }
        val latest = strengthLogs.maxWithOrNull(compareBy({ it.value.date }, { it.index }))?.value
        val repsSource = latest?.setReps?.filter { it >= 1 }.orEmpty()
        val baseReps = latest?.reps?.takeIf { it >= 1 } ?: repsSource.lastOrNull() ?: 10
        val count = (latest?.sets?.takeIf { it >= 1 } ?: repsSource.size.takeIf { it > 0 } ?: 3)
            .coerceIn(1, ExerciseRules.LIMIT_SETS)
        val weighted = ExerciseRules.resolvedWeighted(VaultRoutineItem(exerciseId = exercise.id), exercise)
        val weight = if (weighted) ExerciseRules.lastLoggedWeightFor(exercise.id, history) else 0.0
        val weightText = manualDecimal(ExerciseRules.round1(weight))
        val rows = List(count) { index ->
            ManualSetRow(
                weight = if (weighted) weightText else "0",
                reps = (repsSource.getOrNull(index) ?: repsSource.lastOrNull() ?: baseReps)
                    .coerceIn(1, ExerciseRules.LIMIT_REPS).toString()
            )
        }
        return ManualDraft(exercise.id, today, "reps", ExerciseRules.resolvedUnit(null, exercise), rows)
    }

    private fun requireReps(raw: String): Int {
        val value = raw.trim().toIntOrNull()
        require(value != null && value in 1..ExerciseRules.LIMIT_REPS) { REPS_MESSAGE }
        return value
    }

    private fun requireDecimal(raw: String, max: Double, scale: Int, message: String): Double {
        val text = raw.trim()
        require(DECIMAL.matches(text)) { message }
        val exact = BigDecimal(text)
        val value = exact.toDouble()
        require(
            value.isFinite() && value in 0.0..max && exact.stripTrailingZeros().scale() <= scale &&
                BigDecimal.valueOf(value).compareTo(exact) == 0
        ) { message }
        return value
    }

    private fun storedMinutes(raw: String, unit: String): Double {
        return if (unit == "sec") {
            val seconds = requireDecimal(raw, ExerciseRules.LIMIT_DURATION * 60.0, 2, DURATION_SEC_MESSAGE)
            val minutes = runCatching {
                BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(60), 2, RoundingMode.UNNECESSARY)
            }
            require(minutes.isSuccess) { DURATION_SEC_EXACT_MESSAGE }
            minutes.getOrThrow().toDouble()
        } else {
            requireDecimal(raw, ExerciseRules.LIMIT_DURATION, 2, DURATION_MIN_MESSAGE)
        }
    }

    private fun oneDecimal(value: Double): String =
        BigDecimal.valueOf(ExerciseRules.round1(value)).stripTrailingZeros().toPlainString()

    private fun manualDecimal(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun intRange(values: List<Int>, suffix: String): String {
        val min = values.min()
        val max = values.max()
        return if (min == max) "$min$suffix" else "$min-$max$suffix"
    }

    private fun weightRange(values: List<Double>, suffix: String): String {
        val rounded = values.map(ExerciseRules::round1)
        val min = rounded.min()
        val max = rounded.max()
        return if (min == max) "${oneDecimal(min)}$suffix" else "${oneDecimal(min)}-${oneDecimal(max)}$suffix"
    }
}

data class ManualSetRow(
    val weight: String = "0",
    val reps: String = "10",
    val duration: String = "0",
    val distance: String = "0"
)

data class ManualDraft(
    val exerciseId: String,
    val date: String,
    val mode: String,
    val unit: String,
    val rows: List<ManualSetRow>,
    val notes: String = ""
)

data class PersonalRecord(val weight: Double, val date: String, val logId: String)

@Composable
fun ManualLoggingSection(
    exercise: Exercise,
    state: FormState,
    busy: Boolean,
    onLog: () -> Unit,
    onDelete: (VaultTrainingLog) -> Unit
) {
    val blockReason = ManualLogging.blockReason(state)
    val history = remember(exercise.id, state.data.trainingLogs) {
        ManualLogging.historyFor(exercise.id, state.data.trainingLogs)
    }
    val record = remember(exercise.id, state.data.trainingLogs) {
        ManualLogging.personalRecord(exercise.id, state.data.trainingLogs)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Training history", style = MaterialTheme.typography.titleLarge)
        Text(record?.let { ManualLogging.formatPR(it) } ?: "No personal record yet.")
        Text(
            history.firstOrNull()?.let { "Latest: ${ManualLogging.formatLog(it)} (${it.date})" }
                ?: "No logged history for this exercise yet."
        )
        Button(onClick = onLog, enabled = blockReason == null && !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Log progress")
        }
        blockReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (history.isNotEmpty()) {
            Text("History · newest first", style = MaterialTheme.typography.titleMedium)
            history.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(ManualLogging.formatLog(entry))
                        Text(entry.date, style = MaterialTheme.typography.bodySmall)
                        if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onDelete(entry) }, enabled = blockReason == null && !busy) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
fun ManualLoggingDialog(
    session: ManualLoggingSession,
    exercise: Exercise?,
    history: List<VaultTrainingLog>,
    blockReason: String?,
    saveError: String?,
    busy: Boolean,
    today: String,
    onDismiss: () -> Unit,
    onReload: (() -> Unit)?,
    onSave: (VaultTrainingLog) -> Unit
) {
    val draft = session.draft
    val timed = draft.mode == "timed"
    val weighted = exercise?.let { ExerciseRules.resolvedWeighted(VaultRoutineItem(exerciseId = it.id), it) } ?: false
    val dateError = ManualLogging.dateError(draft.date, today)
    val rowErrors = draft.rows.map { ManualLogging.rowError(draft.mode, draft.unit, weighted, it) }
    val notesError = ManualLogging.notesError(draft.notes)
    val valid = dateError == null && notesError == null && rowErrors.all { it == null }
    val enabled = blockReason == null && !busy && exercise != null
    var actionError by remember(draft) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Log progress") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    exercise?.let { "${it.name} · ${listOf(it.category, it.target, it.equipment).filter { part -> part.isNotBlank() }.joinToString(" · ")}" }
                        ?: "This exercise is unavailable. Reload the vault.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    draft.date, { if (enabled) session.draft = ManualLogging.withDate(draft, it) },
                    Modifier.fillMaxWidth(), label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true, enabled = enabled, isError = dateError != null,
                    supportingText = { Text(dateError ?: "Defaults to today; future dates are not allowed.") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.mode == "reps",
                        onClick = { if (enabled) session.draft = ManualLogging.withMode(draft, requireNotNull(exercise), history, "reps") },
                        enabled = enabled,
                        label = { Text("Reps") }
                    )
                    FilterChip(
                        selected = draft.mode == "timed",
                        onClick = { if (enabled) session.draft = ManualLogging.withMode(draft, requireNotNull(exercise), history, "timed") },
                        enabled = enabled,
                        label = { Text("Timed") }
                    )
                }
                if (timed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.unit == "min",
                            onClick = { if (enabled) session.draft = ManualLogging.withUnit(draft, "min") },
                            enabled = enabled,
                            label = { Text("Minutes") }
                        )
                        FilterChip(
                            selected = draft.unit == "sec",
                            onClick = { if (enabled) session.draft = ManualLogging.withUnit(draft, "sec") },
                            enabled = enabled,
                            label = { Text("Seconds") }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { if (enabled) session.draft = ManualLogging.removeRow(draft) ?: draft },
                        enabled = enabled && draft.rows.size > 1,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (timed) "Remove interval" else "Remove set") }
                    OutlinedButton(
                        onClick = { if (enabled) session.draft = ManualLogging.addRow(draft) ?: draft },
                        enabled = enabled && draft.rows.size < ExerciseRules.LIMIT_SETS,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (timed) "Add interval" else "Add set") }
                }
                draft.rows.forEachIndexed { index, row ->
                    ManualRowEditor(index, row, timed, draft.unit, weighted, rowErrors[index], enabled) { replacement ->
                        session.draft = ManualLogging.withRow(draft, index, replacement)
                    }
                }
                OutlinedTextField(
                    draft.notes, { if (enabled) session.draft = ManualLogging.withNotes(draft, it) },
                    Modifier.fillMaxWidth(), label = { Text("Notes (optional)") },
                    singleLine = true, enabled = enabled, isError = notesError != null,
                    supportingText = { Text(notesError ?: "${draft.notes.trim().length}/160 characters") }
                )
                blockReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onReload != null) TextButton(onClick = onReload, enabled = !busy) { Text("Reload from disk") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = exercise ?: return@TextButton
                    val result = runCatching { ManualLogging.buildLog(target, draft, today, System.currentTimeMillis()) }
                    val failure = result.exceptionOrNull()
                    if (failure != null) actionError = failure.message else onSave(result.getOrThrow())
                },
                enabled = enabled && valid
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
private fun ManualRowEditor(
    index: Int,
    row: ManualSetRow,
    timed: Boolean,
    unit: String,
    weighted: Boolean,
    error: String?,
    enabled: Boolean,
    onChange: (ManualSetRow) -> Unit
) {
    val durationError = if (timed) ManualLogging.durationError(row.duration, unit) else null
    val distanceError = if (timed) ManualLogging.distanceError(row.distance) else null
    val weightError = if (!timed && weighted) ManualLogging.weightError(row.weight) else null
    val repsError = if (!timed) ManualLogging.repsError(row.reps) else null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (timed) "Interval ${index + 1}" else "Set ${index + 1}", style = MaterialTheme.typography.titleSmall)
        if (timed) {
            ManualNumberField(
                if (unit == "sec") "Duration (sec)" else "Duration (min)", row.duration, enabled,
                durationError, KeyboardType.Decimal
            ) { onChange(row.copy(duration = it)) }
            ManualNumberField("Distance (km)", row.distance, enabled, distanceError, KeyboardType.Decimal) {
                onChange(row.copy(distance = it))
            }
        } else {
            if (weighted) {
                ManualNumberField("Weight (kg)", row.weight, enabled, weightError, KeyboardType.Decimal) {
                    onChange(row.copy(weight = it))
                }
            }
            ManualNumberField("Reps", row.reps, enabled, repsError, KeyboardType.Number) {
                onChange(row.copy(reps = it))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ManualNumberField(
    label: String,
    value: String,
    enabled: Boolean,
    error: String?,
    keyboardType: KeyboardType,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) },
        singleLine = true, enabled = enabled, isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}
