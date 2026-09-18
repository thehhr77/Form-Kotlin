package com.thehhr.form.nativeapp.vault

object VaultFiles {
    const val ROUTINES = "routines.md"
    const val MEALS = "meals.md"
    const val TRAINING_LOGS = "training_logs.md"
    const val NUTRITION_DIARY = "nutrition_diary.md"
    const val CONFIG = "config.md"

    val ALL: List<String> = listOf(ROUTINES, MEALS, TRAINING_LOGS, NUTRITION_DIARY, CONFIG)

    fun all(): List<String> = ALL

    const val HEAD_ROUTINES = "# Routines"
    const val HEAD_MEALS = "# Meal Library"
    const val HEAD_TRAINING = "# Training Log"
    const val HEAD_NUTRITION = "# Nutrition Diary"
    const val HEAD_CONFIG = "# Config"

    val expectedHeadings: Map<String, String> = mapOf(
        ROUTINES to HEAD_ROUTINES,
        MEALS to HEAD_MEALS,
        TRAINING_LOGS to HEAD_TRAINING,
        NUTRITION_DIARY to HEAD_NUTRITION,
        CONFIG to HEAD_CONFIG
    )

    const val MAX_FILE_BYTES = 4L * 1024 * 1024

    fun isKnownVaultFile(name: String): Boolean = ALL.contains(name)
}
