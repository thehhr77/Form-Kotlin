package com.thehhr.form.nativeapp.vault

import org.junit.Assert.*
import org.junit.Test

class ClipboardCodecTest {
    private val codec = ClipboardCodec(setOf("0001", "0002", "0003", "00004", "c-custom"))
    private val routine = VaultRoutine("r-one", "First", items = listOf(VaultRoutineItem("0001")))
    private val meal = VaultMeal("m-one", "Rice", 150.5, 130.0, 2.75, 28.2, 0.3)

    private fun rejects(action: () -> Unit) {
        try {
            action()
            fail("Expected transfer rejection")
        } catch (_: IllegalArgumentException) {
        } catch (_: VaultConflictException) {
        }
    }

    private fun snapshot(routines: List<VaultRoutine> = emptyList(), meals: List<VaultMeal> = emptyList(), config: String = "# Config\n") =
        VaultSnapshot("Test", "vault-a", mapOf(
            VaultFiles.ROUTINES to VaultCodec.encodeRoutines(routines),
            VaultFiles.MEALS to VaultCodec.encodeMeals(meals),
            VaultFiles.CONFIG to config
        ), emptyList())

    @Test
    fun canonicalWebRoutinesRoundTripModesFlagsSupersetsAndEmptyRoutine() {
        val text = "First\n0001 3 * 10 reps weighted unweighted ss11\nc-custom 1 * 0 ss12\n0002 2 * 60 timed sec\n00004 1 * 30 timed min\n\nEmpty"
        var ids = 0
        val parsed = codec.parseRoutines(text) { "r-${++ids}" }
        assertEquals(2, ids)
        assertEquals(2, parsed.size)
        assertTrue(parsed.last().items.isEmpty())
        assertEquals(text, codec.exportRoutines(parsed))
        assertEquals(parsed, codec.parseRoutines(text) { if (ids++ == 2) "r-1" else "r-2" })
        assertEquals(text, codec.exportRoutines(parsed.map { it.copy(liked = true, secondary = true) }))
    }

    @Test
    fun supersetExportUsesFirstAppearanceLabels() {
        val parsed = codec.parseRoutines("First\n0001 3 * 10 ss421\n0002 3 * 10 ss422")
        assertEquals("First\n0001 3 * 10 ss11\n0002 3 * 10 ss12", codec.exportRoutines(parsed))
    }

    @Test
    fun canonicalMealsRoundTripWithoutLikedFlag() {
        val text = "Rice (150.5g)\nPer100g 130cal 2.75pro 28.2carb 0.3fat\nid: m-one"
        assertEquals(listOf(meal), codec.parseMeals(text))
        assertEquals(text, codec.exportMeals(listOf(meal.copy(liked = true))))
        assertEquals(listOf(meal), codec.readMeals(VaultCodec.encodeMeals(listOf(meal))))
        assertEquals(listOf(meal.copy(liked = true)), codec.readMeals(VaultCodec.encodeMeals(listOf(meal.copy(liked = true)))))
    }

    @Test
    fun missingMealIdsGeneratedOnlyDuringPreviewAndRetained() {
        var calls = 0
        val parsed = codec.parseMeals("Rice (100g)\nPer100g 0cal 0pro 0carb 0fat") { "generated-${++calls}" }
        assertEquals(1, calls)
        assertEquals("generated-1", parsed.single().id)
        assertEquals(parsed, codec.parseMeals(codec.exportMeals(parsed)))
        val preview = codec.preview(snapshot(), VaultKind.MEALS, codec.exportMeals(parsed), TransferMode.ADD)
        assertEquals(parsed, preview.meals)
        assertEquals(preview.meals, codec.mergeMeals(emptyList(), preview.meals, preview.mode))
        assertEquals(preview.meals, codec.mergeMeals(emptyList(), preview.meals, preview.mode))
    }

    @Test
    fun decimalPrecisionAndVaultRangesNeverClampOrRound() {
        val text = codec.exportMeals(listOf(meal))
        for (invalid in listOf(
            text.replace("130cal", "130.5cal"),
            text.replace("150.5g", "0g"),
            text.replace("150.5g", "5001g"),
            text.replace("2.75pro", "999.1pro"),
            text.replace("2.75pro", "0.10000000000000001pro"),
            text.replace("130cal", "9007199254740993cal"),
            text.replace("130cal", "NaNcal"),
            text.replace("2.75pro", "1e-7pro")
        )) rejects { codec.parseMeals(invalid) }
        assertEquals(0.0000001, codec.parseMeals(text.replace("2.75pro", "0.0000001pro")).single().p100, 0.0)
        rejects { codec.exportMeals(listOf(meal.copy(cals100 = 1.5))) }
        rejects { codec.exportMeals(listOf(meal.copy(defaultGrams = Double.NaN))) }
    }

    @Test
    fun unknownRoutineRowsIdsAndAmbiguousModifiersRejectWholeInput() {
        for (row in listOf(
            "9999 3 * 10", "0001 21 * 10", "0001 0 * 10", "0001 3 * 61",
            "0001 3 * 1.5", "0001 3 * 10 future", "0001 3 * 10 timed reps sec",
            "0001 3 * 10 timed sec min", "0001 3 * 10 timed", "0001 3 * 10 sec",
            "0001 3 * 10 reps reps", "0001 3 * 10 ss11", "0001 3 * 10 ss01",
            "0001 3 * 10 ss11 ss21", "0001 3 * 10\n0001 3 * 10",
            "0001 3 * 10 ss12\n0002 3 * 10 ss11",
            "0001 3 * 10 ss11\n0002 3 * 10 ss11",
            "0001 3 * 10\nunknown row", "liked", "id: original"
        )) rejects { codec.parseRoutines("First\n$row") }
        rejects { codec.parseRoutines("# Routines\n\n## First\n0001 3 * 10") }
        rejects { codec.parseRoutines("0001 3 * 10") }
    }

    @Test
    fun everyMealNeedsMacrosAndUnknownOrRepeatedRowsRejectWholeInput() {
        val text = codec.exportMeals(listOf(meal))
        for (invalid in listOf(
            "Rice (100g)", "$text\nunknown", "$text\nliked", "$text\nid: another",
            "$text\nPer100g 0cal 0pro 0carb 0fat", "# Meal Library\n$text",
            text.replace("m-one", "custom"), text.replace("m-one", "bad!id"),
            "$text\n\nIncomplete (100g)", "junk\n$text",
            text.replace("Per100g 130cal 2.75pro 28.2carb 0.3fat\nid: m-one", "id: m-one\nPer100g 130cal 2.75pro 28.2carb 0.3fat")
        )) rejects { codec.parseMeals(invalid) }
    }

    @Test
    fun collisionsRejectEvenWhenAddingExportBackToSameLibrary() {
        rejects { codec.mergeRoutines(listOf(routine), codec.parseRoutines(codec.exportRoutines(listOf(routine))), TransferMode.ADD) }
        rejects { codec.mergeMeals(listOf(meal), codec.parseMeals(codec.exportMeals(listOf(meal))), TransferMode.ADD) }
        rejects { codec.mergeRoutines(listOf(routine), listOf(routine.copy(id = "new", name = "FIRST")), TransferMode.ADD) }
        rejects { codec.mergeRoutines(listOf(routine), listOf(routine.copy(name = "Other")), TransferMode.ADD) }
        rejects { codec.mergeMeals(listOf(meal), listOf(meal.copy(id = "new", name = "RICE")), TransferMode.ADD) }
        rejects { codec.mergeMeals(listOf(meal), listOf(meal.copy(name = "Other")), TransferMode.ADD) }
        rejects { codec.parseRoutines("First\n\nFIRST") }
        rejects { codec.parseMeals(codec.exportMeals(listOf(meal)) + "\n\n" + codec.exportMeals(listOf(meal.copy(name = "Other")))) }
    }

    @Test
    fun addAppendsReplaceUsesExactIncomingAndEmptySemanticsAreExplicit() {
        val other = routine.copy(id = "r-two", name = "Other")
        assertEquals(listOf(routine, other), codec.mergeRoutines(listOf(routine), listOf(other), TransferMode.ADD))
        assertEquals(listOf(other), codec.mergeRoutines(listOf(routine), listOf(other), TransferMode.REPLACE))
        assertEquals(listOf(routine), codec.mergeRoutines(listOf(routine), emptyList(), TransferMode.ADD))
        assertEquals(emptyList<VaultRoutine>(), codec.mergeRoutines(listOf(routine), emptyList(), TransferMode.REPLACE))
        assertEquals(listOf(meal), codec.mergeMeals(emptyList(), listOf(meal), TransferMode.ADD))
        assertEquals(listOf(meal), codec.mergeMeals(listOf(meal), listOf(meal), TransferMode.REPLACE))
        assertEquals(emptyList<VaultMeal>(), codec.mergeMeals(listOf(meal), emptyList(), TransferMode.REPLACE))
        assertEquals("", codec.exportRoutines(emptyList()))
        assertEquals("", codec.exportMeals(emptyList()))
        assertEquals(emptyList<VaultRoutine>(), codec.readRoutines("# Routines\n"))
        assertEquals(emptyList<VaultMeal>(), codec.readMeals("# Meal Library\n"))
        rejects { codec.parseRoutines(" \n") }
        rejects { codec.parseMeals("") }
        rejects { codec.preview(snapshot(listOf(routine)), VaultKind.ROUTINES, "", TransferMode.REPLACE) }
    }

    @Test
    fun missingLossyOrAmbiguousVaultTargetsAreRefused() {
        val routineSource = VaultCodec.encodeRoutines(listOf(routine))
        val mealSource = VaultCodec.encodeMeals(listOf(meal))
        for (source in listOf("", routineSource + "future: content\n", routineSource.replace("3 * 10", "3 * 10 timed reps sec"),
            routineSource.replace("- id: r-one", "- id: r-one\n- id: r-two"), routineSource.replace("- id: r-one\n", ""))) {
            rejects { codec.readRoutines(source) }
        }
        for (source in listOf("", mealSource.replace("130cal", "130.5cal"), mealSource.replace("- id: m-one\n", ""),
            mealSource.replace("Per100g 130cal 2.75pro 28.2carb 0.3fat\n", ""), mealSource + "future: content\n")) {
            rejects { codec.readMeals(source) }
        }
        rejects { codec.preview(snapshot().copy(files = emptyMap()), VaultKind.MEALS, codec.exportMeals(listOf(meal)), TransferMode.ADD) }
        assertEquals(listOf(routine), codec.readRoutines(routineSource))
        assertEquals(listOf(routine.copy(liked = true, secondary = true)), codec.readRoutines(VaultCodec.encodeRoutines(listOf(routine.copy(liked = true, secondary = true)))))
    }

    @Test
    fun staleBaseRejectsDiskChangesSnapshotChangesVaultSwitchAndMissingFile() {
        ClipboardCodec.requireBase("a", "base", "a", "base", "base")
        rejects { ClipboardCodec.requireBase("a", "base", "a", "base", "changed") }
        rejects { ClipboardCodec.requireBase("a", "base", "a", "changed", "base") }
        rejects { ClipboardCodec.requireBase("a", "base", "b", "base", "base") }
        rejects { ClipboardCodec.requireBase("a", "base", "a", null, "base") }
        rejects { ClipboardCodec.requireBase("a", "base", "a", "desired", "desired") }
    }

    @Test
    fun previewCapturesStableModelsOriginalFileAndMode() {
        val snapshot = snapshot(listOf(routine))
        val preview = codec.preview(snapshot, VaultKind.ROUTINES, "Other\n0002 2 * 20", TransferMode.ADD)
        val ids = preview.routines.map { it.id }
        assertEquals(snapshot.uri, preview.uri)
        assertEquals(snapshot.file(VaultFiles.ROUTINES), preview.original)
        assertEquals(snapshot.file(VaultFiles.CONFIG), preview.configOriginal)
        assertEquals(1, preview.beforeCount)
        assertEquals(listOf("Other"), preview.names)
        assertEquals(ids, codec.mergeRoutines(emptyList(), preview.routines, TransferMode.ADD).map { it.id })
        val reloaded = snapshot.copy(files = snapshot.files + (VaultFiles.ROUTINES to "# Routines\n"))
        assertEquals(snapshot.file(VaultFiles.ROUTINES), preview.original)
        rejects { ClipboardCodec.requireBase(preview.uri, preview.original, reloaded.uri, reloaded.file(VaultFiles.ROUTINES), reloaded.file(VaultFiles.ROUTINES)!!) }
    }

    @Test
    fun scheduledRoutineMustSurviveUnchangedAndAmbiguousSchedulesReject() {
        val base = listOf(routine)
        val other = routine.copy(id = "r-two", name = "Other")
        for (reference in listOf(routine.id, routine.name)) {
            val config = "# Config\n\n## Weekly Schedule\nmon: $reference\n"
            ClipboardCodec.checkSchedule(config, base, base + other)
            ClipboardCodec.checkSchedule(config, base, base)
            rejects { ClipboardCodec.checkSchedule(config, base, listOf(other)) }
            rejects { ClipboardCodec.checkSchedule(config, base, listOf(routine.copy(items = emptyList()))) }
            rejects { ClipboardCodec.checkSchedule(config, base, listOf(routine.copy(liked = true))) }
            rejects { codec.preview(snapshot(base, config = config), VaultKind.ROUTINES, codec.exportRoutines(base), TransferMode.REPLACE) }
        }
        for (rows in listOf("mon: Missing", "mon: First\nmon: First", "Mon: First", "mon: First\n\n## Weekly Schedule\ntue: First", "monday: First", "mon: First\n- ## Other\ntue: Missing")) {
            rejects { ClipboardCodec.checkSchedule("# Config\n\n## Weekly Schedule\n$rows\n", base, base) }
        }
        rejects { ClipboardCodec.checkSchedule("# Config\n\n## Weekly Schedule\nmon: First\n", base + other.copy(id = "First"), base + other.copy(id = "First")) }
        ClipboardCodec.checkSchedule("# Config\n\n## Weekly Schedule\nmon: \n", base, emptyList())
    }

    @Test
    fun transferLimitAndControlCharactersAreRejected() {
        rejects { codec.parseRoutines("a".repeat(ClipboardCodec.MAX_TEXT_BYTES + 1)) }
        rejects { codec.parseMeals("Rice\u0000 (100g)\nPer100g 0cal 0pro 0carb 0fat") }
        rejects { codec.parseRoutines("Bad\u2028Name") }
        rejects { codec.parseRoutines("a".repeat(41)) }
        assertEquals(codec.parseRoutines("First\r\n0001 3 * 10") { "id" }, codec.parseRoutines("First\n0001 3 * 10") { "id" })
    }
}
