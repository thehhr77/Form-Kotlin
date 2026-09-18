package com.thehhr.form.nativeapp.vault

import org.junit.Assert.*
import org.junit.Test

class VaultFormatTest {
    @Test fun allDefaultFilesHaveCompatibleHeadings() {
        assertEquals(5, VaultFiles.ALL.size)
        VaultFiles.ALL.forEach { name ->
            val content = VaultFormat.canonicalContent(name)
            assertTrue(name, VaultFormat.headingsCompatible(name, content))
            assertTrue(content.endsWith("\n"))
        }
    }

    @Test fun acceptsBomCommentsAndWindowsLineEndings() {
        assertTrue(VaultFormat.headingsCompatible(VaultFiles.CONFIG, "\uFEFF\r\n<!-- backup -->\r\n// note\r\n# Config\r\n"))
    }

    @Test fun rejectsUnrelatedAndEmptyFiles() {
        assertFalse(VaultFormat.headingsCompatible(VaultFiles.CONFIG, "# Configuration of another application"))
        assertFalse(VaultFormat.headingsCompatible(VaultFiles.ROUTINES, "# Routines backup"))
        assertFalse(VaultFormat.headingsCompatible(VaultFiles.CONFIG, ""))
        assertFalse(VaultFormat.headingsCompatible(VaultFiles.MEALS, "# Config\n"))
    }

    @Test fun configRetainsEveryOriginalSection() {
        val config = VaultFormat.canonicalContent(VaultFiles.CONFIG)
        listOf("Profile", "Targets", "Weekly Schedule", "Preferences", "Custom Exercises", "Exercise Tags").forEach {
            assertTrue(config.contains("## $it\n"))
        }
        assertTrue(config.contains("liked: \n"))
        assertTrue(config.contains("rest-between-sets: 60\n"))
        assertTrue(config.contains("rest-between-exercises: 90\n"))
    }

    @Test fun freshVaultHasNoInventedUserRecords() {
        assertEquals("# Routines\n", VaultFormat.canonicalContent(VaultFiles.ROUTINES))
        assertEquals("# Meal Library\n", VaultFormat.canonicalContent(VaultFiles.MEALS))
        assertEquals("# Training Log\n", VaultFormat.canonicalContent(VaultFiles.TRAINING_LOGS))
        assertEquals("# Nutrition Diary\n", VaultFormat.canonicalContent(VaultFiles.NUTRITION_DIARY))
    }
}
