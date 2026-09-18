package com.thehhr.form.nativeapp.vault

class VaultConflictException(message: String) : IllegalStateException(message)

object ConfigCodec {
    data class Field(val section: String, val key: String, val value: String, val start: Int, val end: Int)
    data class Document(val source: String, val fields: List<Field>) {
        fun values(section: String): Map<String, String> = fields.filter { it.section.equals(section, true) }.associate { it.key to it.value }
        fun preference(key: String): String? {
            val matches = fields.filter { it.section.equals("Preferences", true) && it.key == key }
            require(matches.size <= 1) { "Duplicate preference: $key" }
            return matches.singleOrNull()?.value
        }
        fun customExercises(): List<Map<String, String>> {
            val records = mutableListOf<MutableMap<String, String>>()
            fields.filter { it.section.equals("Custom Exercises", true) }.forEach {
                if (it.key == "name") records += linkedMapOf("name" to it.value)
                else records.lastOrNull()?.put(it.key, it.value)
            }
            return records.map { it.toMap() }
        }
        fun exerciseTags(): Map<String, List<String>> {
            val tags = linkedMapOf<String, List<String>>()
            var id: String? = null
            fields.filter { it.section.equals("Exercise Tags", true) }.forEach {
                if (it.key == "id") id = it.value.removePrefix("#").trim()
                if (it.key == "tags") id?.let { key -> tags[key] = it.value.split(',').map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase) }
            }
            return tags
        }
    }

    private val line = Regex("[^\\r\\n]*(?:\\r\\n|\\n|\\r|$)")
    private val heading = Regex("^#{1,6}\\s+(.+)$")
    private val field = Regex("^(\\s*(?:-\\s*)?[A-Za-z][\\w-]*\\s*:\\s*)(.*?)([ \\t]*)$")

    fun parse(source: String): Document {
        var section = ""
        val fields = mutableListOf<Field>()
        for (row in line.findAll(source)) {
            val body = row.value.trimEnd('\r', '\n')
            val trimmed = body.trim().removePrefix("\uFEFF")
            if (trimmed.startsWith("//") || trimmed.startsWith("<!--")) continue
            val title = heading.matchEntire(trimmed)
            if (title != null) {
                section = title.groupValues[1].trim()
                continue
            }
            val match = field.matchEntire(body) ?: continue
            val key = match.groupValues[1].trim().removePrefix("-").trim().substringBefore(':').trim()
            val value = match.groups[2]!!
            fields += Field(section, key, value.value, row.range.first + value.range.first, row.range.first + value.range.last + 1)
        }
        return Document(source, fields)
    }

    fun likedPreferenceValue(base: String, id: String, liked: Boolean): String {
        require(id.matches(Regex("(?:[0-9]{4,5}|c-[\\w-]+)"))) { "Invalid exercise ID" }
        val original = parse(base).preference("liked").orEmpty()
        val ids = original.split(',').map(String::trim).filter(String::isNotEmpty)
        val present = ids.any { it.removePrefix("#").trim() == id }
        if (present == liked) return original
        return if (liked) (ids + id).joinToString(", ")
        else ids.filterNot { it.removePrefix("#").trim() == id }.joinToString(", ")
    }

    fun scheduleValue(source: String, day: Int): String? {
        require(day in 0..6) { "Invalid schedule day" }
        val key = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")[day]
        val sections = line.findAll(source).mapNotNull { row -> heading.matchEntire(row.value.trim().removePrefix("\uFEFF"))?.groupValues?.get(1) }
            .count { it.equals("Weekly Schedule", true) }
        require(sections <= 1) { "Duplicate Weekly Schedule sections" }
        val fields = parse(source).fields.filter { it.section.equals("Weekly Schedule", true) && it.key.equals(key, true) }
        require(fields.size <= 1) { "Duplicate schedule day: $key" }
        require(fields.all { it.key == key }) { "Use lowercase schedule day keys" }
        return fields.singleOrNull()?.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun patchSchedule(external: String, day: Int, original: String?, desired: String?): String {
        require(VaultFormat.headingsCompatible(VaultFiles.CONFIG, external)) { "Unsupported config header" }
        require(desired == null || (desired.isNotBlank() && desired == desired.trim() && desired.length <= 40 &&
            desired.none { it == '\r' || it == '\n' || it == '\u0000' || it == '\u0085' || it == '\u2028' || it == '\u2029' })) { "Invalid routine name" }
        val theirs = scheduleValue(external, day)
        if (theirs != original && theirs != desired) throw VaultConflictException("This day was changed outside Form. Reload and reopen the schedule editor.")
        if (theirs == desired) return external
        val key = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")[day]
        val existing = parse(external).fields.singleOrNull { it.section.equals("Weekly Schedule", true) && it.key == key }
        if (existing != null) {
            if (desired != null) return external.replaceRange(existing.start, existing.end, desired)
            val row = line.findAll(external).first { existing.start in it.range }
            return external.removeRange(row.range.first, row.range.last + 1)
        }
        if (desired == null) return external
        val newline = if (external.contains("\r\n")) "\r\n" else if (external.contains('\r')) "\r" else "\n"
        val headings = line.findAll(external).mapNotNull { row ->
            heading.matchEntire(row.value.trim().removePrefix("\uFEFF"))?.let { it.groupValues[1].trim() to row.range }
        }.toList()
        val section = headings.indexOfFirst { it.first.equals("Weekly Schedule", true) }
        if (section < 0) return external + (if (external.endsWith('\n') || external.endsWith('\r')) "" else newline) + newline + "## Weekly Schedule" + newline + "$key: $desired" + newline
        val insertion = headings.getOrNull(section + 1)?.second?.first ?: external.length
        val prefix = external.substring(0, insertion)
        return prefix + (if (prefix.endsWith('\n') || prefix.endsWith('\r')) "" else newline) + "$key: $desired" + newline + external.substring(insertion)
    }

    fun replaceCustomExercises(source: String, records: List<VaultCustomExercise>): String {
        customExerciseRecords(source)
        validateCustomExercises(records)
        return rewriteConfigSection(source, "Custom Exercises", VaultConfig(customExercises = records))
    }

    fun clearExerciseTags(source: String): String {
        isolatedConfig(source, "Exercise Tags")
        return rewriteConfigSection(source, "Exercise Tags", VaultConfig())
    }

    fun checkedLikes(source: String): List<String> {
        configSection(source, "Preferences")
        val fields = parse(source).fields.filter { it.section.equals("Preferences", true) && it.key.equals("liked", true) }
        require(fields.size <= 1 && fields.all { it.key == "liked" }) { "Ambiguous liked preference" }
        val raw = fields.singleOrNull()?.value.orEmpty()
        val ids = if (raw.isBlank()) emptyList() else raw.split(',').map { it.trim().removePrefix("#") }
        require(ids.all(::validExerciseId) && ids.distinct().size == ids.size) { "Invalid or duplicate liked IDs" }
        return ids
    }

    fun checkedSchedule(source: String): Map<Int, String> {
        val section = configSection(source, "Weekly Schedule") ?: return emptyMap()
        val rows = section.text.lines().drop(1).filter { it.isNotBlank() }
        require(rows.all { it.trim().matches(Regex("(?:-\\s*)?(sun|mon|tue|wed|thu|fri|sat):[ \\t]*[^\\r\\n]*")) }) { "Unsupported Weekly Schedule content" }
        return (0..6).mapNotNull { day -> scheduleValue(source, day)?.let { day to it } }.toMap()
    }

    fun customExerciseRecords(source: String): List<VaultCustomExercise> =
        isolatedConfig(source, "Custom Exercises").customExercises

    private fun forbiddenControl(value: Char): Boolean =
        Character.isISOControl(value) || value == '\u2028' || value == '\u2029'

    fun validCustomExerciseName(value: String): Boolean =
        value.isNotBlank() && value == value.trim() && value.length <= 40 && value.none(::forbiddenControl)

    fun validCustomId(value: String): Boolean = value.length <= 64 && value.matches(Regex("(?:[0-9]{5}|c-[A-Za-z0-9_-]+)"))

    private fun validExerciseId(value: String): Boolean = validCustomId(value) || value.matches(Regex("[0-9]{4,5}"))

    fun validTags(values: List<String>): Boolean =
        values.size <= 12 && values.distinctBy { it.lowercase(java.util.Locale.ROOT) }.size == values.size &&
            values.all { value ->
                value.isNotBlank() && value.length <= 24 && value == value.trim() &&
                    value == value.replace(Regex("\\s+"), " ") &&
                    value.none { it == ',' || it == '|' || it == '#' || forbiddenControl(it) || (it.isWhitespace() && it != ' ') }
            }

    private fun validateCustomExercises(records: List<VaultCustomExercise>) {
        require(records.map { it.id }.distinct().size == records.size) { "Duplicate custom exercise IDs" }
        require(records.distinctBy { it.name.lowercase(java.util.Locale.ROOT) }.size == records.size) { "Duplicate custom exercise names (case-insensitive)" }
        for (record in records) {
            require(validCustomId(record.id)) { "Use an unchanged c- ID of at most 64 characters" }
            require(validCustomExerciseName(record.name)) { "Use a trimmed single-line name of 1–40 characters without controls" }
            for (value in listOf(record.category, record.target, record.equipment)) {
                require(value.length <= 40 && value == value.trim() && value.none(::forbiddenControl)) { "Use trimmed category, target and equipment of at most 40 characters without controls" }
            }
            require(record.description.length <= 400 && record.description.none { it != '\n' && forbiddenControl(it) }) { "Use a description of at most 400 characters; only newline controls are allowed" }
        }
    }

    private data class ConfigSection(val start: Int, val end: Int, val text: String)

    private fun configSection(source: String, name: String): ConfigSection? {
        val headings = line.findAll(source).mapNotNull { row ->
            val body = row.value.trim().let { if (row.range.first == 0) it.removePrefix("\uFEFF") else it }
            heading.matchEntire(body.removePrefix("-").trim())?.let { it.groupValues[1].trim() to row.range.first }
        }.toList()
        val matches = headings.withIndex().filter { it.value.first.equals(name, true) }
        require(matches.size <= 1) { "Duplicate $name sections; edit the source before saving" }
        val match = matches.singleOrNull() ?: return null
        val start = match.value.second
        val end = headings.getOrNull(match.index + 1)?.second ?: source.length
        return ConfigSection(start, end, source.substring(start, end))
    }

    private fun isolatedConfig(source: String, name: String): VaultConfig {
        require(VaultFormat.headingsCompatible(VaultFiles.CONFIG, source)) { "Unsupported config header" }
        val section = configSection(source, name) ?: return VaultConfig()
        val document = VaultCodec.parseConfig("# Config\n" + section.text)
        require(document.diagnostics.isEmpty()) {
            "$name cannot be rewritten without losing comments or unsupported content: ${document.diagnostics.joinToString { "line ${it.line}: ${it.message}" }}"
        }
        val custom = name == "Custom Exercises"
        val firstKey = if (custom) "name" else "id"
        val known = if (custom) setOf("name", "id", "category", "target", "equipment", "description") else setOf("id", "tags")
        val records = mutableListOf<MutableMap<String, String>>()
        val syntax = Regex("^([A-Za-z][\\w-]*)[ \\t]*:[ \\t]*(.*)$")
        for (row in line.findAll(section.text).drop(1)) {
            val body = row.value.trimEnd('\r', '\n')
            if (body.isBlank()) continue
            val text = body.trimStart().removePrefix("-").trimStart()
            require(!text.startsWith("//") && !text.contains("<!--") && !text.contains("-->")) { "$name contains comments; edit the source before saving" }
            val match = syntax.matchEntire(text)
            require(match != null) { "$name contains unsupported content; edit the source before saving" }
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            require(key in known) { "Unknown $name field: $key; edit the source before saving" }
            if (key == firstKey) records.add(linkedMapOf())
            val record = records.lastOrNull()
            require(record != null) { "Orphan $name field: $key" }
            require(!record.containsKey(key)) { "Duplicate $name field: $key; edit the source before saving" }
            record[key] = value
        }
        if (custom) {
            val raw = records.map { record ->
                val id = record["id"]
                require(id != null && validCustomId(id)) { "Every custom exercise needs an explicit, unnormalized c- ID of at most 64 characters" }
                VaultCustomExercise(id, record.getValue("name"), record["category"].orEmpty(), record["target"].orEmpty(),
                    record["equipment"].orEmpty(), record["description"].orEmpty().replace(" / ", "\n"))
            }
            validateCustomExercises(raw)
            require(document.value.customExercises == raw) { "Custom Exercises would be normalized; edit the source before saving" }
        } else {
            val raw = linkedMapOf<String, List<String>>()
            for (record in records) {
                val id = record.getValue("id")
                require(validExerciseId(id)) { "Invalid or normalized Exercise Tags ID: $id" }
                require(!raw.containsKey(id)) { "Duplicate Exercise Tags ID: $id; edit the source before saving" }
                val value = record["tags"].orEmpty()
                val tags = if (value.isEmpty()) emptyList() else value.split(',').map(String::trim)
                require(validTags(tags)) { "Exercise Tags contains invalid or normalized tags; use at most 12 unique tags of 1–24 characters without commas, |, # or controls" }
                require(value == tags.joinToString(", ") && value.none { forbiddenControl(it) || (it.isWhitespace() && it != ' ') }) { "Exercise Tags contains noncanonical whitespace; edit the source before saving" }
                raw[id] = tags
            }
            require(document.value.exerciseTags == raw) { "Exercise Tags would be normalized; edit the source before saving" }
        }
        return document.value
    }

    private fun rewriteConfigSection(external: String, name: String, value: VaultConfig): String {
        val encoded = VaultCodec.encodeConfig(value)
        val replacement = requireNotNull(configSection(encoded, name)).text
        val newline = if (external.contains("\r\n")) "\r\n" else if (external.contains('\r')) "\r" else "\n"
        val text = replacement.replace("\n", newline)
        val section = configSection(external, name)
        val result = if (section == null) {
            external + (if (external.endsWith('\n') || external.endsWith('\r')) "" else newline) + newline + text
        } else external.replaceRange(section.start, section.end, text)
        val isolated = isolatedConfig(result, name)
        val parsed = VaultCodec.parseConfig(result).value
        require(if (name == "Custom Exercises") {
            isolated.customExercises == value.customExercises && parsed.customExercises == value.customExercises
        } else {
            isolated.exerciseTags == value.exerciseTags && parsed.exerciseTags == value.exerciseTags
        }) { "$name cannot round-trip exactly; no changes were saved" }
        return result
    }

    fun saveCustomExercise(external: String, original: VaultCustomExercise?, replacement: VaultCustomExercise?): String {
        val records = customExerciseRecords(external).toMutableList()
        require(original != null || replacement != null) { "Provide an original or replacement custom exercise" }
        original?.let { require(validCustomId(it.id)) { "Invalid original custom exercise ID" } }
        replacement?.let { validateCustomExercises(listOf(it)) }
        require(original == null || replacement == null || original.id == replacement.id) { "Custom exercise IDs are immutable" }
        val id = original?.id ?: requireNotNull(replacement).id
        val index = records.indexOfFirst { it.id == id }
        val theirs = records.getOrNull(index)
        if (theirs != original) throw VaultConflictException("This custom exercise changed outside Form. Reload and reopen the editor.")
        if (replacement != null && records.any { it.id != id && it.name.equals(replacement.name, true) }) {
            throw VaultConflictException("A custom exercise with this name already exists (case-insensitive).")
        }
        if (replacement == original) return external
        if (replacement == null) records.removeAt(index)
        else if (index < 0) records.add(replacement)
        else records[index] = replacement
        validateCustomExercises(records)
        return rewriteConfigSection(external, "Custom Exercises", VaultConfig(customExercises = records))
    }

    fun exerciseTagValue(source: String, id: String): List<String>? {
        require(validExerciseId(id)) { "Invalid exercise ID" }
        return isolatedConfig(source, "Exercise Tags").exerciseTags[id]
    }

    fun patchTags(external: String, id: String, original: List<String>?, desired: List<String>): String {
        require(validExerciseId(id)) { "Invalid exercise ID" }
        require(validTags(desired)) { "Use at most 12 case-insensitively unique tags of 1–24 characters with canonical spaces and no commas, |, # or controls" }
        require(original == null || validTags(original)) { "Original tags are not canonical; reload and edit the source before saving" }
        val tags = isolatedConfig(external, "Exercise Tags").exerciseTags.toMutableMap()
        val theirs = tags[id]
        if (theirs.orEmpty() != original.orEmpty() && theirs.orEmpty() != desired) throw VaultConflictException("Tags were changed outside Form. Reload before editing them.")
        if (theirs == desired || (theirs == null && desired.isEmpty())) return external
        tags[id] = desired.toList()
        return rewriteConfigSection(external, "Exercise Tags", VaultConfig(exerciseTags = tags))
    }

    private val targetAliases = mapOf("cals" to "calories", "p" to "protein", "c" to "carbs", "f" to "fats")
    private val profileRanges = mapOf(
        "age" to 10.0..110.0, "height" to 50.0..300.0,
        "current-weight" to 20.0..500.0, "start-weight" to 20.0..500.0, "goal-weight" to 20.0..500.0,
        "activity" to 1.0..3.0, "strategy" to -1000.0..1000.0, "protein-rate" to 0.5..5.0
    )
    private val targetRanges = mapOf(
        "calories" to 500.0..10000.0, "protein" to 0.0..1000.0, "carbs" to 0.0..1000.0,
        "fats" to 0.0..1000.0, "water" to 500.0..10000.0
    )
    private val preferenceChoices = mapOf(
        "week-start" to setOf("0", "1", "6"), "default-view" to setOf("week", "month", "all"),
        "workout-reminder" to setOf("true", "false"),
        "pill-routine" to setOf("default", "pin", "hidden"),
        "pill-category" to setOf("default", "pin", "hidden"),
        "pill-target" to setOf("default", "pin", "hidden"),
        "pill-equipment" to setOf("default", "pin", "hidden"),
        "pill-tags-host" to setOf("routine", "category", "target", "equipment"),
        "pill-toggles" to setOf("routine", "category", "target", "equipment"),
        "show-secondary-pills" to setOf("true", "false")
    )
    private val restKeys = setOf("rest-between-sets", "rest-between-exercises")

    private fun logicalSection(section: String): String = when (section.lowercase(java.util.Locale.ROOT)) {
        "profile" -> "Profile"
        "targets", "macro overrides" -> "Targets"
        "preferences" -> "Preferences"
        else -> throw IllegalArgumentException("Unsupported config section: $section")
    }

    private fun logicalKey(section: String, key: String): String {
        val lower = key.lowercase(java.util.Locale.ROOT)
        return if (section == "Targets") targetAliases[lower] ?: lower else lower
    }

    private fun checkedConfigKey(section: String, key: String): Pair<String, String> {
        val name = logicalSection(section)
        val canonical = logicalKey(name, key)
        require(key == key.lowercase(java.util.Locale.ROOT)) { "Use lowercase field names" }
        require(when (name) {
            "Profile" -> canonical in profileRanges || canonical == "sex"
            "Targets" -> canonical in targetRanges
            else -> canonical in preferenceChoices || canonical in restKeys
        }) { "Unsupported $name field: $key" }
        return name to canonical
    }

    fun validateConfigField(section: String, key: String, value: String?) {
        val (name, canonical) = checkedConfigKey(section, key)
        if (value == null) {
            require(name == "Targets") { "Only target overrides can be cleared" }
            return
        }
        require(value.isNotEmpty() && value.length <= 64 && value == value.trim() && value.none(::forbiddenControl)) { "Use a trimmed single-line value" }
        val choices = if (name == "Profile" && canonical == "sex") setOf("m", "f")
            else if (name == "Preferences") preferenceChoices[canonical] else null
        if (choices != null) {
            require(value in choices) { "Unsupported value for $canonical" }
        } else {
            val range = when (name) {
                "Profile" -> profileRanges.getValue(canonical)
                "Targets" -> targetRanges.getValue(canonical)
                else -> 30.0..180.0
            }
            val number = value.toDoubleOrNull()
            require(value.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) &&
                number != null && number.isFinite() && number in range) { "Enter a finite number between ${range.start} and ${range.endInclusive}" }
        }
    }

    private data class ConfigValue(val field: Field, val lineStart: Int, val lineEnd: Int)
    private data class ConfigFieldLocation(val section: ConfigSection?, val value: ConfigValue?)

    private fun configFieldLocation(source: String, section: String, key: String): ConfigFieldLocation {
        require(VaultFormat.headingsCompatible(VaultFiles.CONFIG, source)) { "Unsupported config header" }
        val (name, canonical) = checkedConfigKey(section, key)
        val names = if (name == "Targets") listOf("Targets", "Macro Overrides") else listOf(name)
        val sections = names.mapNotNull { configSection(source, it) }
        require(sections.size <= 1) { "Duplicate $name sections (including aliases); edit the source before saving" }
        val found = sections.singleOrNull() ?: return ConfigFieldLocation(null, null)
        val syntax = Regex("^[ \\t]*(?:-[ \\t]*)?([A-Za-z][\\w-]*)[ \\t]*(?:\\([^)]*\\))?[ \\t]*:[ \\t]*(.*?)([ \\t]*)$")
        val values = line.findAll(found.text).drop(1).mapNotNull { row ->
            val match = syntax.matchEntire(row.value.trimEnd('\r', '\n')) ?: return@mapNotNull null
            val rawKey = match.groupValues[1]
            val value = match.groups[2]!!
            ConfigValue(Field(name, rawKey, value.value, found.start + row.range.first + value.range.first,
                found.start + row.range.first + value.range.first + value.value.length),
                found.start + row.range.first, found.start + row.range.last + 1)
        }.toList()
        require(values.distinctBy { logicalKey(name, it.field.key) }.size == values.size) { "Duplicate logical $name fields; edit the source before saving" }
        val selected = values.singleOrNull { logicalKey(name, it.field.key) == canonical }
        require(selected == null || selected.field.key == selected.field.key.lowercase(java.util.Locale.ROOT)) { "Use lowercase $name keys in the source before saving" }
        return ConfigFieldLocation(found, selected)
    }

    fun configFieldValue(source: String, section: String, key: String): String? =
        configFieldLocation(source, section, key).value?.field?.value?.trim()

    fun sectionFieldValue(source: String, section: String, key: String): String? = configFieldValue(source, section, key)

    fun patchConfigField(external: String, section: String, key: String, original: String?, value: String?): String {
        validateConfigField(section, key, value)
        val location = configFieldLocation(external, section, key)
        val existing = location.value
        val theirs = existing?.field?.value?.trim()
        if (theirs != original && theirs != value) throw VaultConflictException("$key was changed outside Form. Reload and reopen the editor.")
        if (theirs == value) return external
        if (existing != null) {
            if (value == null) return external.removeRange(existing.lineStart, existing.lineEnd)
            return external.replaceRange(existing.field.start, existing.field.end, value)
        }
        if (value == null) return external
        val newline = if (external.contains("\r\n")) "\r\n" else if (external.contains('\r')) "\r" else "\n"
        val (name, canonical) = checkedConfigKey(section, key)
        val insertion = location.section?.end ?: external.length
        val prefix = external.substring(0, insertion)
        val separator = if (prefix.endsWith('\n') || prefix.endsWith('\r')) "" else newline
        val title = if (location.section == null) newline + "## $name" + newline else ""
        return prefix + separator + title + "$canonical: $value" + newline + external.substring(insertion)
    }

    fun patchPreference(base: String, external: String, key: String, value: String): String {
        require(key.matches(Regex("[a-z][a-z-]*"))) { "Invalid preference name" }
        require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Preference must be a single line" }
        require(VaultFormat.headingsCompatible(VaultFiles.CONFIG, external)) { "Unsupported config header" }
        val original = parse(base).preference(key)
        val document = parse(external)
        val theirs = document.preference(key)
        if (theirs != original && theirs != value) throw VaultConflictException("$key was changed outside Form. Reload before editing it.")
        if (theirs == value) return external
        val existing = document.fields.singleOrNull { it.section.equals("Preferences", true) && it.key == key }
        if (existing != null) return external.replaceRange(existing.start, existing.end, value)
        val newline = if (external.contains("\r\n")) "\r\n" else if (external.contains('\r')) "\r" else "\n"
        val headings = line.findAll(external).mapNotNull { row ->
            heading.matchEntire(row.value.trim().removePrefix("\uFEFF"))?.let { it.groupValues[1].trim() to row.range }
        }.toList()
        val sections = headings.withIndex().filter { it.value.first.equals("Preferences", true) }
        require(sections.size <= 1) { "Duplicate Preferences sections" }
        val section = sections.singleOrNull()
        if (section == null) return external + (if (external.endsWith('\n') || external.endsWith('\r')) "" else newline) + newline + "## Preferences" + newline + "$key: $value" + newline
        val insertion = headings.getOrNull(section.index + 1)?.second?.first ?: external.length
        val prefix = external.substring(0, insertion)
        return prefix + (if (prefix.endsWith('\n') || prefix.endsWith('\r')) "" else newline) + "$key: $value" + newline + external.substring(insertion)
    }
}
