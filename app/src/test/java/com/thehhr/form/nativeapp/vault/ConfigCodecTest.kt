package com.thehhr.form.nativeapp.vault

import org.junit.Assert.*
import org.junit.Test

class ConfigCodecTest {
    private val config = VaultFormat.canonicalContent(VaultFiles.CONFIG)

    @Test fun preservesUnknownSectionsAndFormatting() {
        val source = config.replace("accent: red", "  - accent: red  ").replace("\n", "\r\n") + "\r\n## Future\r\nmystery: keep me\r\n"
        val result = ConfigCodec.patchPreference(source, source, "accent", "blue")
        assertEquals(source.replace("accent: red", "accent: blue"), result)
    }

    @Test fun mergesUnrelatedExternalChanges() {
        val external = config.replace("age: 22", "age: 30")
        assertEquals(external.replace("accent: red", "accent: green"), ConfigCodec.patchPreference(config, external, "accent", "green"))
    }

    @Test(expected = VaultConflictException::class) fun rejectsCompetingExternalChange() {
        ConfigCodec.patchPreference(config, config.replace("accent: red", "accent: pink"), "accent", "blue")
    }

    @Test fun sameConcurrentValueIsNoOp() {
        val external = config.replace("accent: red", "accent: blue")
        assertEquals(external, ConfigCodec.patchPreference(config, external, "accent", "blue"))
    }

    @Test fun addsMissingFieldInsidePreferences() {
        val result = ConfigCodec.patchPreference(config, config, "native-theme", "system")
        assertEquals("system", ConfigCodec.parse(result).preference("native-theme"))
        assertTrue(result.contains("native-theme: system\n## Custom Exercises"))
    }

    @Test fun emptyFieldDoesNotConsumeNextLine() {
        val result = ConfigCodec.patchPreference(config, config, "liked", "0001, c-abc")
        assertEquals(config.replace("liked: \n", "liked: 0001, c-abc\n"), result)
    }

    @Test fun preservesZeroPaddedAndCustomIds() {
        val source = config + "- id: 0001\n  tags: warmup, Warmup, legs\n- id: c-abc\n  tags: custom\n"
        assertEquals(listOf("warmup", "legs"), ConfigCodec.parse(source).exerciseTags()["0001"])
        assertEquals(listOf("custom"), ConfigCodec.parse(source).exerciseTags()["c-abc"])
    }

    @Test fun customExerciseFieldsRetainUnknownMetadata() {
        val source = config.replace("## Custom Exercises\n", "## Custom Exercises\n- name: Press\n  id: c-001\n  future: untouched\n")
        assertEquals("untouched", ConfigCodec.parse(source).customExercises().single()["future"])
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicatePreference() {
        val source = config.replace("accent: red", "accent: red\naccent: pink")
        ConfigCodec.patchPreference(source, source, "accent", "blue")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsInjectedLines() {
        ConfigCodec.patchPreference(config, config, "accent", "blue\nliked: 0001")
    }

    @Test fun likesPreserveUnknownIdsAndZeroPadding() {
        val source = config.replace("liked: ", "liked: 0001, 01234, c-old_id, future:id, 123456, future:id")
        val value = ConfigCodec.likedPreferenceValue(source, "c-new-id", true)
        assertEquals("0001, 01234, c-old_id, future:id, 123456, future:id, c-new-id", value)
        assertEquals(source.replace("123456, future:id", "123456, future:id, c-new-id"), ConfigCodec.patchPreference(source, source, "liked", value))
    }

    @Test fun unlikesRemoveOnlyTheRequestedIdIncludingDuplicates() {
        val source = config.replace("liked: ", "liked: 0001, #0001, 00010, c-0001, future:id, 0001")
        assertEquals("00010, c-0001, future:id", ConfigCodec.likedPreferenceValue(source, "0001", false))
    }

    @Test fun repeatedLikesAndUnlikesPreserveValueFormatting() {
        val source = config.replace("liked: ", "liked: #0001,  c-test , future:id")
        val original = ConfigCodec.parse(source).preference("liked")
        assertEquals(original, ConfigCodec.likedPreferenceValue(source, "0001", true))
        assertEquals(original, ConfigCodec.likedPreferenceValue(source, "c-test", true))
        assertEquals(original, ConfigCodec.likedPreferenceValue(source, "01234", false))
    }

    @Test fun acceptsFourFiveDigitAndCustomIds() {
        for (id in listOf("0001", "01234", "c-a", "c-A_1--")) {
            assertEquals(id, ConfigCodec.likedPreferenceValue(config, id, true))
        }
    }

    @Test fun rejectsInvalidLikeAndUnlikeIds() {
        for (id in listOf("", "123", "123456", "c-", "C-test", "#0001", " 0001", "0001 ", "c-a,b", "c-a\n", "c-a\u0000", "１２３４")) {
            for (liked in listOf(true, false)) {
                assertThrows(IllegalArgumentException::class.java) { ConfigCodec.likedPreferenceValue(config, id, liked) }
            }
        }
    }

    @Test fun likesPreserveCrlfAndUnrelatedContent() {
        val source = config.replace("liked: ", "  - liked: 0001, future:id  ")
            .replace("\n", "\r\n") + "\r\n## Future\r\nopaque: untouched\r\n"
        val value = ConfigCodec.likedPreferenceValue(source, "01234", true)
        assertEquals(source.replace("0001, future:id", "0001, future:id, 01234"), ConfigCodec.patchPreference(source, source, "liked", value))
    }

    @Test fun unlikingLastIdPreservesCrlfAndFollowingField() {
        val source = config.replace("liked: ", "liked: 0001").replace("\n", "\r\n")
        val value = ConfigCodec.likedPreferenceValue(source, "0001", false)
        assertEquals("", value)
        assertEquals(source.replace("liked: 0001", "liked: "), ConfigCodec.patchPreference(source, source, "liked", value))
    }

    @Test fun insertsMissingLikesUsingExistingCrlf() {
        val source = config.replace("liked: \n", "").replace("\n", "\r\n")
        val result = ConfigCodec.patchPreference(source, source, "liked", ConfigCodec.likedPreferenceValue(source, "0001", true))
        assertEquals("0001", ConfigCodec.parse(result).preference("liked"))
        assertTrue(result.contains("liked: 0001\r\n## Custom Exercises"))
        assertEquals(source, result.replace("liked: 0001\r\n", ""))
    }

    @Test fun likesMergeUnrelatedExternalFieldsAndSections() {
        val base = config.replace("liked: ", "liked: 0001, future:id")
        val external = base.replace("accent: red", "accent: pink").replace("age: 22", "age: 30") + "\n## Future\nvalue: keep\n"
        val value = ConfigCodec.likedPreferenceValue(base, "c-new", true)
        assertEquals(external.replace("liked: 0001, future:id", "liked: 0001, future:id, c-new"), ConfigCodec.patchPreference(base, external, "liked", value))
    }

    @Test(expected = VaultConflictException::class) fun rejectsConcurrentLikesInsteadOfBuildingFromExternalList() {
        val base = config.replace("liked: ", "liked: 0001, future:id")
        val external = base.replace("liked: 0001, future:id", "liked: 0001, future:id, 01234")
        val value = ConfigCodec.likedPreferenceValue(base, "c-new", true)
        ConfigCodec.patchPreference(base, external, "liked", value)
    }

    @Test(expected = VaultConflictException::class) fun rejectsConcurrentLikesWhenLocalRequestWasAlreadySatisfied() {
        val base = config.replace("liked: ", "liked: 0001")
        val external = base.replace("liked: 0001", "liked: 01234")
        ConfigCodec.patchPreference(base, external, "liked", ConfigCodec.likedPreferenceValue(base, "0001", true))
    }

    @Test(expected = VaultConflictException::class) fun rejectsConcurrentRemovalOfLikesField() {
        val base = config.replace("liked: ", "liked: 0001")
        val external = base.replace("liked: 0001\n", "")
        ConfigCodec.patchPreference(base, external, "liked", ConfigCodec.likedPreferenceValue(base, "0001", false))
    }

    @Test fun matchingConcurrentLikesAreNoOp() {
        val value = ConfigCodec.likedPreferenceValue(config, "0001", true)
        val external = config.replace("liked: ", "liked: 0001").replace("accent: red", "accent: green")
        assertEquals(external, ConfigCodec.patchPreference(config, external, "liked", value))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateLikesBeforeComputingDesiredValue() {
        ConfigCodec.likedPreferenceValue(config.replace("liked: ", "liked: 0001\nliked: 01234"), "c-new", true)
    }

    @Test fun nutritionPatchPreservesUnrelatedBytesAndExternalEdits() {
        val base = "\uFEFF# Config\r\n## Profile\r\n\t- age : 22  \r\nsex: m\r\n// keep this\r\n## Future\r\nopaque: untouched"
        val original = ConfigCodec.configFieldValue(base, "Profile", "age")
        val external = base.replace("sex: m", "sex: f").replace("untouched", "externally changed")
        val result = ConfigCodec.patchConfigField(external, "Profile", "age", original, "30.5")
        assertEquals(external.replace("age : 22", "age : 30.5"), result)
        assertEquals(30.5, VaultCodec.parseConfig(result).value.profile.getValue("age"), 0.0)
    }

    @Test fun targetAliasesPatchExistingSpellingInEitherSection() {
        for (section in listOf("Targets", "Macro Overrides", "mAcRo OvErRiDeS")) {
            for ((key, alias, desired) in listOf(
                Triple("calories", "cals", "2500"), Triple("protein", "p", "150"),
                Triple("carbs", "c", "300"), Triple("fats", "f", "70"), Triple("water", "water", "3000")
            )) {
                val source = "# Config\n## $section\n  - $alias: 900  \n## Preferences\nliked: 0001\n"
                assertEquals("900", ConfigCodec.configFieldValue(source, "Targets", key))
                val result = ConfigCodec.patchConfigField(source, "Targets", key, "900", desired)
                assertEquals(source.replace("$alias: 900", "$alias: $desired"), result)
                assertEquals(desired.toDouble(), VaultCodec.parseConfig(result).value.overrides.getValue(alias), 0.0)
                assertEquals(source.replace("$alias: 900", "$alias: $desired"),
                    ConfigCodec.patchConfigField(source, "Macro Overrides", alias, "900", desired))
            }
        }
    }

    @Test fun nutritionPatchRejectsDuplicateLogicalFieldsAndSectionsBeforeNoOp() {
        for ((section, key, body) in listOf(
            Triple("Targets", "protein", "## Targets\np: 100\nprotein: 100\n"),
            Triple("Targets", "protein", "## Targets\np: 100\nP: 100\n"),
            Triple("Targets", "protein", "## Targets\np: 100\n## Macro Overrides\nc: 200\n"),
            Triple("Targets", "protein", "## Macro Overrides\np: 100\n## macro overrides\n"),
            Triple("Profile", "age", "## Profile\nage: 22\nAge: 22\n"),
            Triple("Profile", "age", "## Profile\nage: 22\n- ## PROFILE\n"),
            Triple("Preferences", "week-start", "## Preferences\nweek-start: 1\nweek-start: 1\n"),
            Triple("Preferences", "week-start", "## Preferences\nweek-start: 1\n## preferences\n")
        )) {
            val source = "# Config\n$body"
            assertThrows(IllegalArgumentException::class.java) { ConfigCodec.configFieldValue(source, section, key) }
            assertThrows(IllegalArgumentException::class.java) { ConfigCodec.patchConfigField(source, section, key, null, "100") }
        }
    }

    @Test fun nutritionPatchRefusesUnsupportedCaseInsteadOfAddingShadowField() {
        val source = "# Config\n## Targets\nCalories: 2000\n"
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.configFieldValue(source, "Targets", "calories") }
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.patchConfigField(source, "Targets", "calories", null, "2500") }
    }

    @Test fun nutritionPatchDetectsExternalChangesRemovalsAndInsertions() {
        val external = "# Config\n## Macro Overrides\np: 150\n"
        assertThrows(VaultConflictException::class.java) { ConfigCodec.patchConfigField(external, "Targets", "protein", "100", "120") }
        assertThrows(VaultConflictException::class.java) { ConfigCodec.patchConfigField(external, "Targets", "protein", null, "120") }
        assertThrows(VaultConflictException::class.java) { ConfigCodec.patchConfigField(external, "Targets", "protein", "100", null) }
        assertThrows(VaultConflictException::class.java) { ConfigCodec.patchConfigField("# Config\n", "Targets", "protein", "100", "120") }
        assertEquals(external, ConfigCodec.patchConfigField(external, "Targets", "protein", "100", "150"))
        assertEquals("# Config\n", ConfigCodec.patchConfigField("# Config\n", "Targets", "protein", "100", null))
    }

    @Test fun nutritionPatchInsertsMissingSectionsAndFieldsWithExistingNewlines() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val source = "# Config${newline}## Future${newline}opaque: keep"
            for ((section, key, value) in listOf(Triple("Profile", "age", "25"), Triple("Targets", "protein", "150"), Triple("Preferences", "default-view", "month"))) {
                val result = ConfigCodec.patchConfigField(source, section, key, null, value)
                assertEquals(source + newline + newline + "## $section" + newline + "$key: $value" + newline, result)
                assertEquals(value, ConfigCodec.configFieldValue(result, section, key))
            }
            val aliased = "# Config${newline}- ## Macro Overrides${newline}// keep${newline}## Future${newline}opaque: keep"
            val inserted = ConfigCodec.patchConfigField(aliased, "Targets", "protein", null, "150")
            assertEquals(aliased.replace("## Future", "protein: 150${newline}## Future"), inserted)
            assertEquals(150.0, VaultCodec.parseConfig(inserted).value.overrides.getValue("p"), 0.0)
        }
    }

    @Test fun clearingTargetRemovesWholeLineIncludingNewlineAndHandlesEmptyEof() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            for (value in listOf("150", "")) {
                for (suffix in listOf("", newline, newline + "water: 3000" + newline + "## Future" + newline + "keep")) {
                    val prefix = "\uFEFF# Config${newline}## Macro Overrides${newline}"
                    val row = "  - p: $value  "
                    val source = prefix + row + suffix
                    val result = ConfigCodec.patchConfigField(source, "Targets", "protein", value, null)
                    assertEquals(prefix + suffix.removePrefix(newline), result)
                    assertNull(ConfigCodec.configFieldValue(result, "Targets", "protein"))
                }
            }
        }
    }

    @Test fun emptyTargetIsDistinctFromAbsentAndCanBeReplacedWithoutEatingNextLine() {
        val source = "# Config\n## Targets\np: \nwater: 3000\n"
        assertEquals("", ConfigCodec.configFieldValue(source, "Targets", "protein"))
        assertThrows(VaultConflictException::class.java) { ConfigCodec.patchConfigField(source, "Targets", "protein", null, "150") }
        assertEquals(source.replace("p: \n", "p: 150\n"), ConfigCodec.patchConfigField(source, "Targets", "protein", "", "150"))
    }

    @Test fun configFieldValidationMatchesNutritionEditorRangesAndChoices() {
        val ranges = listOf(
            Triple("Profile", "age", 10.0..110.0), Triple("Profile", "height", 50.0..300.0),
            Triple("Profile", "current-weight", 20.0..500.0), Triple("Profile", "start-weight", 20.0..500.0),
            Triple("Profile", "goal-weight", 20.0..500.0), Triple("Profile", "activity", 1.0..3.0),
            Triple("Profile", "strategy", -1000.0..1000.0), Triple("Profile", "protein-rate", 0.5..5.0),
            Triple("Targets", "calories", 500.0..10000.0), Triple("Targets", "water", 500.0..10000.0),
            Triple("Targets", "protein", 0.0..1000.0), Triple("Targets", "carbs", 0.0..1000.0), Triple("Targets", "fats", 0.0..1000.0),
            Triple("Preferences", "rest-between-sets", 30.0..180.0), Triple("Preferences", "rest-between-exercises", 30.0..180.0)
        )
        for ((section, key, range) in ranges) {
            for (valid in listOf(range.start, range.endInclusive, (range.start + range.endInclusive) / 2)) {
                ConfigCodec.validateConfigField(section, key, valid.toString())
            }
            for (invalid in listOf((range.start - 0.1).toString(), (range.endInclusive + 0.1).toString(), "NaN", "Infinity", "1e999", "", " 50", "50 ", "50f", "0x1p6", "50\n", "50\u0000", "50\u0085", "50\u2028", "50\u2029")) {
                assertThrows(IllegalArgumentException::class.java) { ConfigCodec.validateConfigField(section, key, invalid) }
            }
            if (section == "Targets") ConfigCodec.validateConfigField(section, key, null)
            else assertThrows(IllegalArgumentException::class.java) { ConfigCodec.validateConfigField(section, key, null) }
        }
        for ((section, key, choices) in listOf(
            Triple("Profile", "sex", listOf("m", "f")), Triple("Preferences", "week-start", listOf("0", "1", "6")),
            Triple("Preferences", "default-view", listOf("week", "month", "all")), Triple("Preferences", "workout-reminder", listOf("true", "false"))
        )) {
            choices.forEach { ConfigCodec.validateConfigField(section, key, it) }
            for (invalid in listOf("", "other", "TRUE", "2", "true\n")) {
                assertThrows(IllegalArgumentException::class.java) { ConfigCodec.validateConfigField(section, key, invalid) }
            }
        }
        for ((section, key) in listOf("Future" to "age", "Preferences" to "accent", "Preferences" to "liked", "Profile" to "unknown", "Profile\n## Targets" to "age", "Profile" to "age\nsex")) {
            assertThrows(IllegalArgumentException::class.java) { ConfigCodec.patchConfigField(config, section, key, null, "22") }
        }
    }

    private val exerciseFilterChoices = linkedMapOf(
        "pill-routine" to listOf("default", "pin", "hidden"),
        "pill-category" to listOf("default", "pin", "hidden"),
        "pill-target" to listOf("default", "pin", "hidden"),
        "pill-equipment" to listOf("default", "pin", "hidden"),
        "pill-tags-host" to listOf("routine", "category", "target", "equipment"),
        "pill-toggles" to listOf("routine", "category", "target", "equipment"),
        "show-secondary-pills" to listOf("false", "true")
    )

    @Test fun exerciseFilterValidationAcceptsOnlyExactWhitelistedChoices() {
        val candidates = listOf<String?>(null, "", "other", "pinned", "DEFAULT", "Routine", "TRUE", "yes", "on", "0", "1", "60",
            "default", "pin", "hidden", "routine", "category", "target", "equipment", "true", "false")
        for ((key, choices) in exerciseFilterChoices) {
            for (value in choices) {
                ConfigCodec.validateConfigField("Preferences", key, value)
                val result = ConfigCodec.patchConfigField("# Config\n", "Preferences", key, null, value)
                assertEquals(value, ConfigCodec.configFieldValue(result, "Preferences", key))
                assertEquals(value, VaultCodec.parseConfig(result).value.preferences[key])
            }
            val invalidValues = candidates.filterNot { it in choices } + choices.flatMap {
                listOf(" $it", "$it ", "$it\n", "$it\r", "$it\t", "$it\u0000", "$it\u0085", "$it\u2028", "$it\u2029", "$it\npill-routine: hidden")
            }
            for (value in invalidValues) {
                assertThrows(IllegalArgumentException::class.java) { ConfigCodec.validateConfigField("Preferences", key, value) }
                assertThrows(IllegalArgumentException::class.java) { ConfigCodec.patchConfigField("# Config\n", "Preferences", key, null, value) }
            }
        }
        for (key in listOf("pill", "pill-unknown", "pill-tags", "pill-toggle", "show-secondary-pill", "Pill-routine", "pill-routine\n")) {
            assertThrows(IllegalArgumentException::class.java) { ConfigCodec.validateConfigField("Preferences", key, "default") }
        }
        for (section in listOf("Profile", "Targets", "Exercise filters")) {
            for ((key, choices) in exerciseFilterChoices) {
                assertThrows(IllegalArgumentException::class.java) { ConfigCodec.validateConfigField(section, key, choices.first()) }
            }
        }
    }

    @Test fun exerciseFilterPatchesPreserveFormattingAndUnrelatedExternalChanges() {
        for ((key, choices) in exerciseFilterChoices) {
            val base = "\uFEFF# Config\r\n## Preferences\r\n\t- $key : ${choices.first()}  \r\nliked: 0001, future:id\r\n// keep this\r\nfuture-pref: opaque\r\n## Exercise Tags\r\nid: 0001\r\ntags: legs\r\n## Future\r\nopaque: untouched"
            val original = ConfigCodec.configFieldValue(base, "Preferences", key)
            assertEquals(choices.first(), original)
            val external = base.replace("liked: 0001, future:id", "liked: 0001, 01234, future:id").replace("untouched", "externally changed")
            val result = ConfigCodec.patchConfigField(external, "Preferences", key, original, choices.last())
            assertEquals(external.replace("$key : ${choices.first()}", "$key : ${choices.last()}"), result)
            assertEquals(choices.last(), VaultCodec.parseConfig(result).value.preferences[key])
        }
    }

    @Test fun exerciseFilterOriginalCaptureDistinguishesAbsentEmptyAndUnnormalizedValues() {
        for ((key, choices) in exerciseFilterChoices) {
            val absent = "# Config\n## Preferences\nliked: 0001\n"
            assertNull(ConfigCodec.configFieldValue(absent, "Preferences", key))
            for (raw in listOf("", "unsupported", choices.first().uppercase(java.util.Locale.ROOT))) {
                val source = "# Config\n## Preferences\n  - $key: $raw\nliked: 0001\n"
                val original = ConfigCodec.configFieldValue(source, "Preferences", key)
                assertEquals(raw, original)
                assertThrows(VaultConflictException::class.java) { ConfigCodec.patchConfigField(source, "Preferences", key, null, choices.last()) }
                assertEquals(source.replace("$key: $raw\n", "$key: ${choices.last()}\n"),
                    ConfigCodec.patchConfigField(source, "Preferences", key, original, choices.last()))
            }
        }
    }

    @Test fun exerciseFilterPatchesKeepCapturedOriginalAcrossConflictsAndReloads() {
        for ((key, choices) in exerciseFilterChoices) {
            val base = "# Config\n## Preferences\n$key: ${choices.first()}\nliked: 0001\n"
            val original = ConfigCodec.configFieldValue(base, "Preferences", key)
            val competing = if (choices.size > 2) choices[1] else ""
            val changed = base.replace("$key: ${choices.first()}", "$key: $competing")
            val removed = base.replace("$key: ${choices.first()}\n", "")
            for (external in listOf(changed, removed)) {
                assertThrows(VaultConflictException::class.java) { ConfigCodec.patchConfigField(external, "Preferences", key, original, choices.last()) }
                ConfigCodec.configFieldValue(external, "Preferences", key)
                assertThrows(VaultConflictException::class.java) { ConfigCodec.patchConfigField(external, "Preferences", key, original, choices.last()) }
            }
            assertThrows(VaultConflictException::class.java) { ConfigCodec.patchConfigField(base, "Preferences", key, null, choices.last()) }
        }
    }

    @Test fun exerciseFilterPatchesAreIdempotentIncludingMatchingConcurrentWrites() {
        for ((key, choices) in exerciseFilterChoices) {
            val base = "# Config\n## Preferences\n$key: ${choices.first()}\nliked: 0001\n"
            val original = ConfigCodec.configFieldValue(base, "Preferences", key)
            assertEquals(base, ConfigCodec.patchConfigField(base, "Preferences", key, original, original))
            val result = ConfigCodec.patchConfigField(base, "Preferences", key, original, choices.last())
            val external = result.replace("liked: 0001", "liked: 01234")
            assertEquals(external, ConfigCodec.patchConfigField(external, "Preferences", key, original, choices.last()))
            assertEquals(external, ConfigCodec.patchConfigField(external, "Preferences", key, null, choices.last()))
            assertEquals(external, ConfigCodec.patchConfigField(external, "Preferences", key, choices.last(), choices.last()))
        }
    }

    @Test fun exerciseFilterPatchesInsertMissingFieldsAndSectionsWithoutOtherChanges() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            for ((key, choices) in exerciseFilterChoices) {
                val missingSection = "# Config${newline}## Future${newline}opaque: keep"
                val value = choices.last()
                val insertedSection = ConfigCodec.patchConfigField(missingSection, "Preferences", key, null, value)
                assertEquals(missingSection + newline + newline + "## Preferences" + newline + "$key: $value" + newline, insertedSection)
                val source = "# Config${newline}## Preferences${newline}liked: 0001${newline}## Future${newline}opaque: keep"
                val insertedField = ConfigCodec.patchConfigField(source, "Preferences", key, null, value)
                assertEquals(source.replace("## Future", "$key: $value${newline}## Future"), insertedField)
                assertEquals(value, ConfigCodec.configFieldValue(insertedField, "Preferences", key))
                assertEquals(insertedField, ConfigCodec.patchConfigField(insertedField, "Preferences", key, null, value))
            }
        }
    }

    @Test fun exerciseFilterPatchesRejectAmbiguousSourcesEvenForNoOp() {
        for ((key, choices) in exerciseFilterChoices) {
            val value = choices.first()
            for (body in listOf(
                "## Preferences\n$key: $value\n$key: $value\n",
                "## Preferences\n$key: $value\n${key.uppercase(java.util.Locale.ROOT)}: $value\n",
                "## Preferences\n$key: $value\n## preferences\n",
                "## Preferences\n${key.uppercase(java.util.Locale.ROOT)}: $value\n"
            )) {
                val source = "# Config\n$body"
                assertThrows(IllegalArgumentException::class.java) { ConfigCodec.configFieldValue(source, "Preferences", key) }
                assertThrows(IllegalArgumentException::class.java) { ConfigCodec.patchConfigField(source, "Preferences", key, value, value) }
            }
        }
    }

    @Test fun nutritionPatchRejectsUnsupportedHeader() {
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.patchConfigField("# Other\n", "Profile", "age", null, "22") }
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.patchConfigField("", "Targets", "protein", null, null) }
    }

    @Test fun createsMissingSectionWithoutRemovingContent() {
        val source = "# Config\n\n## Future\nvalue: 1"
        val result = ConfigCodec.patchPreference(source, source, "accent", "red")
        assertTrue(result.startsWith(source))
        assertEquals("red", ConfigCodec.parse(result).preference("accent"))
    }
}
