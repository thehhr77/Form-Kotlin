package com.thehhr.form.nativeapp

import com.thehhr.form.nativeapp.vault.VaultDiaryMeal
import com.thehhr.form.nativeapp.vault.VaultMeal
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

object FuelMath {
    val DIARY_CATEGORIES = listOf("Breakfast", "Lunch", "Dinner", "Snacks")

    fun jsRound(value: Double): Double = floor(value + 0.5)

    fun round1(value: Double): Double = jsRound(value * 10.0) / 10.0

    fun kcalFromMacros(protein: Double, carbohydrate: Double, fat: Double): Double =
        jsRound(protein * 4.0 + carbohydrate * 4.0 + fat * 9.0)

    fun parseIntLike(text: String): Long? {
        val match = Regex("^[+-]?[0-9]+").find(text.trim()) ?: return null
        return match.value.toLongOrNull()
    }

    fun fallbackKcal(kcal: Long?, protein: Double, carbohydrate: Double, fat: Double): Double =
        if (kcal != null && kcal > 0) kcal.toDouble() else kcalFromMacros(protein, carbohydrate, fat)

    fun mealKcalFallback(kcalText: String, protein: Double, carbohydrate: Double, fat: Double): Double =
        fallbackKcal(parseIntLike(kcalText)?.takeIf { it in 0..100000L }, protein, carbohydrate, fat)

    fun toFixed1(value: Double): String = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString()

    fun fuelNumber(value: Double): String =
        if (value.isFinite()) BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() else "Unavailable"

    private val groupedFormat = DecimalFormat("#,##0.###", DecimalFormatSymbols(Locale.US))

    fun groupedNumber(value: Double): String = if (value.isFinite()) groupedFormat.format(value) else "Unavailable"

    data class FuelBalance(val remaining: Double, val left: Boolean, val magnitudeText: String)

    fun balance(effectiveCalories: Double, consumedCalories: Double): FuelBalance {
        val remaining = effectiveCalories - consumedCalories
        val magnitude = if (remaining.isFinite()) groupedNumber(abs(remaining)) else "Unavailable"
        return FuelBalance(remaining, remaining >= 0.0, magnitude)
    }

    fun cappedPercent(current: Double, goal: Double): Double = when {
        !current.isFinite() || !goal.isFinite() -> 0.0
        goal <= 0.0 -> if (current > 0.0) 100.0 else 0.0
        else -> (current / goal * 100.0).coerceIn(0.0, 100.0)
    }

    fun macroCurrentGoalText(current: Double, goal: Double): String =
        "${jsRound(current).toLong()} / ${fuelNumber(goal)}g"

    fun bmi(currentWeightKg: Double, heightCm: Double): String? {
        if (!currentWeightKg.isFinite() || !heightCm.isFinite() || heightCm <= 0.0) return null
        val meters = heightCm / 100.0
        return BigDecimal.valueOf(currentWeightKg / (meters * meters)).setScale(1, RoundingMode.HALF_UP).toPlainString()
    }

    fun weightText(kg: Double): String =
        if (kg.isFinite()) BigDecimal.valueOf(kg).setScale(1, RoundingMode.HALF_UP).toPlainString() else "Unavailable"

    fun weightGoalPercent(startWeightKg: Double, goalWeightKg: Double, currentWeightKg: Double): Double = when {
        startWeightKg > goalWeightKg ->
            clampPercent(jsRound((startWeightKg - currentWeightKg) / (startWeightKg - goalWeightKg) * 100.0))
        startWeightKg < goalWeightKg ->
            clampPercent(jsRound((currentWeightKg - startWeightKg) / (goalWeightKg - startWeightKg) * 100.0))
        else -> 100.0
    }

    private fun clampPercent(value: Double): Double = if (value.isFinite()) value.coerceIn(0.0, 100.0) else 0.0

    fun weightGoalMessage(goalWeightKg: Double, currentWeightKg: Double): String {
        if (!goalWeightKg.isFinite() || !currentWeightKg.isFinite()) return "Unavailable"
        val diff = BigDecimal.valueOf(abs(goalWeightKg - currentWeightKg)).setScale(1, RoundingMode.HALF_UP)
        return when {
            diff.compareTo(BigDecimal.ZERO) == 0 -> "Goal Achieved!"
            currentWeightKg > goalWeightKg -> "${diff.toPlainString()} kg to lose"
            else -> "${diff.toPlainString()} kg to gain"
        }
    }

    fun nextLogSlot(dayMeals: List<VaultDiaryMeal>): String =
        DIARY_CATEGORIES.firstOrNull { slot -> dayMeals.none { it.category == slot } } ?: "Snacks"

    fun groupedDiaryMeals(meals: List<VaultDiaryMeal>): List<Pair<String, List<VaultDiaryMeal>>> {
        val webGroups = DIARY_CATEGORIES.mapNotNull { category ->
            val entries = meals.filter { it.category == category }
            if (entries.isEmpty()) null else category to entries
        }
        val extraGroups = meals.filterNot { it.category in DIARY_CATEGORIES }.groupBy { it.category }
        return webGroups + extraGroups.map { (category, entries) -> category to entries }
    }

    fun diaryEntryName(mealName: String, grams: Double): String =
        "${mealName.substringBefore(',')} (${fuelNumber(grams)}g)"

    fun customFoodName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name.length > 40) return null
        if (name.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
        return name
    }

    fun mealPickerOrder(): Comparator<VaultMeal> =
        compareByDescending<VaultMeal> { it.liked }.thenBy { it.name.lowercase(Locale.ROOT) }

    fun numericDiaryId(existingIds: Set<String>, createdAtMillis: Long): String {
        var candidate = createdAtMillis.coerceIn(0L, 9007199254740991L).toString()
        while (candidate in existingIds) candidate = ((candidate.toLongOrNull() ?: return candidate) + 1L).toString()
        return candidate
    }
}
