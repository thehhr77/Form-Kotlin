package com.thehhr.form.nativeapp.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultCodecTest {

    private fun requireClean(document: VaultDocument<*>) {
        if (!document.canRewrite) fail(document.diagnostics.joinToString("\n") { "line ${it.line}: ${it.message}" })
    }

    private fun requireRejected(message: String, action: () -> Unit) {
        try {
            action()
            fail("Expected VaultWriteException: $message")
        } catch (expected: VaultWriteException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(message))
        }
    }

    @Test
    fun parseDateHeadingSupportsBothFormats() {
        assertEquals("2026-09-16", VaultCodec.parseDateHeading("2026-09-16"))
        assertEquals("2026-09-16", VaultCodec.parseDateHeading("Wed, Sep 16, 2026"))
    }

    @Test
    fun routinesRoundTripWithSupersetsModesUnits() {
        val text = """
            # Routines

            ## Push day
            - id: push-a
            - liked
            - secondary
            0001 3 * 10 weighted
            0002 3 * 10 weighted ss11
            0003 3 * 10 weighted ss12
            0004 3 * 60 timed sec

            ## Pull day
            - id: pull-b
            c-legs-1 4 * 30 timed min reps
        """.trimIndent()
        val doc = VaultCodec.parseRoutines(text)
        requireClean(doc)
        assertEquals(2, doc.value.size)
        val push = doc.value.first()
        assertEquals("push-a", push.id)
        assertTrue(push.liked)
        assertTrue(push.secondary)
        assertEquals(listOf("0001", "0002", "0003", "0004"), push.items.map { it.exerciseId })
        assertTrue(push.items[0].weighted)
        assertFalse(push.items[0].unweighted)
        assertEquals("1", push.items[1].superset)
        assertEquals("1", push.items[2].superset)
        assertEquals("timed", push.items[3].mode)
        assertEquals("sec", push.items[3].unit)
        assertEquals("timed", doc.value[1].items[0].mode)
        assertEquals("min", doc.value[1].items[0].unit)
        assertEquals(text, VaultCodec.encodeRoutines(doc.value, doc))
        val rendered = VaultCodec.encodeRoutines(doc.value)
        assertTrue(rendered.contains("weighted ss11\n"))
        assertTrue(rendered.contains("weighted ss12\n"))
        val reparsed = VaultCodec.parseRoutines(rendered)
        requireClean(reparsed)
        assertEquals(doc.value, reparsed.value)
    }

    @Test
    fun routineHeadingsAreRoutineNamesAndParseClean() {
        val text = "# Routines\n\n## Legacy section\n0001 3 * 10\n"
        val doc = VaultCodec.parseRoutines(text)
        requireClean(doc)
        assertEquals("Legacy section", doc.value.single().name)
        assertEquals(text, VaultCodec.encodeRoutines(doc.value, doc))
    }

    @Test
    fun mealsRoundTripPer100Values() {
        val text = """
            # Meal Library

            Oats (80g)
            Per100g 379cal 13.5pro 60.2carb 7.1fat
            - id: oats
            - liked

            Chicken breast (150g)
            Per100g 165cal 31pro 0carb 3.6fat
            - id: chicken
        """.trimIndent()
        val doc = VaultCodec.parseMeals(text)
        requireClean(doc)
        assertEquals(2, doc.value.size)
        val oats = doc.value.first()
        assertEquals("oats", oats.id)
        assertTrue(oats.liked)
        assertEquals(80.0, oats.defaultGrams, 0.0)
        assertEquals(379.0, oats.cals100, 0.0)
        assertEquals(13.5, oats.p100, 0.0)
        assertEquals(60.2, oats.c100, 0.0)
        assertEquals(7.1, oats.f100, 0.0)
        assertEquals(text, VaultCodec.encodeMeals(doc.value, doc))
        assertEquals(165.0, doc.value[1].cals100, 0.0)
    }

    @Test
    fun trainingLogsRoundTripPerSetAndTimedValues() {
        val text = """
            # Training Log

            ## Wed, Sep 16, 2026

            - exercise: "3/4 Sit-up"
            	- exerciseId: #0001
            	- sets: 3
            	- weight(kg): 60, 62.5, 65
            	- reps: 10, 8, 6
            	- id: log-1

            - exercise: "Air bike"
            	- exerciseId: #0003
            	- int: 3
            	- dur(sec): 60, 90, 120
            	- dist(km): 0.4, 0.6, 0.8
            	- notes: steady pace
            	- id: log-2
        """.trimIndent()
        val doc = VaultCodec.parseTrainingLogs(text)
        requireClean(doc)
        assertEquals(2, doc.value.size)
        val strength = doc.value[0]
        assertEquals("0001", strength.exerciseId)
        assertEquals("2026-09-16", strength.date)
        assertEquals(3, strength.sets)
        assertEquals(listOf(60.0, 62.5, 65.0), strength.setWeights)
        assertEquals(listOf(10, 8, 6), strength.setReps)
        val timed = doc.value[1]
        assertTrue(timed.timed)
        assertEquals(3, timed.intervals)
        assertEquals(listOf(1.0, 1.5, 2.0), timed.setDurations)
        assertEquals("sec", timed.durUnit)
        assertEquals(listOf(0.4, 0.6, 0.8), timed.setDistances)
        assertEquals(text, VaultCodec.encodeTrainingLogs(doc.value, doc))
    }

    @Test
    fun trainingLogsParseIsoHeadingsAndLegacyPipes() {
        val doc = VaultCodec.parseTrainingLogs(
            "# Training Log\n\n## 2026-09-15\n\n0001 | 3 sets | 10 reps | 60 kg | id: legacy-1\n"
        )
        assertEquals(1, doc.value.size)
        assertEquals("legacy-1", doc.value[0].id)
        assertEquals("0001", doc.value[0].exerciseId)
        assertEquals("2026-09-15", doc.value[0].date)
        assertEquals(3, doc.value[0].sets)
        assertEquals(listOf(10), doc.value[0].setReps)
        assertEquals(listOf(60.0), doc.value[0].setWeights)
        assertTrue(doc.diagnostics.any { it.message.contains("Legacy pipe log") })
        assertEquals(doc.source, VaultCodec.encodeTrainingLogs(doc.value, doc))
        requireRejected("Typed replacement rejected") {
            VaultCodec.encodeTrainingLogs(doc.value.map { it.copy(notes = "Changed") }, doc)
        }
    }

    @Test
    fun diaryRoundTripWaterMealsCategories() {
        val text = """
            # Nutrition Diary

            ## Tue, Sep 15, 2026
            water: 2500 ml
            ### Breakfast
            - Oats 300 kcal · **15p · 45c · 6f**
            	- id: 1726390000000
            ### Lunch
            - Chicken rice 520 kcal · **40.5p · 60c · 10.5f**
            	- id: 1726390100000
        """.trimIndent()
        val doc = VaultCodec.parseNutritionDiary(text)
        requireClean(doc)
        assertEquals("2026-09-15", doc.value.keys.single())
        val day = doc.value.getValue("2026-09-15")
        assertEquals(2500.0, day.water, 0.0)
        assertEquals(listOf("Breakfast", "Lunch"), day.meals.map { it.category })
        assertEquals(300.0, day.meals[0].cals, 0.0)
        assertEquals(10.5, day.meals[1].f, 0.0)
        assertEquals(text, VaultCodec.encodeNutritionDiary(doc.value, doc))
    }

    @Test
    fun diaryAcceptsLegacyPipeMeals() {
        val doc = VaultCodec.parseNutritionDiary(
            "# Nutrition Diary\n\n## 2026-09-15\n\n- Oats | 300 kcal | 10 p · 45 c · 6 f | id: m1\n"
        )
        requireClean(doc)
        val meal = doc.value.getValue("2026-09-15").meals.single()
        assertEquals("Oats", meal.name)
        assertEquals("m1", meal.id)
        assertEquals(doc.value, VaultCodec.parseNutritionDiary(VaultCodec.encodeNutritionDiary(doc.value)).value)
    }

    @Test
    fun configRoundTripEverySection() {
        val text = """
            # Config

            ## Profile
            age: 30
            sex: m
            height: 172
            current-weight: 68.5
            start-weight: 75
            goal-weight: 65
            activity: 1.55
            strategy: 250
            protein-rate: 2

            ## Targets
            calories: 2200
            protein: 140

            ## Weekly Schedule
            mon: Push day
            wed: Pull day

            ## Preferences
            accent: blue
            liked: 0001, 0002
            week-start: 1
            default-view: week
            workout-reminder: true
            rest-enabled: true
            rest-between-sets: 60
            rest-between-exercises: 90
            show-secondary-pills: false
            pill-routine: default
            pill-category: default
            pill-target: default
            pill-equipment: default
            pill-tags-host: equipment
            pill-toggles: equipment

            ## Custom Exercises
            - name: Nordic curl
              id: c-0001
              category: upper legs
              target: hamstrings
              equipment: body weight
              description: Slow eccentric / partner assisted

            ## Exercise Tags
            - id: 0001
              tags: push, chest
        """.trimIndent()
        val doc = VaultCodec.parseConfig(text)
        requireClean(doc)
        assertEquals(30.0, doc.value.profile.getValue("age"), 0.0)
        assertEquals("m", doc.value.sex)
        assertEquals(140.0, doc.value.overrides.getValue("p"), 0.0)
        assertEquals("Pull day", doc.value.schedule[3])
        assertTrue(doc.value.booleanPreference("workout-reminder") == true)
        assertEquals(listOf("0001", "0002"), doc.value.liked)
        val custom = doc.value.customExercises.single()
        assertEquals("c-0001", custom.id)
        assertEquals("Nordic curl", custom.name)
        assertEquals("Slow eccentric\npartner assisted", custom.description)
        assertEquals(listOf("push", "chest"), doc.value.exerciseTags["0001"])
        assertEquals(text, VaultCodec.encodeConfig(doc.value, doc))
        val reparsed = VaultCodec.parseConfig(VaultCodec.encodeConfig(doc.value))
        requireClean(reparsed)
        assertEquals(doc.value, reparsed.value)
    }

    @Test
    fun configSexMatchesWebParserWithoutSilentlyRewritingSource() {
        for ((raw, expected) in listOf("m" to "m", "f" to "m", "Female" to "m", "other" to "f")) {
            val text = "# Config\n\n## Profile\nsex: $raw\n"
            val doc = VaultCodec.parseConfig(text)
            assertEquals(expected, doc.value.sex)
            assertEquals(text, VaultCodec.encodeConfig(doc.value, doc))
            if (raw == expected) requireClean(doc)
            else {
                assertTrue(doc.diagnostics.any { it.line == 4 && it.message.contains("Sex normalized") })
                requireRejected("Typed replacement rejected") {
                    VaultCodec.encodeConfig(doc.value.copy(profile = mapOf("age" to 30.0)), doc)
                }
            }
        }
        requireRejected("unsupported content") { VaultCodec.encodeConfig(VaultConfig(sex = "f")) }
    }

    @Test
    fun validLikedMealRendersWithoutLosingMacros() {
        val meals = listOf(VaultMeal("m1", "Oats", liked = true, cals100 = 379.0, p100 = 13.0, c100 = 60.0, f100 = 7.1))
        val text = VaultCodec.encodeMeals(meals)
        assertTrue(text.contains("Per100g 379cal 13pro 60carb 7.1fat\n"))
        assertTrue(text.contains("- liked\n"))
        val doc = VaultCodec.parseMeals(text)
        requireClean(doc)
        assertEquals(meals, doc.value)
    }

    @Test
    fun explicitSingleSetRepsSurviveFreshRendering() {
        val logs = listOf(VaultTrainingLog("log-a", "0001", "2026-09-16", sets = 3, reps = 10, setReps = listOf(10)))
        val text = VaultCodec.encodeTrainingLogs(logs)
        assertTrue(text.contains("- setreps: 10\n"))
        val doc = VaultCodec.parseTrainingLogs(text)
        requireClean(doc)
        assertEquals(logs, doc.value)
    }

    @Test
    fun invalidSupersetGroupsRejectWrites() {
        val item = VaultRoutineItem("0001", superset = "1")
        for (items in listOf(listOf(item), listOf(item, item.copy(exerciseId = "0002"), item.copy(exerciseId = "0003")))) {
            requireRejected("Superset must have exactly two members") {
                VaultCodec.encodeRoutines(listOf(VaultRoutine("r1", "Push", items = items)))
            }
        }
        requireRejected("numeric string labels") {
            VaultCodec.encodeRoutines(listOf(VaultRoutine("r1", "Push", items = listOf(item.copy(superset = "ss1")))))
        }
    }

    @Test
    fun duplicateIdentifiersRejectFreshWrites() {
        requireRejected("Duplicate meal identifier") {
            VaultCodec.encodeMeals(listOf(VaultMeal("m1", "Oats"), VaultMeal("m1", "Rice")))
        }
    }

    @Test
    fun configNormalizesInvalidPreferences() {
        val doc = VaultCodec.parseConfig(
            "# Config\n\n## Preferences\naccent: magenta\nweek-start: 3\nrest-between-sets: 10\nworkout-reminder: yes\nliked: 99999, 0001\n"
        )
        assertTrue(doc.diagnostics.isNotEmpty())
        assertEquals("red", doc.value.preferences["accent"])
        assertEquals("1", doc.value.preferences["week-start"])
        assertEquals("30", doc.value.preferences["rest-between-sets"])
        assertTrue(doc.value.booleanPreference("workout-reminder") == true)
        assertEquals(listOf("99999", "0001"), doc.value.liked)
    }

    @Test
    fun missingIdsGenerateStableDeterministicIdentifiers() {
        val first = VaultCodec.parseMeals("# Meal Library\n\nOats (100g)\nPer100g 379cal 13.5pro 60.2carb 7.1fat\n")
        val second = VaultCodec.parseMeals("# Meal Library\n\nOats (100g)\nPer100g 379cal 13.5pro 60.2carb 7.1fat\n")
        requireClean(first)
        requireClean(second)
        val id = first.value.single().id
        assertEquals(id, second.value.single().id)
        assertTrue(id.startsWith("m-"))
        val routines = VaultCodec.parseRoutines("# Routines\n\n## Push\n0001 3 * 10\n")
        assertTrue(routines.value.single().id.startsWith("r-"))
    }

    @Test
    fun unknownSectionsAreDiagnosticsNotDropped() {
        val config = VaultCodec.parseConfig("# Config\n\n## Legacy Section\nsome-key: value\n")
        assertTrue(config.diagnostics.any { it.message.contains("Legacy Section") })
        val meals = VaultCodec.parseMeals("# Meal Library\n\nOats (100g)\nPer100g 379cal 13pro 60carb 7fat\nFreeform note\n")
        assertTrue(meals.diagnostics.isNotEmpty())
        assertTrue(meals.value.size == 1)
        assertEquals(config.source, VaultCodec.encodeConfig(config.value, config))
        assertEquals(meals.source, VaultCodec.encodeMeals(meals.value, meals))
        requireRejected("Typed replacement rejected") {
            VaultCodec.encodeConfig(config.value.copy(sex = "m"), config)
        }
        requireRejected("Typed replacement rejected") {
            VaultCodec.encodeMeals(meals.value.map { it.copy(name = "Rolled oats") }, meals)
        }
    }

    @Test
    fun generatedIdentifiersSurviveTypedRewrite() {
        val doc = VaultCodec.parseMeals("# Meal Library\n\nOats (100g)\nPer100g 379cal 13pro 60carb 7fat\n")
        requireClean(doc)
        val renamed = doc.value.map { it.copy(name = "Rolled oats") }
        val text = VaultCodec.encodeMeals(renamed, doc)
        assertTrue(text.contains("- id: ${doc.value.single().id}\n"))
        val reparsed = VaultCodec.parseMeals(text)
        requireClean(reparsed)
        assertEquals(renamed, reparsed.value)
        assertEquals(doc.value.single().id, reparsed.value.single().id)
        assertEquals(doc.source, VaultCodec.encodeMeals(doc.value, doc))
    }

    @Test
    fun unsupportedValuesRejectWritesInsteadOfDropping() {
        requireRejected("Invalid date") { VaultCodec.dateHeading("2026-13-40") }
        requireRejected("Unsupported identifier") {
            VaultCodec.encodeRoutines(listOf(VaultRoutine("r1", "Push", items = listOf(VaultRoutineItem("9", 3, 10)))))
        }
        requireRejected("reserves custom") {
            VaultCodec.encodeMeals(listOf(VaultMeal("custom", "Oats")))
        }
        requireRejected("Non-finite number") {
            VaultCodec.encodeMeals(listOf(VaultMeal("m1", "Oats", p100 = Double.NaN)))
        }
        requireRejected("without normalization") {
            VaultCodec.encodeMeals(listOf(VaultMeal("m1", "Oats", cals100 = 379.5)))
        }
        requireRejected("single-line text") {
            VaultCodec.encodeMeals(listOf(VaultMeal("m1", "Oats\nInjected")))
        }
    }

    @Test
    fun encodeRendersFreshReadableTrainingDocument() {
        val logs = listOf(
            VaultTrainingLog("log-a", "0001", "2026-09-16", sets = 3, reps = 10, setWeights = listOf(60.0, 60.0, 60.0)),
            VaultTrainingLog("log-b", "0003", "2026-09-16", intervals = 2, setDurations = listOf(10.0, 15.0), setDistances = listOf(4.0, 6.0), durUnit = "min")
        )
        val text = VaultCodec.encodeTrainingLogs(logs, null, VaultDateStyle.READABLE)
        assertTrue(text.contains("## Wed, Sep 16, 2026"))
        assertTrue(text.contains("- weight(kg): 60, 60, 60"))
        assertTrue(text.contains("- reps: 10\n"))
        assertFalse(text.contains("- reps: 10, 10, 10"))
        assertTrue(text.contains("- dur(min): 10, 15"))
        val reparsed = VaultCodec.parseTrainingLogs(text)
        requireClean(reparsed)
        assertEquals(logs, reparsed.value)
    }

    @Test
    fun freshCanonicalTemplatesParseClean() {
        listOf(VaultKind.ROUTINES to "# Routines", VaultKind.MEALS to "# Meal Library", VaultKind.TRAINING_LOGS to "# Training Log", VaultKind.NUTRITION_DIARY to "# Nutrition Diary", VaultKind.CONFIG to "# Config").forEach { (kind, heading) ->
            val doc = VaultCodec.parse(kind.fileName, "$heading\n")
            requireClean(doc)
        }
        assertTrue(VaultCodec.parseConfig("# Config\n").value == VaultConfig())
    }

    @Test
    fun trainingLogsRejectMixedTimedAndStrengthFields() {
        val doc = VaultCodec.parseTrainingLogs(
            "# Training Log\n\n## 2026-09-16\n\n- exercise: \"Row\"\n\t- exerciseId: #0002\n\t- sets: 3\n\t- int: 2\n\t- id: log-x\n"
        )
        assertEquals(1, doc.value.size)
        assertTrue(doc.diagnostics.any { it.message.contains("Mixed timed and strength fields") })
        assertEquals(doc.source, VaultCodec.encodeTrainingLogs(doc.value, doc))
        requireRejected("Typed replacement rejected") {
            VaultCodec.encodeTrainingLogs(doc.value.map { it.copy(notes = "Changed") }, doc)
        }
        requireRejected("without normalization") {
            VaultCodec.encodeTrainingLogs(listOf(VaultTrainingLog("log-x", "0002", "2026-09-16", sets = 3, intervals = 2)))
        }
    }

    @Test
    fun configUnchangedValueReturnsOriginalSource() {
        val text = "# Config\n\n## Preferences\naccent: red\nliked: 0001\n"
        val doc = VaultCodec.parseConfig(text)
        requireClean(doc)
        assertEquals(text, VaultCodec.encodeConfig(doc.value, doc))
    }

    @Test
    fun diaryInvalidDateHeadingIsFlaggedAndContentIgnoredSafely() {
        val doc = VaultCodec.parseNutritionDiary(
            "# Nutrition Diary\n\n## Not a date\n- Oats 300 kcal · **10p · 45c · 6f**\n\n## 2026-02-30\n- Oats 300 kcal · **10p · 45c · 6f**\n"
        )
        assertTrue(doc.diagnostics.isNotEmpty())
        assertTrue(doc.value.isEmpty())
    }

    @Test
    fun routineUnknownModifierIsFlagged() {
        val doc = VaultCodec.parseRoutines("# Routines\n\n## Push\n0001 3 * 10 dropset\n")
        assertTrue(doc.diagnostics.isNotEmpty())
        assertEquals(1, doc.value.single().items.size)
    }
}
