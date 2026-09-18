package com.thehhr.form.nativeapp.vault

enum class VaultKind(val fileName: String, val heading: String) {
    ROUTINES("routines.md", "Routines"),
    MEALS("meals.md", "Meal Library"),
    TRAINING_LOGS("training_logs.md", "Training Log"),
    NUTRITION_DIARY("nutrition_diary.md", "Nutrition Diary"),
    CONFIG("config.md", "Config")
}

enum class VaultDateStyle { ISO, READABLE }

data class VaultDiagnostic(val line: Int, val message: String)

class VaultDocument<T> internal constructor(
    val kind: VaultKind,
    val source: String,
    val value: T,
    val diagnostics: List<VaultDiagnostic>
) {
    val canRewrite: Boolean get() = diagnostics.isEmpty()
}

class VaultWriteException(message: String) : IllegalArgumentException(message)

data class VaultRoutineItem(
    val exerciseId: String,
    val sets: Int = 3,
    val reps: Int = 10,
    val mode: String? = null,
    val unit: String? = null,
    val weighted: Boolean = false,
    val unweighted: Boolean = false,
    val superset: String? = null
)

data class VaultRoutine(
    val id: String,
    val name: String,
    val liked: Boolean = false,
    val secondary: Boolean = false,
    val items: List<VaultRoutineItem> = emptyList()
)

data class VaultMeal(
    val id: String,
    val name: String,
    val defaultGrams: Double = 100.0,
    val cals100: Double = 0.0,
    val p100: Double = 0.0,
    val c100: Double = 0.0,
    val f100: Double = 0.0,
    val liked: Boolean = false
)

data class VaultTrainingLog(
    val id: String,
    val exerciseId: String,
    val date: String,
    val sets: Int = 1,
    val reps: Int = 1,
    val weight: Double? = null,
    val setWeights: List<Double> = emptyList(),
    val setReps: List<Int> = emptyList(),
    val intervals: Int? = null,
    val setDurations: List<Double> = emptyList(),
    val setDistances: List<Double> = emptyList(),
    val durUnit: String? = null,
    val notes: String = "",
    val exerciseLabel: String = "\"Unknown exercise\""
) {
    val timed: Boolean get() = intervals != null || setDurations.isNotEmpty() || setDistances.isNotEmpty()
}

data class VaultDiaryMeal(
    val id: String,
    val name: String,
    val cals: Double,
    val p: Double,
    val c: Double,
    val f: Double,
    val category: String = "Snacks"
)

data class VaultDiaryDay(val water: Double = 0.0, val meals: List<VaultDiaryMeal> = emptyList())

data class VaultCustomExercise(
    val id: String,
    val name: String,
    val category: String = "",
    val target: String = "",
    val equipment: String = "",
    val description: String = ""
)

data class VaultConfig(
    val profile: Map<String, Double> = emptyMap(),
    val sex: String? = null,
    val overrides: Map<String, Double> = emptyMap(),
    val schedule: Map<Int, String> = emptyMap(),
    val liked: List<String> = emptyList(),
    val preferences: Map<String, String> = emptyMap(),
    val customExercises: List<VaultCustomExercise> = emptyList(),
    val exerciseTags: Map<String, List<String>> = emptyMap()
) {
    fun booleanPreference(key: String): Boolean? = preferences[key]?.let { it == "true" }
    fun numberPreference(key: String): Double? = preferences[key]?.toDoubleOrNull()
}
