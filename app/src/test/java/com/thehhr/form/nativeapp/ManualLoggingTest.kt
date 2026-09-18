package com.thehhr.form.nativeapp

import com.thehhr.form.nativeapp.vault.VaultSnapshot
import com.thehhr.form.nativeapp.vault.VaultTrainingLog
import com.thehhr.form.nativeapp.workout.ExerciseRules
import com.thehhr.form.nativeapp.workout.WorkoutExerciseState
import com.thehhr.form.nativeapp.workout.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualLoggingTest {

    private fun exercise(id: String, category: String = "", equipment: String = "", name: String = "Press") =
        Exercise(id, name, category, "target", equipment, "", "", emptyList(), "")

    private fun strengthLog(
        id: String,
        exerciseId: String,
        date: String,
        sets: Int = 1,
        reps: Int = 10,
        weight: Double? = null,
        setWeights: List<Double> = emptyList(),
        setReps: List<Int> = emptyList()
    ) = VaultTrainingLog(
        id = id, exerciseId = exerciseId, date = date, sets = sets, reps = reps,
        weight = weight, setWeights = setWeights, setReps = setReps
    )

    private fun timedLog(
        id: String,
        exerciseId: String,
        date: String,
        intervals: Int = 1,
        durations: List<Double> = emptyList(),
        distances: List<Double> = emptyList(),
        durUnit: String = "min"
    ) = VaultTrainingLog(
        id = id, exerciseId = exerciseId, date = date, intervals = intervals,
        setDurations = durations, setDistances = distances, durUnit = durUnit
    )

    private fun draft(
        rows: List<ManualSetRow>,
        exerciseId: String = "0001",
        date: String = "2026-09-18",
        mode: String = "reps",
        unit: String = "sec",
        notes: String = ""
    ) = ManualDraft(exerciseId, date, mode, unit, rows, notes)

    @Test
    fun personalRecordPrefersPerSetWeightsThenScalarAndUsesStrictlyGreater() {
        val logs = listOf(
            strengthLog("l1", "0001", "2026-09-01", setWeights = listOf(60.0, 80.0), weight = 100.0),
            timedLog("l2", "0001", "2026-09-02", durations = listOf(10.0)),
            strengthLog("l3", "0001", "2026-09-03", setWeights = listOf(0.0), weight = 95.0),
            strengthLog("l4", "0001", "2026-09-04", weight = 90.0),
            strengthLog("l5", "0002", "2026-09-05", weight = 500.0)
        )
        val record = ManualLogging.personalRecord("0001", logs)!!
        assertEquals(95.0, record.weight, 0.0)
        assertEquals("2026-09-03", record.date)
        assertEquals("l3", record.logId)
        assertEquals("PR: 95 kg", ManualLogging.formatPR(record))
    }

    @Test
    fun personalRecordIgnoresTimedLogsAndNonPositiveWeights() {
        val logs = listOf(
            timedLog("t1", "0001", "2026-09-05", durations = listOf(30.0)),
            strengthLog("s1", "0001", "2026-09-04", setWeights = listOf(0.0, 0.0), weight = 0.0)
        )
        assertNull(ManualLogging.personalRecord("0001", logs))
    }

    @Test
    fun personalRecordKeepsFirstEntryOnEqualWeights() {
        val logs = listOf(
            strengthLog("first", "0001", "2026-09-01", weight = 80.0),
            strengthLog("second", "0001", "2026-09-05", setWeights = listOf(80.0))
        )
        assertEquals("first", ManualLogging.personalRecord("0001", logs)!!.logId)
    }

    @Test
    fun latestLogPicksNewestDateThenLatestDocumentPosition() {
        val logs = listOf(
            strengthLog("old", "0001", "2026-09-01"),
            strengthLog("newA", "0001", "2026-09-10"),
            strengthLog("newB", "0001", "2026-09-10"),
            strengthLog("other", "0002", "2026-09-12")
        )
        assertEquals("newB", ManualLogging.latestLogFor("0001", logs)!!.id)
        val history = ManualLogging.historyFor("0001", logs)
        assertEquals(listOf("newB", "newA", "old"), history.map { it.id })
        assertSame(history.first(), ManualLogging.latestLogFor("0001", logs))
        assertTrue(ManualLogging.historyFor("0003", logs).isEmpty())
    }

    @Test
    fun manualLogIdMatchesWorkoutSessionStoreScheme() {
        assertEquals(ManualLogging.manualLogId(42L, "0001"), ManualLogging.manualLogId(42L, "0001"))
        assertEquals(ExerciseRules.logId(42L, "0001"), ManualLogging.manualLogId(42L, "0001"))
        assertNotEquals(ManualLogging.manualLogId(42L, "0001"), ManualLogging.manualLogId(43L, "0001"))
        assertNotEquals(ManualLogging.manualLogId(42L, "0001"), ManualLogging.manualLogId(42L, "0002"))
        assertTrue(ManualLogging.manualLogId(42L, "0001").matches(Regex("[A-Za-z0-9_-]{1,64}")))
    }

    @Test
    fun buildLogStrengthRecordsPerSetValuesAndScalarLastPositiveWeight() {
        val dumbbell = exercise("0001", equipment = "dumbbell")
        val log = ManualLogging.buildLog(
            dumbbell,
            draft(
                listOf(
                    ManualSetRow(weight = "0", reps = "10"),
                    ManualSetRow(weight = "55.5", reps = "12"),
                    ManualSetRow(weight = "57", reps = "8")
                ),
                notes = "  felt strong  "
            ),
            "2026-09-18",
            1000L
        )
        assertEquals(ManualLogging.manualLogId(1000L, "0001"), log.id)
        assertEquals("0001", log.exerciseId)
        assertEquals("2026-09-18", log.date)
        assertEquals(3, log.sets)
        assertEquals(10, log.reps)
        assertEquals(listOf(10, 12, 8), log.setReps)
        assertEquals(listOf(0.0, 55.5, 57.0), log.setWeights)
        assertEquals(57.0, log.weight!!, 0.0)
        assertEquals("felt strong", log.notes)
        assertEquals("\"Press\"", log.exerciseLabel)
        assertTrue(!log.timed)
    }

    @Test
    fun buildLogBodyWeightOmitsWeightsAndRejectsTrackedWeight() {
        val bodyWeight = exercise("0002", equipment = "body weight")
        val log = ManualLogging.buildLog(
            bodyWeight,
            draft(listOf(ManualSetRow(weight = "0", reps = "15"), ManualSetRow(weight = "0", reps = "15")), exerciseId = "0002"),
            "2026-09-18",
            1000L
        )
        assertNull(log.weight)
        assertTrue(log.setWeights.isEmpty())
        assertEquals(2, log.sets)
        assertEquals(listOf(15, 15), log.setReps)
        val rejection = runCatching {
            ManualLogging.buildLog(
                bodyWeight,
                draft(listOf(ManualSetRow(weight = "40", reps = "15")), exerciseId = "0002"),
                "2026-09-18",
                1000L
            )
        }.exceptionOrNull()
        assertNotNull(rejection)
        assertTrue(rejection!!.message!!.contains("does not track weight"))
    }

    @Test
    fun buildLogTimedSecondsConvertExactlyToHundredthsOfMinutes() {
        val skierg = exercise("0013", category = "cardio", equipment = "skierg machine")
        val log = ManualLogging.buildLog(
            skierg,
            draft(
                listOf(
                    ManualSetRow(duration = "90", distance = "1.3"),
                    ManualSetRow(duration = "740.4", distance = "0")
                ),
                exerciseId = "0013",
                mode = "timed",
                unit = "sec"
            ),
            "2026-09-18",
            1000L
        )
        assertTrue(log.timed)
        assertEquals(2, log.intervals)
        assertEquals(listOf(1.5, 12.34), log.setDurations)
        assertEquals(listOf(1.3, 0.0), log.setDistances)
        assertEquals("sec", log.durUnit)
        assertNull(log.weight)
    }

    @Test
    fun buildLogTimedMinutesKeepsUnitAndDropsAllZeroLists() {
        val skierg = exercise("0013", category = "cardio", equipment = "skierg machine")
        val log = ManualLogging.buildLog(
            skierg,
            draft(listOf(ManualSetRow(duration = "2.5", distance = "0")), exerciseId = "0013", mode = "timed", unit = "min"),
            "2026-09-18",
            1000L
        )
        assertEquals(listOf(2.5), log.setDurations)
        assertTrue(log.setDistances.isEmpty())
        assertEquals("min", log.durUnit)
        val distanceOnly = ManualLogging.buildLog(
            skierg,
            draft(listOf(ManualSetRow(duration = "0", distance = "5")), exerciseId = "0013", mode = "timed", unit = "min"),
            "2026-09-18",
            1000L
        )
        assertTrue(distanceOnly.setDurations.isEmpty())
        assertEquals(listOf(5.0), distanceOnly.setDistances)
    }

    @Test
    fun secondsRequireExactHundredthsOfMinuteConversion() {
        assertNull(ManualLogging.durationError("0.6", "sec"))
        assertNull(ManualLogging.durationError("90", "sec"))
        assertNull(ManualLogging.durationError("740.4", "sec"))
        assertNull(ManualLogging.durationError("36000", "sec"))
        assertNotNull(ManualLogging.durationError("61", "sec"))
        assertNotNull(ManualLogging.durationError("0.5", "sec"))
        assertNotNull(ManualLogging.durationError("36060", "sec"))
        assertNotNull(ManualLogging.durationError("60.001", "sec"))
        assertNotNull(ManualLogging.durationError("abc", "sec"))
    }

    @Test
    fun minuteDurationsAcceptAtMostTwoDecimalsWithinLimit() {
        assertNull(ManualLogging.durationError("1", "min"))
        assertNull(ManualLogging.durationError("2.75", "min"))
        assertNull(ManualLogging.durationError("600", "min"))
        assertNotNull(ManualLogging.durationError("2.755", "min"))
        assertNotNull(ManualLogging.durationError("600.01", "min"))
        assertNotNull(ManualLogging.durationError("601", "min"))
    }

    @Test
    fun datesMustBeValidCalendarDatesNotInTheFuture() {
        assertNull(ManualLogging.dateError("2026-09-18", "2026-09-18"))
        assertNull(ManualLogging.dateError("2024-02-29", "2026-09-18"))
        assertNotNull(ManualLogging.dateError("2025-02-29", "2026-09-18"))
        assertNotNull(ManualLogging.dateError("2026-9-18", "2026-09-18"))
        assertNotNull(ManualLogging.dateError("2026-09-18T00:00", "2026-09-18"))
        assertNotNull(ManualLogging.dateError("2026-09-19", "2026-09-18"))
        assertNotNull(ManualLogging.dateError("", "2026-09-18"))
        val future = runCatching {
            ManualLogging.buildLog(
                exercise("0001", equipment = "dumbbell"),
                draft(listOf(ManualSetRow()), date = "2026-09-19"),
                "2026-09-18",
                1000L
            )
        }.exceptionOrNull()
        assertNotNull(future)
        assertTrue(future!!.message!!.contains("Future"))
    }

    @Test
    fun buildLogRejectsOutOfRangeFieldsAndNotes() {
        val dumbbell = exercise("0001", equipment = "dumbbell")
        fun build(rows: List<ManualSetRow>, notes: String = "") = ManualLogging.buildLog(
            dumbbell, draft(rows, notes = notes), "2026-09-18", 1000L
        )
        assertNotNull(ManualLogging.repsError("0"))
        assertNotNull(ManualLogging.repsError("101"))
        assertNotNull(ManualLogging.repsError("1.5"))
        assertNotNull(ManualLogging.weightError("2000.1"))
        assertNotNull(ManualLogging.weightError("55.505"))
        assertNull(ManualLogging.weightError("2000"))
        assertNull(ManualLogging.weightError("0"))
        assertNotNull(ManualLogging.notesError(" ".repeat(1) + "x".repeat(160) + "x"))
        assertNotNull(ManualLogging.notesError("line one\nline two"))
        assertTrue(ManualLogging.notesError("x".repeat(160)) == null)
        val notesTooLong = runCatching { build(listOf(ManualSetRow()), notes = "x".repeat(161)) }.exceptionOrNull()
        assertNotNull(notesTooLong)
        val emptyTimed = runCatching {
            ManualLogging.buildLog(
                exercise("0013", category = "cardio", equipment = "skierg machine"),
                draft(listOf(ManualSetRow(duration = "0", distance = "0")), exerciseId = "0013", mode = "timed", unit = "sec"),
                "2026-09-18",
                1000L
            )
        }.exceptionOrNull()
        assertEquals("Add a duration or a distance", emptyTimed!!.message)
    }

    @Test
    fun deletionTargetCapturesExactRecordForStaleBaseDetection() {
        val logs = listOf(
            strengthLog("keep", "0001", "2026-09-01", weight = 40.0),
            strengthLog("drop", "0001", "2026-09-02", weight = 50.0),
            strengthLog("other", "0002", "2026-09-03", weight = 60.0)
        )
        val target = ManualLogging.deletionTarget(logs, "drop")
        assertSame(logs[1], target)
        assertEquals(50.0, target.weight!!, 0.0)
        val stale = target.copy(notes = "changed elsewhere")
        assertTrue(ManualLogging.deletionTarget(logs, "drop") != stale)
        assertEquals(1, logs.filterNot { it.id == ManualLogging.deletionTarget(logs, "drop").id }.count { it.exerciseId == "0001" })
        val missing = runCatching { ManualLogging.deletionTarget(logs, "missing") }.exceptionOrNull()
        assertNotNull(missing)
        assertTrue(missing!!.message!!.contains("no longer exists"))
    }

    @Test
    fun seedStrengthUsesLatestLogSetsLastPositiveWeightAndPerSetReps() {
        val dumbbell = exercise("0001", equipment = "dumbbell")
        val history = listOf(
            strengthLog("a", "0001", "2026-09-01", sets = 3, setWeights = listOf(50.0, 60.0), setReps = listOf(5, 6, 7)),
            strengthLog("b", "0001", "2026-09-10", sets = 2, setWeights = listOf(70.0), setReps = listOf(8, 9))
        )
        val seeded = ManualLogging.seedDraft(dumbbell, history, "2026-09-18")
        assertEquals("reps", seeded.mode)
        assertEquals("2026-09-18", seeded.date)
        assertEquals(2, seeded.rows.size)
        assertTrue(seeded.rows.all { it.weight == "70" })
        assertEquals(listOf("8", "9"), seeded.rows.map { it.reps })
        val empty = ManualLogging.seedDraft(dumbbell, emptyList(), "2026-09-18")
        assertEquals(3, empty.rows.size)
        assertTrue(empty.rows.all { it.weight == "0" && it.reps == "10" })
    }

    @Test
    fun seedTimedUsesLastLoggedUnitDurationAndDistance() {
        val cardio = exercise("0013", category = "cardio", equipment = "skierg machine")
        val history = listOf(
            timedLog("t", "0013", "2026-09-10", intervals = 2, durations = listOf(1.2, 2.4), distances = listOf(3.0), durUnit = "min")
        )
        val seeded = ManualLogging.seedDraft(cardio, history, "2026-09-18")
        assertEquals("timed", seeded.mode)
        assertEquals("min", seeded.unit)
        assertEquals(2, seeded.rows.size)
        assertTrue(seeded.rows.all { it.duration == "2.4" && it.distance == "3" })
        val seconds = ManualLogging.seedDraft(
            cardio,
            listOf(timedLog("s", "0013", "2026-09-11", intervals = 1, durations = listOf(1.5), durUnit = "sec")),
            "2026-09-18"
        )
        assertEquals("sec", seconds.unit)
        assertEquals("90", seconds.rows.single().duration)
        val fresh = ManualLogging.seedDraft(cardio, emptyList(), "2026-09-18")
        assertEquals(1, fresh.rows.size)
        assertTrue(fresh.rows.all { it.duration == "1" && it.distance == "0" })
    }

    @Test
    fun addAndRemoveRowsSeedFromTheLastRowWithinLimits() {
        val base = draft(listOf(ManualSetRow(weight = "40", reps = "6")))
        val added = ManualLogging.addRow(base)!!
        assertEquals(listOf(ManualSetRow(weight = "40", reps = "6"), ManualSetRow(weight = "40", reps = "6")), added.rows)
        assertNull(ManualLogging.addRow(base.copy(rows = List(20) { ManualSetRow() })))
        assertNull(ManualLogging.removeRow(base))
        val removed = ManualLogging.removeRow(base.copy(rows = List(3) { ManualSetRow(weight = "$it") }))!!
        assertEquals(2, removed.rows.size)
        assertEquals("1", removed.rows.last().weight)
    }

    @Test
    fun withModeReseedsRowsAndKeepsDateWhileWithUnitOnlyAppliesToTimed() {
        val dumbbell = exercise("0001", equipment = "dumbbell")
        val cardio = exercise("0013", category = "cardio", equipment = "skierg machine")
        val base = ManualLogging.seedDraft(dumbbell, emptyList(), "2026-09-18").let { ManualLogging.withDate(it, "2026-09-01") }
        val timed = ManualLogging.withMode(base, cardio, emptyList(), "timed")
        assertEquals("timed", timed.mode)
        assertEquals("2026-09-01", timed.date)
        assertEquals(1, timed.rows.size)
        val units = ManualLogging.withUnit(timed, "sec")
        assertEquals("sec", units.unit)
        assertEquals(timed.rows, units.rows)
        assertNotNull(runCatching { ManualLogging.withUnit(base, "sec") }.exceptionOrNull())
        assertNotNull(runCatching { ManualLogging.withUnit(timed, "hours") }.exceptionOrNull())
    }

    @Test
    fun formatLogSummarizesTimedAndStrengthEntries() {
        assertEquals(
            "2 intervals · 3.6 min · 1.3 km",
            ManualLogging.formatLog(timedLog("t", "0013", "2026-09-01", intervals = 2, durations = listOf(1.2, 2.4), distances = listOf(1.3, 0.0)))
        )
        assertEquals(
            "2 sets × 8-10 reps × 70-80 kg",
            ManualLogging.formatLog(strengthLog("s", "0001", "2026-09-01", sets = 2, setWeights = listOf(70.0, 80.0), setReps = listOf(8, 10)))
        )
        assertEquals(
            "3 sets × 10 reps",
            ManualLogging.formatLog(strengthLog("b", "0002", "2026-09-01", sets = 3))
        )
    }

    @Test
    fun blockReasonGuardsWorkoutDraftsAndVaultStates() {
        val vault = VaultSnapshot("folder", "uri", mapOf("training_logs.md" to "# Training Log\n"), emptyList())
        val ready = FormState(vault = vault, loading = false, vaultBusy = false)
        assertNull(ManualLogging.blockReason(ready))
        val session = WorkoutSession(
            id = "00000000-0000-0000-0000-000000000000", date = "2026-09-18", startedAt = 1L,
            routineId = "r1", routineName = "Routine", exercises = listOf(WorkoutExerciseState("0001", "Press"))
        )
        assertNotNull(ManualLogging.blockReason(ready.copy(workout = session)))
        assertNotNull(ManualLogging.blockReason(ready.copy(workout = session.copy(paused = true))))
        assertNotNull(ManualLogging.blockReason(ready.copy(workout = session.copy(finishing = true))))
        assertNotNull(ManualLogging.blockReason(ready.copy(workoutLoadFailed = true)))
        assertNotNull(ManualLogging.blockReason(ready.copy(reloadRequired = true)))
        assertNotNull(ManualLogging.blockReason(ready.copy(vault = null)))
        assertNotNull(ManualLogging.blockReason(ready.copy(loading = true)))
        assertNotNull(ManualLogging.blockReason(ready.copy(vaultBusy = true)))
        assertNotNull(ManualLogging.blockReason(ready.copy(workoutBusy = true)))
    }
}
