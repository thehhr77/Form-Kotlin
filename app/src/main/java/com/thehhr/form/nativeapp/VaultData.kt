package com.thehhr.form.nativeapp

import com.thehhr.form.nativeapp.vault.ConfigCodec
import com.thehhr.form.nativeapp.vault.VaultCodec
import com.thehhr.form.nativeapp.vault.VaultConfig
import com.thehhr.form.nativeapp.vault.VaultFiles
import com.thehhr.form.nativeapp.vault.VaultMeal
import com.thehhr.form.nativeapp.vault.VaultRoutine
import com.thehhr.form.nativeapp.vault.VaultSnapshot

data class Exercise(
    val id: String,
    val name: String,
    val category: String,
    val target: String,
    val equipment: String,
    val image: String,
    val animation: String,
    val instructions: List<String>,
    val attribution: String,
    val muscleGroup: String = "",
    val secondaryMuscles: List<String> = emptyList()
)

data class VaultData(
    val routines: List<VaultRoutine> = emptyList(),
    val meals: List<VaultMeal> = emptyList(),
    val config: VaultConfig = VaultConfig(),
    val diagnostics: List<String> = emptyList(),
    val missingFiles: Set<String> = emptySet(),
    val trainingLogs: List<com.thehhr.form.nativeapp.vault.VaultTrainingLog> = emptyList(),
    val nutritionDiary: Map<String, com.thehhr.form.nativeapp.vault.VaultDiaryDay> = emptyMap()
) {
    companion object {
        fun from(snapshot: VaultSnapshot): VaultData {
            val diagnostics = snapshot.warnings.toMutableList()
            val configDocument = snapshot.file(VaultFiles.CONFIG)?.let(VaultCodec::parseConfig)
            val routineDocument = snapshot.file(VaultFiles.ROUTINES)?.let(VaultCodec::parseRoutines)
            val mealDocument = snapshot.file(VaultFiles.MEALS)?.let(VaultCodec::parseMeals)
            val logDocument = snapshot.file(VaultFiles.TRAINING_LOGS)?.let(VaultCodec::parseTrainingLogs)
            val diaryDocument = snapshot.file(VaultFiles.NUTRITION_DIARY)?.let(VaultCodec::parseNutritionDiary)
            listOf(configDocument, routineDocument, mealDocument, logDocument, diaryDocument).filterNotNull().forEach { document ->
                diagnostics += document.diagnostics.map { "${document.kind.fileName}:${it.line}: ${it.message}" }
            }
            var config = configDocument?.value ?: VaultConfig()
            snapshot.file(VaultFiles.CONFIG)?.let { source ->
                val sex = runCatching { ConfigCodec.configFieldValue(source, "Profile", "sex") }
                sex.exceptionOrNull()?.let { diagnostics += "config.md: ${it.message}" }
                config = config.copy(sex = sex.getOrNull()?.takeIf { it == "m" || it == "f" } ?: config.sex)
            }
            val routines = routineDocument?.value.orEmpty()
            val meals = mealDocument?.value.orEmpty()
            routines.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach { diagnostics += "Duplicate routine ID: $it. Records are displayed without changing the file." }
            meals.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach { diagnostics += "Duplicate meal ID: $it. Records are displayed without changing the file." }
            return VaultData(routines, meals, config, diagnostics, VaultFiles.ALL.filterNot(snapshot.files::containsKey).toSet(), logDocument?.value.orEmpty(), diaryDocument?.value.orEmpty())
        }
    }
}

data class VaultProjection(val data: VaultData, val exercises: List<Exercise>) {
    companion object {
        fun from(snapshot: VaultSnapshot?, builtIns: List<Exercise>): VaultProjection {
            if (snapshot == null) return VaultProjection(VaultData(), builtIns)
            val data = VaultData.from(snapshot)
            val catalog = builtIns.associateByTo(linkedMapOf()) { it.id }
            val diagnostics = data.diagnostics.toMutableList()
            data.config.customExercises.forEach { custom ->
                if (catalog.containsKey(custom.id)) {
                    diagnostics += "Duplicate exercise ID: ${custom.id}. The first catalog entry is retained."
                } else {
                    catalog[custom.id] = Exercise(custom.id, custom.name, custom.category, custom.target, custom.equipment, "", "", custom.description.lines().filter(String::isNotBlank), "")
                }
            }
            data.routines.flatMap { it.items }.map { it.exerciseId }.distinct().filterNot(catalog::containsKey).forEach {
                diagnostics += "Routine refers to unavailable exercise: $it. Its reference is retained."
            }
            return VaultProjection(data.copy(diagnostics = diagnostics), catalog.values.toList())
        }
    }
}
