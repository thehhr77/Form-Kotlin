package com.thehhr.form.nativeapp.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RoutineMealCodecTest {
    private val codec = RoutineMealCodec(setOf("0001", "0002", "0003", "00004", "c-my_exercise-01"))

    private fun clean(document: RoutineMealCodec.Document<*>) {
        assertTrue(document.diagnostics.joinToString(), document.canRewrite)
    }

    private fun rejects(action: () -> Unit) {
        try {
            action()
            fail("Expected serialization refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun routinesRoundTripWithAllFlagsModesUnitsAndStringIds() {
        val source = """
            # Routines

            ## First
            - id: 00007
            - liked
            - secondary
            0001 3 * 10 reps weighted unweighted ss11
            c-my_exercise-01 4 * 0 reps ss12
            0002 2 * 60 timed sec
            00004 1 * 30 timed min

            ## Second
            - id: custom-routine_01
            0003 1 * 0
        """.trimIndent() + "\n"
        val document = codec.parseRoutinesMd(source)
        clean(document)
        val routine = document.value.first()
        assertEquals("00007", routine.id)
        assertTrue(routine.liked)
        assertTrue(routine.secondary)
        assertEquals("0001", routine.items[0].exerciseId)
        assertEquals("c-my_exercise-01", routine.items[1].exerciseId)
        assertEquals("00004", routine.items[3].exerciseId)
        assertEquals(RoutineMealCodec.Mode.REPS, routine.items[0].mode)
        assertTrue(routine.items[0].weighted)
        assertTrue(routine.items[0].unweighted)
        assertEquals("1", routine.items[0].superset)
        assertEquals("1", routine.items[1].superset)
        assertEquals(RoutineMealCodec.Unit.SEC, routine.items[2].unit)
        assertEquals(RoutineMealCodec.Unit.MIN, routine.items[3].unit)
        assertEquals(source, codec.routinesToMd(document))
        assertEquals(document.value, codec.parseRoutinesMd(codec.routinesToMd(document)).value)
    }

    @Test
    fun mealRoundTripPreservesDecimalMacrosAndCustomRecordIds() {
        val source = """
            # Meal Library

            Rice (150.5g)
            Per100g 130cal 2.75pro 28.2carb 0.3fat
            - id: 00009
            - liked

            Shake (250g)
            Per100g 80cal 8pro 5carb 2fat
            - id: c-shake_01
        """.trimIndent() + "\n"
        val document = codec.parseMealsMd(source)
        clean(document)
        assertEquals("00009", document.value[0].id)
        assertEquals(150.5, document.value[0].defaultGrams, 0.0)
        assertEquals(2.75, document.value[0].p100, 0.0)
        assertTrue(document.value[0].liked)
        assertFalse(document.value[1].liked)
        assertEquals(source, codec.mealsToMd(document))
        assertEquals(document.value, codec.parseMealsMd(codec.mealsToMd(document)).value)
    }

    @Test
    fun missingIdsAreDeterministicDistinctAndSurviveSerialization() {
        val routines = "## Same\n0001 3 * 10\n## Same\n0002 3 * 10\n"
        val first = codec.parseRoutinesMd(routines)
        val second = codec.parseRoutinesMd(routines)
        clean(first)
        assertEquals(first.value, second.value)
        assertNotEquals(first.value[0].id, first.value[1].id)
        assertEquals(first.value, codec.parseRoutinesMd(codec.routinesToMd(first)).value)
        val meals = "Same (100g)\nSame (100g)\n"
        val food = codec.parseMealsMd(meals)
        clean(food)
        assertEquals(food.value, codec.parseMealsMd(meals).value)
        assertNotEquals(food.value[0].id, food.value[1].id)
        assertEquals(food.value, codec.parseMealsMd(codec.mealsToMd(food)).value)
    }

    @Test
    fun duplicateRecordIdsAreDiagnosedAndRejectedLikeJsFirstWins() {
        val routines = codec.parseRoutinesMd("## A\n- id: 0001\n## B\n- id: 0001\n")
        assertEquals("0001", routines.value[0].id)
        assertNotEquals("0001", routines.value[1].id)
        assertTrue(routines.diagnostics.any { it.message.contains("Duplicate routine") && it.line == 4 })
        rejects { codec.routinesToMd(routines) }
        val meals = codec.parseMealsMd("A (100g)\nid: same\nB (100g)\nid: same\n")
        assertEquals("same", meals.value[0].id)
        assertNotEquals("same", meals.value[1].id)
        assertTrue(meals.diagnostics.any { it.message.contains("Duplicate meal") })
        rejects { codec.mealsToMd(meals) }
        rejects { codec.routinesToMd(listOf(RoutineMealCodec.Routine("a", "A"), RoutineMealCodec.Routine("a", "B"))) }
        rejects { codec.mealsToMd(listOf(RoutineMealCodec.Meal("a", "A"), RoutineMealCodec.Meal("a", "B"))) }
    }

    @Test
    fun duplicateExerciseIdsAreScopedToRoutine() {
        val document = codec.parseRoutinesMd("## A\n0001 3 * 10\n0001 4 * 12\n## B\n0001 2 * 5\n")
        assertEquals(1, document.value[0].items.size)
        assertEquals(1, document.value[1].items.size)
        assertEquals(3, document.diagnostics.single().line)
        rejects { codec.routinesToMd(document) }
    }

    @Test
    fun unknownRowsRetainExactSourceAndPhysicalLineNumbers() {
        val raw = "# Routines\r\n\r\n## A\r\n  future: untouched  \r\n0001 3 * 10\r\n"
        val document = codec.parseRoutinesMd(raw)
        assertEquals(raw, document.source)
        assertEquals(4, document.diagnostics.single().line)
        assertEquals("  future: untouched  ", document.diagnostics.single().raw)
        rejects { codec.routinesToMd(document) }
        val food = "# Meal Library\rRice (100g)\r  future: meal  \r"
        val meals = codec.parseMealsMd(food)
        assertEquals(food, meals.source)
        assertEquals(3, meals.diagnostics.single().line)
        assertEquals("  future: meal  ", meals.diagnostics.single().raw)
        rejects { codec.mealsToMd(meals) }
    }

    @Test
    fun flagSyntaxMatchesJsWithoutSilentlyDroppingSuffixesOrFalseRows() {
        val routine = codec.parseRoutinesMd("## A\n- LiKeD: TRUE\nsecondary: true\n0001 1 * 0\n")
        clean(routine)
        assertTrue(routine.value.single().liked)
        assertTrue(routine.value.single().secondary)
        val meal = codec.parseMealsMd("A (100g)\nLIKED: true\n")
        clean(meal)
        assertTrue(meal.value.single().liked)
        val suffix = codec.parseRoutinesMd("## A\nliked: trueFuture\nsecondary: false\n")
        assertTrue(suffix.value.single().liked)
        assertFalse(suffix.value.single().secondary)
        assertEquals(2, suffix.diagnostics.size)
        rejects { codec.routinesToMd(suffix) }
    }

    @Test
    fun supersetExportRenumbersGroupsInFirstAppearanceOrder() {
        val parsed = codec.parseRoutinesMd("## A\n0001 3 * 10 ss421\n0002 3 * 10 ss422\n")
        clean(parsed)
        assertEquals("42", parsed.value.single().items.first().superset)
        val output = codec.routinesToMd(parsed)
        assertTrue(output.contains("0001 3 * 10 ss11\n0002 3 * 10 ss12"))
        val renamed = parsed.value.single().copy(items = parsed.value.single().items.map { it.copy(superset = "opaque-token") })
        assertEquals(output, codec.routinesToMd(listOf(renamed)))
        assertEquals("1", codec.parseRoutinesMd(output).value.single().items.first().superset)
    }

    @Test
    fun invalidSupersetSizesFollowJsSanitizationWithDiagnostics() {
        for (rows in listOf("0001 3 * 10 ss11", "0001 3 * 10 ss11\n0002 3 * 10 ss12\n0003 3 * 10 ss13")) {
            val document = codec.parseRoutinesMd("## A\n$rows\n")
            assertTrue(document.value.single().items.all { it.superset == null })
            assertFalse(document.canRewrite)
            rejects { codec.routinesToMd(document) }
        }
    }

    @Test
    fun modifiersUseJsPrecedenceAndBoundariesWithLossDiagnostics() {
        val document = codec.parseRoutinesMd("## A\n0001 3 * 10 timed reps sec min unweighted future\n")
        val item = document.value.single().items.single()
        assertEquals(RoutineMealCodec.Mode.TIMED, item.mode)
        assertEquals(RoutineMealCodec.Unit.SEC, item.unit)
        assertFalse(item.weighted)
        assertTrue(item.unweighted)
        assertEquals(3, document.diagnostics.size)
        rejects { codec.routinesToMd(document) }
        for (suffix in listOf("timed", "reps sec", "sec", "weighted weighted")) {
            val lossy = codec.parseRoutinesMd("## A\n0001 3 * 10 $suffix\n")
            assertFalse(lossy.canRewrite)
            rejects { codec.routinesToMd(lossy) }
        }
    }

    @Test
    fun catalogValidationRequiresExactStringIdsIncludingCustomExercises() {
        val document = codec.parseRoutinesMd("## A\n0001 1 * 0\n00004 1 * 0\nc-my_exercise-01 1 * 0\n9999 1 * 0\nc-missing 1 * 0\n")
        assertEquals(listOf("0001", "00004", "c-my_exercise-01"), document.value.single().items.map { it.exerciseId })
        assertEquals(2, document.diagnostics.size)
        rejects { codec.routinesToMd(document) }
    }

    @Test
    fun numericClampsNameTruncationAndIdCleaningAreNeverSilent() {
        val longName = "x".repeat(41)
        val routines = codec.parseRoutinesMd("## $longName\nid: a.b\n0001 0 * 99\n")
        assertEquals("x".repeat(40), routines.value.single().name)
        assertEquals("ab", routines.value.single().id)
        assertEquals(1, routines.value.single().items.single().sets)
        assertEquals(60, routines.value.single().items.single().reps)
        assertEquals(4, routines.diagnostics.size)
        rejects { codec.routinesToMd(routines) }
        val meals = codec.parseMealsMd("A (0g)\nPer100g 1.5cal 1000pro 2.5carb 0fat\n")
        assertEquals(1.0, meals.value.single().defaultGrams, 0.0)
        assertEquals(2.0, meals.value.single().cals100, 0.0)
        assertEquals(999.0, meals.value.single().p100, 0.0)
        assertEquals(3, meals.diagnostics.size)
        rejects { codec.mealsToMd(meals) }
    }

    @Test
    fun commentsRepeatedFieldsAndReservedCustomMealBlockSerialization() {
        val routines = codec.parseRoutinesMd("<!-- keep -->\n## A\nid: first\nid: second\n// keep too\n")
        assertEquals("second", routines.value.single().id)
        assertEquals(3, routines.diagnostics.size)
        rejects { codec.routinesToMd(routines) }
        val meals = codec.parseMealsMd("A (100g)\nid: custom\nPer100g 1cal 0pro 0carb 0fat\nPer100g 2cal 0pro 0carb 0fat\n")
        assertEquals("custom", meals.value.single().id)
        assertEquals(2.0, meals.value.single().cals100, 0.0)
        assertEquals(2, meals.diagnostics.size)
        rejects { codec.mealsToMd(meals) }
        rejects { codec.mealsToMd(listOf(RoutineMealCodec.Meal("custom", "A"))) }
    }

    @Test
    fun headingBehaviorMatchesOriginalParsers() {
        val routines = codec.parseRoutinesMd("# Routines\n###### A\n- #0001 3 * 10\n# Weekly Schedule\nmon: A\n")
        assertEquals("A", routines.value.single().name)
        assertEquals("0001", routines.value.single().items.single().exerciseId)
        assertEquals(5, routines.diagnostics.single().line)
        val meals = codec.parseMealsMd("# Meal Library\n## Rice (100g)\nPer100g 1cal 0pro 0carb 0fat\n## Other\nliked\n")
        assertEquals("## Rice", meals.value.single().name)
        assertFalse(meals.value.single().liked)
        assertEquals(2, meals.diagnostics.size)
    }

    @Test
    fun typedSerializationRejectsUnrepresentableValues() {
        val item = RoutineMealCodec.Item("0001")
        val routine = RoutineMealCodec.Routine("r", "A", items = listOf(item))
        rejects { codec.routinesToMd(listOf(routine.copy(name = "Routines"))) }
        rejects { codec.routinesToMd(listOf(routine.copy(items = listOf(item.copy(reps = 61))))) }
        rejects { codec.routinesToMd(listOf(routine.copy(items = listOf(item.copy(mode = RoutineMealCodec.Mode.TIMED))))) }
        rejects { codec.routinesToMd(listOf(routine.copy(items = listOf(item.copy(unit = RoutineMealCodec.Unit.MIN))))) }
        rejects { codec.routinesToMd(listOf(routine.copy(items = listOf(item.copy(superset = "pair"))))) }
        rejects { codec.mealsToMd(listOf(RoutineMealCodec.Meal("m", "A", cals100 = 1.5))) }
        rejects { codec.mealsToMd(listOf(RoutineMealCodec.Meal("m", "A", p100 = Double.NaN))) }
        rejects { codec.mealsToMd(listOf(RoutineMealCodec.Meal("m", "A", defaultGrams = 0.0))) }
        rejects { codec.mealsToMd(listOf(RoutineMealCodec.Meal("m", "A\nB"))) }
    }

    @Test
    fun emptyDocumentsAndAbsentOptionalFieldsMatchJsDefaults() {
        val routines = codec.parseRoutinesMd("")
        clean(routines)
        assertEquals("# Routines\n", codec.routinesToMd(routines))
        val meals = codec.parseMealsMd("")
        clean(meals)
        assertEquals("# Meal Library\n", codec.mealsToMd(meals))
        val routine = codec.parseRoutinesMd("## A\n0001 3 * 10\n").value.single()
        assertFalse(routine.liked)
        assertFalse(routine.secondary)
        assertNull(routine.items.single().mode)
        assertNull(routine.items.single().unit)
        assertEquals(0.0, codec.parseMealsMd("A (100g)\n").value.single().cals100, 0.0)
    }
}
