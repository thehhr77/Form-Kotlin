package com.thehhr.form.nativeapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.unit.dp
import java.util.Locale

enum class LibraryDimension(val key: String, val label: String) {
    ROUTINE("routine", "Routine"), CATEGORY("category", "Category"), TARGET("target", "Target"),
    EQUIPMENT("equipment", "Equipment"), TAG("tag", "Tag"), LIKED("liked", "Liked"), LOGGED("logged", "Logged")
}

enum class LibraryRowMode { DEFAULT, PIN, HIDDEN }

enum class LibrarySort(val key: String, val label: String) {
    NAME("name", "Name"), DATASET("id", "Dataset order"), BODY_PART("category", "Body part"), CUSTOM("custom", "Custom")
}

val libraryRows = listOf(LibraryDimension.ROUTINE, LibraryDimension.CATEGORY, LibraryDimension.TARGET, LibraryDimension.EQUIPMENT)

data class LibraryFilterPreferences(val values: Map<String, String> = emptyMap()) {
    fun mode(row: LibraryDimension): LibraryRowMode = when (values["pill-${row.key}"]) {
        "pin" -> LibraryRowMode.PIN
        "hidden" -> LibraryRowMode.HIDDEN
        else -> LibraryRowMode.DEFAULT
    }

    fun host(key: String): LibraryDimension? {
        val available = libraryRows.filter { mode(it) != LibraryRowMode.HIDDEN }
        return available.firstOrNull { it.key == (values[key] ?: "equipment") } ?: available.firstOrNull()
    }

    val tagsHost get() = host("pill-tags-host")
    val togglesHost get() = host("pill-toggles")
    val canExpand get() = libraryRows.any { mode(it) == LibraryRowMode.DEFAULT }
    val showSecondary get() = values["show-secondary-pills"] == "true"

    fun visibleRows(expanded: Boolean): List<LibraryDimension> = libraryRows.filter {
        mode(it) == LibraryRowMode.PIN || (expanded && mode(it) == LibraryRowMode.DEFAULT)
    }
}

data class LibraryFilterState(
    val query: String = "",
    val routine: String = "",
    val category: String = "",
    val target: String = "",
    val equipment: String = "",
    val tag: String = "",
    val liked: Boolean = false,
    val logged: Boolean = false,
    val descending: Boolean = false,
    val expanded: Boolean = false,
    val sort: LibrarySort = LibrarySort.NAME
) {
    fun value(dimension: LibraryDimension): String = when (dimension) {
        LibraryDimension.ROUTINE -> routine
        LibraryDimension.CATEGORY -> category
        LibraryDimension.TARGET -> target
        LibraryDimension.EQUIPMENT -> equipment
        LibraryDimension.TAG -> tag
        LibraryDimension.LIKED -> if (liked) "true" else ""
        LibraryDimension.LOGGED -> if (logged) "true" else ""
    }

    fun select(dimension: LibraryDimension, value: String): LibraryFilterState = when (dimension) {
        LibraryDimension.ROUTINE -> copy(
            routine = value,
            sort = if (value.isNotEmpty()) LibrarySort.CUSTOM else if (sort == LibrarySort.CUSTOM) LibrarySort.NAME else sort
        )
        LibraryDimension.CATEGORY -> copy(category = value)
        LibraryDimension.TARGET -> copy(target = value)
        LibraryDimension.EQUIPMENT -> copy(equipment = value)
        LibraryDimension.TAG -> copy(tag = value.lowercase(Locale.ROOT))
        LibraryDimension.LIKED -> copy(liked = value.isNotEmpty())
        LibraryDimension.LOGGED -> copy(logged = value.isNotEmpty())
    }

    fun toggle(dimension: LibraryDimension, value: String): LibraryFilterState =
        select(dimension, if (value(dimension) == value) "" else value)

    fun reconcile(data: VaultData): LibraryFilterState {
        val preferences = LibraryFilterPreferences(data.config.preferences)
        val validRoutine = data.routines.singleOrNull { it.id == routine }
            ?.takeIf { it.items.isNotEmpty() && (!it.secondary || preferences.showSecondary) }
        return copy(
            routine = if (validRoutine == null) "" else routine,
            sort = if (validRoutine == null && sort == LibrarySort.CUSTOM) LibrarySort.NAME else sort,
            expanded = expanded && preferences.canExpand
        )
    }

    fun reset(): LibraryFilterState = LibraryFilterState()

    fun summary(data: VaultData): String = buildList {
        if (query.isNotBlank()) add("Search: ${query.trim()}")
        LibraryDimension.entries.forEach { dimension ->
            val value = value(dimension)
            if (value.isNotEmpty()) add(when (dimension) {
                LibraryDimension.ROUTINE -> "Routine: ${data.routines.singleOrNull { it.id == value }?.name ?: value}"
                LibraryDimension.LIKED, LibraryDimension.LOGGED -> dimension.label
                else -> "${dimension.label}: $value"
            })
        }
        if (sort != LibrarySort.NAME) add(sort.label)
        if (descending && sort == LibrarySort.NAME) add("Z–A")
    }.joinToString(" · ")
}

val LibraryFilterStateSaver = listSaver<LibraryFilterState, String>(
    save = { listOf(it.query, it.routine, it.category, it.target, it.equipment, it.tag, it.liked.toString(), it.logged.toString(), it.descending.toString(), it.expanded.toString(), it.sort.key) },
    restore = { values ->
        LibraryFilterState(
            values[0], values[1], values[2], values[3], values[4], values[5],
            values[6].toBoolean(), values[7].toBoolean(), values[8].toBoolean(), values[9].toBoolean(),
            LibrarySort.entries.firstOrNull { it.key == values[10] } ?: LibrarySort.NAME
        )
    }
)

data class LibraryPill(val dimension: LibraryDimension, val value: String, val label: String, val selected: Boolean, val available: Boolean) {
    val enabled get() = selected || available
    val key get() = "${dimension.key}:$value"
}

class LibraryFilterCatalog(val exercises: List<Exercise>, val data: VaultData) {
    val preferences = LibraryFilterPreferences(data.config.preferences)
    private val liked = data.config.liked.toSet()
    private val logged = data.trainingLogs.map { it.exerciseId }.toSet()
    private val routines = data.routines.filter { it.items.isNotEmpty() && (!it.secondary || preferences.showSecondary) }
        .filter { routine -> data.routines.count { it.id == routine.id } == 1 }
    private val routineMembership = buildMap<String, MutableSet<String>> {
        routines.forEach { routine -> routine.items.forEach { getOrPut(it.exerciseId) { linkedSetOf() }.add(routine.id) } }
    }
    private val tags = data.config.exerciseTags.mapValues { (_, values) -> values.filter(String::isNotBlank).map { it.lowercase(Locale.ROOT) }.toSet() }
    private val tagLabels = data.config.exerciseTags.values.flatten().filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }
        .associateBy { it.lowercase(Locale.ROOT) }

    private fun values(exercise: Exercise, dimension: LibraryDimension): Set<String> = when (dimension) {
        LibraryDimension.ROUTINE -> routineMembership[exercise.id].orEmpty()
        LibraryDimension.CATEGORY -> setOf(exercise.category).filterTo(linkedSetOf(), String::isNotBlank)
        LibraryDimension.TARGET -> setOf(exercise.target).filterTo(linkedSetOf(), String::isNotBlank)
        LibraryDimension.EQUIPMENT -> setOf(exercise.equipment).filterTo(linkedSetOf(), String::isNotBlank)
        LibraryDimension.TAG -> tags[exercise.id].orEmpty()
        LibraryDimension.LIKED -> if (exercise.id in liked) setOf("true") else emptySet()
        LibraryDimension.LOGGED -> if (exercise.id in logged) setOf("true") else emptySet()
    }

    private val memberships = exercises.map { exercise -> LibraryDimension.entries.associateWith { values(exercise, it) } }

    private fun searchHaystack(exercise: Exercise, exerciseTags: Set<String>): String =
        (listOf(exercise.name, exercise.id, exercise.category, exercise.target, exercise.equipment, exercise.muscleGroup) +
            exercise.secondaryMuscles + exerciseTags).joinToString(" ") { it.lowercase(Locale.ROOT) }

    private fun matchesQuery(exercise: Exercise, exerciseTags: Set<String>, query: String): Boolean {
        val trimmed = query.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith("#")) return exercise.id.startsWith(trimmed.substring(1).trim())
        val haystack = searchHaystack(exercise, exerciseTags)
        return trimmed.split(Regex("\\s+")).filter(String::isNotEmpty).all { token ->
            haystack.contains(token) || (token.length >= 3 && token.endsWith("s") && haystack.contains(token.removeSuffix("s")))
        }
    }

    fun evaluate(state: LibraryFilterState): LibraryFilterResult {
        val available = LibraryDimension.entries.associateWith { linkedSetOf<String>() }
        val matches = mutableListOf<Exercise>()
        exercises.forEachIndexed { index, exercise ->
            if (state.query.isNotBlank() && !matchesQuery(exercise, tags[exercise.id].orEmpty(), state.query)) return@forEachIndexed
            val membership = memberships[index]
            val failed = LibraryDimension.entries.filter { dimension ->
                val selected = state.value(dimension).let { if (dimension == LibraryDimension.TAG) it.lowercase(Locale.ROOT) else it }
                selected.isNotEmpty() && selected !in membership.getValue(dimension)
            }
            if (failed.isEmpty()) matches += exercise
            LibraryDimension.entries.forEach { dimension ->
                if (failed.isEmpty() || (failed.size == 1 && failed.single() == dimension)) available.getValue(dimension).addAll(membership.getValue(dimension))
            }
        }
        val pills = LibraryDimension.entries.associateWith { dimension ->
            val options = when (dimension) {
                LibraryDimension.ROUTINE -> routines.associate { it.id to it.name }
                LibraryDimension.TAG -> tagLabels
                LibraryDimension.LIKED -> if (liked.isNotEmpty() || state.liked) mapOf("true" to "Liked") else emptyMap()
                LibraryDimension.LOGGED -> if (logged.isNotEmpty() || state.logged) mapOf("true" to "Logged") else emptyMap()
                else -> memberships.flatMap { it.getValue(dimension) }.distinct().associateWith { it }
            }.toMutableMap()
            val selected = state.value(dimension).let { if (dimension == LibraryDimension.TAG) it.lowercase(Locale.ROOT) else it }
            if (selected.isNotEmpty()) options.putIfAbsent(selected, selected)
            options.map { (value, label) -> LibraryPill(dimension, value, label, selected == value, value in available.getValue(dimension)) }
                .sortedWith(compareBy<LibraryPill> { !it.enabled }.thenBy { it.label.lowercase(Locale.ROOT) }.thenBy { it.value })
        }
        val routineOrder = if (state.sort == LibrarySort.CUSTOM) {
            data.routines.firstOrNull { it.id == state.routine }?.items?.mapIndexed { index, item -> item.exerciseId to index }?.toMap()
        } else null
        val sorted = when {
            routineOrder != null -> matches.sortedBy { routineOrder[it.id] ?: Int.MAX_VALUE }
            state.sort == LibrarySort.DATASET -> matches.sortedBy { it.id }
            state.sort == LibrarySort.BODY_PART -> matches.sortedWith(compareBy({ it.category }, { it.name }))
            else -> matches.sortedBy { it.name }
        }
        return LibraryFilterResult(if (state.sort == LibrarySort.NAME && state.descending) sorted.reversed() else sorted, pills, preferences)
    }
}

data class LibraryFilterResult(
    val exercises: List<Exercise>,
    val pills: Map<LibraryDimension, List<LibraryPill>>,
    val preferences: LibraryFilterPreferences
) {
    fun rowPills(row: LibraryDimension): List<LibraryPill> = buildList {
        if (preferences.togglesHost == row) {
            addAll(pills.getValue(LibraryDimension.LIKED))
            addAll(pills.getValue(LibraryDimension.LOGGED))
        }
        if (preferences.tagsHost == row) addAll(pills.getValue(LibraryDimension.TAG))
        addAll(pills.getValue(row))
    }
}

@Composable
fun LibraryPillRow(pills: List<LibraryPill>, onSelect: (LibraryPill) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pills, key = { it.key }) { pill ->
            FilterChip(selected = pill.selected, enabled = pill.enabled, onClick = { onSelect(pill) }, label = { Text(pill.label) })
        }
    }
}
