package com.thehhr.form.nativeapp

import com.thehhr.form.nativeapp.vault.VaultDiaryMeal
import com.thehhr.form.nativeapp.vault.VaultMeal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelMathTest {

    private fun diaryMeal(
        id: String,
        name: String,
        cals: Double,
        p: Double = 0.0,
        c: Double = 0.0,
        f: Double = 0.0,
        category: String = "Snacks"
    ) = VaultDiaryMeal(id = id, name = name, cals = cals, p = p, c = c, f = f, category = category)

    @Test fun kcalFromMacrosMirrorsJavascriptRound() {
        assertEquals(165.0, FuelMath.kcalFromMacros(20.0, 10.0, 5.0), 0.0)
        assertEquals(0.0, FuelMath.kcalFromMacros(0.0, 0.0, 0.0), 0.0)
        assertEquals(10.0, FuelMath.kcalFromMacros(2.55, 0.0, 0.0), 0.0)
        assertEquals(2.0, FuelMath.kcalFromMacros(0.5, 0.0, 0.0), 0.0)
        assertEquals(2.0, FuelMath.kcalFromMacros(0.375, 0.0, 0.0), 0.0)
        assertEquals(9.0, FuelMath.kcalFromMacros(0.0, 0.0, 1.0), 0.0)
    }

    @Test fun kcalFallbackRecomputesFromMacrosWhenInvalidOrNonPositive() {
        assertEquals(165.0, FuelMath.fallbackKcal(null, 20.0, 10.0, 5.0), 0.0)
        assertEquals(165.0, FuelMath.fallbackKcal(0L, 20.0, 10.0, 5.0), 0.0)
        assertEquals(165.0, FuelMath.fallbackKcal(-3L, 20.0, 10.0, 5.0), 0.0)
        assertEquals(500.0, FuelMath.fallbackKcal(500L, 20.0, 10.0, 5.0), 0.0)
    }

    @Test fun parseIntLikeMirrorsJavascriptParseInt() {
        assertEquals(12L, FuelMath.parseIntLike("12"))
        assertEquals(12L, FuelMath.parseIntLike("12.9"))
        assertEquals(12L, FuelMath.parseIntLike(" 12abc "))
        assertEquals(-5L, FuelMath.parseIntLike("-5"))
        assertEquals(7L, FuelMath.parseIntLike("+7"))
        assertNull(FuelMath.parseIntLike("abc"))
        assertNull(FuelMath.parseIntLike(""))
        assertNull(FuelMath.parseIntLike("9".repeat(30)))
    }

    @Test fun mealKcalFallbackReadsLeadingIntegerAndClampsInputRange() {
        assertEquals(165.0, FuelMath.mealKcalFallback("165", 20.0, 10.0, 5.0), 0.0)
        assertEquals(12.0, FuelMath.mealKcalFallback("12.9", 20.0, 10.0, 5.0), 0.0)
        assertEquals(12.0, FuelMath.mealKcalFallback(" 12abc ", 20.0, 10.0, 5.0), 0.0)
        assertEquals(7.0, FuelMath.mealKcalFallback("+7", 20.0, 10.0, 5.0), 0.0)
        assertEquals(100000.0, FuelMath.mealKcalFallback("100000", 20.0, 10.0, 5.0), 0.0)
        assertEquals(165.0, FuelMath.mealKcalFallback("", 20.0, 10.0, 5.0), 0.0)
        assertEquals(165.0, FuelMath.mealKcalFallback("abc", 20.0, 10.0, 5.0), 0.0)
        assertEquals(165.0, FuelMath.mealKcalFallback("0", 20.0, 10.0, 5.0), 0.0)
        assertEquals(165.0, FuelMath.mealKcalFallback("-4", 20.0, 10.0, 5.0), 0.0)
        assertEquals(165.0, FuelMath.mealKcalFallback("100001", 20.0, 10.0, 5.0), 0.0)
        assertEquals(165.0, FuelMath.mealKcalFallback("99999999999999999999", 20.0, 10.0, 5.0), 0.0)
    }

    @Test fun balanceUsesMagnitudeWithLeftAndOverWording() {
        val left = FuelMath.balance(2975.0, 1500.0)
        assertTrue(left.left)
        assertEquals("1,475", left.magnitudeText)
        val over = FuelMath.balance(2000.0, 2500.0)
        assertFalse(over.left)
        assertEquals("500", over.magnitudeText)
        val even = FuelMath.balance(2000.0, 2000.0)
        assertTrue(even.left)
        assertEquals("0", even.magnitudeText)
        assertEquals("123.5", FuelMath.balance(2000.0, 1876.5).magnitudeText)
    }

    @Test fun cappedPercentClampsMacroBars() {
        assertEquals(25.0, FuelMath.cappedPercent(50.0, 200.0), 0.0)
        assertEquals(100.0, FuelMath.cappedPercent(300.0, 200.0), 0.0)
        assertEquals(0.0, FuelMath.cappedPercent(0.0, 0.0), 0.0)
        assertEquals(100.0, FuelMath.cappedPercent(50.0, 0.0), 0.0)
        assertEquals(0.0, FuelMath.cappedPercent(0.0, 150.0), 0.0)
    }

    @Test fun macroCurrentGoalTextRoundsCurrentAndKeepsGoal() {
        assertEquals("123 / 150g", FuelMath.macroCurrentGoalText(123.4, 150.0))
        assertEquals("0 / 411g", FuelMath.macroCurrentGoalText(0.0, 411.0))
        assertEquals("10 / 62.5g", FuelMath.macroCurrentGoalText(10.2, 62.5))
    }

    @Test fun bmiMatchesWebOneDecimalComputation() {
        assertEquals("23.7", FuelMath.bmi(75.0, 178.0))
        assertEquals("22.2", FuelMath.bmi(50.0, 150.0))
        assertEquals("30.0", FuelMath.bmi(120.0, 200.0))
        assertNull(FuelMath.bmi(75.0, 0.0))
        assertNull(FuelMath.bmi(Double.NaN, 178.0))
    }

    @Test fun weightGoalPercentCoversGainLossEqualAndClamps() {
        assertEquals(50.0, FuelMath.weightGoalPercent(80.0, 75.0, 77.5), 0.0)
        assertEquals(48.0, FuelMath.weightGoalPercent(80.0, 75.0, 77.6), 0.0)
        assertEquals(50.0, FuelMath.weightGoalPercent(75.0, 78.0, 76.5), 0.0)
        assertEquals(47.0, FuelMath.weightGoalPercent(75.0, 78.0, 76.4), 0.0)
        assertEquals(100.0, FuelMath.weightGoalPercent(80.0, 80.0, 90.0), 0.0)
        assertEquals(100.0, FuelMath.weightGoalPercent(80.0, 80.0, 70.0), 0.0)
        assertEquals(0.0, FuelMath.weightGoalPercent(80.0, 75.0, 85.0), 0.0)
        assertEquals(100.0, FuelMath.weightGoalPercent(80.0, 75.0, 70.0), 0.0)
        assertEquals(0.0, FuelMath.weightGoalPercent(75.0, 78.0, 70.0), 0.0)
        assertEquals(100.0, FuelMath.weightGoalPercent(75.0, 78.0, 80.0), 0.0)
        assertEquals(47.0, FuelMath.weightGoalPercent(80.0, 75.0, 77.65), 0.0)
    }

    @Test fun weightGoalMessageUsesGainLoseAndAchievedBranches() {
        assertEquals("3.0 kg to gain", FuelMath.weightGoalMessage(78.0, 75.0))
        assertEquals("5.0 kg to lose", FuelMath.weightGoalMessage(75.0, 80.0))
        assertEquals("Goal Achieved!", FuelMath.weightGoalMessage(78.0, 78.0))
        assertEquals("Goal Achieved!", FuelMath.weightGoalMessage(78.0, 77.96))
        assertEquals("0.1 kg to gain", FuelMath.weightGoalMessage(78.0, 77.9))
    }

    @Test fun nextLogSlotPicksFirstEmptyWebCategory() {
        assertEquals("Breakfast", FuelMath.nextLogSlot(emptyList()))
        assertEquals(
            "Lunch",
            FuelMath.nextLogSlot(listOf(diaryMeal("1", "a", 100.0, category = "Breakfast")))
        )
        assertEquals(
            "Dinner",
            FuelMath.nextLogSlot(
                listOf(
                    diaryMeal("1", "a", 100.0, category = "Breakfast"),
                    diaryMeal("2", "b", 100.0, category = "Lunch")
                )
            )
        )
        assertEquals(
            "Snacks",
            FuelMath.nextLogSlot(
                listOf(
                    diaryMeal("1", "a", 100.0, category = "Breakfast"),
                    diaryMeal("2", "b", 100.0, category = "Lunch"),
                    diaryMeal("3", "c", 100.0, category = "Dinner")
                )
            )
        )
        assertEquals(
            "Snacks",
            FuelMath.nextLogSlot(
                FuelMath.DIARY_CATEGORIES.mapIndexed { index, category -> diaryMeal(index.toString(), "m", 1.0, category = category) }
            )
        )
    }

    @Test fun groupedDiaryMealsUsesWebCategoryOrderAndSumsTotals() {
        val meals = listOf(
            diaryMeal("1", "Eggs", 200.0, category = "Breakfast"),
            diaryMeal("2", "Chips", 150.0, category = "Snacks"),
            diaryMeal("3", "Chicken", 400.0, category = "Lunch"),
            diaryMeal("4", "Oats", 100.0, category = "Breakfast"),
            diaryMeal("5", "Legacy", 90.0, category = "Other"),
            diaryMeal("6", "Steak", 500.0, category = "Dinner")
        )
        val groups = FuelMath.groupedDiaryMeals(meals)
        assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snacks", "Other"), groups.map { it.first })
        assertEquals(300.0, groups.first().second.sumOf { it.cals }, 0.0)
        assertEquals(400.0, groups[1].second.sumOf { it.cals }, 0.0)
        assertEquals(500.0, groups[2].second.sumOf { it.cals }, 0.0)
        assertEquals(150.0, groups[3].second.sumOf { it.cals }, 0.0)
        assertEquals(90.0, groups[4].second.sumOf { it.cals }, 0.0)
        assertEquals(emptyList<Pair<String, List<VaultDiaryMeal>>>(), FuelMath.groupedDiaryMeals(emptyList()))
    }

    @Test fun diaryEntryNameUsesNameBeforeFirstCommaPlusGrams() {
        assertEquals("Oatmeal (250g)", FuelMath.diaryEntryName("Oatmeal, rolled oats", 250.0))
        assertEquals("Soup (62.5g)", FuelMath.diaryEntryName("Soup", 62.5))
        assertEquals("Rice (100g)", FuelMath.diaryEntryName("Rice", 100.0))
        assertEquals("No comma (75g)", FuelMath.diaryEntryName("No comma", 75.0))
    }

    @Test fun customFoodNameValidatesLengthTrimAndSingleLine() {
        assertEquals("Chicken rice", FuelMath.customFoodName("  Chicken rice "))
        assertEquals("A", FuelMath.customFoodName("A"))
        assertNull(FuelMath.customFoodName("   "))
        assertNull(FuelMath.customFoodName("a\nb"))
        assertNull(FuelMath.customFoodName("a\u2028b"))
        assertNull(FuelMath.customFoodName("a".repeat(41)))
        assertEquals("a".repeat(40), FuelMath.customFoodName("a".repeat(40)))
    }

    @Test fun mealPickerOrderPutsLikedMealsFirstThenName() {
        val meals = listOf(
            VaultMeal("1", "banana", liked = true),
            VaultMeal("2", "Apple"),
            VaultMeal("3", "Cherry", liked = true),
            VaultMeal("4", "apple two"),
            VaultMeal("5", "cherry pie", liked = true)
        )
        val ordered = meals.sortedWith(FuelMath.mealPickerOrder())
        assertEquals(listOf("1", "3", "5", "2", "4"), ordered.map { it.id })
    }

    @Test fun numericDiaryIdFollowsWebSafeNumericScheme() {
        assertEquals("1730000000000", FuelMath.numericDiaryId(emptySet(), 1730000000000L))
        assertEquals(
            "1730000000001",
            FuelMath.numericDiaryId(setOf("1730000000000"), 1730000000000L)
        )
        assertEquals(
            "1730000000002",
            FuelMath.numericDiaryId(setOf("1730000000000", "1730000000001"), 1730000000000L)
        )
        assertTrue(FuelMath.numericDiaryId(emptySet(), System.currentTimeMillis()).matches(Regex("[0-9]{1,16}")))
    }

    @Test fun round1MirrorsWebOneDecimalSnapshotRounding() {
        assertEquals(20.0, FuelMath.round1(20.0), 0.0)
        assertEquals(20.5, FuelMath.round1(20.47), 0.0)
        assertEquals(62.5, FuelMath.round1(62.5), 0.0)
        assertEquals(1.1, FuelMath.round1(1.05), 0.0)
        assertEquals(0.0, FuelMath.round1(0.04), 0.0)
        assertEquals(100000.0, FuelMath.round1(99999.97), 0.0)
    }

    @Test fun toFixed1AndFuelNumberMatchWebDisplay() {
        assertEquals("12.0", FuelMath.toFixed1(12.0))
        assertEquals("2.6", FuelMath.toFixed1(2.55))
        assertEquals("250", FuelMath.fuelNumber(250.0))
        assertEquals("62.5", FuelMath.fuelNumber(62.5))
        assertEquals("0", FuelMath.fuelNumber(0.0))
        assertEquals("Unavailable", FuelMath.fuelNumber(Double.NaN))
    }
}
