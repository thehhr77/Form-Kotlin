package com.thehhr.form.nativeapp

import com.thehhr.form.nativeapp.vault.VaultTrainingLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class StatsMathTest {

    private fun strengthLog(
        id: String,
        date: String,
        exerciseId: String = "0001",
        sets: Int = 3,
        reps: Int = 10,
        weight: Double? = 50.0,
        setWeights: List<Double> = emptyList(),
        setReps: List<Int> = emptyList()
    ) = VaultTrainingLog(id = id, exerciseId = exerciseId, date = date, sets = sets, reps = reps, weight = weight, setWeights = setWeights, setReps = setReps)

    private fun timedLog(
        id: String,
        date: String,
        exerciseId: String = "0100",
        intervals: Int? = 4,
        durations: List<Double> = emptyList(),
        distances: List<Double> = emptyList()
    ) = VaultTrainingLog(id = id, exerciseId = exerciseId, date = date, intervals = intervals, setDurations = durations, setDistances = distances)

    @Test fun logSetsCountCountsStrengthSetsAndTimedIntervals() {
        assertEquals(3L, StatsMath.logSetsCount(strengthLog("a", "2024-01-08", setWeights = listOf(40.0, 50.0, 60.0), setReps = listOf(8, 8, 8))))
        assertEquals(4L, StatsMath.logSetsCount(strengthLog("b", "2024-01-08", sets = 4)))
        assertEquals(4L, StatsMath.logSetsCount(timedLog("c", "2024-01-08", intervals = 4)))
        assertEquals(2L, StatsMath.logSetsCount(timedLog("d", "2024-01-08", intervals = null, durations = listOf(10.0, 20.0))))
        assertEquals(0L, StatsMath.logSetsCount(timedLog("e", "2024-01-08", intervals = 0)))
    }

    @Test fun headlineCountsDistinctSessionsSetsAndExercises() {
        val logs = listOf(
            strengthLog("1", "2024-01-08", exerciseId = "0001", setWeights = listOf(40.0, 50.0), setReps = listOf(8, 8)),
            strengthLog("2", "2024-01-08", exerciseId = "0002", sets = 2),
            timedLog("3", "2024-01-10", exerciseId = "0001", intervals = 5),
            strengthLog("4", "not-a-date", exerciseId = "0003", sets = 1)
        )
        val headline = StatsMath.headline(logs)
        assertEquals(2L, headline.sessions)
        assertEquals(2L + 2L + 5L + 1L, headline.sets)
        assertEquals(3L, headline.exercises)
        assertEquals(0L, StatsMath.headline(emptyList()).sessions)
        assertEquals(0L, StatsMath.headline(emptyList()).exercises)
    }

    @Test fun parseLogDateMirrorsCanonicalIsoValidation() {
        assertEquals(LocalDate.of(2024, 2, 29), StatsMath.parseLogDate("2024-02-29"))
        assertNull(StatsMath.parseLogDate("2023-02-29"))
        assertNull(StatsMath.parseLogDate("2024-1-08"))
        assertNull(StatsMath.parseLogDate("0000-01-01"))
        assertNull(StatsMath.parseLogDate("2024-01-08T00:00"))
    }

    @Test fun logVolumeFollowsWebStrengthFormulaAndExcludesTimed() {
        assertEquals(40.0 * 8 + 50.0 * 8, StatsMath.logVolume(strengthLog("a", "2024-01-08", setWeights = listOf(40.0, 50.0), setReps = listOf(8, 8))), 1e-9)
        assertEquals(40.0 * 8 + 50.0 * 8, StatsMath.logVolume(strengthLog("b", "2024-01-08", setWeights = listOf(40.0, 50.0, 60.0), setReps = listOf(8, 8))), 1e-9)
        assertEquals((40.0 + 50.0) * 10, StatsMath.logVolume(strengthLog("c", "2024-01-08", sets = 2, reps = 10, weight = 5.0, setWeights = listOf(40.0, 50.0))), 1e-9)
        assertEquals(3.0 * 10 * 50.0, StatsMath.logVolume(strengthLog("d", "2024-01-08", sets = 3, reps = 10, weight = 50.0)), 1e-9)
        assertEquals(0.0, StatsMath.logVolume(strengthLog("e", "2024-01-08", sets = 3, reps = 10, weight = null)), 1e-9)
        assertEquals(0.0, StatsMath.logVolume(timedLog("f", "2024-01-08", intervals = 4, durations = listOf(20.0, 25.0))), 1e-9)
    }

    private val absExercise = Exercise(
        "0001", "3/4 sit-up", "waist", "abs", "body weight", "", "", emptyList(), "",
        muscleGroup = "hip flexors", secondaryMuscles = listOf("hip flexors", "lower back")
    )

    @Test fun muscleContributionsWeightSecondariesAtHalfAndExcludeCustomExercises() {
        val logs = listOf(
            strengthLog("1", "2024-01-08", exerciseId = "0001", setWeights = listOf(1.0, 1.0, 1.0), setReps = listOf(1, 1, 1)),
            strengthLog("2", "2024-01-09", exerciseId = "0001", setWeights = listOf(1.0, 1.0, 1.0), setReps = listOf(1, 1, 1)),
            strengthLog("3", "2024-01-09", exerciseId = "c-custom", sets = 9)
        )
        val exercisesById = mapOf(
            "0001" to absExercise,
            "c-custom" to Exercise("c-custom", "Custom press", "chest", "pectorals", "barbell", "", "", emptyList(), "")
        )
        val totals = StatsMath.muscleContributions(logs, exercisesById)
        assertEquals(listOf("abs" to 6.0, "quadriceps" to 3.0, "lower-back" to 3.0), totals.map { it.region to it.sets })
    }

    @Test fun muscleContributionsUseMuscleGroupFallbackWhenTargetIsUnmapped() {
        val logs = listOf(strengthLog("1", "2024-01-08", exerciseId = "0300", sets = 6, setWeights = listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0), setReps = listOf(1, 1, 1, 1, 1, 1)))
        val cardio = Exercise("0300", "Run", "cardio", "cardiovascular system", "body weight", "", "", emptyList(), "", muscleGroup = "quadriceps", secondaryMuscles = listOf("hamstrings"))
        val totals = StatsMath.muscleContributions(logs, mapOf("0300" to cardio))
        assertEquals(listOf("quadriceps" to 6.0, "hamstring" to 3.0), totals.map { it.region to it.sets })
    }

    @Test fun muscleContributionsSkipUnmappedMusclesAndZeroSetLogs() {
        val logs = listOf(strengthLog("1", "2024-01-08", exerciseId = "0001", sets = 0, weight = null))
        val totals = StatsMath.muscleContributions(logs, mapOf("0001" to absExercise))
        assertTrue(totals.isEmpty())
    }

    @Test fun categoryTotalsRankBodyPartsBySetsWithDistinctExercises() {
        val logs = listOf(
            strengthLog("1", "2024-01-08", exerciseId = "0001", setWeights = listOf(1.0, 1.0), setReps = listOf(1, 1)),
            strengthLog("2", "2024-01-08", exerciseId = "0002", sets = 2),
            strengthLog("3", "2024-01-09", exerciseId = "0003", sets = 1),
            strengthLog("4", "2024-01-09", exerciseId = "unknown-id", sets = 5)
        )
        val exercisesById = mapOf(
            "0001" to absExercise.copy(id = "0001"),
            "0002" to absExercise.copy(id = "0002"),
            "0003" to absExercise.copy(id = "0003", category = "")
        )
        val totals = StatsMath.categoryTotals(logs, exercisesById)
        assertEquals(listOf("Other" to 6L, "waist" to 4L), totals.map { it.category to it.sets })
        assertEquals(2L, totals.first { it.category == "waist" }.exercises)
        assertEquals(2L, totals.first { it.category == "Other" }.exercises)
    }

    @Test fun trendRoundingAndLabelsMatchWebSemantics() {
        assertEquals("Building trend", StatsMath.trend(listOf(100.0, 100.0, 100.0, 100.0)).label)
        assertEquals("New activity", StatsMath.trend(listOf(0.0, 0.0, 0.0, 0.0, 10.0)).label)
        fun volumes(firstFour: Double, lastFour: Double) = List(8) { if (it < 4) firstFour / 4 else lastFour / 4 }
        val up = StatsMath.trend(volumes(400.0, 402.0))
        assertEquals(1L, up.change)
        assertEquals("Last 4 workouts: ↑ 1%", up.label)
        val down = StatsMath.trend(volumes(400.0, 388.0))
        assertEquals(-3L, down.change)
        assertEquals("Last 4 workouts: ↓ 3%", down.label)
        assertEquals("Last 4 workouts: No change", StatsMath.trend(volumes(400.0, 401.0)).label)
        assertEquals("Last 4 workouts: No change", StatsMath.trend(volumes(400.0, 398.0)).label)
        assertEquals(1L, StatsMath.jsRound(0.5))
        assertEquals(0L, StatsMath.jsRound(-0.5))
        assertEquals(0L, StatsMath.jsRound(0.4))
        assertEquals(-2L, StatsMath.jsRound(-2.5))
    }

    @Test fun movingAveragesUseFourPointWindow() {
        assertEquals(listOf(10.0, 15.0, 20.0, 25.0, 35.0), StatsMath.movingAverages(listOf(10.0, 20.0, 30.0, 40.0, 50.0)))
        assertEquals(emptyList<Double>(), StatsMath.movingAverages(emptyList()))
        assertEquals(listOf(7.0), StatsMath.movingAverages(listOf(7.0)))
    }

    @Test fun allTimeBucketsBucketPerLoggedDateWithSetsVolumeAndExercises() {
        val logs = listOf(
            strengthLog("1", "2024-01-08", exerciseId = "0001", setWeights = listOf(40.0), setReps = listOf(10)),
            strengthLog("2", "2024-01-08", exerciseId = "0002", sets = 2, reps = 10, weight = 20.0),
            timedLog("3", "2024-01-09", exerciseId = "0100", intervals = 3),
            strengthLog("4", "2024-01-03", exerciseId = "0001", sets = 1, reps = 5, weight = 100.0)
        )
        val buckets = StatsMath.allTimeBuckets(logs)
        assertEquals(listOf("2024-01-03", "2024-01-08", "2024-01-09"), buckets.map { it.date.toString() })
        assertEquals(500.0, buckets[0].volume, 1e-9)
        assertEquals(800.0, buckets[1].volume, 1e-9)
        assertEquals(0.0, buckets[2].volume, 1e-9)
        assertEquals(3L, buckets[2].sets)
        assertEquals(2L, buckets[1].exercises)
        assertTrue(StatsMath.allTimeBuckets(listOf(strengthLog("x", "bad"))).isEmpty())
    }

    @Test fun weekStartOfRespectsSundayMondayAndSaturdayPreferences() {
        val wednesday = LocalDate.of(2024, 1, 10)
        assertEquals(LocalDate.of(2024, 1, 8), StatsMath.weekStartOf(wednesday, 1))
        assertEquals(LocalDate.of(2024, 1, 7), StatsMath.weekStartOf(wednesday, 0))
        assertEquals(LocalDate.of(2024, 1, 6), StatsMath.weekStartOf(wednesday, 6))
    }

    @Test fun weekBucketsBucketSetsAndEntriesByDayWithinWeekOnly() {
        val logs = listOf(
            strengthLog("1", "2024-01-08", exerciseId = "0001", setWeights = listOf(1.0, 1.0, 1.0), setReps = listOf(1, 1, 1)),
            strengthLog("2", "2024-01-10", exerciseId = "0001", setWeights = listOf(1.0, 1.0), setReps = listOf(1, 1)),
            timedLog("3", "2024-01-10", exerciseId = "0100", intervals = 5),
            strengthLog("4", "2024-01-14", exerciseId = "0002", sets = 1),
            strengthLog("5", "2024-01-15", exerciseId = "0002", sets = 7)
        )
        val buckets = StatsMath.weekBuckets(logs, LocalDate.of(2024, 1, 8))
        assertEquals(7, buckets.size)
        assertEquals(3L, buckets[0].sets)
        assertEquals(1L, buckets[0].entries)
        assertEquals(7L, buckets[2].sets)
        assertEquals(2L, buckets[2].entries)
        assertEquals(2L, buckets[2].exercises)
        assertEquals(1L, buckets[6].sets)
        assertEquals(0L, buckets[1].sets)
        assertEquals(0L, buckets[1].entries)
    }

    @Test fun adjacentLoggedWeekStartSkipsEmptyWeeksInBothDirections() {
        val onlyThisWeek = listOf(LocalDate.of(2024, 1, 10))
        assertNull(StatsMath.adjacentLoggedWeekStart(LocalDate.of(2024, 1, 10), 1, onlyThisWeek, -1))
        assertNull(StatsMath.adjacentLoggedWeekStart(LocalDate.of(2024, 1, 10), 1, onlyThisWeek, 1))
        val sparse = listOf(LocalDate.of(2023, 12, 27), LocalDate.of(2024, 1, 10))
        assertEquals(LocalDate.of(2023, 12, 25), StatsMath.adjacentLoggedWeekStart(LocalDate.of(2024, 1, 10), 1, sparse, -1))
        assertNull(StatsMath.adjacentLoggedWeekStart(LocalDate.of(2024, 1, 10), 1, sparse, 1))
        val after = listOf(LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 24))
        assertEquals(LocalDate.of(2024, 1, 22), StatsMath.adjacentLoggedWeekStart(LocalDate.of(2024, 1, 10), 1, after, 1))
        assertEquals(LocalDate.of(2024, 1, 8), StatsMath.adjacentLoggedWeekStart(LocalDate.of(2024, 1, 26), 1, after, -1))
        assertNull(StatsMath.adjacentLoggedWeekStart(LocalDate.of(2024, 1, 10), 1, emptyList(), -1))
    }

    @Test fun adjacentLoggedMonthSkipsEmptyMonthsInBothDirections() {
        val sparse = listOf(LocalDate.of(2024, 1, 10), LocalDate.of(2024, 3, 5))
        assertEquals(YearMonth.of(2024, 3), StatsMath.adjacentLoggedMonth(LocalDate.of(2024, 1, 20), sparse, 1))
        assertNull(StatsMath.adjacentLoggedMonth(LocalDate.of(2024, 1, 20), sparse, -1))
        assertEquals(YearMonth.of(2024, 1), StatsMath.adjacentLoggedMonth(LocalDate.of(2024, 3, 20), sparse, -1))
        assertNull(StatsMath.adjacentLoggedMonth(LocalDate.of(2024, 3, 20), sparse, 1))
        assertNull(StatsMath.adjacentLoggedMonth(LocalDate.of(2024, 1, 20), emptyList(), 1))
    }

    @Test fun monthSetsAndLeadingBlanksRespectWeekStartPreference() {
        val logs = listOf(
            strengthLog("1", "2024-01-08", setWeights = listOf(1.0, 1.0), setReps = listOf(1, 1)),
            strengthLog("2", "2024-01-08", sets = 2),
            timedLog("3", "2024-01-31", intervals = 2),
            strengthLog("4", "2024-02-01", sets = 9)
        )
        val january = StatsMath.monthSets(logs, YearMonth.of(2024, 1))
        assertEquals(2, january.size)
        assertEquals(4L, january[LocalDate.of(2024, 1, 8)])
        assertEquals(2L, january[LocalDate.of(2024, 1, 31)])
        val february = StatsMath.monthSets(logs, YearMonth.of(2024, 2))
        assertEquals(1, february.size)
        assertEquals(9L, february[LocalDate.of(2024, 2, 1)])
        assertEquals(0, StatsMath.monthLeadingBlanks(YearMonth.of(2024, 1), 1))
        assertEquals(1, StatsMath.monthLeadingBlanks(YearMonth.of(2024, 1), 0))
        assertEquals(2, StatsMath.monthLeadingBlanks(YearMonth.of(2024, 1), 6))
    }

    @Test fun barHeightPercentMirrorsWebFloorAndMinimums() {
        assertEquals(3, StatsMath.barHeightPercent(0, 10))
        assertEquals(10, StatsMath.barHeightPercent(1, 100))
        assertEquals(50, StatsMath.barHeightPercent(5, 10))
        assertEquals(100, StatsMath.barHeightPercent(10, 10))
        assertEquals(200, StatsMath.barHeightPercent(20, 10))
    }
}
