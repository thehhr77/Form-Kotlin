package com.thehhr.form.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thehhr.form.nativeapp.vault.ConfigCodec
import com.thehhr.form.nativeapp.vault.VaultConfig
import com.thehhr.form.nativeapp.vault.VaultFiles
import java.math.BigDecimal
import kotlin.math.floor
import kotlin.math.max

object NutritionTargets {
    fun calculate(config: VaultConfig): Map<String, Double> {
        fun profile(key: String): Double = nutritionProfileFields.first { it.key == key }.number(config.profile[key])
        val age = profile("age")
        val height = profile("height")
        val weight = profile("current-weight")
        val activity = profile("activity")
        val strategy = profile("strategy")
        val rate = profile("protein-rate")
        val bmr = 10 * weight + 6.25 * height - 5 * age + if ((config.sex ?: "m") == "m") 5 else -161
        val tdee = floor(bmr * activity + 0.5)
        val initial = max(1200.0, floor(tdee + strategy + 0.5))
        val protein = floor(weight * rate + 0.5)
        val fat = floor(initial * 0.25 / 9 + 0.5)
        val carbs = floor(max(0.0, initial - protein * 4 - fat * 9) / 4 + 0.5)
        val calories = protein * 4 + carbs * 4 + fat * 9
        val water = floor(max(2000.0, weight * if (activity >= 1.5) 45 else 35) + 0.5)
        return linkedMapOf("cals" to calories, "p" to protein, "c" to carbs, "f" to fat, "water" to water)
    }

    fun effective(config: VaultConfig): Map<String, Double> = calculate(config).mapValues { (key, calculated) ->
        config.overrides[key]?.takeIf { it.isFinite() }?.coerceIn(
            if (key == "cals" || key == "water") 500.0 else 0.0,
            if (key == "cals" || key == "water") 10000.0 else 1000.0
        ) ?: calculated
    }
}

private data class NutritionField(
    val section: String,
    val key: String,
    val label: String,
    val unit: String = "",
    val default: String,
    val minimum: Double = 0.0,
    val maximum: Double = 0.0,
    val choices: Map<String, String> = emptyMap(),
    val target: String? = null
) {
    fun number(value: Double?): Double = value?.takeIf { it.isFinite() }?.coerceIn(minimum, maximum) ?: default.toDouble()
    fun display(value: String): String = choices[value] ?: if (unit.isEmpty()) value else "$value $unit"
    fun valid(value: String): Boolean = runCatching { ConfigCodec.validateConfigField(section, key, value) }.isSuccess
}

private val nutritionProfileFields = listOf(
    NutritionField("Profile", "age", "Age", "years", "22", 10.0, 110.0),
    NutritionField("Profile", "height", "Height", "cm", "178", 50.0, 300.0),
    NutritionField("Profile", "current-weight", "Current weight", "kg", "75", 20.0, 500.0),
    NutritionField("Profile", "start-weight", "Starting weight", "kg", "75", 20.0, 500.0),
    NutritionField("Profile", "goal-weight", "Goal weight", "kg", "78", 20.0, 500.0),
    NutritionField("Profile", "sex", "Sex for BMR", default = "m", choices = linkedMapOf("m" to "Male (m)", "f" to "Female (f)")),
    NutritionField("Profile", "activity", "Activity multiplier", "× BMR", "1.55", 1.0, 3.0),
    NutritionField("Profile", "strategy", "Calorie adjustment", "kcal/day", "250", -1000.0, 1000.0),
    NutritionField("Profile", "protein-rate", "Protein rate", "g/kg/day", "2", 0.5, 5.0)
)

private val nutritionTargetFields = listOf(
    NutritionField("Targets", "calories", "Calories", "kcal/day", "500", 500.0, 10000.0, target = "cals"),
    NutritionField("Targets", "protein", "Protein", "g/day", "0", 0.0, 1000.0, target = "p"),
    NutritionField("Targets", "carbs", "Carbohydrate", "g/day", "0", 0.0, 1000.0, target = "c"),
    NutritionField("Targets", "fats", "Fat", "g/day", "0", 0.0, 1000.0, target = "f"),
    NutritionField("Targets", "water", "Water", "ml/day", "500", 500.0, 10000.0, target = "water")
)

private val nutritionPreferenceFields = listOf(
    NutritionField("Preferences", "week-start", "Week starts on", default = "1", choices = linkedMapOf("0" to "Sunday", "1" to "Monday", "6" to "Saturday")),
    NutritionField("Preferences", "default-view", "Default statistics view", default = "week", choices = linkedMapOf("week" to "Week", "month" to "Month", "all" to "All time")),
    NutritionField("Preferences", "workout-reminder", "Scheduled workout reminder", default = "true", choices = linkedMapOf("true" to "On", "false" to "Off")),
    NutritionField("Preferences", "rest-between-sets", "Rest between sets", "seconds", "60", 30.0, 180.0),
    NutritionField("Preferences", "rest-between-exercises", "Rest between exercises", "seconds", "90", 30.0, 180.0)
)

private val exerciseFilterHosts = linkedMapOf("routine" to "Routine", "category" to "Category", "target" to "Target", "equipment" to "Equipment")
private val exerciseFilterVisibility = linkedMapOf("default" to "Default · expanded only", "pin" to "Pinned · always", "hidden" to "Hidden · never")
private val exerciseFilterFields = exerciseFilterHosts.map { (host, label) ->
    NutritionField("Preferences", "pill-$host", "$label filter", default = "default", choices = exerciseFilterVisibility)
} + listOf(
    NutritionField("Preferences", "pill-tags-host", "Tags host", default = "equipment", choices = exerciseFilterHosts),
    NutritionField("Preferences", "pill-toggles", "Toggles host", default = "equipment", choices = exerciseFilterHosts),
    NutritionField("Preferences", "show-secondary-pills", "Expand secondary filters", default = "false", choices = linkedMapOf("true" to "On", "false" to "Off"))
)

private fun effectiveFilterHostLabel(config: VaultConfig, requested: String): String {
    val available = exerciseFilterHosts.keys.filter { config.preferences["pill-$it"] != "hidden" }
    val host = requested.takeIf { it in available } ?: available.firstOrNull()
    return exerciseFilterHosts[host] ?: "Hidden"
}

private fun Double.nutritionNumber(): String = if (isFinite()) BigDecimal.valueOf(this).stripTrailingZeros().toPlainString() else "Unavailable"

private fun NutritionField.current(config: VaultConfig, effective: Map<String, Double>): String = when {
    target != null -> effective.getValue(target).nutritionNumber()
    key == "sex" -> config.sex ?: default
    section == "Profile" -> number(config.profile[key]).nutritionNumber()
    choices.isNotEmpty() -> config.preferences[key]?.takeIf { it in choices } ?: default
    else -> number(config.numberPreference(key)).nutritionNumber()
}

private class NutritionEditorSession(val field: NutritionField, val original: String?, initial: String) {
    var draft by mutableStateOf(initial)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun NutritionSettingsScreen(
    state: FormState,
    onReload: () -> Unit,
    onSave: (String, String, String?, String?, () -> Unit) -> Unit,
    onBack: () -> Unit
) {
    val config = state.data.config
    val raw = state.vault?.file(VaultFiles.CONFIG)
    val busy = state.loading || state.vaultBusy || state.workoutBusy
    val blockReason = if (state.workoutBusy) "A workout operation is in progress. Changes are temporarily disabled." else state.configWriteBlockReason()
    val calculated = remember(config) { NutritionTargets.calculate(config) }
    val effective = remember(config) { NutritionTargets.effective(config) }
    var editing by remember(state.vault?.uri) { mutableStateOf<NutritionEditorSession?>(null) }
    val originals = remember(raw) {
        (nutritionProfileFields + nutritionTargetFields + nutritionPreferenceFields + exerciseFilterFields).associate { field ->
            (field.section to field.key) to runCatching {
                raw?.let { ConfigCodec.configFieldValue(it, field.section, field.key) }
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "back") { TextButton(onClick = onBack, enabled = !busy) { Text("Back to Settings") } }
        item(key = "status") {
            VaultStatus(state, onReload)
            blockReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        item(key = "summary") { NutritionTargetSummary(state) }
        listOf(
            Triple("Profile", "Current weight drives nutrition calculations. Starting and goal weights describe your weight goal; they do not change daily targets.", nutritionProfileFields),
            Triple("Daily targets", "Calculated targets are the defaults. Each override changes only its own target; calories and other macros are not recomputed. Clear an override to follow the profile again.", nutritionTargetFields),
            Triple("Preferences", "The reminder appears in the app for today's scheduled workout. Rest durations apply when rest timers are enabled in Settings.", nutritionPreferenceFields),
            Triple("Exercise filters", "Default filters appear only when expanded; pinned filters always appear; hidden filters never appear. Active filters are retained, even when hidden. Secondary filters start collapsed by default. If a selected host is hidden, tags and toggles use the first non-hidden host in Routine, Category, Target, Equipment order; if all are hidden, they are hidden too.", exerciseFilterFields)
        ).forEach { (heading, explanation, fields) ->
            item(key = "heading:$heading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(heading, style = MaterialTheme.typography.titleLarge)
                    Text(explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
            fields.forEach { field ->
                item(key = "${field.section}:${field.key}") {
                    val result = originals.getValue(field.section to field.key)
                    val original = result.getOrNull()
                    val current = field.current(config, effective)
                    val readError = result.exceptionOrNull()?.let { it.message ?: "Cannot read this field safely. Resolve its source and reload." }
                    val enabled = blockReason == null && !busy && raw != null && readError == null
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(field.label, style = MaterialTheme.typography.titleMedium)
                                if (field.target != null) {
                                    Text("Effective: ${field.display(current)}")
                                    Text("Calculated default: ${field.display(calculated.getValue(field.target).nutritionNumber())}", style = MaterialTheme.typography.bodySmall)
                                    Text(if (original == null) "No saved override" else "Saved override: $original ${field.unit}", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text(field.display(current))
                                    if (field.key == "pill-tags-host" || field.key == "pill-toggles") {
                                        Text("Effective host: ${effectiveFilterHostLabel(config, current)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("Default: ${field.display(field.default)}${if (original == null) " (not saved)" else " · Saved: $original"}", style = MaterialTheme.typography.bodySmall)
                                }
                                readError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                            TextButton(onClick = {
                                if (enabled) editing = NutritionEditorSession(field, original, original ?: current)
                            }, enabled = enabled) { Text("Edit") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { session ->
        key(session) {
            NutritionFieldDialog(
                session, state, blockReason, calculated, effective, onReload,
                onDismiss = { if (!busy) editing = null },
                onSave = { value ->
                    if (blockReason == null && !busy && raw != null) {
                        session.error = null
                        try {
                            onSave(session.field.section, session.field.key, session.original, value) {
                                if (editing === session) editing = null
                            }
                        } catch (error: Exception) {
                            session.error = error.message ?: "Unable to save. Your edit has been retained."
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun NutritionFieldDialog(
    session: NutritionEditorSession,
    state: FormState,
    blockReason: String?,
    calculated: Map<String, Double>,
    effective: Map<String, Double>,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    val field = session.field
    val busy = state.loading || state.vaultBusy || state.workoutBusy
    val enabled = blockReason == null && !busy
    val value = session.draft.trim()
    val valid = field.valid(value)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(field.label) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved when opened: ${session.original ?: "Not set"}", style = MaterialTheme.typography.bodySmall)
                if (field.target != null) {
                    Text("Effective: ${field.display(effective.getValue(field.target).nutritionNumber())}")
                    Text("Calculated default: ${field.display(calculated.getValue(field.target).nutritionNumber())}")
                    Text("Only this target changes. Other targets are not recomputed.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Default: ${field.display(field.default)}", style = MaterialTheme.typography.bodySmall)
                }
                if (field.choices.isEmpty()) {
                    OutlinedTextField(
                        value = session.draft,
                        onValueChange = { session.draft = it; session.error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("${field.label} (${field.unit})") },
                        singleLine = true,
                        enabled = enabled,
                        isError = !valid,
                        supportingText = { Text("Enter ${field.minimum.nutritionNumber()}–${field.maximum.nutritionNumber()} ${field.unit}; decimals allowed.") }
                    )
                } else {
                    field.choices.forEach { (choice, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = value == choice, onClick = { session.draft = choice; session.error = null }, enabled = enabled)
                            TextButton(onClick = { session.draft = choice; session.error = null }, enabled = enabled) { Text(label) }
                        }
                    }
                }
                if (field.key == "pill-tags-host" || field.key == "pill-toggles") {
                    Text("Effective host: ${effectiveFilterHostLabel(state.data.config, value)}", style = MaterialTheme.typography.bodySmall)
                }
                if (!valid) Text(if (field.choices.isEmpty()) "Enter a finite number in the allowed range." else "Choose one of the supported values.", color = MaterialTheme.colorScheme.error)
                if (field.target != null && session.original != null) {
                    TextButton(onClick = { if (enabled) onSave(null) }, enabled = enabled) { Text("Clear override · use calculated") }
                }
                blockReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.reloadRequired && state.vault != null) {
                    TextButton(onClick = onReload, enabled = !busy) { Text("Reload from disk") }
                    Text("Your draft and original value are retained. If a conflict remains, cancel and reopen the field after reviewing the reloaded value.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (enabled && valid) onSave(if (field.choices.isEmpty()) value.toDouble().nutritionNumber() else value)
            }, enabled = enabled && valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
fun NutritionTargetSummary(state: FormState) {
    val config = state.data.config
    val calculated = remember(config) { NutritionTargets.calculate(config) }
    val effective = remember(config) { NutritionTargets.effective(config) }
    val missing = state.vault?.file(VaultFiles.CONFIG) == null || VaultFiles.CONFIG in state.data.missingFiles
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Daily nutrition targets", style = MaterialTheme.typography.titleLarge)
            if (missing) Text("config.md is unavailable. These are default estimates, not saved personal targets.", style = MaterialTheme.typography.bodySmall)
            if (state.loading || state.vaultBusy || state.reloadRequired) Text("Targets may be out of date until the vault finishes loading or is reloaded.", style = MaterialTheme.typography.bodySmall)
            nutritionTargetFields.forEach { field ->
                val target = requireNotNull(field.target)
                val overridden = config.overrides[target]?.isFinite() == true
                Text("${field.label}: ${field.display(effective.getValue(target).nutritionNumber())}${if (overridden) " · override" else " · calculated"}")
                Text("Calculated: ${field.display(calculated.getValue(target).nutritionNumber())}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Calculated targets use your profile: BMR × activity, plus your calorie adjustment (minimum 1200 kcal before macro rounding). Protein uses current weight × protein rate; fat uses 25% of initial calories, and carbohydrate uses the remainder. Calories then match the rounded macros.", style = MaterialTheme.typography.bodySmall)
            Text("Water uses 45 ml/kg at activity 1.5 or above, otherwise 35 ml/kg, with a 2000 ml minimum. Missing profile values use age 22, height 178 cm, weight 75 kg, male sex, activity 1.55, +250 kcal/day and protein 2 g/kg/day.", style = MaterialTheme.typography.bodySmall)
            Text("Fuel uses effective targets. Each finite saved override is clamped independently (calories/water 500–10000; macros 0–1000 g). Overrides never recalculate other targets, so effective calories may differ from macro energy. Clear an override in nutrition settings to use its calculated value.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
