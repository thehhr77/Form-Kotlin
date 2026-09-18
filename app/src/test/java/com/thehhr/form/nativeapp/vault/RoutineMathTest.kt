package com.thehhr.form.nativeapp.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineMathTest {
    private fun strengthLog(id: String, exerciseId: String, date: String, reps: Int = 8, setReps: List<Int> = emptyList()) =
        VaultTrainingLog(id, exerciseId, date, sets = 3, reps = reps, setReps = setReps, exerciseLabel = "\"X\"")

    private fun timedLog(id: String, exerciseId: String, date: String, durations: List<Double>) =
        VaultTrainingLog(id, exerciseId, date, intervals = durations.size, setDurations = durations, durUnit = "min", exerciseLabel = "\"X\"")

    @Test fun durationHistoryUsesLatestTimedLogRoundsClampsAndTreatsZeroAsMissing() {
        val logs = listOf(
            timedLog("a", "0001", "2026-09-10", listOf(30.0)),
            strengthLog("b", "0001", "2026-09-17"),
            timedLog("c", "0001", "2026-09-18", listOf(12.0, 45.679)),
            timedLog("d", "0002", "2026-09-19", listOf(5.0))
        )
        assertEquals(45.68, RoutineMath.lastLoggedDurationMinutes(logs, "0001")!!, 0.0)
        assertEquals(20.0, RoutineMath.lastLoggedDurationMinutes(listOf(timedLog("a", "0001", "2026-09-18", listOf(10.0)), timedLog("b", "0001", "2026-09-18", listOf(20.0))), "0001")!!, 0.0)
        assertEquals(600.0, RoutineMath.lastLoggedDurationMinutes(listOf(timedLog("a", "0001", "2026-09-18", listOf(1000.0))), "0001")!!, 0.0)
        assertNull(RoutineMath.lastLoggedDurationMinutes(listOf(strengthLog("b", "0001", "2026-09-17")), "0001"))
        assertNull(RoutineMath.lastLoggedDurationMinutes(listOf(timedLog("a", "0001", "2026-09-10", listOf(0.0))), "0001"))
        assertNull(RoutineMath.lastLoggedDurationMinutes(listOf(timedLog("a", "0001", "2026-09-10", emptyList())), "0001"))
        assertNull(RoutineMath.lastLoggedDurationMinutes(emptyList(), "0001"))
    }

    @Test fun repsHistoryPrefersLatestStrengthLogSetRepsAndIgnoresTimedLogs() {
        val logs = listOf(
            timedLog("a", "0001", "2026-09-19", listOf(10.0)),
            strengthLog("b", "0001", "2026-09-10", reps = 5, setReps = listOf(8, 12)),
            strengthLog("c", "0001", "2026-09-18", reps = 3)
        )
        assertEquals(3, RoutineMath.lastLoggedReps(logs, "0001"))
        assertEquals(12, RoutineMath.lastLoggedReps(listOf(logs[1]), "0001"))
        assertEquals(100, RoutineMath.lastLoggedReps(listOf(strengthLog("b", "0001", "2026-09-18", setReps = listOf(150))), "0001"))
        assertEquals(100, RoutineMath.lastLoggedReps(listOf(strengthLog("b", "0001", "2026-09-18", reps = 150)), "0001"))
        assertNull(RoutineMath.lastLoggedReps(listOf(strengthLog("b", "0001", "2026-09-18", reps = 0)), "0001"))
        assertNull(RoutineMath.lastLoggedReps(listOf(timedLog("a", "0001", "2026-09-19", listOf(10.0))), "0001"))
        assertNull(RoutineMath.lastLoggedReps(emptyList(), "0001"))
    }

    @Test fun timedPrescriptionDerivesSecondsAndMinutesFromLastLogWithDefaults() {
        val halfMinute = listOf(timedLog("a", "0001", "2026-09-18", listOf(0.5)))
        assertEquals(30, RoutineMath.timedPrescription(halfMinute, "0001", unitIsSec = true))
        assertEquals(1, RoutineMath.timedPrescription(halfMinute, "0001", unitIsSec = false))
        assertEquals(10, RoutineMath.timedPrescription(emptyList(), "0001", unitIsSec = true))
        assertEquals(1, RoutineMath.timedPrescription(emptyList(), "0001", unitIsSec = false))
        val tiny = listOf(timedLog("a", "0001", "2026-09-18", listOf(0.05)))
        assertEquals(10, RoutineMath.timedPrescription(tiny, "0001", unitIsSec = true))
        val big = listOf(timedLog("a", "0001", "2026-09-18", listOf(2.5)))
        assertEquals(150, RoutineMath.timedPrescription(big, "0001", unitIsSec = true))
        assertEquals(3, RoutineMath.timedPrescription(big, "0001", unitIsSec = false))
    }

    @Test fun strengthPrescriptionUsesHistoryOtherwiseClampsPreviousIntoEditorRange() {
        assertEquals(7, RoutineMath.strengthPrescription(listOf(strengthLog("a", "0001", "2026-09-18", reps = 7)), "0001", previous = 3))
        assertEquals(1, RoutineMath.strengthPrescription(emptyList(), "0001", previous = 0))
        assertEquals(100, RoutineMath.strengthPrescription(emptyList(), "0001", previous = 150))
        assertEquals(42, RoutineMath.strengthPrescription(emptyList(), "0001", previous = 42))
    }

    @Test fun persistenceClampMirrorsMarkdownParserCap() {
        assertEquals(0, RoutineMath.clampForPersistence(-5))
        assertEquals(0, RoutineMath.clampForPersistence(0))
        assertEquals(45, RoutineMath.clampForPersistence(45))
        assertEquals(60, RoutineMath.clampForPersistence(60))
        assertEquals(60, RoutineMath.clampForPersistence(100))
    }

    private fun item(id: String, superset: String? = null) = VaultRoutineItem(id, superset = superset)

    @Test fun supersetPairingClearsOldGroupsMovesSecondAdjacentAndLeavesOthersStandalone() {
        val items = listOf(item("0001"), item("0002"), item("0003"), item("0004"))
        val paired = RoutineMath.pairSuperset(items, "0001", "0003")
        assertEquals(listOf("0001", "0003", "0002", "0004"), paired.map { it.exerciseId })
        val token = paired.first { it.exerciseId == "0001" }.superset
        assertEquals(token, paired.first { it.exerciseId == "0003" }.superset)
        assertNull(paired.first { it.exerciseId == "0002" }.superset)
        assertNull(paired.first { it.exerciseId == "0004" }.superset)
        val moved = RoutineMath.pairSuperset(items, "0003", "0001")
        assertEquals(listOf("0002", "0003", "0001", "0004"), moved.map { it.exerciseId })
        assertEquals(moved.first { it.exerciseId == "0003" }.superset, moved.first { it.exerciseId == "0001" }.superset)
        assertEquals(items, RoutineMath.pairSuperset(items, "0001", "0001"))
        assertTrue(RoutineMath.pairSuperset(items, "missing", "0001").map { it.exerciseId }.containsAll(items.map { it.exerciseId }))
    }

    @Test fun repairingOrphansOldPartnersIntoStandaloneExercises() {
        val items = listOf(item("0001"), item("0002"), item("0003"), item("0004"))
        val paired = RoutineMath.pairSuperset(items, "0001", "0003")
        val repaired = RoutineMath.pairSuperset(paired, "0003", "0002")
        assertNull(repaired.first { it.exerciseId == "0001" }.superset)
        assertEquals(repaired.first { it.exerciseId == "0003" }.superset, repaired.first { it.exerciseId == "0002" }.superset)
        assertEquals(listOf("0001", "0003", "0002", "0004"), repaired.map { it.exerciseId })
    }

    @Test fun unlinkClearsTheWholePairWithoutReorderingOrTouchingOtherPairs() {
        val items = listOf(item("0001", "2"), item("0002", "2"), item("0003", "9"), item("0004", "9"))
        val unlinked = RoutineMath.unlinkSuperset(items, "0002")
        assertNull(unlinked.first { it.exerciseId == "0001" }.superset)
        assertNull(unlinked.first { it.exerciseId == "0002" }.superset)
        assertEquals("9", unlinked.first { it.exerciseId == "0003" }.superset)
        assertEquals("9", unlinked.first { it.exerciseId == "0004" }.superset)
        assertEquals(items.map { it.exerciseId }, unlinked.map { it.exerciseId })
        assertEquals(items, RoutineMath.unlinkSuperset(items, "0009"))
    }

    @Test fun supersetTokensAreSequentialNumericLabelsForVaultEncoding() {
        assertEquals("1", RoutineMath.nextSupersetToken(emptyList()))
        val items = listOf(item("0001", "2"), item("0002", "2"), item("0003"))
        assertEquals("3", RoutineMath.nextSupersetToken(items))
        val mixed = listOf(item("0001", "2"), item("0002", "2"), item("0003"), item("0004"))
        val two = RoutineMath.pairSuperset(mixed, "0003", "0004")
        assertEquals("2", two.first { it.exerciseId == "0001" }.superset)
        assertEquals("2", two.first { it.exerciseId == "0002" }.superset)
        assertEquals("3", two.first { it.exerciseId == "0003" }.superset)
        assertEquals("3", two.first { it.exerciseId == "0004" }.superset)
    }

    @Test fun schedulePatchPlansOnlyExactNameMatchesForRenameAndDelete() {
        val schedule = mapOf(0 to "Push", 1 to "Pull", 2 to "push", 3 to "Push", 4 to null as String?, 5 to "Legs", 6 to null as String?)
        assertEquals(mapOf(0 to "Push Day", 3 to "Push Day"), RoutineMath.schedulePatch(schedule, "Push", "Push Day"))
        assertEquals(mapOf(0 to null, 3 to null), RoutineMath.schedulePatch(schedule, "Push", null))
        assertTrue(RoutineMath.schedulePatch(schedule, "Absent", "X").isEmpty())
        val patched = RoutineMath.schedulePatch(schedule, "Push", "Push Day")
        assertEquals("Push Day", patched.getValue(0))
        assertEquals("Push", schedule.getValue(0))
        assertEquals("push", schedule.getValue(2))
    }

    @Test fun schedulePatchAppliesThroughConfigCodecPatchSchedule() {
        val config = "# Config\n## Weekly Schedule\nsun: Push\nmon: Pull\nwed: Push\n"
        val schedule = ConfigCodec.checkedSchedule(config)
        var patched = config
        RoutineMath.schedulePatch(schedule, "Push", "Push Day").forEach { (day, desired) ->
            patched = ConfigCodec.patchSchedule(patched, day, ConfigCodec.scheduleValue(patched, day), desired)
        }
        assertEquals("Push Day", ConfigCodec.scheduleValue(patched, 0))
        assertEquals("Pull", ConfigCodec.scheduleValue(patched, 1))
        assertEquals("Push Day", ConfigCodec.scheduleValue(patched, 3))
        var cleared = patched
        RoutineMath.schedulePatch(ConfigCodec.checkedSchedule(patched), "Push Day", null).forEach { (day, desired) ->
            cleared = ConfigCodec.patchSchedule(cleared, day, ConfigCodec.scheduleValue(cleared, day), desired)
        }
        assertNull(ConfigCodec.scheduleValue(cleared, 0))
        assertNull(ConfigCodec.scheduleValue(cleared, 3))
        assertEquals("Pull", ConfigCodec.scheduleValue(cleared, 1))
    }
}
