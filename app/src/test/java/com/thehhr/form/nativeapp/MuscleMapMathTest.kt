package com.thehhr.form.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuscleMapMathTest {
    @Test fun regionsMirrorWebModalRules() {
        val regions = MuscleMapMath.regionsForExercise("pectorals", listOf("rear deltoids", "Triceps", "trapezius"))
        assertEquals(linkedMapOf("chest" to 1.0, "deltoids" to 0.5, "triceps" to 0.5, "trapezius" to 0.5), regions)
        assertEquals(emptyMap<String, Double>(), MuscleMapMath.regionsForExercise("cardiovascular system", emptyList()))
    }

    @Test fun regionsSkipSecondariesMatchingPrimary() {
        val regions = MuscleMapMath.regionsForExercise("quads", listOf("quadriceps", "hip flexors"))
        assertEquals(linkedMapOf("quadriceps" to 1.0), regions)
    }

    @Test fun exerciseEntriesUseFlatHalfAlpha() {
        val entries = MuscleMapMath.exerciseEntries(linkedMapOf("chest" to 1.0, "deltoids" to 0.5), "255,69,58")
        assertEquals("rgba(255,69,58,1)", entries[0].color)
        assertEquals("rgba(255,69,58,.5)", entries[1].color)
    }

    @Test fun sideTableMatchesWeb() {
        assertEquals("front", MuscleMapMath.side("abs"))
        assertEquals("back", MuscleMapMath.side("upper-back"))
        assertEquals("both", MuscleMapMath.side("deltoids"))
        assertEquals("both", MuscleMapMath.side("unknown-region"))
    }

    @Test fun dashboardEntriesTierAndPlaceholders() {
        val contributions = listOf(
            StatsMath.MuscleWeight("chest", 8.0),
            StatsMath.MuscleWeight("upper-back", 6.4),
            StatsMath.MuscleWeight("biceps", 4.8),
            StatsMath.MuscleWeight("abs", 1.0)
        )
        val entries = MuscleMapMath.dashboardEntries(contributions, "10,132,255")
        assertEquals(listOf("chest", "upper-back", "biceps", "abs", "feet", "hands"), entries.map { it.muscle })
        assertEquals("rgba(10,132,255,1)", entries[0].color)
        assertEquals("rgba(10,132,255,.75)", entries[1].color)
        assertEquals("rgba(10,132,255,.5)", entries[2].color)
        assertEquals("rgba(10,132,255,.10)", entries[3].color)
        assertEquals("#454545", entries[4].color)
    }

    @Test fun dashboardEntriesSkipCoveredPlaceholders() {
        val entries = MuscleMapMath.dashboardEntries(listOf(StatsMath.MuscleWeight("feet", 3.0)), "255,69,58")
        assertEquals(listOf("feet", "hands"), entries.map { it.muscle })
        assertTrue(entries.none { it.color == "#454545" && it.muscle == "feet" })
    }

    @Test fun dashboardEntriesEmpty() {
        assertEquals(emptyList<MuscleMapMath.Entry>(), MuscleMapMath.dashboardEntries(emptyList(), "255,69,58"))
    }

    @Test fun genderAndAccentMirrorWeb() {
        assertEquals("female", MuscleMapMath.genderFor("f"))
        assertEquals("male", MuscleMapMath.genderFor("m"))
        assertEquals("male", MuscleMapMath.genderFor(null))
        assertEquals("255,69,58", MuscleMapMath.accentRgb("red"))
        assertEquals("48,209,88", MuscleMapMath.accentRgb("green"))
    }

    @Test fun renderCallShapesJavascriptArguments() {
        val call = MuscleMapMath.renderCall("female", MuscleMapMath.exerciseEntries(MuscleMapMath.regionsForExercise("pectorals", listOf("triceps")), "255,69,58"))
        assertEquals(true, call.startsWith("FormMap.render('female',true,true,"))
        assertTrue(call.contains("\"muscle\":\"chest\""))
        assertTrue(call.endsWith("])"))
    }
}
