package com.thehhr.form.nativeapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.thehhr.form.nativeapp.vault.VaultFiles
import com.thehhr.form.nativeapp.vault.VaultFormat
import com.thehhr.form.nativeapp.vault.VaultSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultPlansScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedRoutineCanBeExpandedAndItsExerciseOpened() {
        val markdown = "# Routines\n\n## Morning strength\n- id: morning\n0001 3 * 10\n"
        val snapshot = VaultSnapshot("Fixture", "content://fixture/tree/vault", VaultFiles.ALL.associateWith(VaultFormat::canonicalContent) + (VaultFiles.ROUTINES to markdown), emptyList())
        val exercise = Exercise("0001", "Sit-up", "waist", "abs", "body weight", "", "", emptyList(), "")
        val projection = VaultProjection.from(snapshot, listOf(exercise))
        val state = FormState(exercises = projection.exercises, vault = snapshot, data = projection.data, loading = false, vaultBusy = false)
        var opened: Exercise? = null
        compose.setContent {
            MaterialTheme { VaultPlansScreen(state, onOpenExercise = { opened = it }, onReload = {}) }
        }
        compose.onNodeWithText("Morning strength").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Show exercises").performScrollTo().performClick()
        compose.onNodeWithText("1. Sit-up").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3 sets × 10 reps").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View exercise").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(exercise, opened)
            assertEquals(markdown, snapshot.file(VaultFiles.ROUTINES))
        }
    }
}
