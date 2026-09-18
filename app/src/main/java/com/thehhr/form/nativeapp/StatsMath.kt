package com.thehhr.form.nativeapp

import com.thehhr.form.nativeapp.vault.VaultTrainingLog
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.floor

object StatsMath {

    data class Headline(val sessions: Long, val sets: Long, val exercises: Long)

    data class DayBucket(val date: LocalDate, val sets: Long, val entries: Long, val exercises: Long)

    data class AllTimeBucket(val date: LocalDate, val sets: Long, val volume: Double, val exercises: Long)

    data class Trend(val change: Long?, val label: String)

    data class CategoryTotal(val category: String, val sets: Long, val exercises: Long)

    data class MuscleWeight(val region: String, val sets: Double)

    val targetToMuscle: Map<String, String> = mapOf(
        "abs" to "abs", "quads" to "quadriceps", "lats" to "upper-back", "calves" to "calves",
        "pectorals" to "chest", "glutes" to "gluteal", "hamstrings" to "hamstring", "adductors" to "adductors",
        "triceps" to "triceps", "spine" to "lower-back", "upper back" to "upper-back", "biceps" to "biceps",
        "delts" to "deltoids", "forearms" to "forearm", "traps" to "trapezius", "serratus anterior" to "obliques",
        "abductors" to "gluteal", "levator scapulae" to "neck"
    )

    val secondaryMuscleToMap: Map<String, String> = mapOf(
        "shoulders" to "deltoids", "rear deltoids" to "deltoids", "deltoids" to "deltoids",
        "rotator cuff" to "rotator-cuff", "trapezius" to "trapezius", "traps" to "trapezius",
        "rhomboids" to "rhomboids", "upper back" to "upper-back", "back" to "upper-back",
        "latissimus dorsi" to "upper-back", "lats" to "upper-back", "chest" to "chest",
        "upper chest" to "chest", "biceps" to "biceps", "brachialis" to "biceps", "triceps" to "triceps",
        "forearms" to "forearm", "wrist flexors" to "forearm", "wrist extensors" to "forearm",
        "wrists" to "forearm", "grip muscles" to "forearm", "hands" to "forearm", "core" to "abs",
        "abdominals" to "abs", "lower abs" to "abs", "obliques" to "obliques", "hip flexors" to "quadriceps",
        "groin" to "adductors", "inner thighs" to "adductors", "quadriceps" to "quadriceps",
        "hamstrings" to "hamstring", "glutes" to "gluteal", "calves" to "calves", "soleus" to "calves",
        "shins" to "tibialis", "ankles" to "ankles", "ankle stabilizers" to "ankles", "feet" to "feet",
        "sternocleidomastoid" to "neck", "lower back" to "lower-back"
    )

    private val isoDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    fun parseLogDate(value: String): LocalDate? {
        if (!isoDate.matches(value)) return null
        return runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { it.year in 1..9999 && it.toString() == value }
    }

    fun jsRound(value: Double): Long = floor(value + 0.5).toLong()

    fun logSetsCount(log: VaultTrainingLog): Long = if (log.timed) {
        (log.intervals ?: maxOf(log.setDurations.size, log.setDistances.size, 1)).coerceAtLeast(0).toLong()
    } else {
        (log.setWeights.size.takeIf { it > 0 } ?: maxOf(log.sets, log.setReps.size)).coerceAtLeast(0).toLong()
    }

    fun logVolume(log: VaultTrainingLog): Double {
        if (log.timed) return 0.0
        fun finite(value: Double?): Double = value?.takeIf { it.isFinite() } ?: 0.0
        return when {
            log.setWeights.isNotEmpty() && log.setReps.isNotEmpty() ->
                log.setWeights.mapIndexed { index, weight ->
                    finite(weight) * (log.setReps.getOrNull(index) ?: 0).coerceAtLeast(0)
                }.sum()
            log.setWeights.isNotEmpty() -> log.setWeights.sumOf { finite(it) } * log.reps.coerceAtLeast(0)
            else -> log.sets.coerceAtLeast(0).toDouble() * log.reps.coerceAtLeast(0) * finite(log.weight)
        }
    }

    fun headline(logs: List<VaultTrainingLog>): Headline = Headline(
        sessions = logs.mapNotNull { parseLogDate(it.date) }.distinct().size.toLong(),
        sets = logs.sumOf(::logSetsCount),
        exercises = logs.map { it.exerciseId }.distinct().size.toLong()
    )

    fun loggedDates(logs: List<VaultTrainingLog>): List<LocalDate> =
        logs.mapNotNull { parseLogDate(it.date) }.distinct().sorted()

    fun weekStartOf(date: LocalDate, weekStartPref: Int): LocalDate =
        date.minusDays(((date.dayOfWeek.value % 7 - weekStartPref + 7) % 7).toLong())

    fun weekHasLogs(weekStart: LocalDate, dates: Collection<LocalDate>): Boolean {
        val end = weekStart.plusDays(6)
        return dates.any { !it.isBefore(weekStart) && !it.isAfter(end) }
    }

    fun weekBuckets(logs: List<VaultTrainingLog>, weekStart: LocalDate): List<DayBucket> {
        val byDate = logs.groupBy { parseLogDate(it.date) }
        return (0L..6L).map { offset ->
            val day = weekStart.plusDays(offset)
            val dayLogs = byDate[day].orEmpty()
            DayBucket(
                date = day,
                sets = dayLogs.sumOf(::logSetsCount),
                entries = dayLogs.size.toLong(),
                exercises = dayLogs.map { it.exerciseId }.distinct().size.toLong()
            )
        }
    }

    fun barHeightPercent(sets: Long, maxSets: Long): Int = when {
        sets <= 0 -> 3
        else -> jsRound(sets.toDouble() / maxSets.coerceAtLeast(1) * 100).toInt().coerceAtLeast(10)
    }

    fun monthSets(logs: List<VaultTrainingLog>, month: YearMonth): Map<LocalDate, Long> {
        val totals = sortedMapOf<LocalDate, Long>()
        logs.forEach { log ->
            val date = parseLogDate(log.date) ?: return@forEach
            if (YearMonth.from(date) == month) totals[date] = (totals[date] ?: 0L) + logSetsCount(log)
        }
        return totals
    }

    fun monthLeadingBlanks(month: YearMonth, weekStartPref: Int): Int =
        ((month.atDay(1).dayOfWeek.value % 7) - weekStartPref + 7) % 7

    fun adjacentLoggedWeekStart(anchor: LocalDate, weekStartPref: Int, dates: List<LocalDate>, direction: Long): LocalDate? {
        if (dates.isEmpty()) return null
        val minimum = weekStartOf(dates.minOrNull() ?: return null, weekStartPref)
        val maximum = weekStartOf(dates.maxOrNull() ?: return null, weekStartPref)
        var candidate = weekStartOf(anchor, weekStartPref).plusWeeks(direction)
        val limit = if (direction < 0) minimum else maximum
        while (if (direction < 0) !candidate.isBefore(limit) else !candidate.isAfter(limit)) {
            if (weekHasLogs(candidate, dates)) return candidate
            candidate = candidate.plusWeeks(direction)
        }
        return null
    }

    fun adjacentLoggedMonth(anchor: LocalDate, dates: List<LocalDate>, direction: Long): YearMonth? {
        if (dates.isEmpty()) return null
        val minimum = YearMonth.from(dates.minOrNull() ?: return null)
        val maximum = YearMonth.from(dates.maxOrNull() ?: return null)
        var candidate = YearMonth.from(anchor).plusMonths(direction)
        while (candidate >= minimum && candidate <= maximum) {
            if (dates.any { YearMonth.from(it) == candidate }) return candidate
            candidate = candidate.plusMonths(direction)
        }
        return null
    }

    fun allTimeBuckets(logs: List<VaultTrainingLog>): List<AllTimeBucket> {
        class Accumulator {
            var sets: Long = 0
            var volume: Double = 0.0
            val exercises = mutableSetOf<String>()
        }
        val buckets = linkedMapOf<LocalDate, Accumulator>()
        logs.forEach { log ->
            val date = parseLogDate(log.date) ?: return@forEach
            val bucket = buckets.getOrPut(date) { Accumulator() }
            bucket.sets += logSetsCount(log)
            bucket.volume += logVolume(log)
            bucket.exercises += log.exerciseId
        }
        return buckets.entries.sortedBy { it.key }.map { AllTimeBucket(it.key, it.value.sets, it.value.volume, it.value.exercises.size.toLong()) }
    }

    fun movingAverages(volumes: List<Double>): List<Double> =
        List(volumes.size) { index -> volumes.subList(maxOf(0, index - 3), index + 1).average() }

    fun trend(volumes: List<Double>): Trend {
        if (volumes.size < 5) return Trend(null, "Building trend")
        val currentFour = volumes.takeLast(4).sum()
        val previousFour = volumes.dropLast(4).takeLast(4).sum()
        val change = if (previousFour != 0.0) jsRound((currentFour - previousFour) / previousFour * 100) else null
        return when {
            change == null -> Trend(null, "New activity")
            change > 0 -> Trend(change, "Last 4 workouts: ↑ $change%")
            change < 0 -> Trend(change, "Last 4 workouts: ↓ ${-change}%")
            else -> Trend(change, "Last 4 workouts: No change")
        }
    }

    fun categoryTotals(logs: List<VaultTrainingLog>, exercisesById: Map<String, Exercise>): List<CategoryTotal> {
        val grouped = linkedMapOf<String, MutableList<VaultTrainingLog>>()
        logs.forEach { log ->
            val category = exercisesById[log.exerciseId]?.category?.takeIf { it.isNotBlank() } ?: "Other"
            grouped.getOrPut(category) { mutableListOf() }.add(log)
        }
        return grouped.map { (category, entries) ->
            CategoryTotal(category, entries.sumOf(::logSetsCount), entries.map { it.exerciseId }.distinct().size.toLong())
        }.sortedByDescending { it.sets }
    }

    fun muscleContributions(logs: List<VaultTrainingLog>, exercisesById: Map<String, Exercise>): List<MuscleWeight> {
        val totals = linkedMapOf<String, Double>()
        fun add(region: String, amount: Double) {
            if (region.isNotBlank()) totals[region] = (totals[region] ?: 0.0) + amount
        }
        logs.forEach { log ->
            val exercise = exercisesById[log.exerciseId] ?: return@forEach
            if (exercise.muscleGroup.isBlank() && exercise.secondaryMuscles.isEmpty()) return@forEach
            val sets = logSetsCount(log).toDouble()
            if (sets <= 0.0) return@forEach
            val primary = targetToMuscle[exercise.target.trim().lowercase()]
                ?: secondaryMuscleToMap[exercise.muscleGroup.trim().lowercase()]
            if (primary != null) add(primary, sets)
            exercise.secondaryMuscles.forEach { raw ->
                val mapped = secondaryMuscleToMap[raw.trim().lowercase()]
                if (mapped != null && mapped != primary) add(mapped, sets * 0.5)
            }
        }
        return totals.map { MuscleWeight(it.key, it.value) }.sortedByDescending { it.sets }
    }
}
