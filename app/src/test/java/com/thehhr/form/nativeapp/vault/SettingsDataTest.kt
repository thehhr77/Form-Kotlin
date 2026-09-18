package com.thehhr.form.nativeapp.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDataTest {
    @Test fun failedSecondWritePreservesRecoveryAndRejectsRetry() = kotlinx.coroutines.runBlocking {
        val originals = linkedMapOf(VaultFiles.MEALS to "# Meal Library\n", VaultFiles.ROUTINES to "# Routines\n")
        val outputs = linkedMapOf(VaultFiles.MEALS to "# Meal Library\n\n", VaultFiles.ROUTINES to "# Routines\n\n")
        val plan = VaultBatchPlan("test", "clear-test", originals, outputs, emptyList())
        val files = originals.toMutableMap()
        var writes = 0
        val store = object : VaultStore {
            override suspend fun read(fileName: String) = VaultReadResult(files[fileName], null)
            override suspend fun write(fileName: String, content: String) {
                writes++
                if (writes == 2) { files[fileName] = "partial"; error("injected second write failure") }
                files[fileName] = content
            }
            override suspend fun list() = emptyList<VaultEntryInfo>()
            override suspend fun displayName() = "test"
        }
        var backup = emptyMap<String, String>()
        val statuses = mutableListOf<String>()
        val journal = object : VaultBatchJournal {
            override val location = "fake recovery"
            override suspend fun prepare(plan: VaultBatchPlan) { backup = plan.expected.toMap() }
            override suspend fun mark(status: String) { statuses += status }
        }
        var invalidated = false
        val failure = runCatching { VaultBatchExecutor.execute(store, plan, journal, {}, { invalidated = true }) }.exceptionOrNull()
        assertTrue(failure is VaultReloadRequiredException)
        assertTrue(invalidated)
        assertEquals(originals, backup)
        assertEquals(outputs[VaultFiles.MEALS], files[VaultFiles.MEALS])
        assertEquals("partial", files[VaultFiles.ROUTINES])
        assertEquals("partial-or-unverified", statuses.last())
        files.putAll(originals)
        assertTrue(runCatching { VaultBatchExecutor.execute(store, plan, journal, {}, {}) }.isFailure)
        assertEquals(2, writes)
    }

    @Test fun customSectionPatchRetainsFemaleProfileAndUnknownBytes() {
        val prefix = "# Config\r\n\r\n## Profile\r\nsex: f\r\n\r\n## Future\r\nopaque: untouched\r\n\r\n"
        val source = prefix + "## Custom Exercises\r\n"
        val record = VaultCustomExercise("c-123", "Cable Row", "back", "upper back", "cable")
        val patched = ConfigCodec.replaceCustomExercises(source, listOf(record))
        assertTrue(patched.startsWith(prefix))
        assertEquals(listOf(record), ConfigCodec.customExerciseRecords(patched))
    }

    @Test fun logRoundTripThroughClipboard() {
        val codec = SettingsClipboardCodec(setOf("00001"))
        val log = VaultTrainingLog("1000000000", "00001", "2026-09-18", sets = 3, reps = 8, setWeights = listOf(60.0, 61.5, 62.0), setReps = listOf(8, 7, 6), notes = "felt strong", exerciseLabel = "\"Squat\"")
        assertEquals(listOf(log), codec.parseLogs(codec.exportLogs(listOf(log))))
        val timed = VaultTrainingLog("1000000001", "00001", "2026-09-18", intervals = 2, setDurations = listOf(0.25, 1.0), setDistances = listOf(1.2, 2.0), durUnit = "min", exerciseLabel = "\"Row\"")
        assertEquals(listOf(timed), codec.parseLogs(codec.exportLogs(listOf(timed))))
    }

    @Test fun lossyValuesAreExplicitErrors() {
        val codec = SettingsClipboardCodec(setOf("00001"))
        val base = "exercise: \"Squat\"\nexerciseId: 00001\ndate: 2026-09-18\nsets: 2\nreps: 10\nweight(kg): 10.05\nid: 1"
        assertTrue(runCatching { codec.parseLogs(base) }.isFailure)
        val sec = "exercise: \"Row\"\nexerciseId: 00001\ndate: 2026-09-18\nint: 1\ndur(sec): 61\nid: 1"
        val secFailure = runCatching { codec.parseLogs(sec) }.exceptionOrNull()
        assertTrue(secFailure != null && secFailure.message.orEmpty().contains("hundredths of a minute"))
        val missingInt = "exercise: \"Row\"\nexerciseId: 00001\ndate: 2026-09-18\nint: 1\ndur(min): 1\n\nexercise: \"Row\"\nexerciseId: 00001\ndate: 2026-09-18\ndist(km): 2\nid: 2"
        val intFailure = runCatching { codec.parseLogs(missingInt) }.exceptionOrNull()
        assertTrue(intFailure != null && intFailure.message.orEmpty().contains("int:"))
        val dup = "exercise: \"Squat\"\nexerciseId: 00001\ndate: 2026-09-18\nsets: 1\nreps: 10\nid: 1\n\nexercise: \"Squat\"\nexerciseId: 00001\ndate: 2026-09-18\nsets: 1\nreps: 10\nid: 1"
        assertTrue(runCatching { codec.parseLogs(dup) }.isFailure)
    }

    @Test fun zeroValuesRoundTripLikeAppWrittenLogs() {
        val codec = SettingsClipboardCodec(setOf("00001"))
        val strength = VaultTrainingLog("1", "00001", "2026-09-18", sets = 2, reps = 10, setWeights = listOf(60.0, 0.0), setReps = listOf(10, 8), exerciseLabel = "\"Squat\"")
        assertEquals(listOf(strength), codec.parseLogs(codec.exportLogs(listOf(strength)), requireCatalog = false))
        val parsed = codec.parseLogs("exercise: \"Squat\"\nexerciseId: 00001\ndate: 2026-09-18\nsets: 2\nreps: 10, 8\nweight(kg): 60, 0\nid: 1")
        assertEquals(listOf(strength), parsed)
        val timed = VaultTrainingLog("2", "00001", "2026-09-18", intervals = 2, setDurations = listOf(1.0, 0.0), setDistances = listOf(2.0, 0.0), durUnit = "min", exerciseLabel = "\"Row\"")
        assertEquals(listOf(timed), codec.parseLogs(codec.exportLogs(listOf(timed)), requireCatalog = false))
        val mixed = codec.parseLogs("exercise: \"Row\"\nexerciseId: 00001\ndate: 2026-09-18\nint: 2\ndur(min): 1, 0\ndist(km): 2, 0\nid: 2")
        assertEquals(listOf(timed), mixed)
    }

    @Test fun noOpPlanCompletesAndCannotRerun() = kotlinx.coroutines.runBlocking {
        val plan = VaultBatchPlan("test", "noop-test", linkedMapOf(VaultFiles.MEALS to "# Meal Library\n"), linkedMapOf(), emptyList())
        var writes = 0
        val store = object : VaultStore {
            override suspend fun read(fileName: String) = VaultReadResult("# Meal Library\n", null)
            override suspend fun write(fileName: String, content: String) { writes++ }
            override suspend fun list() = emptyList<VaultEntryInfo>()
            override suspend fun displayName() = "test"
        }
        val statuses = mutableListOf<String>()
        val journal = object : VaultBatchJournal {
            override val location = "fake recovery"
            override suspend fun prepare(plan: VaultBatchPlan) { statuses += "prepared" }
            override suspend fun mark(status: String) { statuses += status }
        }
        VaultBatchExecutor.execute(store, plan, journal, {}, {})
        assertEquals(listOf("prepared", "complete"), statuses)
        assertEquals(0, writes)
        val rerun = runCatching { VaultBatchExecutor.execute(store, plan, journal, {}, {}) }
        assertTrue(rerun.isFailure)
    }

    @Test fun diaryExportParsesBackIdentically() {
        val codec = SettingsClipboardCodec(emptySet())
        val diary = linkedMapOf("2026-09-18" to VaultDiaryDay(500.0, listOf(VaultDiaryMeal("100", "Eggs", 140.0, 12.0, 1.0, 10.0, "Breakfast"))))
        assertEquals(diary, codec.parseDiary(codec.exportDiary(diary)))
    }

    @Test fun customRoundTripAndValidation() {
        val codec = SettingsClipboardCodec(setOf("00001"), setOf("cable"))
        val records = listOf(VaultCustomExercise("c-100", "Cable Row", "back", "upper back", "cable", "Pull to chest"))
        val text = codec.exportCustoms(records)
        assertEquals(records, codec.parseCustoms(text))
        assertTrue(runCatching { codec.validateCustoms(listOf(records[0].copy(id = "zzz"))) }.isFailure)
        assertTrue(runCatching { codec.validateCustoms(listOf(records[0].copy(category = "unknown"))) }.isFailure)
        assertTrue(runCatching { codec.validateCustoms(listOf(records[0].copy(equipment = "unobtainium"))) }.isFailure)
    }

    @Test fun customReplaceCleansRoutinesLogsAndLikes() {
        val customs = listOf(VaultCustomExercise("c-100", "Cable Row", "back", "upper back", "cable"))
        val replacement = listOf(VaultCustomExercise("c-200", "Lat Pulldown", "back", "upper back", "cable"))
        val config = "# Config\n\n## Preferences\nliked: c-100, 00001\n\n## Custom Exercises\n- name: Cable Row\n  id: c-100\n  category: back\n  target: upper back\n  equipment: cable\n"
        val routines = VaultCodec.encodeRoutines(listOf(VaultRoutine("r-1", "Day A", items = listOf(VaultRoutineItem("c-100", 3, 10), VaultRoutineItem("00001", 3, 10)))))
        val logs = VaultCodec.encodeTrainingLogs(listOf(VaultTrainingLog("1", "c-100", "2026-09-18", sets = 1, reps = 10, exerciseLabel = "\"X\"")))
        val snapshot = VaultSnapshot("Test", "test://vault", linkedMapOf(VaultFiles.CONFIG to config, VaultFiles.ROUTINES to routines, VaultFiles.TRAINING_LOGS to logs), emptyList())
        val preview = SettingsDataPlanner(setOf("00001"), setOf("cable")).preview(snapshot, VaultKind.CONFIG, SettingsClipboardCodec(emptySet(), setOf("cable")).exportCustoms(replacement), TransferMode.REPLACE)
        assertEquals(VaultCodec.encodeRoutines(listOf(VaultRoutine("r-1", "Day A", items = listOf(VaultRoutineItem("00001", 3, 10))))), preview.batch?.desired?.get(VaultFiles.ROUTINES))
        assertEquals("# Training Log\n", preview.batch?.desired?.get(VaultFiles.TRAINING_LOGS))
        val patched = requireNotNull(preview.batch?.desired?.get(VaultFiles.CONFIG))
        assertTrue(ConfigCodec.checkedLikes(patched).single() == "00001")
        assertEquals(3, preview.batch?.desired?.size)
    }

    @Test fun clearPlansPreserveRetainedData() {
        val config = "# Config\n\n## Profile\nage: 30\nsex: f\n\n## Preferences\nliked: 00001\n\n## Exercise Tags\n- id: 00001\n  tags: home\n"
        val routines = VaultCodec.encodeRoutines(listOf(VaultRoutine("r-1", "Day A", items = listOf(VaultRoutineItem("00001", 3, 10)))))
        val files = linkedMapOf(
            VaultFiles.CONFIG to config,
            VaultFiles.ROUTINES to routines,
            VaultFiles.MEALS to VaultCodec.encodeMeals(listOf(VaultMeal("m1", "Rice"))),
            VaultFiles.TRAINING_LOGS to "# Training Log\n",
            VaultFiles.NUTRITION_DIARY to "# Nutrition Diary\n"
        )
        val snapshot = VaultSnapshot("Test", "test://vault", files, emptyList())
        val plan = SettingsDataPlanner(setOf("00001")).clear(snapshot, setOf(ClearCategory.LOGS, ClearCategory.CUSTOMS, ClearCategory.TAGS)).batch
        val desired = requireNotNull(plan?.desired)
        assertTrue(!desired.containsKey(VaultFiles.MEALS) && !desired.containsKey(VaultFiles.NUTRITION_DIARY))
        assertEquals("liked: 00001", requireNotNull(desired[VaultFiles.CONFIG]).lines().single { it.startsWith("liked: ") })
        assertTrue(!desired.containsKey(VaultFiles.ROUTINES))
        assertEquals(emptyMap<String, List<String>>(), VaultCodec.parseConfig(requireNotNull(desired[VaultFiles.CONFIG])).value.exerciseTags)
        assertTrue(!requireNotNull(desired[VaultFiles.CONFIG]).contains("tags: home"))
    }
}
