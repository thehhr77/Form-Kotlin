package com.thehhr.form.nativeapp.workout

import com.thehhr.form.nativeapp.Exercise
import com.thehhr.form.nativeapp.vault.VaultRoutine
import com.thehhr.form.nativeapp.vault.VaultRoutineItem
import com.thehhr.form.nativeapp.vault.VaultTrainingLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseRulesTest {

    private fun exercise(id: String, category: String = "", equipment: String = "", name: String = id) =
        Exercise(id, name, category, "target", equipment, "", "", emptyList(), "")

    private fun item(
        exerciseId: String,
        sets: Int = 3,
        reps: Int = 10,
        mode: String? = null,
        unit: String? = null,
        weighted: Boolean = false,
        unweighted: Boolean = false,
        superset: String? = null
    ) = VaultRoutineItem(exerciseId, sets, reps, mode, unit, weighted, unweighted, superset)

    private fun strengthLog(
        exerciseId: String,
        date: String,
        setWeights: List<Double> = emptyList(),
        weight: Double? = null,
        setReps: List<Int> = emptyList()
    ) = VaultTrainingLog(
        id = "s-$exerciseId-$date-${setWeights.hashCode()}",
        exerciseId = exerciseId, date = date, sets = maxOf(1, setWeights.size),
        weight = weight, setWeights = setWeights, setReps = setReps
    )

    private fun timedLog(
        exerciseId: String,
        date: String,
        durations: List<Double> = emptyList(),
        distances: List<Double> = emptyList()
    ) = VaultTrainingLog(
        id = "t-$exerciseId-$date-${durations.hashCode()}-${distances.hashCode()}",
        exerciseId = exerciseId, date = date, intervals = maxOf(1, durations.size),
        setDurations = durations, setDistances = distances, durUnit = "min"
    )

    private fun row(
        weight: Double = 0.0,
        reps: Int = 10,
        durationMin: Double = 0.0,
        distanceKm: Double = 0.0,
        done: Boolean = false
    ) = WorkoutSetRow(weight, reps, durationMin, distanceKm, done)

    private fun state(
        exerciseId: String,
        sets: List<WorkoutSetRow>,
        mode: String = "reps",
        unit: String = "sec",
        weighted: Boolean = false,
        skipped: Boolean = false
    ) = WorkoutExerciseState(
        exerciseId = exerciseId, exerciseName = exerciseId, sets = sets,
        mode = mode, unit = unit, weighted = weighted, skipped = skipped
    )

    private fun session(exercises: List<WorkoutExerciseState>, startedAt: Long = 1000L, routineId: String = "r1") =
        WorkoutSession(
            id = "00000000-0000-0000-0000-000000000000", date = "2026-09-18",
            startedAt = startedAt, routineId = routineId, routineName = "Routine", exercises = exercises
        )

    @Test
    fun strengthDefaultsToRepsAndWeightForEquipment() {
        val dumbbell = exercise("0001", equipment = "dumbbell")
        assertFalse(ExerciseRules.isTimedCardioExercise(dumbbell))
        assertEquals("reps", ExerciseRules.resolvedMode(null, dumbbell))
        assertEquals("sec", ExerciseRules.resolvedUnit(null, dumbbell))
        assertTrue(ExerciseRules.resolvedWeighted(item("0001"), dumbbell))
    }

    @Test
    fun bodyWeightEquipmentHasNoWeight() {
        val bodyWeight = exercise("0002", equipment = "body weight")
        assertFalse(ExerciseRules.exerciseHasWeight(bodyWeight))
        assertFalse(ExerciseRules.resolvedWeighted(item("0002"), bodyWeight))
        assertTrue(ExerciseRules.resolvedWeighted(item("0002", weighted = true), bodyWeight))
        assertTrue(ExerciseRules.resolvedWeighted(item("0002", weighted = true, unweighted = true), bodyWeight) == false)
        assertFalse(ExerciseRules.resolvedWeighted(item("0002", weighted = true, unweighted = true), bodyWeight))
    }

    @Test
    fun bodyWeightCardioIsUnweightedRepsLikeWeb() {
        val cardio = exercise("0003", category = "cardio", equipment = "body weight")
        assertFalse(ExerciseRules.isTimedCardioExercise(cardio))
        assertEquals("reps", ExerciseRules.resolvedMode(null, cardio))
        assertEquals("sec", ExerciseRules.resolvedUnit(null, cardio))
        assertFalse(ExerciseRules.resolvedWeighted(item("0003"), cardio))
    }

    @Test
    fun machineCardioIsTimedWithMinutes() {
        val skierg = exercise("0013", category = "cardio", equipment = "skierg machine")
        assertTrue(ExerciseRules.isTimedCardioExercise(skierg))
        assertEquals("timed", ExerciseRules.resolvedMode(null, skierg))
        assertEquals("min", ExerciseRules.resolvedUnit(null, skierg))
        assertFalse(ExerciseRules.resolvedWeighted(item("0013"), skierg))
        assertEquals("sec", ExerciseRules.resolvedUnit("sec", skierg))
    }

    @Test
    fun weightedCardioEquipmentIsStrengthWithWeight() {
        val sled = exercise("0004", category = "cardio", equipment = "sled machine")
        assertFalse(ExerciseRules.isTimedCardioExercise(sled))
        assertEquals("reps", ExerciseRules.resolvedMode(null, sled))
        assertTrue(ExerciseRules.resolvedWeighted(item("0004"), sled))
        val kettlebell = exercise("0005", category = "cardio", equipment = "kettlebell")
        assertFalse(ExerciseRules.isTimedCardioExercise(kettlebell))
        assertTrue(ExerciseRules.resolvedWeighted(item("0005"), kettlebell))
    }

    @Test
    fun bandCardioIsUnweightedStrength() {
        val band = exercise("0006", category = "cardio", equipment = "band")
        assertFalse(ExerciseRules.isTimedCardioExercise(band))
        assertEquals("reps", ExerciseRules.resolvedMode(null, band))
        assertFalse(ExerciseRules.exerciseHasWeight(band))
        assertFalse(ExerciseRules.resolvedWeighted(item("0006"), band))
    }

    @Test
    fun explicitTimedModeHasNoWeight() {
        val dumbbell = exercise("0007", equipment = "dumbbell")
        assertEquals("timed", ExerciseRules.resolvedMode("timed", dumbbell))
        assertFalse(ExerciseRules.resolvedWeighted(item("0007", mode = "timed"), dumbbell))
        assertTrue(ExerciseRules.resolvedWeighted(item("0007", mode = "reps"), dumbbell))
    }

    @Test
    fun unweightedFlagBeatsWeightedFlag() {
        val dumbbell = exercise("0008", equipment = "dumbbell")
        assertFalse(ExerciseRules.resolvedWeighted(item("0008", weighted = true, unweighted = true), dumbbell))
        assertTrue(ExerciseRules.resolvedWeighted(item("0008", weighted = true), dumbbell))
    }

    @Test
    fun explicitUnitsAndModesAreCaseSensitiveLikeWeb() {
        val cardio = exercise("0009", category = "cardio", equipment = "body weight")
        assertEquals("sec", ExerciseRules.resolvedUnit("sec", cardio))
        assertEquals("reps", ExerciseRules.resolvedMode("reps", cardio))
        val dumbbell = exercise("0010", equipment = "dumbbell")
        assertEquals("reps", ExerciseRules.resolvedMode("TIMED", dumbbell))
        assertTrue(ExerciseRules.resolvedWeighted(item("0010", mode = "TIMED"), dumbbell))
    }

    @Test
    fun equipmentNormalizationTrimsAndLowercasesExceptBodyWeightCardioCheck() {
        val padded = exercise("0011", equipment = "  Dumbbell ")
        assertTrue(ExerciseRules.exerciseHasWeight(padded))
        val capitalizedBodyWeight = exercise("0012", category = "cardio", equipment = "Body Weight")
        assertFalse(ExerciseRules.exerciseHasWeight(capitalizedBodyWeight))
        assertTrue(ExerciseRules.isTimedCardioExercise(capitalizedBodyWeight))
    }

    @Test
    fun strengthSeedingUsesPrescribedRepsOnEveryRow() {
        val history = listOf(
            strengthLog("0001", "2026-09-01", setWeights = listOf(50.0, 60.0), setReps = listOf(5, 6, 7))
        )
        val rows = ExerciseRules.seedRows(item("0001", sets = 4, reps = 12), exercise("0001", equipment = "dumbbell"), history)
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.reps == 12 })
        assertTrue(rows.all { !it.done })
    }

    @Test
    fun strengthSeedingUsesLastPositiveWeightOfLatestWeightTrackingLogRoundedToDecimal() {
        val dumbbell = exercise("0001", equipment = "dumbbell")
        val history = listOf(
            strengthLog("0001", "2026-09-10", setWeights = listOf(70.0, 72.46)),
            strengthLog("0001", "2026-09-01", setWeights = listOf(90.0))
        )
        val rows = ExerciseRules.seedRows(item("0001", sets = 2, reps = 10), dumbbell, history)
        assertTrue(rows.all { it.weight == 72.5 })
        val tie = listOf(
            strengthLog("0001", "2026-09-10", setWeights = listOf(10.0)),
            strengthLog("0001", "2026-09-10", setWeights = listOf(80.0))
        )
        val tieRows = ExerciseRules.seedRows(item("0001", sets = 1, reps = 10), dumbbell, tie)
        assertEquals(80.0, tieRows.single().weight, 0.0)
        val scalar = listOf(strengthLog("0001", "2026-09-15", weight = 55.5))
        assertEquals(55.5, ExerciseRules.seedRows(item("0001", sets = 1, reps = 10), dumbbell, scalar).single().weight, 0.0)
    }

    @Test
    fun strengthSeedingIgnoresTimedLogsAndUntrackedWeights() {
        val dumbbell = exercise("0001", equipment = "dumbbell")
        val history = listOf(
            timedLog("0001", "2026-09-12", durations = listOf(10.0)),
            strengthLog("0001", "2026-09-11", setWeights = listOf(0.0, 0.0))
        )
        val rows = ExerciseRules.seedRows(item("0001", sets = 2, reps = 8), dumbbell, history)
        assertTrue(rows.all { it.weight == 0.0 })
        assertTrue(rows.all { it.reps == 8 })
    }

    @Test
    fun strengthSeedingOfUnweightedItemIgnoresHistory() {
        val history = listOf(strengthLog("0002", "2026-09-01", setWeights = listOf(100.0)))
        val rows = ExerciseRules.seedRows(item("0002", sets = 3, reps = 15, unweighted = true), exercise("0002", equipment = "dumbbell"), history)
        assertTrue(rows.all { it.weight == 0.0 && it.reps == 15 })
    }

    @Test
    fun timedSeedingRepeatsLastDurationWithWholeMinuteRoundingForSeconds() {
        val cardio = exercise("0003", category = "cardio", equipment = "skierg machine")
        val history = listOf(timedLog("0003", "2026-09-01", durations = listOf(1.2, 2.4)))
        val rows = ExerciseRules.seedRows(item("0003", sets = 3, reps = 45, unit = "sec"), cardio, history)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.durationMin == 120.0 })
        assertTrue(rows.all { it.distanceKm == 0.0 && !it.done })
    }

    @Test
    fun timedSeedingClampsWholeMinutesForMinuteUnit() {
        val cardio = exercise("0003", category = "cardio", equipment = "skierg machine")
        val history = listOf(timedLog("0003", "2026-09-01", durations = listOf(2.5, 2.6)))
        val rows = ExerciseRules.seedRows(item("0003", sets = 2, reps = 45), cardio, history)
        assertTrue(rows.all { it.durationMin == 3.0 })
    }

    @Test
    fun timedSeedingFallsBackToPrescribedRepsWithoutDurations() {
        val cardio = exercise("0003", category = "cardio", equipment = "skierg machine")
        val rows = ExerciseRules.seedRows(item("0003", sets = 2, reps = 45), cardio, emptyList())
        assertTrue(rows.all { it.durationMin == 45.0 })
        val withEmptyLog = listOf(timedLog("0003", "2026-09-01", distances = listOf(3.0)))
        val rows2 = ExerciseRules.seedRows(item("0003", sets = 2, reps = 30), cardio, withEmptyLog)
        assertTrue(rows2.all { it.durationMin == 30.0 && it.distanceKm == 3.0 })
    }

    @Test
    fun timedSeedingRepeatsLastDistanceRoundedToDecimal() {
        val cardio = exercise("0003", category = "cardio", equipment = "skierg machine")
        val history = listOf(timedLog("0003", "2026-09-01", durations = listOf(10.0), distances = listOf(4.56, 3.27)))
        val rows = ExerciseRules.seedRows(item("0003", sets = 2, reps = 10), cardio, history)
        assertTrue(rows.all { it.distanceKm == 3.3 })
    }

    @Test
    fun addedRowCopiesTemplateAndHonorsLimits() {
        val timed = state("0001", listOf(row(durationMin = 90.0, distanceKm = 1.5, done = true), row(durationMin = 60.0, distanceKm = 2.0)), mode = "timed")
        val added = ExerciseRules.addedRow(timed.sets, timed)
        assertNotNull(added)
        assertEquals(60.0, added!!.durationMin, 0.0)
        assertEquals(2.0, added.distanceKm, 0.0)
        assertFalse(added.done)
        val capped = state("0001", List(20) { row() })
        assertNull(ExerciseRules.addedRow(capped.sets, capped))
        val freshTimed = state("0002", emptyList(), mode = "timed", unit = "sec")
        val fallback = ExerciseRules.addedRow(freshTimed.sets, freshTimed.copy(prescribedReps = 0))
        assertEquals(10.0, fallback!!.durationMin, 0.0)
        val freshMinute = state("0002", emptyList(), mode = "timed", unit = "min")
        val minuteFallback = ExerciseRules.addedRow(freshMinute.sets, freshMinute.copy(prescribedReps = 0))
        assertEquals(1.0, minuteFallback!!.durationMin, 0.0)
        val freshStrength = state("0003", emptyList())
        val strengthFallback = ExerciseRules.addedRow(freshStrength.sets, freshStrength.copy(prescribedReps = 14))
        assertEquals(14, strengthFallback!!.reps)
        assertEquals(0.0, strengthFallback.weight, 0.0)
        val template = state("0003", listOf(row(weight = 80.0, reps = 6)))
        val copied = ExerciseRules.addedRow(template.sets, template)
        assertEquals(80.0, copied!!.weight, 0.0)
        assertEquals(6, copied.reps)
    }

    @Test
    fun logForExerciseUpsertsStrengthRecordsFromCheckedRows() {
        val session = session(listOf(state("0001", listOf(row(weight = 0.0, reps = 10, done = true), row(weight = 55.5, reps = 12, done = true), row(weight = 0.0, reps = 8)))))
        val log = ExerciseRules.logForExercise(session, session.exercises.first())
        assertNotNull(log)
        assertEquals(ExerciseRules.logId(1000L, "0001"), log!!.id)
        assertEquals("0001", log.exerciseId)
        assertEquals("2026-09-18", log.date)
        assertEquals(2, log.sets)
        assertEquals(10, log.reps)
        assertEquals(listOf(10, 12), log.setReps)
        assertEquals(listOf(0.0, 55.5), log.setWeights)
        assertEquals(55.5, log.weight!!, 0.0)
        assertEquals("\"0001\"", log.exerciseLabel)
    }

    @Test
    fun logForExerciseUpsertsTimedRecordsInMinutesWithInvertedUnitLabel() {
        val secSession = session(listOf(state("0003", listOf(row(durationMin = 90.0, done = true), row(durationMin = 30.0, done = true)), mode = "timed", unit = "sec")))
        val secLog = ExerciseRules.logForExercise(secSession, secSession.exercises.first())
        assertNotNull(secLog)
        assertEquals(listOf(1.5, 0.5), secLog!!.setDurations)
        assertEquals("min", secLog.durUnit)
        assertEquals(2, secLog.intervals)
        assertNull(secLog.weight)
        val minSession = session(listOf(state("0004", listOf(row(durationMin = 5.0, distanceKm = 1.25, done = true)), mode = "timed", unit = "min")))
        val minLog = ExerciseRules.logForExercise(minSession, minSession.exercises.first())
        assertEquals(listOf(5.0), minLog!!.setDurations)
        assertEquals(listOf(1.3), minLog.setDistances)
        assertEquals("sec", minLog.durUnit)
    }

    @Test
    fun logForExerciseReturnsNullForEmptyOrValuelessCheckedRows() {
        val unchecked = session(listOf(state("0001", listOf(row(weight = 50.0, done = false)))))
        assertNull(ExerciseRules.logForExercise(unchecked, unchecked.exercises.first()))
        val zeroed = session(listOf(state("0003", listOf(row(durationMin = 0.0, distanceKm = 0.0, done = true)), mode = "timed", unit = "sec")))
        assertNull(ExerciseRules.logForExercise(zeroed, zeroed.exercises.first()))
    }

    @Test
    fun logIdIsDeterministicPerSessionAndExercise() {
        assertEquals(ExerciseRules.logId(5L, "0001"), ExerciseRules.logId(5L, "0001"))
        assertTrue(ExerciseRules.logId(5L, "0001") != ExerciseRules.logId(6L, "0001"))
        assertTrue(ExerciseRules.logId(5L, "0001") != ExerciseRules.logId(5L, "0002"))
    }

    private fun toggleSession(): WorkoutSession {
        val a = state("0001", listOf(row(weight = 40.0, reps = 8, done = true)))
        val b = state("0002", listOf(row(weight = 50.0, reps = 9)))
        return session(listOf(a, b))
    }

    private fun catalog(): Map<String, Exercise> = listOf(
        exercise("0001", equipment = "dumbbell", name = "Press"),
        exercise("0002", equipment = "body weight", name = "Dip"),
        exercise("0003", equipment = "barbell", name = "Row"),
        exercise("0004", equipment = "body weight", name = "Plank"),
        exercise("0005", equipment = "dumbbell", name = "Curl")
    ).associateBy { it.id }

    private fun routines(): List<VaultRoutine> = listOf(
        VaultRoutine("r1", "Primary", items = listOf(item("0001", reps = 8), item("0002", reps = 9))),
        VaultRoutine("s1", "Arms", secondary = true, items = listOf(item("0003", sets = 2, reps = 12), item("0004", sets = 2, reps = 30))),
        VaultRoutine("s2", "Core", secondary = true, items = listOf(item("0005", sets = 1, reps = 15), item("0004", sets = 1, reps = 20)))
    )

    @Test
    fun secondaryToggleAppendsInOrderAndDedupesAgainstExisting() {
        val updated = ExerciseRules.withSecondaryToggled(toggleSession(), "s1", routines(), catalog(), emptyList())
        assertNotNull(updated)
        assertEquals(listOf("s1"), updated!!.secondaryIds)
        assertEquals(listOf("0001", "0002", "0003", "0004"), updated.exercises.map { it.exerciseId })
        assertEquals(40.0, updated.exercises.first().sets.first().weight, 0.0)
        val core = updated.exercises.first { it.exerciseId == "0003" }
        assertEquals(2, core.sets.size)
        assertEquals(12, core.sets.first().reps)
    }

    @Test
    fun secondaryToggleNeverResetsExistingRowsAndKeepsCurrentIndex() {
        val base = toggleSession()
        val updated = ExerciseRules.withSecondaryToggled(base, "s1", routines(), catalog(), emptyList())!!
        assertEquals(0, updated.currentIndex)
        val reweighed = updated.copy(exercises = updated.exercises.mapIndexed { index, existing ->
            when (index) {
                1 -> existing.copy(sets = listOf(row(weight = 77.0, reps = 5, done = true)))
                2 -> existing.copy(sets = listOf(row(weight = 88.0, reps = 4, done = true)))
                else -> existing
            }
        })
        val toggledOff = ExerciseRules.withSecondaryToggled(reweighed, "s1", routines(), catalog(), emptyList())
        assertNotNull(toggledOff)
        assertEquals(listOf("0001", "0002"), toggledOff!!.exercises.map { it.exerciseId })
        assertEquals(77.0, toggledOff.exercises[1].sets.single().weight, 0.0)
        assertEquals(setOf("0003", "0004"), toggledOff.dormant.keys)
        assertEquals(88.0, toggledOff.dormant.getValue("0003").sets.single().weight, 0.0)
        val toggledBack = ExerciseRules.withSecondaryToggled(toggledOff, "s1", routines(), catalog(), emptyList())
        assertEquals(listOf("0001", "0002", "0003", "0004"), toggledBack!!.exercises.map { it.exerciseId })
        assertEquals(77.0, toggledBack.exercises[1].sets.single().weight, 0.0)
        assertEquals(88.0, toggledBack.exercises[2].sets.single().weight, 0.0)
        assertTrue(toggledBack.dormant.isEmpty())
        assertEquals(0, toggledBack.currentIndex)
    }

    @Test
    fun secondaryToggleOrdersMultipleSecondariesAndDedupesSharedExercises() {
        val withS1 = ExerciseRules.withSecondaryToggled(toggleSession(), "s1", routines(), catalog(), emptyList())!!
        val withBoth = ExerciseRules.withSecondaryToggled(withS1, "s2", routines(), catalog(), emptyList())
        assertEquals(listOf("s1", "s2"), withBoth!!.secondaryIds)
        assertEquals(listOf("0001", "0002", "0003", "0004", "0005"), withBoth.exercises.map { it.exerciseId })
        val first0004 = withBoth.exercises.first { it.exerciseId == "0004" }
        assertEquals(30, first0004.sets.first().reps)
    }

    @Test
    fun secondaryToggleRejectsPrimaryAndMissingRoutines() {
        assertNull(ExerciseRules.withSecondaryToggled(toggleSession(), "r1", routines(), catalog(), emptyList()))
        assertNull(ExerciseRules.withSecondaryToggled(toggleSession(), "missing", routines(), catalog(), emptyList()))
        val empty = routines().first { it.id == "s1" }.copy(items = emptyList())
        assertNull(ExerciseRules.withSecondaryToggled(toggleSession(), "s1", listOf(routines()[0], empty, routines()[2]), catalog(), emptyList()))
    }

    @Test
    fun secondaryToggleSkipsExercisesMissingFromCatalog() {
        val partialCatalog = catalog().filterKeys { it != "0004" }
        val updated = ExerciseRules.withSecondaryToggled(toggleSession(), "s1", routines(), partialCatalog, emptyList())
        assertEquals(listOf("0001", "0002", "0003"), updated!!.exercises.map { it.exerciseId })
    }

    @Test
    fun migrateV1ConvertsSecondUnitDurationsToSeconds() {
        val legacy = session(listOf(
            state("0003", listOf(row(durationMin = 2.5), row(durationMin = 10.0)), mode = "timed", unit = "sec"),
            state("0004", listOf(row(durationMin = 5.0)), mode = "timed", unit = "min"),
            state("0001", listOf(row(weight = 60.0, reps = 8)))
        ))
        val migrated = ExerciseRules.migrateV1(legacy)
        assertEquals(150.0, migrated.exercises[0].sets[0].durationMin, 0.0)
        assertEquals(600.0, migrated.exercises[0].sets[1].durationMin, 0.0)
        assertEquals(5.0, migrated.exercises[1].sets[0].durationMin, 0.0)
        assertEquals(10, migrated.exercises[2].prescribedReps)
        assertTrue(migrated.secondaryIds.isEmpty())
        assertTrue(migrated.dormant.isEmpty())
    }

    @Test
    fun completionCountsUseAllRowsCompleteAndSkipSkipped() {
        val exercises = listOf(
            state("0001", listOf(row(done = true), row(done = true))),
            state("0002", listOf(row(done = true)), skipped = true),
            state("0003", listOf(row(done = true), row())),
            state("0004", listOf(row(weight = 50.0, done = false)))
        )
        val testSession = session(exercises)
        assertEquals(3, ExerciseRules.completedSetCount(testSession))
        assertEquals(1, ExerciseRules.completedExerciseCount(testSession))
        assertEquals(2, ExerciseRules.firstIncompleteIndex(testSession))
        val allDone = session(exercises.map { existing -> existing.copy(sets = existing.sets.map { it.copy(done = true) }) })
        assertEquals(-1, ExerciseRules.firstIncompleteIndex(allDone))
        assertEquals(3, ExerciseRules.completedExerciseCount(allDone))
    }
}
