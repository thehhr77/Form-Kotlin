package com.thehhr.form.nativeapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thehhr.form.nativeapp.vault.VaultCodec
import com.thehhr.form.nativeapp.vault.VaultConfig
import com.thehhr.form.nativeapp.vault.VaultDiaryDay
import com.thehhr.form.nativeapp.vault.VaultDiaryMeal
import com.thehhr.form.nativeapp.vault.VaultFiles
import com.thehhr.form.nativeapp.vault.VaultMeal
import com.thehhr.form.nativeapp.vault.VaultTrainingLog
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor

private val progressPeriods = listOf("week", "month", "all")

private fun FormState.progressBusy(): Boolean = loading || vaultBusy || workoutBusy

@Composable
private fun progressFileBlock(state: FormState, file: String): String? {
    val source = state.vault?.file(file)
    val readable = remember(source, file) { source?.let { VaultCodec.parse(file, it).canRewrite } == true }
    return when {
        state.progressBusy() -> "Wait for the current operation to finish."
        state.vault == null -> "Choose a vault in Settings. No recorded totals are available."
        state.reloadRequired -> "Reload from disk before trusting totals or making changes."
        file in state.data.missingFiles || source == null -> "$file is missing. Restore it and reload; missing data is not an empty log."
        !readable || (state.data.diagnostics + state.vault.warnings).any { it.contains(file, ignoreCase = true) } ->
            "$file has diagnostics. Totals and changes are unavailable until the file is resolved and reloaded."
        else -> null
    }
}

@Composable
private fun ProgressVaultStatus(state: FormState, onReload: () -> Unit) {
    VaultStatus(state.copy(vaultBusy = state.vaultBusy || state.workoutBusy)) {
        if (!state.progressBusy()) onReload()
    }
}

private fun progressDate(value: String): LocalDate? {
    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) return null
    return runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { it.year in 1..9999 && it.toString() == value }
}

private fun progressShift(date: LocalDate?, period: String, direction: Long): String? = date?.let {
    runCatching {
        when (period) {
            "week" -> it.plusWeeks(direction)
            "month" -> it.plusMonths(direction)
            else -> it.plusDays(direction)
        }
    }.getOrNull()?.takeIf { shifted -> shifted.year in 1..9999 }?.toString()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressDateControls(value: String, period: String, enabled: Boolean, maxDate: String? = null, onChange: (String) -> Unit) {
    val date = progressDate(value)
    val previous = progressShift(date, period, -1)
    val next = progressShift(date, period, 1)
    val nextAllowed = next != null && (maxDate == null || next <= maxDate)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(), label = { Text("Date (YYYY-MM-DD)") },
            singleLine = true, enabled = enabled, isError = date == null,
            supportingText = { Text(if (date == null) "Enter a valid ISO date, years 0001–9999." else "Selected date: $date") }
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { previous?.let(onChange) }, enabled = enabled && previous != null) { Text("Previous") }
            TextButton(onClick = { onChange(LocalDate.now().toString()) }, enabled = enabled) { Text("Today") }
            OutlinedButton(onClick = { next?.let(onChange) }, enabled = enabled && nextAllowed) { Text("Next") }
        }
    }
}

private data class ProgressLogTotals(
    val sets: Long = 0,
    val reps: Long = 0,
    val volume: Double = 0.0,
    val intervals: Long = 0,
    val minutes: Double = 0.0,
    val kilometers: Double = 0.0,
    val unknownWeightSets: Long = 0
) {
    operator fun plus(other: ProgressLogTotals) = ProgressLogTotals(
        sets + other.sets, reps + other.reps, volume + other.volume,
        intervals + other.intervals, minutes + other.minutes, kilometers + other.kilometers,
        unknownWeightSets + other.unknownWeightSets
    )
}

private fun VaultTrainingLog.progressTotals(): ProgressLogTotals {
    if (timed) return ProgressLogTotals(
        intervals = (intervals ?: maxOf(setDurations.size, setDistances.size, 1)).toLong(),
        minutes = setDurations.sum(), kilometers = setDistances.sum()
    )
    val count = setWeights.size.takeIf { it > 0 } ?: maxOf(sets, setReps.size)
    var totalReps = 0L
    var volume = 0.0
    var unknown = 0L
    repeat(count.coerceAtLeast(0)) { index ->
        val repetitions = setReps.getOrNull(index) ?: reps
        val kilograms = setWeights.getOrNull(index) ?: weight
        totalReps += repetitions.toLong()
        if (kilograms == null) unknown++ else volume += repetitions.toDouble() * kilograms
    }
    return ProgressLogTotals(sets = count.toLong(), reps = totalReps, volume = volume, unknownWeightSets = unknown)
}

private fun Double.progressNumber(scale: Int = 2): String = if (isFinite()) {
    BigDecimal.valueOf(this).setScale(scale, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
} else "Unavailable"

private fun Double.diaryNumber(): String = if (isFinite()) BigDecimal.valueOf(this).stripTrailingZeros().toPlainString() else "Unavailable"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsScreen(state: FormState, onReload: () -> Unit, onOpenExercise: (String) -> Unit) {
    var period by rememberSaveable(state.vault?.uri) {
        mutableStateOf(state.data.config.preferences["default-view"]?.takeIf { it in progressPeriods } ?: "week")
    }
    var dateText by rememberSaveable(state.vault?.uri) { mutableStateOf(LocalDate.now().toString()) }
    var selectedDate by rememberSaveable(state.vault?.uri) { mutableStateOf<String?>(null) }
    val anchor = progressDate(dateText)
    val weekStart = state.data.config.preferences["week-start"]?.toIntOrNull()?.takeIf { it in listOf(0, 1, 6) } ?: 1
    val start = anchor?.let {
        when (period) {
            "week" -> it.minusDays(((it.dayOfWeek.value % 7 - weekStart + 7) % 7).toLong())
            "month" -> it.withDayOfMonth(1)
            else -> null
        }
    }
    val end = start?.let { if (period == "week") it.plusDays(6) else it.plusMonths(1).minusDays(1) }
    val logs = remember(state.data.trainingLogs, start, end, anchor, period) {
        if (anchor == null) emptyList() else state.data.trainingLogs.filter { log ->
            val date = progressDate(log.date)
            date != null && (period == "all" || (start != null && end != null && !date.isBefore(start) && !date.isAfter(end)))
        }
    }
    val groups = remember(logs) { logs.groupBy { it.date }.toSortedMap(reverseOrder()) }
    val totals = remember(logs) { logs.fold(ProgressLogTotals()) { sum, log -> sum + log.progressTotals() } }
    val catalog = remember(state.exercises) { state.exercises.groupBy { it.id } }
    val exercisesById = remember(state.exercises) { state.exercises.associateBy { it.id } }
    val block = progressFileBlock(state, VaultFiles.TRAINING_LOGS)
        ?: if (state.data.trainingLogs.any { progressDate(it.date) == null }) "Training history contains invalid dates; totals are unavailable." else null
    val enabled = !state.progressBusy() && !state.reloadRequired
    val headline = remember(logs) { StatsMath.headline(logs) }
    val loggedDates = remember(state.data.trainingLogs) { StatsMath.loggedDates(state.data.trainingLogs) }
    val previousLoggedPeriod = anchor?.let {
        when (period) {
            "week" -> StatsMath.adjacentLoggedWeekStart(it, weekStart, loggedDates, -1L)?.toString()
            "month" -> StatsMath.adjacentLoggedMonth(it, loggedDates, -1L)?.atDay(1)?.toString()
            else -> null
        }
    }
    val nextLoggedPeriod = anchor?.let {
        when (period) {
            "week" -> StatsMath.adjacentLoggedWeekStart(it, weekStart, loggedDates, 1L)?.toString()
            "month" -> StatsMath.adjacentLoggedMonth(it, loggedDates, 1L)?.atDay(1)?.toString()
            else -> null
        }
    }
    val weekBuckets = remember(logs, start, period) {
        if (period == "week" && start != null) StatsMath.weekBuckets(logs, start) else emptyList()
    }
    val monthPerDaySets = remember(logs, anchor, period) {
        if (period == "month" && anchor != null) StatsMath.monthSets(logs, YearMonth.from(anchor)) else emptyMap<LocalDate, Long>()
    }
    val allBuckets = remember(logs, period) { if (period == "all") StatsMath.allTimeBuckets(logs) else emptyList() }
    val allVolumes = remember(allBuckets) { allBuckets.map { it.volume } }
    val trend = remember(allVolumes) { StatsMath.trend(allVolumes) }
    val movingAverages = remember(allVolumes) { StatsMath.movingAverages(allVolumes) }
    val balanceLogs = remember(logs, selectedDate) { if (selectedDate == null) logs else logs.filter { it.date == selectedDate } }
    val categoryTotals = remember(balanceLogs, exercisesById) { StatsMath.categoryTotals(balanceLogs, exercisesById) }
    val muscleTotals = remember(balanceLogs, exercisesById) { StatsMath.muscleContributions(balanceLogs, exercisesById) }
    val dayLogs = remember(selectedDate, state.data.trainingLogs) {
        selectedDate?.let { selection -> state.data.trainingLogs.filter { it.date == selection } }.orEmpty()
    }
    val dayHeadline = remember(dayLogs) { StatsMath.headline(dayLogs) }
    val dayReps = remember(dayLogs) { dayLogs.sumOf { it.progressTotals().reps } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ProgressVaultStatus(state, onReload) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Training history", style = MaterialTheme.typography.headlineSmall)
                Text("Sessions are distinct logged dates, sets include timed intervals, and exercises are distinct IDs. These are recorded entries, not claims of completed workouts.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    progressPeriods.forEach { value ->
                        FilterChip(period == value, { period = value }, enabled = enabled, label = { Text(value.replaceFirstChar { it.uppercase() }) })
                    }
                }
                ProgressDateControls(dateText, period, enabled) { dateText = it }
                if (period != "all") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { previousLoggedPeriod?.let { dateText = it } },
                            enabled = enabled && previousLoggedPeriod != null
                        ) { Text("Previous logged") }
                        OutlinedButton(
                            onClick = { nextLoggedPeriod?.let { dateText = it } },
                            enabled = enabled && nextLoggedPeriod != null
                        ) { Text("Next logged") }
                    }
                }
                if (anchor != null) Text(if (period == "all") "All recorded dates" else "$start through $end", style = MaterialTheme.typography.titleMedium)
                if (period == "week") Text("Week starts ${when (weekStart) { 0 -> "Sunday"; 6 -> "Saturday"; else -> "Monday" }}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (block != null) item { Text(block, color = MaterialTheme.colorScheme.error) }
        if (block == null && anchor != null) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${headline.sessions} sessions · ${headline.sets} sets · ${headline.exercises} exercises", style = MaterialTheme.typography.titleLarge)
                        Text("Strength", style = MaterialTheme.typography.titleMedium)
                        Text("${totals.sets} sets · ${totals.reps} reps")
                        Text("${totals.volume.progressNumber()} kg recorded volume")
                        Text("Volume is the sum of reps × recorded kg for each strength set; body weight is not inferred.", style = MaterialTheme.typography.bodySmall)
                        if (totals.unknownWeightSets > 0) Text("${totals.unknownWeightSets} sets have no recorded weight and are excluded from volume.", style = MaterialTheme.typography.bodySmall)
                        Text("Timed", style = MaterialTheme.typography.titleMedium)
                        Text("${totals.intervals} intervals · ${totals.minutes.progressNumber()} min · ${totals.kilometers.progressNumber()} km")
                        Text("Only recorded duration and distance are summed, separately from strength.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (period == "week" && weekBuckets.isNotEmpty()) {
                item { WeeklySetsBars(weekBuckets, selectedDate, enabled) { selectedDate = it } }
            }
            if (period == "month") {
                item { MonthIntensityGrid(YearMonth.from(anchor), weekStart, monthPerDaySets, selectedDate, enabled) { selectedDate = it } }
            }
            if (period == "all") {
                item { AllTimeVolumeChart(allBuckets, movingAverages, trend) }
            }
            if (logs.isEmpty()) item { Text("No logged exercises in this calendar range. This does not indicate completed workouts.") }
            if (categoryTotals.isNotEmpty()) item { CategoryBreakdownCard(categoryTotals) }
            if (muscleTotals.isNotEmpty()) item { MuscleContributionCard(muscleTotals, MuscleMapMath.accentKey(state), state.data.config.sex) }
            if (selectedDate != null) {
                item(key = "drilldown:$selectedDate") {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Entries · $selectedDate", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = { selectedDate = null }, enabled = enabled) { Text("Show all") }
                            }
                            if (dayLogs.isEmpty()) {
                                Text("No workouts logged this day.")
                            } else {
                                Text("${dayHeadline.exercises} exercises · ${dayHeadline.sets} sets · $dayReps reps", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                itemsIndexed(dayLogs, key = { index, log -> "day:$index:${log.id}" }) { _, log ->
                    StatsEntryCard(log, catalog, onOpenExercise)
                }
            }
            groups.forEach { (date, entries) ->
                item(key = "date:$date") { Text(date, style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(entries, key = { index, log -> "$date:$index:${log.id}" }) { _, log ->
                    StatsEntryCard(log, catalog, onOpenExercise)
                }
            }
        }
    }
}

@Composable
private fun StatsEntryCard(log: VaultTrainingLog, catalog: Map<String, List<Exercise>>, onOpenExercise: (String) -> Unit) {
    val matches = catalog[log.exerciseId].orEmpty()
    val label = matches.singleOrNull()?.name ?: log.exerciseLabel.removeSurrounding("\"").takeIf { it.isNotBlank() && it != "Unknown exercise" } ?: "Unknown exercise"
    val summary = log.progressTotals()
    ElevatedCard(onClick = { if (matches.isNotEmpty()) onOpenExercise(log.exerciseId) }, enabled = matches.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text("ID: ${log.exerciseId}${if (matches.size > 1) " · ambiguous catalog ID" else if (matches.isEmpty()) " · unavailable in catalog" else ""}", style = MaterialTheme.typography.bodySmall)
            if (log.timed) {
                Text("${summary.intervals} intervals · ${summary.minutes.progressNumber()} min · ${summary.kilometers.progressNumber()} km")
                if (log.setDurations.isNotEmpty()) Text("Duration (min): ${log.setDurations.joinToString { it.progressNumber() }}", style = MaterialTheme.typography.bodySmall)
                if (log.setDistances.isNotEmpty()) Text("Distance (km): ${log.setDistances.joinToString { it.progressNumber() }}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${summary.sets} sets · ${summary.reps} reps · ${summary.volume.progressNumber()} kg recorded volume")
                repeat(summary.sets.toInt().coerceAtLeast(0)) { index ->
                    val kilograms = log.setWeights.getOrNull(index) ?: log.weight
                    Text("Set ${index + 1}: ${log.setReps.getOrNull(index) ?: log.reps} reps · ${kilograms?.let { "${it.progressNumber()} kg" } ?: "weight not recorded"}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (log.notes.isNotBlank()) Text(log.notes, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun narrowWeekday(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())

@Composable
private fun WeeklySetsBars(buckets: List<StatsMath.DayBucket>, selectedDate: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    val maxSets = (buckets.maxOfOrNull { it.sets } ?: 0L).coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sets per day", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            buckets.forEach { bucket ->
                val key = bucket.date.toString()
                val selected = selectedDate == key
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable(enabled = enabled && bucket.entries > 0) { onSelect(key) }
                        .padding(vertical = 4.dp)
                ) {
                    Text(if (bucket.sets > 0) bucket.sets.toString() else " ", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Box(
                        Modifier.fillMaxWidth().height(88.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            Modifier.fillMaxWidth()
                                .height((88 * StatsMath.barHeightPercent(bucket.sets, maxSets) / 100).dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        )
                    }
                    Text(narrowWeekday(bucket.date), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text("Select a day to list its recorded entries.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MonthIntensityGrid(
    month: YearMonth,
    weekStartPref: Int,
    perDaySets: Map<LocalDate, Long>,
    selectedDate: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    val maxSets = (perDaySets.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val leading = StatsMath.monthLeadingBlanks(month, weekStartPref)
    val cells: List<LocalDate?> = List(leading) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val weekdayBase = LocalDate.of(2024, 1, 7 + weekStartPref)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Daily intensity", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (0..6).forEach { offset ->
                Text(narrowWeekday(weekdayBase.plusDays(offset.toLong())), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    if (day == null) {
                        Box(Modifier.weight(1f).height(40.dp))
                    } else {
                        val sets = perDaySets[day] ?: 0L
                        val key = day.toString()
                        val selected = selectedDate == key
                        Box(
                            Modifier.weight(1f).height(40.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        sets <= 0 -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.primary.copy(alpha = (0.2f + 0.8f * (sets.toFloat() / maxSets)).coerceAtMost(1f))
                                    }
                                )
                                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(6.dp)) else Modifier)
                                .clickable(enabled = enabled && sets > 0) { onSelect(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                repeat(7 - week.size) { Box(Modifier.weight(1f).height(40.dp)) }
            }
        }
        Text("Darker cells recorded more sets. Only logged days are selectable.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AllTimeVolumeChart(buckets: List<StatsMath.AllTimeBucket>, movingAverages: List<Double>, trend: StatsMath.Trend) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recorded volume per day", style = MaterialTheme.typography.titleMedium)
        Text(
            trend.label,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                trend.change == null || trend.change == 0L -> MaterialTheme.colorScheme.onSurface
                trend.change > 0 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            }
        )
        Text("Bars show daily recorded volume (reps × recorded kg). The line is the 4-point moving average. The trend compares the last four recorded days with the previous four.", style = MaterialTheme.typography.bodySmall)
        if (buckets.isEmpty()) {
            Text("No training data yet")
        } else {
            val maximum = maxOf(1.0, buckets.maxOf { it.volume })
            Text(
                "${buckets.first().date} through ${buckets.last().date} · max ${maximum.progressNumber(0)} kg",
                style = MaterialTheme.typography.bodySmall
            )
            val barColor = MaterialTheme.colorScheme.primary
            val lineColor = MaterialTheme.colorScheme.tertiary
            val gridColor = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val left = 2.dp.toPx()
                val right = size.width - 2.dp.toPx()
                val top = 6.dp.toPx()
                val baseline = size.height - 6.dp.toPx()
                val count = buckets.size
                val step = if (count > 1) (right - left) / (count - 1) else 0f
                val barWidth = maxOf(1.5.dp.toPx(), minOf(18.dp.toPx(), (right - left) / maxOf(count, 1) * 0.58f))
                drawLine(gridColor, Offset(left, top), Offset(right, top), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(left, baseline), Offset(right, baseline), strokeWidth = 1.dp.toPx())
                buckets.forEachIndexed { index, bucket ->
                    val x = if (count == 1) (left + right) / 2f else left + index * step
                    if (bucket.volume > 0.0) {
                        val y = baseline - (bucket.volume / maximum).toFloat() * (baseline - top)
                        val capped = y.coerceAtMost(baseline - 2.dp.toPx())
                        drawRect(
                            barColor.copy(alpha = (0.25f + 0.75f * (bucket.volume / maximum).toFloat()).coerceIn(0f, 1f)),
                            topLeft = Offset(x - barWidth / 2f, capped),
                            size = Size(barWidth, (baseline - capped).coerceAtLeast(2.dp.toPx()))
                        )
                    } else {
                        drawLine(barColor.copy(alpha = 0.3f), Offset(x - barWidth / 2f, baseline - 1.dp.toPx()), Offset(x + barWidth / 2f, baseline - 1.dp.toPx()), strokeWidth = 2.dp.toPx())
                    }
                }
                if (movingAverages.isNotEmpty()) {
                    val path = Path()
                    movingAverages.forEachIndexed { index, average ->
                        val x = if (count == 1) (left + right) / 2f else left + index * step
                        val y = baseline - (average / maximum).toFloat() * (baseline - top)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(buckets.first().date.toString(), style = MaterialTheme.typography.labelSmall)
                Text(buckets.last().date.toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun CategoryBreakdownCard(totals: List<StatsMath.CategoryTotal>) {
    val maxSets = (totals.maxOfOrNull { it.sets } ?: 0L).coerceAtLeast(1L)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Body-part totals", style = MaterialTheme.typography.titleMedium)
            totals.forEach { total ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${total.category.replaceFirstChar { it.uppercase() }} · ${total.sets} sets · ${total.exercises} exercises", style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                        Box(
                            Modifier.fillMaxHeight()
                                .fillMaxWidth((total.sets.toDouble() / maxSets).coerceIn(0.0, 1.0).toFloat())
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
            Text("Sets count timed intervals; exercises are distinct IDs within each category.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun muscleNumber(value: Double): String =
    if (value == floor(value)) value.toLong().toString()
    else BigDecimal.valueOf(value).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

@Composable
private fun MuscleContributionCard(totals: List<StatsMath.MuscleWeight>, accent: String, sex: String?) {
    val maxSets = (totals.maxOfOrNull { it.sets } ?: 0.0).coerceAtLeast(0.5)
    val mapEntries = remember(totals, accent) { MuscleMapMath.dashboardEntries(totals, MuscleMapMath.accentRgb(accent)) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Muscle contribution", style = MaterialTheme.typography.titleMedium)
            Text("Primary muscles count each set fully; mapped secondary muscles add 0.5 per set. Custom exercises without catalog muscle data are excluded.", style = MaterialTheme.typography.bodySmall)
            if (mapEntries.isNotEmpty()) {
                MuscleMapView(mapEntries, MuscleMapMath.genderFor(sex), Modifier.fillMaxWidth().height(300.dp))
                Text("Body figure follows the profile sex setting. Darker regions carried a larger share of the period's sets.", style = MaterialTheme.typography.bodySmall)
            }
            totals.forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${entry.region.replaceFirstChar { it.uppercase() }.replace('-', ' ')} · ${muscleNumber(entry.sets)} sets", style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                        Box(
                            Modifier.fillMaxHeight()
                                .fillMaxWidth((entry.sets / maxSets).coerceIn(0.0, 1.0).toFloat())
                                .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    }
}

private val diaryCategories = FuelMath.DIARY_CATEGORIES

private fun fuelProfileValue(config: VaultConfig, key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double =
    config.profile[key]?.takeIf { it.isFinite() }?.coerceIn(range) ?: default

@Composable
private fun BodyGoalCard(state: FormState) {
    val config = state.data.config
    val missing = state.vault?.file(VaultFiles.CONFIG) == null || VaultFiles.CONFIG in state.data.missingFiles
    val heightCm = fuelProfileValue(config, "height", 178.0, 50.0..300.0)
    val currentKg = fuelProfileValue(config, "current-weight", 75.0, 20.0..500.0)
    val startKg = fuelProfileValue(config, "start-weight", 75.0, 20.0..500.0)
    val goalKg = fuelProfileValue(config, "goal-weight", 78.0, 20.0..500.0)
    val percent = FuelMath.weightGoalPercent(startKg, goalKg, currentKg)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Body Index & Targets", style = MaterialTheme.typography.labelMedium)
            Text("BMI & Target Engine", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Current BMI", style = MaterialTheme.typography.bodySmall)
                    Text(FuelMath.bmi(currentKg, heightCm) ?: "Unavailable", style = MaterialTheme.typography.headlineSmall)
                    Text("Normal: 18.5 - 24.9", style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Current Weight", style = MaterialTheme.typography.bodySmall)
                    Text("${FuelMath.weightText(currentKg)} kg", style = MaterialTheme.typography.headlineSmall)
                    Text(FuelMath.weightGoalMessage(goalKg, currentKg), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Start: ${FuelMath.weightText(startKg)} kg", style = MaterialTheme.typography.bodyMedium)
                Text("${percent.toLong()}% complete", style = MaterialTheme.typography.bodyMedium)
                Text("Target: ${FuelMath.weightText(goalKg)} kg", style = MaterialTheme.typography.bodyMedium)
            }
            Box(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth((percent / 100.0).toFloat())
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                )
            }
            if (missing) Text("config.md is unavailable. These are default estimates, not saved personal targets.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FuelBar(label: String, current: Double, goal: Double, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label: ${FuelMath.macroCurrentGoalText(current, goal)}", style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth((FuelMath.cappedPercent(current, goal) / 100.0).toFloat())
                    .background(color, RoundedCornerShape(4.dp))
            )
        }
    }
}

private sealed class DiarySession(val date: String) {
    class Add(date: String, val id: String) : DiarySession(date)
    class Water(date: String, val original: Double, val initial: Double) : DiarySession(date)
    class Delete(date: String, val original: VaultDiaryMeal) : DiarySession(date)
}

private class DiaryEditorState {
    var session by mutableStateOf<DiarySession?>(null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FuelDiaryScreen(
    state: FormState,
    onReload: () -> Unit,
    onSaveEntry: (String, VaultDiaryMeal?, VaultDiaryMeal?, () -> Unit) -> Unit,
    onWater: (String, Double, Double, () -> Unit) -> Unit,
    onLibrary: () -> Unit
) {
    var dateText by rememberSaveable(state.vault?.uri) { mutableStateOf(LocalDate.now().toString()) }
    val date = progressDate(dateText)?.toString()
    val today = LocalDate.now().toString()
    val future = date != null && date > today
    val editor = remember(state.vault?.uri) { DiaryEditorState() }
    val block = progressFileBlock(state, VaultFiles.NUTRITION_DIARY)
    val libraryBlock = progressFileBlock(state, VaultFiles.MEALS)
    val busy = state.progressBusy()
    val enabled = !busy && !state.reloadRequired
    val day = date?.let { state.data.nutritionDiary[it] }
    val canWrite = block == null && date != null && !future
    val targets = remember(state.data.config) { NutritionTargets.effective(state.data.config) }
    val groups = remember(day?.meals) { FuelMath.groupedDiaryMeals(day?.meals.orEmpty()) }
    val favorites = remember(state.data.meals) { state.data.meals.filter { it.liked }.sortedWith(FuelMath.mealPickerOrder()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ProgressVaultStatus(state, onReload) }
        item { BodyGoalCard(state) }
        item { NutritionTargetSummary(state) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nutrition diary", style = MaterialTheme.typography.headlineSmall)
                Text("Recorded nutrition snapshots for the selected date. Library changes do not alter these entries.")
                ProgressDateControls(dateText, "day", enabled && editor.session == null, maxDate = today) { dateText = it }
                if (future) Text("Future dates cannot be recorded.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { if (enabled) onLibrary() }, enabled = enabled) { Text("Saved meal library") }
                block?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (block == null && date != null) {
            val meals = day?.meals.orEmpty()
            val water = day?.water ?: 0.0
            val consumedCals = meals.sumOf { it.cals }
            val balance = FuelMath.balance(targets["cals"] ?: 0.0, consumedCals)
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recorded totals · $date", style = MaterialTheme.typography.titleLarge)
                        Text("Goal: ${FuelMath.groupedNumber(targets["cals"] ?: 0.0)} kcal", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(balance.magnitudeText, style = MaterialTheme.typography.headlineMedium)
                            Text(if (balance.left) "kcal left" else "kcal over", style = MaterialTheme.typography.bodyMedium)
                        }
                        DiaryMacros(consumedCals, meals.sumOf { it.p }, meals.sumOf { it.c }, meals.sumOf { it.f })
                        if (day == null) Text("No diary record for this date. These are empty recorded totals, not a claim of no intake.", style = MaterialTheme.typography.bodySmall)
                        else if (meals.isEmpty()) Text("No meal entries recorded for this date.", style = MaterialTheme.typography.bodySmall)
                        FuelBar("Protein", meals.sumOf { it.p }, targets["p"] ?: 0.0, MaterialTheme.colorScheme.primary)
                        FuelBar("Carbs", meals.sumOf { it.c }, targets["c"] ?: 0.0, MaterialTheme.colorScheme.tertiary)
                        FuelBar("Fats", meals.sumOf { it.f }, targets["f"] ?: 0.0, MaterialTheme.colorScheme.secondary)
                        Text("Water: ${FuelMath.groupedNumber(water)} / ${FuelMath.groupedNumber(targets["water"] ?: 0.0)} ml", style = MaterialTheme.typography.titleMedium)
                        Box(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                            Box(
                                Modifier.fillMaxHeight()
                                    .fillMaxWidth((FuelMath.cappedPercent(water, targets["water"] ?: 0.0) / 100.0).toFloat())
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                if (canWrite && water in 0.0..49750.0) editor.session = DiarySession.Water(date, water, water + 250.0)
                            }, enabled = canWrite && water in 0.0..49750.0) { Text("+250 ml") }
                            OutlinedButton(onClick = {
                                if (canWrite && water in 0.0..49500.0) editor.session = DiarySession.Water(date, water, water + 500.0)
                            }, enabled = canWrite && water in 0.0..49500.0) { Text("+500 ml") }
                            TextButton(onClick = {
                                if (canWrite) editor.session = DiarySession.Water(date, water, 0.0)
                            }, enabled = canWrite) { Text("Reset") }
                            TextButton(onClick = {
                                if (canWrite) editor.session = DiarySession.Water(date, water, water)
                            }, enabled = canWrite) { Text("Set water total") }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (canWrite && libraryBlock == null) {
                            val existingIds = state.data.nutritionDiary.values.flatMap { value -> value.meals }.map { it.id }.toSet()
                            editor.session = DiarySession.Add(date, FuelMath.numericDiaryId(existingIds, System.currentTimeMillis()))
                        }
                    }, enabled = canWrite && libraryBlock == null) { Text("Log meal") }
                    libraryBlock?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (libraryBlock == null && state.data.meals.isEmpty()) {
                        Text("Your saved meal library is empty; log a custom food, or open the library to create a meal.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (favorites.isNotEmpty()) {
                        Text("Favorite meals", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            favorites.forEach { meal ->
                                val unique = state.data.meals.count { it.id == meal.id } == 1
                                val usable = unique && meal.defaultGrams in 1.0..5000.0 &&
                                    meal.name.isNotBlank() && meal.name.length <= 40 && meal.name == meal.name.trim() &&
                                    meal.name.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        if (canWrite && libraryBlock == null && usable && !busy) {
                                            val existingIds = state.data.nutritionDiary.values.flatMap { value -> value.meals }.map { it.id }.toSet()
                                            val factor = meal.defaultGrams / 100.0
                                            val session = DiarySession.Add(date, FuelMath.numericDiaryId(existingIds, System.currentTimeMillis()))
                                            val snapshot = diarySnapshot(
                                                session, meal, "", meal.defaultGrams, FuelMath.nextLogSlot(day?.meals.orEmpty()),
                                                meal.p100 * factor, meal.c100 * factor, meal.f100 * factor,
                                                FuelMath.jsRound(meal.cals100 * factor).toLong().toString()
                                            )
                                            if (snapshot != null) onSaveEntry(date, null, snapshot) { }
                                        }
                                    },
                                    enabled = canWrite && libraryBlock == null && usable,
                                    label = { Text("${meal.name.substringBefore(',')} (${meal.defaultGrams.diaryNumber()}g)") }
                                )
                            }
                        }
                        Text("Favorite shortcuts log the saved meal at its default portion into the first open slot.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            groups.forEach { (category, entries) ->
                item(key = "group:$date:$category") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(category, style = MaterialTheme.typography.titleMedium)
                        Text("${FuelMath.fuelNumber(entries.sumOf { it.cals })} kcal", style = MaterialTheme.typography.titleMedium)
                    }
                }
                itemsIndexed(entries, key = { index, meal -> "$date:$category:$index:${meal.id}" }) { _, meal ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(meal.name, style = MaterialTheme.typography.titleMedium)
                            DiaryMacros(meal.cals, meal.p, meal.c, meal.f)
                            TextButton(onClick = {
                                if (canWrite) editor.session = DiarySession.Delete(date, meal)
                            }, enabled = canWrite) { Text("Delete entry") }
                        }
                    }
                }
            }
        }
    }
    editor.session?.let { session ->
        key(state.vault?.uri, session) {
            val dismiss = { if (!busy && editor.session === session) editor.session = null }
            val saved = { if (editor.session === session) editor.session = null }
            when (session) {
                is DiarySession.Add -> DiaryAddDialog(
                    session, state, block ?: libraryBlock, onReload, dismiss,
                    onSave = { replacement ->
                        if (block == null && libraryBlock == null && !busy) onSaveEntry(session.date, null, replacement, saved)
                    }
                )
                is DiarySession.Water -> DiaryWaterDialog(session, state, block, onReload, dismiss) { desired ->
                    if (block == null && !busy) onWater(session.date, session.original, desired, saved)
                }
                is DiarySession.Delete -> AlertDialog(
                    onDismissRequest = dismiss,
                    title = { Text("Delete diary entry?") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Remove \"${session.original.name}\" from ${session.date}? The saved meal library is unchanged.")
                            DiaryMacros(session.original.cals, session.original.p, session.original.c, session.original.f)
                            DiaryDialogStatus(state, block, onReload)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (block == null && !busy) onSaveEntry(session.date, session.original, null, saved)
                        }, enabled = block == null && !busy) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } }
                )
            }
        }
    }
}

@Composable
private fun DiaryMacros(calories: Double, protein: Double, carbohydrate: Double, fat: Double) {
    Text("${calories.progressNumber(0)} kcal")
    Text("Protein: ${protein.progressNumber(1)} g · Carbs: ${carbohydrate.progressNumber(1)} g · Fat: ${fat.progressNumber(1)} g")
}

@Composable
private fun DiaryDialogStatus(state: FormState, block: String?, onReload: () -> Unit) {
    if (state.progressBusy()) LinearProgressIndicator(Modifier.fillMaxWidth())
    block?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (state.reloadRequired && state.vault != null) {
        TextButton(onClick = { if (!state.progressBusy()) onReload() }, enabled = !state.progressBusy()) { Text("Reload from disk") }
    }
}

private fun diaryInputNumber(value: String): Double? = value.trim()
    .takeIf { Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)").matches(it) }
    ?.toDoubleOrNull()?.takeIf { it.isFinite() }

@Composable
private fun DiaryWaterDialog(
    session: DiarySession.Water,
    state: FormState,
    block: String?,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var text by remember { mutableStateOf(session.initial.diaryNumber()) }
    val desired = diaryInputNumber(text)?.takeIf { it in 0.0..50000.0 }
    val enabled = block == null && !state.progressBusy()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm water total") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Date: ${session.date}")
                Text("Original total when opened: ${session.original.diaryNumber()} ml")
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Absolute water total (ml)") },
                    singleLine = true, enabled = enabled, isError = desired == null,
                    supportingText = { Text("Enter 0–50000 ml. This replaces the total, not an increment.") }
                )
                if (desired != null) Text("Confirm ${session.original.diaryNumber()} → ${desired.diaryNumber()} ml. Changes made elsewhere will not be overwritten silently.")
                DiaryDialogStatus(state, block, onReload)
            }
        },
        confirmButton = { TextButton(onClick = { if (enabled && desired != null) onSave(desired) }, enabled = enabled && desired != null) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.progressBusy()) { Text("Cancel") } }
    )
}

private fun diarySnapshot(
    session: DiarySession.Add,
    meal: VaultMeal?,
    customName: String,
    grams: Double?,
    category: String,
    protein: Double,
    carbohydrate: Double,
    fat: Double,
    kcalText: String
): VaultDiaryMeal? {
    if (category !in FuelMath.DIARY_CATEGORIES) return null
    val p = FuelMath.round1(protein)
    val c = FuelMath.round1(carbohydrate)
    val f = FuelMath.round1(fat)
    val cals = FuelMath.fallbackKcal(FuelMath.parseIntLike(kcalText), p, c, f)
    if (listOf(cals, p, c, f).any { !it.isFinite() || it !in 0.0..100000.0 }) return null
    val name = if (meal == null) {
        FuelMath.customFoodName(customName) ?: return null
    } else {
        if (grams == null || grams !in 1.0..5000.0) return null
        if (meal.name.isBlank() || meal.name.length > 40 || meal.name != meal.name.trim() ||
            meal.name.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
        val portionName = FuelMath.diaryEntryName(meal.name, grams)
        portionName.takeIf { it.length <= 40 } ?: meal.name
    }
    val snapshot = VaultDiaryMeal(id = session.id, name = name, cals = cals, p = p, c = c, f = f, category = category)
    return snapshot.takeIf {
        runCatching { VaultCodec.encodeNutritionDiary(mapOf(session.date to VaultDiaryDay(meals = listOf(snapshot)))) }.isSuccess
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiaryAddDialog(
    session: DiarySession.Add,
    state: FormState,
    block: String?,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (VaultDiaryMeal) -> Unit
) {
    val dayMeals = state.data.nutritionDiary[session.date]?.meals.orEmpty()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<VaultMeal?>(null) }
    var customName by remember { mutableStateOf("") }
    var gramsText by remember { mutableStateOf("100") }
    var caloriesText by remember { mutableStateOf("0") }
    var proteinText by remember { mutableStateOf("0.0") }
    var carbohydrateText by remember { mutableStateOf("0.0") }
    var fatText by remember { mutableStateOf("0.0") }
    var category by remember { mutableStateOf(FuelMath.nextLogSlot(dayMeals)) }
    val grams = diaryInputNumber(gramsText)?.takeIf { it in 1.0..5000.0 }
    val matches = remember(query, state.data.meals) {
        val search = query.trim()
        state.data.meals.filter { it.name.contains(search, true) || it.id.contains(search, true) }
            .sortedWith(FuelMath.mealPickerOrder())
    }
    val favorites = remember(state.data.meals) { state.data.meals.filter { it.liked }.sortedWith(FuelMath.mealPickerOrder()) }
    val idCounts = remember(state.data.meals) { state.data.meals.groupingBy { it.id }.eachCount() }
    val current = when (val picked = selected) {
        null -> true
        else -> state.data.meals.singleOrNull { it.id == picked.id } == picked
    }

    fun scaledText(value: Double, factor: Double): String = FuelMath.toFixed1(FuelMath.round1(value * factor))
    fun applyReference(meal: VaultMeal, portion: Double) {
        val factor = portion / 100.0
        proteinText = scaledText(meal.p100, factor)
        carbohydrateText = scaledText(meal.c100, factor)
        fatText = scaledText(meal.f100, factor)
        caloriesText = FuelMath.jsRound(meal.cals100 * factor).toLong().toString()
    }
    fun pickMeal(meal: VaultMeal) {
        selected = meal
        gramsText = meal.defaultGrams.diaryNumber()
        applyReference(meal, meal.defaultGrams.takeIf { it in 1.0..5000.0 } ?: 100.0)
    }
    fun pickCustom() {
        selected = null
        customName = ""
        gramsText = "100"
        proteinText = "0.0"
        carbohydrateText = "0.0"
        fatText = "0.0"
        caloriesText = "0"
    }
    fun syncCalories(protein: String, carbohydrate: String, fat: String) {
        val p = diaryInputNumber(protein) ?: 0.0
        val c = diaryInputNumber(carbohydrate) ?: 0.0
        val f = diaryInputNumber(fat) ?: 0.0
        caloriesText = FuelMath.kcalFromMacros(p, c, f).toLong().toString()
    }

    val snapshot = remember(session, selected, customName, grams, category, caloriesText, proteinText, carbohydrateText, fatText) {
        diarySnapshot(
            session, selected, customName, grams, category,
            diaryInputNumber(proteinText) ?: 0.0,
            diaryInputNumber(carbohydrateText) ?: 0.0,
            diaryInputNumber(fatText) ?: 0.0,
            caloriesText
        )
    }
    val enabled = block == null && !state.progressBusy()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add meal · ${session.date}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "shortcuts") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected == null, { if (enabled) pickCustom() }, enabled = enabled, label = { Text("Custom food") })
                            favorites.forEach { meal ->
                                val unique = idCounts[meal.id] == 1
                                FilterChip(
                                    selected = selected?.id == meal.id,
                                    onClick = { if (enabled && unique) pickMeal(meal) },
                                    enabled = enabled && unique,
                                    label = { Text("${meal.name.substringBefore(',')} (${meal.defaultGrams.diaryNumber()}g)") }
                                )
                            }
                        }
                        Text("Custom food logs a named portion from the values below. Favorite shortcuts prefill a saved meal at its default portion; adjusting the values never changes the saved definition.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item(key = "search") {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search all saved meals by name or ID") }, singleLine = true, enabled = enabled)
                    Text("${matches.size} of ${state.data.meals.size} saved meals", style = MaterialTheme.typography.bodySmall)
                }
                item(key = "fields") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(selected?.let { "Selected: ${it.name}" } ?: "Custom food: name this food and enter the values for the portion you ate.", style = MaterialTheme.typography.titleSmall)
                        if (selected == null) {
                            OutlinedTextField(
                                customName, { customName = it }, Modifier.fillMaxWidth(),
                                label = { Text("Custom food name") }, singleLine = true, enabled = enabled,
                                supportingText = { Text("1–40 characters, single line.") }
                            )
                        }
                        if (selected != null) {
                            OutlinedTextField(
                                gramsText, { value ->
                                    gramsText = value
                                    val meal = selected
                                    val parsed = diaryInputNumber(value)?.takeIf { portion -> portion in 1.0..5000.0 }
                                    if (meal != null && parsed != null) applyReference(meal, parsed)
                                }, Modifier.fillMaxWidth(), label = { Text("Portion (grams)") },
                                singleLine = true, enabled = enabled, isError = grams == null,
                                supportingText = { Text("1–5000 g; macros and kcal rescale from the saved meal's per-100 g values.") }
                            )
                        }
                        OutlinedTextField(
                            caloriesText, { caloriesText = it }, Modifier.fillMaxWidth(),
                            label = { Text("Calories (kcal)") }, singleLine = true, enabled = enabled,
                            supportingText = { Text("Recomputed from macros when they change; 0 or invalid falls back to 4P+4C+9F on save.") }
                        )
                        OutlinedTextField(
                            proteinText, { value -> proteinText = value; syncCalories(value, carbohydrateText, fatText) },
                            Modifier.fillMaxWidth(), label = { Text("Protein (g)") }, singleLine = true, enabled = enabled,
                            supportingText = { Text("0–100000 per value; decimals allowed.") }
                        )
                        OutlinedTextField(
                            carbohydrateText, { value -> carbohydrateText = value; syncCalories(proteinText, value, fatText) },
                            Modifier.fillMaxWidth(), label = { Text("Carbs (g)") }, singleLine = true, enabled = enabled
                        )
                        OutlinedTextField(
                            fatText, { value -> fatText = value; syncCalories(proteinText, carbohydrateText, value) },
                            Modifier.fillMaxWidth(), label = { Text("Fats (g)") }, singleLine = true, enabled = enabled
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            diaryCategories.forEach { value ->
                                FilterChip(category == value, { category = value }, enabled = enabled, label = { Text(value) })
                            }
                        }
                    }
                }
                item(key = "preview") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (snapshot != null) {
                            Text("Diary name: ${snapshot.name}")
                            if (selected != null) Text("Portion: ${grams?.diaryNumber()} g")
                            DiaryMacros(snapshot.cals, snapshot.p, snapshot.c, snapshot.f)
                            val meal = selected
                            if (meal != null && snapshot.name == meal.name) {
                                Text("The portion suffix would exceed 40 characters, so the meal name is saved unchanged. Nutrition includes the selected grams; grams are not stored separately.", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Text(
                                if (selected == null) "Enter a single-line custom name of 1–40 characters to save."
                                else "This entry cannot be saved with the current values. Use a 1–5000 g portion and nutrition values of 0–100000 per field.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (selected != null && !current) Text("This library meal changed or is unavailable. Select it again before saving.", color = MaterialTheme.colorScheme.error)
                        DiaryDialogStatus(state, block, onReload)
                    }
                }
                if (matches.isEmpty()) item(key = "no-matches") { Text(if (state.data.meals.isEmpty()) "No saved meals available; custom food still works." else "No matching saved meals.") }
                itemsIndexed(matches, key = { index, meal -> "$index:${meal.id}" }) { _, meal ->
                    val unique = idCounts[meal.id] == 1
                    FilterChip(
                        selected = selected == meal,
                        onClick = { if (enabled && unique) pickMeal(meal) },
                        enabled = enabled && unique,
                        label = { Text("${meal.name} · ${meal.defaultGrams.diaryNumber()} g (${meal.id})${if (!unique) " · ambiguous ID" else ""}") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (enabled && current && snapshot != null) onSave(snapshot) }, enabled = enabled && current && snapshot != null) { Text("Save entry") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.progressBusy()) { Text("Cancel") } }
    )
}
