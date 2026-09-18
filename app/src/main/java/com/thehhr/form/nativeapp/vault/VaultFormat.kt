package com.thehhr.form.nativeapp.vault

object VaultFormat {

    fun canonicalContent(fileName: String): String = when (fileName) {
        VaultFiles.ROUTINES -> routinesTemplate()
        VaultFiles.MEALS -> mealsTemplate()
        VaultFiles.TRAINING_LOGS -> trainingLogsTemplate()
        VaultFiles.NUTRITION_DIARY -> nutritionDiaryTemplate()
        VaultFiles.CONFIG -> configTemplate()
        else -> ""
    }

    fun headingsCompatible(fileName: String, content: String): Boolean {
        val expected = VaultFiles.expectedHeadings[fileName] ?: return true
        val trimmed = content.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return stripComments(trimmed).lineSequence().firstOrNull()?.trim()?.equals(expected, ignoreCase = true) == true
    }

    fun looksEmpty(content: String): Boolean {
        return stripComments(content).isBlank()
    }

    fun stripComments(content: String): String = content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .filterNot { line ->
            val t = line.trim()
            t.isEmpty() ||
                (t.startsWith("<!--") && t.endsWith("-->")) ||
                t.startsWith("//")
        }
        .joinToString("\n")

    fun isTruthyPref(raw: String): Boolean =
        raw.trim().matches(Regex("^(true|yes|1|on)$", RegexOption.IGNORE_CASE))

    fun formatBool(value: Boolean): String = if (value) "true" else "false"

    fun normalizeBoolList(raw: String): List<String> =
        raw.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }

    fun preferenceSectionRegex(): Regex = Regex("(?im)^#{1,6}\\s*preferences\\s*$")

    private fun routinesTemplate(): String = "# Routines\n"

    private fun mealsTemplate(): String = "# Meal Library\n"

    private fun trainingLogsTemplate(): String = "# Training Log\n"

    private fun nutritionDiaryTemplate(): String = "# Nutrition Diary\n"

    private fun configTemplate(): String = buildString {
        appendLine("# Config")
        appendLine()
        appendLine("## Profile")
        appendLine("age: 22")
        appendLine("sex: m")
        appendLine("height: 178")
        appendLine("current-weight: 75.0")
        appendLine("start-weight: 75.0")
        appendLine("goal-weight: 78.0")
        appendLine("activity: 1.55")
        appendLine("strategy: 250")
        appendLine("protein-rate: 2.0")
        appendLine()
        appendLine("## Targets")
        appendLine()
        appendLine("## Weekly Schedule")
        appendLine()
        appendLine("## Preferences")
        appendLine("accent: red")
        appendLine("liked: ")
        appendLine("week-start: 1")
        appendLine("default-view: week")
        appendLine("workout-reminder: true")
        appendLine("rest-enabled: false")
        appendLine("rest-between-sets: 60")
        appendLine("rest-between-exercises: 90")
        appendLine("show-secondary-pills: false")
        appendLine("pill-routine: default")
        appendLine("pill-category: default")
        appendLine("pill-target: default")
        appendLine("pill-equipment: default")
        appendLine("pill-tags-host: equipment")
        appendLine("pill-toggles: equipment")
        appendLine()
        appendLine("## Custom Exercises")
        appendLine()
        appendLine("## Exercise Tags")
    }
}
