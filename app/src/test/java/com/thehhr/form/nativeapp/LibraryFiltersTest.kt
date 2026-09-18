package com.thehhr.form.nativeapp

import com.thehhr.form.nativeapp.vault.VaultConfig
import com.thehhr.form.nativeapp.vault.VaultRoutine
import com.thehhr.form.nativeapp.vault.VaultRoutineItem
import com.thehhr.form.nativeapp.vault.VaultTrainingLog
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LibraryFiltersTest {
    private fun exercise(id: String, name: String, category: String, target: String, equipment: String, muscleGroup: String = "", secondaryMuscles: List<String> = emptyList()) =
        Exercise(id, name, category, target, equipment, "", "", emptyList(), "", muscleGroup, secondaryMuscles)

    private val exercises = listOf(
        exercise("0001", "Alpha press", "upper", "chest", "barbell"),
        exercise("0002", "Beta press", "upper", "shoulders", "dumbbell"),
        exercise("c-custom", "Custom squat", "custom legs", "quads", "custom sled"),
        exercise("00010", "Delta press", "upper", "chest", "dumbbell")
    )
    private val primary = VaultRoutine("primary", "Primary", items = listOf(VaultRoutineItem("0001"), VaultRoutineItem("0002")))
    private val secondary = VaultRoutine("secondary", "Secondary", secondary = true, items = listOf(VaultRoutineItem("c-custom")))
    private val data = VaultData(
        routines = listOf(primary, secondary, VaultRoutine("empty", "Empty")),
        config = VaultConfig(liked = listOf("0001", "0002"), exerciseTags = linkedMapOf("0001" to listOf("Push", "PUSH"), "0002" to listOf("push", "Warmup"), "c-custom" to listOf("Legs"))),
        trainingLogs = listOf(VaultTrainingLog("log-id-not-exercise", "0001", "2026-09-18"), VaultTrainingLog("0002", "c-custom", "2026-09-18"))
    )
    private fun evaluate(state: LibraryFilterState = LibraryFilterState(), source: VaultData = data) = LibraryFilterCatalog(exercises, source).evaluate(state)
    private fun LibraryFilterResult.ids() = exercises.map { it.id }

    @Test fun rowModesDefaultToCollapsedAndPinsIgnoreExpansion() {
        val defaults = LibraryFilterPreferences()
        assertTrue(defaults.visibleRows(false).isEmpty())
        assertEquals(libraryRows, defaults.visibleRows(true))
        assertTrue(defaults.canExpand)
        val configured = LibraryFilterPreferences(mapOf("pill-routine" to "pin", "pill-category" to "hidden"))
        assertEquals(listOf(LibraryDimension.ROUTINE), configured.visibleRows(false))
        assertEquals(listOf(LibraryDimension.ROUTINE, LibraryDimension.TARGET, LibraryDimension.EQUIPMENT), configured.visibleRows(true))
        assertEquals(LibraryRowMode.DEFAULT, LibraryFilterPreferences(mapOf("pill-target" to "invalid")).mode(LibraryDimension.TARGET))
    }

    @Test fun hostsDefaultToEquipmentAndFallbackInRowOrderIndependently() {
        assertEquals(LibraryDimension.EQUIPMENT, LibraryFilterPreferences().tagsHost)
        assertEquals(LibraryDimension.EQUIPMENT, LibraryFilterPreferences().togglesHost)
        val values = mutableMapOf("pill-tags-host" to "target", "pill-toggles" to "category", "pill-target" to "hidden")
        assertEquals(LibraryDimension.ROUTINE, LibraryFilterPreferences(values).tagsHost)
        assertEquals(LibraryDimension.CATEGORY, LibraryFilterPreferences(values).togglesHost)
        values["pill-routine"] = "hidden"
        assertEquals(LibraryDimension.CATEGORY, LibraryFilterPreferences(values).tagsHost)
        values["pill-category"] = "hidden"
        assertEquals(LibraryDimension.EQUIPMENT, LibraryFilterPreferences(values).togglesHost)
        values["pill-equipment"] = "hidden"
        assertNull(LibraryFilterPreferences(values).tagsHost)
        assertNull(LibraryFilterPreferences(values).togglesHost)
    }

    @Test fun expansionDisabledWithoutDefaultRows() {
        val preferences = libraryRows.associate { "pill-${it.key}" to "pin" }
        val configured = LibraryFilterPreferences(preferences)
        assertFalse(configured.canExpand)
        assertEquals(libraryRows, configured.visibleRows(false))
        assertFalse(LibraryFilterState(expanded = true).reconcile(data.copy(config = data.config.copy(preferences = preferences))).expanded)
        val hidden = LibraryFilterPreferences(libraryRows.associate { "pill-${it.key}" to "hidden" })
        assertFalse(hidden.canExpand)
        assertTrue(hidden.visibleRows(true).isEmpty())
    }

    @Test fun dimensionsCombineWithAndIncludingRoutineTagLikedAndLogged() {
        val state = LibraryFilterState(query = " press ", routine = "primary", category = "upper", target = "chest", equipment = "barbell", tag = "PUSH", liked = true, logged = true)
        assertEquals(listOf("0001"), evaluate(state).ids())
        assertTrue(evaluate(state.copy(equipment = "dumbbell")).exercises.isEmpty())
        assertTrue(evaluate(state.copy(tag = "legs")).exercises.isEmpty())
        assertTrue(evaluate(state.copy(routine = "removed")).exercises.isEmpty())
    }

    @Test fun queryPreservesTrimCaseAndSpansEquipmentAndIdFields() {
        assertEquals(listOf("0001", "0002", "00010"), evaluate(LibraryFilterState(query = "  PRESS  ")).ids())
        assertEquals(listOf("c-custom"), evaluate(LibraryFilterState(query = "QUADS")).ids())
        assertEquals(listOf("c-custom"), evaluate(LibraryFilterState(query = "c-custom")).ids())
        assertEquals(listOf("0001"), evaluate(LibraryFilterState(query = "barbell")).ids())
        assertTrue(evaluate(LibraryFilterState(query = "zzz")).exercises.isEmpty())
        assertEquals(exercises, evaluate(LibraryFilterState(query = "  ")).exercises)
        assertEquals(exercises.reversed(), evaluate(LibraryFilterState(descending = true)).exercises)
    }

    @Test fun idPrefixSearchRequiresHashAndUsesPrefixSemanticsOnExerciseIds() {
        assertEquals(listOf("0001", "0002", "00010"), evaluate(LibraryFilterState(query = "#000")).ids())
        assertEquals(listOf("0001", "00010"), evaluate(LibraryFilterState(query = "#0001")).ids())
        assertEquals(listOf("0001", "00010"), evaluate(LibraryFilterState(query = " # 0001 ")).ids())
        assertEquals(listOf("c-custom"), evaluate(LibraryFilterState(query = "#C")).ids())
        assertTrue(evaluate(LibraryFilterState(query = "#9")).exercises.isEmpty())
        assertTrue(evaluate(LibraryFilterState(query = "#zzz")).exercises.isEmpty())
    }

    @Test fun tokenSearchMatchesIndependentlyWithPluralFallbackAcrossAllFields() {
        assertEquals(listOf("0001", "0002", "00010"), evaluate(LibraryFilterState(query = "press")).ids())
        assertEquals(listOf("c-custom"), evaluate(LibraryFilterState(query = "squats")).ids())
        assertTrue(evaluate(LibraryFilterState(query = "presses")).exercises.isEmpty())
        assertEquals(listOf("0001"), evaluate(LibraryFilterState(query = "alpha chest")).ids())
        assertEquals(listOf("0002", "00010"), evaluate(LibraryFilterState(query = "press dumbbell")).ids())
        assertEquals(listOf("0001", "0002"), evaluate(LibraryFilterState(query = "push")).ids())
        assertEquals(listOf("0002"), evaluate(LibraryFilterState(query = "warmup")).ids())
        assertEquals(listOf("0001"), evaluate(LibraryFilterState(query = "0001 alpha")).ids())
        assertTrue(evaluate(LibraryFilterState(query = "press chest sled")).exercises.isEmpty())
        assertEquals(listOf("c-custom"), evaluate(LibraryFilterState(query = "legs sled")).ids())
    }

    @Test fun muscleGroupAndSecondaryMusclesAreSearchable() {
        val anatomical = listOf(
            exercise("0001", "Gamma row", "upper", "lats", "cable", muscleGroup = "back", secondaryMuscles = listOf("biceps", "Forearms"))
        )
        assertEquals(listOf("0001"), LibraryFilterCatalog(anatomical, data).evaluate(LibraryFilterState(query = "biceps")).ids())
        assertEquals(listOf("0001"), LibraryFilterCatalog(anatomical, data).evaluate(LibraryFilterState(query = "forearm")).ids())
        assertEquals(listOf("0001"), LibraryFilterCatalog(anatomical, data).evaluate(LibraryFilterState(query = "backs")).ids())
        assertTrue(LibraryFilterCatalog(anatomical, data).evaluate(LibraryFilterState(query = "chest")).exercises.isEmpty())
    }

    @Test fun sortOptionsOrderByNameDatasetBodyPartAndRoutineCustom() {
        assertEquals(listOf("0001", "0002", "c-custom", "00010"), evaluate().ids())
        assertEquals(listOf("0001", "00010", "0002", "c-custom"), evaluate(LibraryFilterState(sort = LibrarySort.DATASET)).ids())
        assertEquals(listOf("c-custom", "0001", "0002", "00010"), evaluate(LibraryFilterState(sort = LibrarySort.BODY_PART)).ids())
        assertEquals(listOf("0001", "0002", "c-custom", "00010"), evaluate(LibraryFilterState(sort = LibrarySort.CUSTOM)).ids())
        assertEquals(listOf("0001", "0002"), evaluate(LibraryFilterState(routine = "primary", sort = LibrarySort.CUSTOM)).ids())
        val reordered = data.copy(routines = listOf(
            VaultRoutine("reordered", "Reordered", items = listOf(VaultRoutineItem("0002"), VaultRoutineItem("c-custom"), VaultRoutineItem("00010"))),
            primary, secondary, VaultRoutine("empty", "Empty")
        ))
        assertEquals(listOf("0002", "c-custom", "00010"),
            LibraryFilterCatalog(exercises, reordered).evaluate(LibraryFilterState(routine = "reordered", sort = LibrarySort.CUSTOM)).ids())
    }

    @Test fun descendingAppliesOnlyToNameSortWhileCustomFollowsRoutineSelection() {
        assertEquals(listOf("00010", "c-custom", "0002", "0001"), evaluate(LibraryFilterState(descending = true)).ids())
        assertEquals(listOf("0001", "00010", "0002", "c-custom"), evaluate(LibraryFilterState(sort = LibrarySort.DATASET, descending = true)).ids())
        val selected = LibraryFilterState().select(LibraryDimension.ROUTINE, "primary")
        assertEquals("primary", selected.routine)
        assertEquals(LibrarySort.CUSTOM, selected.sort)
        assertEquals(LibrarySort.CUSTOM, selected.select(LibraryDimension.ROUTINE, "secondary").sort)
        assertEquals(LibrarySort.NAME, selected.toggle(LibraryDimension.ROUTINE, "primary").sort)
        assertEquals(LibrarySort.CUSTOM, selected.reconcile(data).sort)
        val dropped = LibraryFilterState(routine = "secondary", sort = LibrarySort.CUSTOM).reconcile(data)
        assertEquals("", dropped.routine)
        assertEquals(LibrarySort.NAME, dropped.sort)
    }

    @Test fun summaryReportsNonDefaultSortsAndKeepsZAToggle() {
        assertEquals("Z–A", LibraryFilterState(descending = true).summary(data))
        assertEquals("Body part", LibraryFilterState(sort = LibrarySort.BODY_PART).summary(data))
        assertEquals("Routine: Primary · Custom", LibraryFilterState(routine = "primary", sort = LibrarySort.CUSTOM).summary(data))
        assertEquals("Dataset order", LibraryFilterState(sort = LibrarySort.DATASET, descending = true).summary(data))
    }

    @Test fun membershipUsesExactExerciseIdAndLogsUseExerciseIdNotLogId() {
        assertEquals(listOf("0001", "0002"), evaluate(LibraryFilterState(liked = true)).ids())
        assertEquals(listOf("0001", "c-custom"), evaluate(LibraryFilterState(logged = true)).ids())
        assertEquals(listOf("0001", "0002"), evaluate(LibraryFilterState(tag = "push")).ids())
        assertFalse("00010" in evaluate(LibraryFilterState(routine = "primary")).ids())
    }

    @Test fun eachAvailabilityIgnoresOnlyItsOwnDimension() {
        val state = LibraryFilterState(routine = "primary", category = "upper", target = "chest", equipment = "barbell", tag = "push", liked = true, logged = true)
        LibraryDimension.entries.forEach { dimension ->
            val filtered = evaluate(state)
            filtered.pills.getValue(dimension).forEach { pill ->
                val expected = evaluate(state.select(dimension, pill.value)).exercises.isNotEmpty()
                assertEquals("${dimension.key}:${pill.value}", expected, pill.available)
            }
        }
        val selected = evaluate(LibraryFilterState(category = "upper", target = "quads"))
        assertTrue(selected.pills.getValue(LibraryDimension.CATEGORY).single { it.value == "custom legs" }.available)
        assertFalse(selected.pills.getValue(LibraryDimension.EQUIPMENT).single { it.value == "custom sled" }.available)
        assertTrue(selected.pills.getValue(LibraryDimension.TARGET).single { it.value == "chest" }.available)
    }

    @Test fun availabilityRespectsQueryAndSelectedPillsStayEnabledWithUnavailableLast() {
        val result = evaluate(LibraryFilterState(query = "no such exercise", category = "upper", liked = true))
        val categories = result.pills.getValue(LibraryDimension.CATEGORY)
        assertEquals("upper", categories.first().value)
        assertTrue(categories.first().enabled)
        assertFalse(categories.first().available)
        assertFalse(categories.last().enabled)
        assertTrue(result.pills.getValue(LibraryDimension.LIKED).single().enabled)
        assertFalse(result.pills.getValue(LibraryDimension.LOGGED).single().enabled)
        assertEquals("", LibraryFilterState(category = "upper").toggle(LibraryDimension.CATEGORY, "upper").category)
    }

    @Test fun secondaryAndEmptyRoutinesExcludedAndSecondarySelectionCleared() {
        assertEquals(listOf("primary"), evaluate().pills.getValue(LibraryDimension.ROUTINE).map { it.value })
        val withSecondary = data.copy(config = data.config.copy(preferences = mapOf("show-secondary-pills" to "true")))
        assertEquals(listOf("primary", "secondary"), evaluate(source = withSecondary).pills.getValue(LibraryDimension.ROUTINE).map { it.value })
        val selected = LibraryFilterState(routine = "secondary", category = "custom legs", query = "Custom", descending = true)
        assertEquals(selected, selected.reconcile(withSecondary))
        assertEquals(selected.copy(routine = ""), selected.reconcile(data))
        assertEquals(listOf("c-custom"), evaluate(selected, withSecondary).ids())
    }

    @Test fun removedEmptyAndAmbiguousRoutinesAreExplicitlyCleared() {
        val selected = LibraryFilterState(routine = "primary", tag = "push")
        assertEquals(selected.copy(routine = ""), selected.reconcile(data.copy(routines = emptyList())))
        assertEquals(selected.copy(routine = ""), selected.reconcile(data.copy(routines = listOf(primary.copy(items = emptyList())))))
        assertEquals(selected.copy(routine = ""), selected.reconcile(data.copy(routines = listOf(primary, primary))))
        assertEquals("Routine: primary · Tag: push", selected.summary(data.copy(routines = emptyList())))
    }

    @Test fun hiddenRowsRetainActiveFiltersAndSummaryUntilReset() {
        val hidden = data.copy(config = data.config.copy(preferences = libraryRows.associate { "pill-${it.key}" to "hidden" }))
        val selected = LibraryFilterState(routine = "primary", category = "upper", target = "chest", equipment = "barbell", tag = "push", liked = true, logged = true)
        assertEquals(selected, selected.reconcile(hidden))
        assertEquals(listOf("0001"), evaluate(selected, hidden).ids())
        assertEquals("Routine: Primary · Category: upper · Target: chest · Equipment: barbell · Tag: push · Liked · Logged", selected.summary(hidden))
        assertEquals(LibraryFilterState(), selected.reset())
    }

    @Test fun resetClearsEveryFilterQuerySortAndExpansion() {
        val state = LibraryFilterState("query", "primary", "upper", "chest", "barbell", "push", true, true, true, true, LibrarySort.CUSTOM)
        assertEquals(LibraryFilterState(), state.reset())
        assertFalse(state.reset().descending)
        assertFalse(state.reset().expanded)
        assertEquals(LibrarySort.NAME, state.reset().sort)
        assertEquals("", state.reset().summary(data))
    }

    @Test fun togglesHideOnlyWhenUnselectedAndGlobalSetAbsent() {
        val empty = data.copy(config = data.config.copy(liked = emptyList()), trainingLogs = emptyList())
        val result = evaluate(source = empty)
        assertTrue(result.pills.getValue(LibraryDimension.LIKED).isEmpty())
        assertTrue(result.pills.getValue(LibraryDimension.LOGGED).isEmpty())
        val active = evaluate(LibraryFilterState(liked = true, logged = true), empty)
        listOf(LibraryDimension.LIKED, LibraryDimension.LOGGED).forEach {
            assertTrue(active.pills.getValue(it).single().enabled)
            assertFalse(active.pills.getValue(it).single().available)
        }
        val stale = empty.copy(config = empty.config.copy(liked = listOf("missing")), trainingLogs = listOf(VaultTrainingLog("x", "missing", "2026-09-18")))
        assertFalse(evaluate(source = stale).pills.getValue(LibraryDimension.LIKED).single().enabled)
        assertFalse(evaluate(source = stale).pills.getValue(LibraryDimension.LOGGED).single().enabled)
    }

    @Test fun hostsPlaceTogglesBeforeTagsBeforeOrdinaryValues() {
        val result = evaluate()
        val row = result.rowPills(LibraryDimension.EQUIPMENT)
        assertEquals(listOf(LibraryDimension.LIKED, LibraryDimension.LOGGED), row.take(2).map { it.dimension })
        assertEquals(listOf(LibraryDimension.TAG), row.drop(2).take(3).map { it.dimension }.distinct())
        assertTrue(row.drop(5).all { it.dimension == LibraryDimension.EQUIPMENT })
        val fallback = data.copy(config = data.config.copy(preferences = mapOf("pill-equipment" to "hidden")))
        assertEquals(LibraryDimension.LIKED, evaluate(source = fallback).rowPills(LibraryDimension.ROUTINE).first().dimension)
    }

    @Test fun customCatalogFieldsGenerateOptionsAndBlankFieldsDoNot() {
        val result = LibraryFilterCatalog(exercises + exercise("blank", "Blank", "", "", ""), data).evaluate(LibraryFilterState())
        assertTrue(result.pills.getValue(LibraryDimension.CATEGORY).any { it.value == "custom legs" })
        assertTrue(result.pills.getValue(LibraryDimension.TARGET).any { it.value == "quads" })
        assertTrue(result.pills.getValue(LibraryDimension.EQUIPMENT).any { it.value == "custom sled" })
        assertTrue(result.pills.values.flatten().none { it.value.isBlank() })
        assertEquals(listOf("c-custom"), evaluate(LibraryFilterState(category = "custom legs", target = "quads", equipment = "custom sled")).ids())
    }

    @Test fun tagsDeduplicateAndMatchWithRootLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val tagged = data.copy(config = data.config.copy(exerciseTags = linkedMapOf("0001" to listOf("INTENSE", "intense"), "0002" to listOf("Intense"))))
            val result = evaluate(LibraryFilterState(tag = "INTENSE"), tagged)
            assertEquals(listOf("0001", "0002"), result.ids())
            assertEquals("intense", result.pills.getValue(LibraryDimension.TAG).single().value)
            assertTrue(result.pills.getValue(LibraryDimension.TAG).single().selected)
            assertEquals("intense", LibraryFilterState().select(LibraryDimension.TAG, "INTENSE").tag)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun configurationSaveDoesNotResetExistingSelections() {
        val state = LibraryFilterState(query = "press", routine = "primary", tag = "push", liked = true, descending = true, expanded = true)
        val changed = data.copy(config = data.config.copy(preferences = mapOf("accent" to "blue", "pill-category" to "hidden")))
        assertEquals(state, state.reconcile(changed))
        assertEquals(evaluate(state).ids(), evaluate(state, changed).ids())
    }
}
