package com.thehhr.form.nativeapp.workout

import com.thehhr.form.nativeapp.Exercise
import com.thehhr.form.nativeapp.vault.VaultRoutine
import com.thehhr.form.nativeapp.vault.VaultRoutineItem
import com.thehhr.form.nativeapp.vault.VaultTrainingLog
import java.util.UUID
import kotlin.math.floor

object ExerciseRules {
    const val LIMIT_SETS = 20
    const val LIMIT_REPS = 100
    const val LIMIT_WEIGHT = 2000.0
    const val LIMIT_DURATION = 600.0
    const val LIMIT_DISTANCE = 500.0
    private const val BODY_WEIGHT = "body weight"
    private const val CARDIO_CATEGORY = "cardio"
    private val WEIGHTLESS_EQUIPMENT = setOf(
        "body weight", "assisted", "band", "bosu ball", "hammer", "medicine ball", "resistance band",
        "roller", "rope", "skierg machine", "stability ball", "tire", "upper body ergometer", "wheel roller"
    )
    private val CARDIO_WEIGHTED_EQUIPMENT = setOf(
        "dumbbell", "barbell", "ez barbell", "smith machine", "kettlebell", "medicine ball",
        "weighted", "sled machine", "band", "resistance band"
    )

    fun logId(startedAt: Long, exerciseId: String): String =
        UUID.nameUUIDFromBytes("$startedAt:$exerciseId".toByteArray(Charsets.UTF_8)).toString()

    fun exerciseHasWeight(exercise: Exercise?): Boolean =
        exercise != null && normalize(exercise.equipment) !in WEIGHTLESS_EQUIPMENT

    fun isTimedCardioExercise(exercise: Exercise?): Boolean =
        exercise != null && exercise.category == CARDIO_CATEGORY &&
            exercise.equipment != BODY_WEIGHT &&
            normalize(exercise.equipment) !in CARDIO_WEIGHTED_EQUIPMENT

    fun resolvedMode(itemMode: String?, exercise: Exercise?): String = when (itemMode) {
        "timed" -> "timed"
        "reps" -> "reps"
        else -> if (isTimedCardioExercise(exercise)) "timed" else "reps"
    }

    fun resolvedWeighted(item: VaultRoutineItem, exercise: Exercise?): Boolean = when {
        item.unweighted -> false
        item.weighted -> true
        item.mode == "timed" -> false
        else -> exerciseHasWeight(exercise)
    }

    fun resolvedUnit(itemUnit: String?, exercise: Exercise?): String = when (itemUnit) {
        "sec" -> "sec"
        "min" -> "min"
        else -> if (isTimedCardioExercise(exercise)) "min" else "sec"
    }

    fun exerciseState(item: VaultRoutineItem, exercise: Exercise, history: List<VaultTrainingLog>): WorkoutExerciseState =
        WorkoutExerciseState(
            exerciseId = item.exerciseId,
            exerciseName = exercise.name,
            sets = seedRows(item, exercise, history),
            mode = resolvedMode(item.mode, exercise),
            unit = resolvedUnit(item.unit, exercise),
            weighted = resolvedWeighted(item, exercise),
            unweighted = item.unweighted,
            superset = item.superset,
            prescribedReps = item.reps
        )

    fun seedRows(item: VaultRoutineItem, exercise: Exercise?, history: List<VaultTrainingLog>): List<WorkoutSetRow> =
        if (resolvedMode(item.mode, exercise) == "timed") seedTimedRows(item, exercise, history)
        else seedStrengthRows(item, exercise, history)

    fun latestLogFor(exerciseId: String, history: List<VaultTrainingLog>): VaultTrainingLog? =
        history.withIndex()
            .filter { it.value.exerciseId == exerciseId }
            .maxWithOrNull(compareBy({ it.value.date }, { it.index }))
            ?.value

    fun lastLoggedWeightFor(exerciseId: String, history: List<VaultTrainingLog>): Double {
        val latest = history.withIndex()
            .filter { it.value.exerciseId == exerciseId && !it.value.timed && tracksWeight(it.value) }
            .maxWithOrNull(compareBy({ it.value.date }, { it.index }))
            ?.value ?: return 0.0
        val positives = latest.setWeights.filter { it > 0.0 }
        val value = if (positives.isNotEmpty()) positives.last() else latest.weight ?: 0.0
        return clamp(round1(value), 0.0, LIMIT_WEIGHT)
    }

    fun addedRow(existing: List<WorkoutSetRow>, state: WorkoutExerciseState): WorkoutSetRow? {
        if (existing.size >= LIMIT_SETS) return null
        val template = existing.lastOrNull()
        return if (state.mode == "timed") {
            val reps = state.prescribedReps.toDouble()
            val fallback = if (state.unit == "sec") {
                roundHalfUp(if (reps == 0.0) 10.0 else reps)
            } else {
                maxOf(1.0, roundHalfUp(if (reps == 0.0) 1.0 else reps))
            }
            WorkoutSetRow(
                durationMin = storedDuration(template?.durationMin ?: fallback),
                distanceKm = template?.distanceKm ?: 0.0
            )
        } else {
            WorkoutSetRow(
                weight = template?.weight ?: 0.0,
                reps = template?.reps ?: state.prescribedReps.coerceIn(1, LIMIT_REPS)
            )
        }
    }

    fun logForExercise(session: WorkoutSession, state: WorkoutExerciseState): VaultTrainingLog? {
        val done = state.sets.filter { it.done }.take(LIMIT_SETS)
        if (done.isEmpty()) return null
        val id = logId(session.startedAt, state.exerciseId)
        return if (state.mode == "timed") {
            val unitSec = state.unit == "sec"
            val durations = done.map { row ->
                val raw = clamp(numberOrZero(row.durationMin), 0.0, LIMIT_DURATION)
                clamp(round2(if (unitSec) raw / 60.0 else raw), 0.0, LIMIT_DURATION)
            }
            val distances = done.map { round1(clamp(numberOrZero(it.distanceKm), 0.0, LIMIT_DISTANCE)) }
            val hasDuration = durations.any { it > 0.0 }
            val hasDistance = distances.any { it > 0.0 }
            if (!hasDuration && !hasDistance) return null
            VaultTrainingLog(
                id = id,
                exerciseId = state.exerciseId,
                date = session.date,
                intervals = done.size,
                setDurations = if (hasDuration) durations else emptyList(),
                setDistances = if (hasDistance) distances else emptyList(),
                durUnit = if (unitSec) "min" else "sec",
                exerciseLabel = "\"${state.exerciseName}\""
            )
        } else {
            val setReps = done.map { clamp(it.reps.toDouble(), 1.0, LIMIT_REPS.toDouble()).toInt() }
            val setWeights = done.map { round1(clamp(numberOrZero(it.weight), 0.0, LIMIT_WEIGHT)) }
            val tracked = setWeights.filter { it > 0.0 }
            VaultTrainingLog(
                id = id,
                exerciseId = state.exerciseId,
                date = session.date,
                sets = done.size,
                reps = setReps.first(),
                weight = if (tracked.isNotEmpty()) tracked.last() else null,
                setWeights = setWeights,
                setReps = setReps,
                exerciseLabel = "\"${state.exerciseName}\""
            )
        }
    }

    fun isComplete(state: WorkoutExerciseState): Boolean =
        state.sets.isNotEmpty() && state.sets.all { it.done }

    fun firstIncompleteIndex(session: WorkoutSession): Int =
        session.exercises.indexOfFirst { !it.skipped && !isComplete(it) }

    fun completedSetCount(session: WorkoutSession): Int =
        session.exercises.filter { !it.skipped }.sumOf { it.sets.count { row -> row.done } }

    fun completedExerciseCount(session: WorkoutSession): Int =
        session.exercises.count { !it.skipped && isComplete(it) }

    fun withSecondaryToggled(
        session: WorkoutSession,
        secondaryId: String,
        routines: List<VaultRoutine>,
        catalog: Map<String, Exercise>,
        history: List<VaultTrainingLog>
    ): WorkoutSession? {
        val primary = routines.firstOrNull { it.id == session.routineId } ?: return null
        val secondary = routines.firstOrNull { it.id == secondaryId } ?: return null
        if (secondary.id == primary.id || secondary.items.isEmpty()) return null
        val ids = if (secondaryId in session.secondaryIds) {
            session.secondaryIds - secondaryId
        } else {
            session.secondaryIds + secondaryId
        }
        val known = (session.exercises + session.dormant.values).associateBy { it.exerciseId }
        val seen = mutableSetOf<String>()
        val exercises = mutableListOf<WorkoutExerciseState>()
        fun place(item: VaultRoutineItem) {
            if (!seen.add(item.exerciseId)) return
            val existing = known[item.exerciseId]
            if (existing != null) {
                exercises += refreshState(existing, item, catalog[item.exerciseId])
                return
            }
            val exercise = catalog[item.exerciseId] ?: return
            exercises += exerciseState(item, exercise, history)
        }
        primary.items.forEach(::place)
        for (id in ids) routines.firstOrNull { it.id == id }?.items?.forEach(::place)
        if (exercises.isEmpty()) return null
        val visible = exercises.mapTo(mutableSetOf()) { it.exerciseId }
        val dormant = (session.dormant + session.exercises.associateBy { it.exerciseId })
            .filterKeys { it !in visible }
        val currentId = session.exercises.getOrNull(session.currentIndex)?.exerciseId
        val index = exercises.indexOfFirst { it.exerciseId == currentId }.takeIf { it >= 0 }
            ?: exercises.indexOfFirst { !it.skipped && !isComplete(it) }.takeIf { it >= 0 }
            ?: 0
        return session.copy(exercises = exercises, dormant = dormant, secondaryIds = ids, currentIndex = index)
    }

    fun migrateV1(session: WorkoutSession): WorkoutSession = session.copy(
        secondaryIds = emptyList(),
        dormant = emptyMap(),
        exercises = session.exercises.map { exercise ->
            exercise.copy(
                prescribedReps = 10,
                sets = if (exercise.mode == "timed" && exercise.unit == "sec") {
                    exercise.sets.map { row ->
                        row.copy(durationMin = round2(numberOrZero(row.durationMin) * 60.0).coerceIn(0.0, LIMIT_DURATION))
                    }
                } else {
                    exercise.sets
                }
            )
        }
    )

    internal fun numberOrZero(value: Double): Double = if (value.isNaN()) 0.0 else value

    internal fun roundHalfUp(value: Double): Double = if (value.isNaN()) 0.0 else floor(value + 0.5)

    internal fun round1(value: Double): Double = roundHalfUp(value * 10.0) / 10.0

    internal fun round2(value: Double): Double = roundHalfUp(value * 100.0) / 100.0

    internal fun clamp(value: Double, minimum: Double, maximum: Double): Double {
        val number = if (value.isNaN() || value == 0.0) minimum else value
        return minOf(maximum, maxOf(minimum, number))
    }

    private fun refreshState(existing: WorkoutExerciseState, item: VaultRoutineItem, exercise: Exercise?): WorkoutExerciseState =
        if (exercise == null) existing
        else existing.copy(
            mode = resolvedMode(item.mode, exercise),
            unit = resolvedUnit(item.unit, exercise),
            weighted = resolvedWeighted(item, exercise),
            unweighted = item.unweighted,
            superset = item.superset,
            prescribedReps = item.reps
        )

    private fun storedDuration(duration: Double): Double =
        numberOrZero(duration).coerceIn(0.0, LIMIT_DURATION)

    private fun seedTimedRows(item: VaultRoutineItem, exercise: Exercise?, history: List<VaultTrainingLog>): List<WorkoutSetRow> {
        val unitSec = resolvedUnit(item.unit, exercise) == "sec"
        val latest = latestLogFor(item.exerciseId, history)
        val durations = latest?.setDurations?.takeIf { it.isNotEmpty() } ?: emptyList()
        val distances = latest?.setDistances?.takeIf { it.isNotEmpty() } ?: emptyList()
        val lastDuration = if (durations.isNotEmpty()) {
            val value = numberOrZero(durations.last())
            if (unitSec) round2(clamp(roundHalfUp(value), 0.0, LIMIT_DURATION) * 60.0)
            else clamp(roundHalfUp(value), 0.0, 60.0)
        } else {
            numberOrZero(item.reps.toDouble())
        }
        val lastDistance = round1(clamp(numberOrZero(distances.lastOrNull() ?: 0.0), 0.0, LIMIT_DISTANCE))
        return List(item.sets.coerceIn(1, LIMIT_SETS)) {
            WorkoutSetRow(durationMin = storedDuration(lastDuration), distanceKm = lastDistance)
        }
    }

    private fun seedStrengthRows(item: VaultRoutineItem, exercise: Exercise?, history: List<VaultTrainingLog>): List<WorkoutSetRow> {
        val weighted = resolvedWeighted(item, exercise)
        val lastWeight = if (weighted) lastLoggedWeightFor(item.exerciseId, history) else 0.0
        return List(item.sets.coerceIn(1, LIMIT_SETS)) {
            WorkoutSetRow(weight = lastWeight, reps = item.reps.coerceIn(1, LIMIT_REPS))
        }
    }

    private fun tracksWeight(log: VaultTrainingLog): Boolean =
        log.setWeights.any { it > 0.0 } || (!log.timed && (log.weight ?: 0.0) > 0.0)

    private fun normalize(equipment: String): String = equipment.trim().lowercase()
}
