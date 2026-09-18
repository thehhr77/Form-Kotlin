package com.thehhr.form.nativeapp.vault

import com.thehhr.form.nativeapp.workout.ExerciseRules
import kotlin.math.floor

object RoutineMath {
    const val REPS_PARSE_CAP = 60
    const val REPS_EDITOR_CAP = 100
    const val DEFAULT_TIMED_SEC = 10
    const val DEFAULT_TIMED_MIN = 1

    fun lastLoggedDurationMinutes(logs: List<VaultTrainingLog>, exerciseId: String): Double? {
        val latest = logs.withIndex()
            .filter { it.value.exerciseId == exerciseId && it.value.timed }
            .maxWithOrNull(compareBy({ it.value.date }, { it.index }))?.value ?: return null
        if (latest.setDurations.isEmpty()) return null
        val value = round2(numberOrZero(latest.setDurations.last())).coerceIn(0.0, ExerciseRules.LIMIT_DURATION)
        return value.takeIf { it > 0.0 }
    }

    fun lastLoggedReps(logs: List<VaultTrainingLog>, exerciseId: String): Int? {
        val latest = logs.withIndex()
            .filter { it.value.exerciseId == exerciseId && !it.value.timed }
            .maxWithOrNull(compareBy({ it.value.date }, { it.index }))?.value ?: return null
        if (latest.setReps.isNotEmpty()) return latest.setReps.last().coerceIn(1, REPS_EDITOR_CAP)
        return latest.reps.takeIf { it >= 1 }?.coerceIn(1, REPS_EDITOR_CAP)
    }

    fun timedPrescription(logs: List<VaultTrainingLog>, exerciseId: String, unitIsSec: Boolean): Int {
        val minutes = lastLoggedDurationMinutes(logs, exerciseId)
            ?: return if (unitIsSec) DEFAULT_TIMED_SEC else DEFAULT_TIMED_MIN
        return if (unitIsSec) maxOf(DEFAULT_TIMED_SEC, roundHalfUp(minutes * 60.0).toInt())
        else maxOf(DEFAULT_TIMED_MIN, roundHalfUp(minutes).toInt())
    }

    fun strengthPrescription(logs: List<VaultTrainingLog>, exerciseId: String, previous: Int): Int =
        lastLoggedReps(logs, exerciseId) ?: previous.coerceIn(1, REPS_EDITOR_CAP)

    fun clampForPersistence(reps: Int): Int = reps.coerceIn(0, REPS_PARSE_CAP)

    fun nextSupersetToken(items: List<VaultRoutineItem>): String =
        ((items.mapNotNull { it.superset?.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()

    fun pairSuperset(items: List<VaultRoutineItem>, firstId: String, secondId: String): List<VaultRoutineItem> {
        if (firstId == secondId) return items
        val first = items.firstOrNull { it.exerciseId == firstId } ?: return items
        val second = items.firstOrNull { it.exerciseId == secondId } ?: return items
        val token = nextSupersetToken(items)
        val relinked = items.map { entry ->
            when (entry.exerciseId) {
                firstId, secondId -> entry.copy(superset = token)
                else -> entry.copy(superset = if (entry.superset == first.superset || entry.superset == second.superset) null else entry.superset)
            }
        }
        val withoutSecond = relinked.filterNot { it.exerciseId == secondId }
        val firstPosition = withoutSecond.indexOfFirst { it.exerciseId == firstId }
        return withoutSecond.toMutableList().apply { add(firstPosition + 1, second.copy(superset = token)) }
    }

    fun unlinkSuperset(items: List<VaultRoutineItem>, exerciseId: String): List<VaultRoutineItem> {
        val token = items.firstOrNull { it.exerciseId == exerciseId }?.superset ?: return items
        return items.map { if (it.superset == token) it.copy(superset = null) else it }
    }

    fun schedulePatch(schedule: Map<Int, String?>, oldName: String, newName: String?): Map<Int, String?> =
        schedule.mapValues { (_, value) -> if (value == oldName) newName else value }
            .filter { (day, value) -> value != schedule[day] }

    private fun numberOrZero(value: Double): Double = if (value.isNaN()) 0.0 else value

    private fun roundHalfUp(value: Double): Double = if (value.isNaN()) 0.0 else floor(value + 0.5)

    private fun round2(value: Double): Double = roundHalfUp(value * 100.0) / 100.0
}
