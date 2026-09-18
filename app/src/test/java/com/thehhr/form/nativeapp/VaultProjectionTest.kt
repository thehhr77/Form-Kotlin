package com.thehhr.form.nativeapp

import com.thehhr.form.nativeapp.vault.VaultFiles
import com.thehhr.form.nativeapp.vault.VaultFormat
import com.thehhr.form.nativeapp.vault.VaultSnapshot
import org.junit.Assert.*
import org.junit.Test

class VaultProjectionTest {
    private val builtIn = Exercise("0001", "Sit-up", "waist", "abs", "body weight", "images/0001.jpg", "videos/0001.gif", emptyList(), "")

    private fun snapshot(routines: String = "# Routines\n", config: String = VaultFormat.canonicalContent(VaultFiles.CONFIG)): VaultSnapshot {
        return VaultSnapshot("Fixture", "content://fixture/tree/vault", VaultFiles.ALL.associateWith(VaultFormat::canonicalContent) + mapOf(VaultFiles.ROUTINES to routines, VaultFiles.CONFIG to config), emptyList())
    }

    @Test fun loadsSavedRoutineAndResolvesExerciseWithoutChangingSource() {
        val source = "# Routines\n\n## Morning strength\n- id: morning\n- liked\n0001 3 * 10\n"
        val vault = snapshot(source)
        val projection = VaultProjection.from(vault, listOf(builtIn))
        val routine = projection.data.routines.single()
        assertEquals("Morning strength", routine.name)
        assertTrue(routine.liked)
        assertEquals("Sit-up", projection.exercises.single { it.id == routine.items.single().exerciseId }.name)
        assertEquals(3, routine.items.single().sets)
        assertEquals(source, vault.file(VaultFiles.ROUTINES))
    }

    @Test fun customExerciseTagsAndFavoritesLoadTogether() {
        val config = """
            # Config
            ## Preferences
            accent: blue
            liked: c-press, 0001
            ## Custom Exercises
            - name: Landmine press
              id: c-press
              category: shoulders
              target: delts
              equipment: barbell
              description: Brace / Press upward
            ## Exercise Tags
            - id: c-press
              tags: warmup, push
        """.trimIndent()
        val projection = VaultProjection.from(snapshot(config = config), listOf(builtIn))
        val custom = projection.exercises.single { it.id == "c-press" }
        assertEquals(listOf("Brace", "Press upward"), custom.instructions)
        assertTrue(custom.image.isEmpty())
        assertTrue(custom.animation.isEmpty())
        assertEquals(listOf("warmup", "push"), projection.data.config.exerciseTags[custom.id])
        assertTrue(custom.id in projection.data.config.liked)
        assertEquals("blue", projection.data.config.preferences["accent"])
    }

    @Test fun switchingVaultDoesNotLeakPreviousCustomExercises() {
        val custom = "# Config\n## Custom Exercises\n- name: Custom\n  id: c-first\n"
        val first = VaultProjection.from(snapshot(config = custom), listOf(builtIn))
        assertTrue(first.exercises.any { it.id == "c-first" })
        val second = VaultProjection.from(snapshot(), listOf(builtIn))
        assertEquals(listOf(builtIn), second.exercises)
    }

    @Test fun missingFileIsNotReportedAsAnEmptyExistingLibrary() {
        val original = snapshot()
        val projection = VaultProjection.from(original.copy(files = original.files - VaultFiles.MEALS), listOf(builtIn))
        assertTrue(VaultFiles.MEALS in projection.data.missingFiles)
        assertFalse(VaultFiles.ROUTINES in projection.data.missingFiles)
    }

    @Test fun unresolvedReferenceIsRetainedAndReported() {
        val projection = VaultProjection.from(snapshot("# Routines\n## Missing movement\n9999 3 * 10\n"), listOf(builtIn))
        assertEquals("9999", projection.data.routines.single().items.single().exerciseId)
        assertTrue(projection.data.diagnostics.any { it.contains("9999") })
    }

    @Test fun duplicateCustomIdDoesNotCreateDuplicateGridKeys() {
        val config = "# Config\n## Custom Exercises\n- name: First\n  id: c-first\n- name: Second\n  id: c-first\n"
        val projection = VaultProjection.from(snapshot(config = config), listOf(builtIn))
        assertEquals(1, projection.exercises.count { it.id == "c-first" })
        assertTrue(projection.data.diagnostics.any { it.contains("c-first") })
    }

    @Test fun femaleProfileRemainsFemaleInDisplayWithoutRewritingFile() {
        val config = "# Config\n## Profile\nsex: f\n"
        val vault = snapshot(config = config)
        assertEquals("f", VaultProjection.from(vault, listOf(builtIn)).data.config.sex)
        assertEquals(config, vault.file(VaultFiles.CONFIG))
    }

    @Test fun defaultNutritionTargetsMatchRoundedFormula() {
        val expected = mapOf("cals" to 2975.0, "p" to 150.0, "c" to 407.0, "f" to 83.0, "water" to 3375.0)
        assertEquals(expected, NutritionTargets.calculate(com.thehhr.form.nativeapp.vault.VaultConfig()))
        assertEquals(expected, NutritionTargets.effective(VaultProjection.from(snapshot(), emptyList()).data.config))
    }

    @Test fun femaleNutritionProjectionCorrectsOnlyNativeFormulaNotCodecCompatibility() {
        val source = "# Config\r\n- ## Profile\r\n  - sex: f  \r\n"
        val document = com.thehhr.form.nativeapp.vault.VaultCodec.parseConfig(source)
        assertEquals("m", document.value.sex)
        assertEquals(source, com.thehhr.form.nativeapp.vault.VaultCodec.encode(document))
        val projected = VaultProjection.from(snapshot(config = source), emptyList()).data.config
        assertEquals("f", projected.sex)
        assertEquals(mapOf("cals" to 2719.0, "p" to 150.0, "c" to 361.0, "f" to 75.0, "water" to 3375.0), NutritionTargets.calculate(projected))
    }

    @Test fun nutritionFormulaUsesCalorieAndWaterFloorsAndNonnegativeCarbs() {
        val config = com.thehhr.form.nativeapp.vault.VaultConfig(profile = mapOf(
            "age" to 110.0, "height" to 50.0, "current-weight" to 20.0, "activity" to 1.0,
            "strategy" to -1000.0, "protein-rate" to 0.5
        ), sex = "f")
        assertEquals(mapOf("cals" to 1201.0, "p" to 10.0, "c" to 216.0, "f" to 33.0, "water" to 2000.0), NutritionTargets.calculate(config))
        val highProtein = NutritionTargets.calculate(config.copy(profile = config.profile + mapOf("current-weight" to 500.0, "protein-rate" to 5.0)))
        assertEquals(2500.0, highProtein.getValue("p"), 0.0)
        assertEquals(0.0, highProtein.getValue("c"), 0.0)
        assertEquals(highProtein.getValue("p") * 4 + highProtein.getValue("f") * 9, highProtein.getValue("cals"), 0.0)
    }

    @Test fun nutritionWaterThresholdAndFractionalRoundingMatchFormula() {
        val base = com.thehhr.form.nativeapp.vault.VaultConfig(profile = mapOf("current-weight" to 75.25, "protein-rate" to 2.0, "activity" to 1.49))
        val low = NutritionTargets.calculate(base)
        val high = NutritionTargets.calculate(base.copy(profile = base.profile + ("activity" to 1.5)))
        assertEquals(2634.0, low.getValue("water"), 0.0)
        assertEquals(3386.0, high.getValue("water"), 0.0)
        assertEquals(151.0, high.getValue("p"), 0.0)
        assertEquals(high.getValue("p") * 4 + high.getValue("c") * 4 + high.getValue("f") * 9, high.getValue("cals"), 0.0)
    }

    @Test fun nutritionOverridesClampIndependentlyAndClearingRestoresCalculatedValue() {
        val source = "# Config\n## Macro Overrides\ncals: 2000\np: 100\nc: 200\nf: 60\nwater: 3000\n"
        val config = VaultProjection.from(snapshot(config = source), emptyList()).data.config
        assertEquals(mapOf("cals" to 2000.0, "p" to 100.0, "c" to 200.0, "f" to 60.0, "water" to 3000.0), NutritionTargets.effective(config))
        val cleared = com.thehhr.form.nativeapp.vault.ConfigCodec.patchConfigField(source, "Targets", "protein", "100", null)
        val after = VaultProjection.from(snapshot(config = cleared), emptyList()).data.config
        assertEquals(NutritionTargets.effective(config) + ("p" to 150.0), NutritionTargets.effective(after))
        val invalid = config.copy(overrides = mapOf("cals" to -1.0, "p" to 2000.0, "c" to -1.0, "f" to Double.NaN, "water" to 20000.0))
        assertEquals(mapOf("cals" to 500.0, "p" to 1000.0, "c" to 0.0, "f" to 83.0, "water" to 10000.0), NutritionTargets.effective(invalid))
        assertEquals(NutritionTargets.calculate(config), NutritionTargets.effective(config.copy(overrides = mapOf("cals" to Double.POSITIVE_INFINITY))))
    }

    @Test fun nutritionProfileDefaultsAndClampingIgnoreGoalAndStartingWeights() {
        val base = com.thehhr.form.nativeapp.vault.VaultConfig()
        val defaults = NutritionTargets.calculate(base)
        assertEquals(defaults, NutritionTargets.calculate(base.copy(profile = mapOf("age" to Double.NaN, "height" to Double.POSITIVE_INFINITY, "current-weight" to Double.NEGATIVE_INFINITY))))
        assertEquals(defaults, NutritionTargets.calculate(base.copy(profile = mapOf("start-weight" to 20.0, "goal-weight" to 500.0))))
        assertEquals(NutritionTargets.calculate(base.copy(profile = mapOf("age" to 110.0))), NutritionTargets.calculate(base.copy(profile = mapOf("age" to 1000.0))))
        assertEquals(NutritionTargets.calculate(base.copy(profile = mapOf("activity" to 1.0))), NutritionTargets.calculate(base.copy(profile = mapOf("activity" to -10.0))))
    }

    @Test fun nutritionPreferencePatchesProjectWithoutChangingOtherPreferences() {
        val source = VaultFormat.canonicalContent(VaultFiles.CONFIG)
        var patched = source
        for ((key, desired) in listOf("week-start" to "6", "default-view" to "month", "workout-reminder" to "false", "rest-between-sets" to "45.5", "rest-between-exercises" to "120")) {
            val original = com.thehhr.form.nativeapp.vault.ConfigCodec.configFieldValue(patched, "Preferences", key)
            patched = com.thehhr.form.nativeapp.vault.ConfigCodec.patchConfigField(patched, "Preferences", key, original, desired)
            val projected = VaultProjection.from(snapshot(config = patched), emptyList()).data.config
            assertEquals(desired, projected.preferences[key])
            assertEquals("red", projected.preferences["accent"])
            assertEquals("false", projected.preferences["rest-enabled"])
        }
    }

    @Test fun noVaultShowsOnlyBundledCatalog() {
        val projection = VaultProjection.from(null, listOf(builtIn))
        assertEquals(listOf(builtIn), projection.exercises)
        assertTrue(projection.data.routines.isEmpty())
        assertTrue(projection.data.missingFiles.isEmpty())
    }
}
