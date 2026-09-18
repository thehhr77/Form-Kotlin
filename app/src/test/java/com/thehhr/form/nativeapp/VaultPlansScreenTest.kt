package com.thehhr.form.nativeapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
        fun scrollTo(text: String) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        }
        scrollTo("Morning strength")
        compose.onNodeWithText("Morning strength").assertIsDisplayed()
        scrollTo("Show exercises")
        compose.onNodeWithText("Show exercises").performClick()
        scrollTo("1. Sit-up")
        compose.onNodeWithText("1. Sit-up").assertIsDisplayed()
        scrollTo("3 sets × 10 reps")
        compose.onNodeWithText("3 sets × 10 reps").assertIsDisplayed()
        scrollTo("View exercise")
        compose.onNodeWithText("View exercise").performClick()
        compose.runOnIdle {
            assertEquals(exercise, opened)
            assertEquals(markdown, snapshot.file(VaultFiles.ROUTINES))
        }
    }
}
