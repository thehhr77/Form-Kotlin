package com.thehhr.form.nativeapp

import java.util.Locale

internal object MuscleMapMath {
    internal data class Entry(val muscle: String, val intensity: Double, val color: String)

    private val sides: Map<String, String> = mapOf(
        "abs" to "front", "chest" to "front", "obliques" to "front", "quadriceps" to "front",
        "biceps" to "front", "knees" to "front", "tibialis" to "front",
        "upper-chest" to "front", "lower-chest" to "front", "inner-quad" to "front", "outer-quad" to "front",
        "upper-abs" to "front", "lower-abs" to "front", "front-deltoid" to "front", "hip-flexors" to "front",
        "upper-back" to "back", "lower-back" to "back", "gluteal" to "back", "hamstring" to "back",
        "calves" to "back", "triceps" to "back", "rhomboids" to "back", "rotator-cuff" to "back",
        "rear-deltoid" to "back", "upper-trapezius" to "back", "lower-trapezius" to "back",
        "deltoids" to "both", "trapezius" to "both", "forearm" to "both", "hands" to "both",
        "feet" to "both", "ankles" to "both", "neck" to "both", "adductors" to "both"
    )

    fun side(muscle: String): String = sides[muscle] ?: "both"

    fun regionsForExercise(target: String, secondaryMuscles: List<String>): Map<String, Double> {
        val regions = linkedMapOf<String, Double>()
        val primary = StatsMath.targetToMuscle[target.trim().lowercase(Locale.ROOT)]
        if (primary != null) regions[primary] = 1.0
        secondaryMuscles.forEach { raw ->
            val muscle = StatsMath.secondaryMuscleToMap[raw.trim().lowercase(Locale.ROOT)]
            if (muscle != null && muscle != primary && !regions.containsKey(muscle)) regions[muscle] = 0.5
        }
        return regions
    }

    fun exerciseEntries(regions: Map<String, Double>, accentRgb: String): List<Entry> = regions.map { (muscle, intensity) ->
        Entry(muscle, intensity, "rgba($accentRgb,${if (intensity >= 1.0) "1" else ".5"})")
    }

    fun dashboardEntries(contributions: List<StatsMath.MuscleWeight>, accentRgb: String): List<Entry> {
        if (contributions.isEmpty()) return emptyList()
        val maxSets = maxOf(1.0, contributions.maxOf { it.sets })
        val entries = contributions.map { weight ->
            val ratio = weight.sets / maxSets
            val alpha = when {
                ratio >= 1.0 -> "1"
                ratio > 0.75 -> ".75"
                ratio > 0.5 -> ".5"
                ratio > 0.25 -> ".25"
                else -> ".10"
            }
            Entry(weight.region, ratio, "rgba($accentRgb,$alpha)")
        }.toMutableList()
        val covered = contributions.mapTo(mutableSetOf()) { it.region }
        listOf("feet", "hands").filter { it !in covered }.forEach { entries.add(Entry(it, 0.0, "#454545")) }
        return entries
    }

    fun genderFor(sex: String?): String = if (sex == "f") "female" else "male"

    private val accentNames = setOf("red", "blue", "green", "orange", "purple", "pink")

    fun accentKey(state: FormState): String = state.data.config.preferences["accent"]?.takeIf { it in accentNames } ?: "red"

    fun accentRgb(name: String): String = when (name) {
        "blue" -> "10,132,255"
        "green" -> "48,209,88"
        "orange" -> "255,159,10"
        "purple" -> "191,90,242"
        "pink" -> "255,55,95"
        else -> "255,69,58"
    }

    fun entriesJson(entries: List<Entry>): String = entries.joinToString(",", "[", "]") { entry ->
        require(entry.muscle.matches(Regex("[a-z-]+")) && entry.color.none { it == '"' || it == '\\' }) { "Invalid muscle map entry" }
        """{"muscle":"${entry.muscle}","intensity":${entry.intensity},"color":"${entry.color}"}"""
    }

    fun renderCall(gender: String, entries: List<Entry>): String {
        val showFront = entries.any { side(it.muscle) != "back" }
        val showBack = entries.any { side(it.muscle) != "front" }
        return "FormMap.render('$gender',$showFront,$showBack,${entriesJson(entries)})"
    }
}
