package com.thehhr.form.nativeapp.vault

import java.math.BigDecimal
import java.util.UUID
import kotlin.math.floor

class RoutineMealCodec(validExerciseIds: Set<String>) {
    private val validExerciseIds = validExerciseIds.toSet()

    enum class Mode { TIMED, REPS }
    enum class Unit { SEC, MIN }

    data class Item(
        val exerciseId: String,
        val sets: Int = 3,
        val reps: Int = 10,
        val mode: Mode? = null,
        val unit: Unit? = null,
        val weighted: Boolean = false,
        val unweighted: Boolean = false,
        val superset: String? = null
    )

    data class Routine(
        val id: String,
        val name: String,
        val liked: Boolean = false,
        val secondary: Boolean = false,
        val items: List<Item> = emptyList()
    )

    data class Meal(
        val id: String,
        val name: String,
        val defaultGrams: Double = 100.0,
        val cals100: Double = 0.0,
        val p100: Double = 0.0,
        val c100: Double = 0.0,
        val f100: Double = 0.0,
        val liked: Boolean = false
    )

    data class Diagnostic(val line: Int, val raw: String, val message: String)

    class Document<T> internal constructor(
        val source: String,
        val value: T,
        val diagnostics: List<Diagnostic>
    ) {
        val canRewrite: Boolean get() = diagnostics.isEmpty()
    }

    private data class Line(val number: Int, val raw: String) {
        val text: String get() = raw.trim()
        val body: String get() = text.removePrefix("-").trim()
    }

    private class Reader(val source: String) {
        val diagnostics = mutableListOf<Diagnostic>()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .mapIndexed { index, raw -> Line(index + 1, raw) }
            .filter { line ->
                when {
                    line.text.isEmpty() -> false
                    line.text.startsWith("//") || (line.text.startsWith("<!--") && line.text.endsWith("-->")) -> {
                        warn(line, "Comment retained in source; serialization would discard it")
                        false
                    }
                    else -> true
                }
            }

        fun warn(line: Line, message: String = "Unknown row retained in source") {
            diagnostics.add(Diagnostic(line.number, line.raw, message))
        }

        fun number(raw: String, min: Double, max: Double, fallback: Double, line: Line): Double {
            val number = raw.toDoubleOrNull()
            val value = number?.takeIf { it.isFinite() }?.coerceIn(min, max) ?: fallback
            if (number == null || !number.isFinite() || number != value) warn(line, "Numeric value normalized: $raw")
            return value
        }

        fun name(raw: String, line: Line): String {
            val value = raw.trim().take(40)
            if (value != raw.trim() || value.isEmpty()) warn(line, "Name truncated or empty")
            return value
        }

        fun id(raw: String, line: Line): String {
            val value = raw.replace(Regex("[^A-Za-z0-9_-]"), "").take(64)
            if (value != raw || value.isEmpty()) warn(line, "Record identifier normalized")
            return value
        }

        fun <T> document(value: T) = Document(source, value, diagnostics.toList())
    }

    private val heading = Regex("^#{1,6}\\s+(.+)$")
    private val recordId = Regex("[A-Za-z0-9_-]{1,64}")
    private val idRow = Regex("^id:\\s*(\\S+)$", RegexOption.IGNORE_CASE)
    private val exerciseRow = Regex("^(\\d{4,5}|c-[\\w-]+)\\s+(\\d+)\\s*\\*\\s*(\\d+)(?:\\s+(\\S.*))?$")
    private val mealHeader = Regex("^(.+)\\s*\\(\\s*(\\d+(?:\\.\\d+)?)\\s*g\\s*\\)$", RegexOption.IGNORE_CASE)
    private val macroRow = Regex("^Per100g\\s+(\\d+(?:\\.\\d+)?)\\s*cal\\s+(\\d+(?:\\.\\d+)?)\\s*pro\\s+(\\d+(?:\\.\\d+)?)\\s*carb\\s+(\\d+(?:\\.\\d+)?)\\s*fat$", RegexOption.IGNORE_CASE)
    private val supersetToken = Regex("\\bss(\\d+)(\\d)\\b", RegexOption.IGNORE_CASE)
    private val modifier = Regex("timed|reps|weighted|unweighted|sec|min|ss\\d{2,}", RegexOption.IGNORE_CASE)

    private fun stableId(prefix: String, name: String, index: Int): String =
        "$prefix-${UUID.nameUUIDFromBytes("$prefix:$index:$name".toByteArray(Charsets.UTF_8))}"

    private fun flag(body: String, name: String): Boolean = body.equals(name, true) ||
        Regex("^$name:\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(body)

    private fun canonicalFlag(body: String, name: String): Boolean = body.equals(name, true) ||
        Regex("^$name:\\s*true$", RegexOption.IGNORE_CASE).matches(body)

    fun parseRoutinesMd(text: String): Document<List<Routine>> {
        val reader = Reader(text)
        val routines = mutableListOf<Routine>()
        val starts = mutableListOf<Line>()
        val itemLines = mutableListOf<MutableList<Line>>()
        var current: Int? = null
        var fields = mutableSetOf<String>()
        for (line in reader.lines) {
            val name = heading.matchEntire(line.text)?.groupValues?.get(1)?.trim()
            if (name != null) {
                if (name.equals("routines", true) || name.equals("weekly schedule", true)) {
                    current = null
                    continue
                }
                current = routines.size
                routines.add(Routine(stableId("r", name, routines.size), reader.name(name, line)))
                starts.add(line)
                itemLines.add(mutableListOf())
                fields = mutableSetOf()
                continue
            }
            val index = current
            if (index == null) { reader.warn(line, "Row outside a routine"); continue }
            val routine = routines[index]
            val body = line.body
            val id = idRow.matchEntire(body)
            when {
                flag(body, "liked") || flag(body, "secondary") -> {
                    val key = if (flag(body, "liked")) "liked" else "secondary"
                    if (!fields.add(key)) reader.warn(line, "Repeated $key field")
                    if (!canonicalFlag(body, key)) reader.warn(line, "Unsupported flag suffix")
                    routines[index] = if (key == "liked") routine.copy(liked = true) else routine.copy(secondary = true)
                }
                id != null -> {
                    if (!fields.add("id")) reader.warn(line, "Repeated record identifier")
                    val clean = reader.id(id.groupValues[1], line)
                    if (clean.isNotEmpty()) {
                        if (routines.withIndex().any { it.index != index && it.value.id == clean }) reader.warn(line, "Duplicate routine identifier: $clean")
                        else routines[index] = routine.copy(id = clean)
                    }
                }
                else -> {
                    val match = exerciseRow.matchEntire(body.removePrefix("#").trim())
                    if (match == null) { reader.warn(line); continue }
                    val exerciseId = match.groupValues[1]
                    if (exerciseId !in validExerciseIds) { reader.warn(line, "Unknown exercise identifier: $exerciseId"); continue }
                    if (routine.items.any { it.exerciseId == exerciseId }) { reader.warn(line, "Duplicate exercise identifier: $exerciseId"); continue }
                    val suffix = match.groupValues[4]
                    fun token(value: String) = Regex("\\b$value\\b", RegexOption.IGNORE_CASE).containsMatchIn(suffix)
                    val tokens = suffix.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (tokens.any { !modifier.matches(it) }) reader.warn(line, "Unknown routine modifier")
                    if (tokens.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size != tokens.size) reader.warn(line, "Repeated routine modifier")
                    if (token("timed") && token("reps")) reader.warn(line, "Conflicting modes; JS prefers timed")
                    if (token("sec") && token("min")) reader.warn(line, "Conflicting units; JS prefers sec")
                    val mode = if (token("timed")) Mode.TIMED else if (token("reps")) Mode.REPS else null
                    val unit = if (token("sec")) Unit.SEC else if (token("min")) Unit.MIN else null
                    if (unit != null && mode != Mode.TIMED) reader.warn(line, "Unit without timed mode would be discarded")
                    if (unit == null && mode == Mode.TIMED) reader.warn(line, "Timed mode without a unit would default to min")
                    val supersets = supersetToken.findAll(suffix).toList()
                    if (supersets.size > 1) reader.warn(line, "Repeated superset modifier")
                    val item = Item(exerciseId,
                        reader.number(match.groupValues[2], 1.0, 20.0, 3.0, line).toInt(),
                        reader.number(match.groupValues[3], 0.0, 60.0, 3.0, line).toInt(),
                        mode, unit, token("weighted"), token("unweighted"), supersets.firstOrNull()?.groupValues?.get(1))
                    routines[index] = routine.copy(items = routine.items + item)
                    itemLines[index].add(line)
                }
            }
        }
        for (index in routines.indices) {
            val routine = routines[index]
            val counts = routine.items.mapNotNull { it.superset }.groupingBy { it }.eachCount()
            routines[index] = routine.copy(items = routine.items.mapIndexed { itemIndex, item ->
                if (item.superset != null && counts[item.superset] != 2) {
                    reader.warn(itemLines[index][itemIndex], "Superset must contain exactly two exercises")
                    item.copy(superset = null)
                } else item
            })
        }
        val ids = mutableSetOf<String>()
        routines.forEachIndexed { index, routine ->
            if (!ids.add(routine.id)) reader.warn(starts[index], "Duplicate routine identifier: ${routine.id}")
        }
        return reader.document(routines.toList())
    }

    fun parseMealsMd(text: String): Document<List<Meal>> {
        val reader = Reader(text)
        val meals = mutableListOf<Meal>()
        val starts = mutableListOf<Line>()
        var current: Int? = null
        var fields = mutableSetOf<String>()
        for (line in reader.lines) {
            val library = line.text.startsWith("# Meal Library", true)
            if (Regex("^#{1,6}\\s").containsMatchIn(line.text) && !library) current = null
            val header = mealHeader.matchEntire(line.text)
            if (header != null) {
                val rawName = header.groupValues[1].trim()
                current = meals.size
                meals.add(Meal(stableId("m", rawName, meals.size), reader.name(rawName, line),
                    reader.number(header.groupValues[2], 1.0, 5000.0, 100.0, line)))
                starts.add(line)
                fields = mutableSetOf()
                continue
            }
            if (line.text.equals("# Meal Library", true)) continue
            val index = current
            if (index == null) { reader.warn(line, "Row outside a meal"); continue }
            val meal = meals[index]
            val body = line.body
            val id = idRow.matchEntire(body)
            val macros = macroRow.matchEntire(body)
            when {
                flag(body, "liked") -> {
                    if (!fields.add("liked")) reader.warn(line, "Repeated liked field")
                    if (!canonicalFlag(body, "liked")) reader.warn(line, "Unsupported flag suffix")
                    meals[index] = meal.copy(liked = true)
                }
                id != null -> {
                    if (!fields.add("id")) reader.warn(line, "Repeated record identifier")
                    val clean = reader.id(id.groupValues[1], line)
                    if (clean == "custom") reader.warn(line, "Reserved custom meal would be omitted by JS serialization")
                    if (clean.isNotEmpty()) {
                        if (meals.withIndex().any { it.index != index && it.value.id == clean }) reader.warn(line, "Duplicate meal identifier: $clean")
                        else meals[index] = meal.copy(id = clean)
                    }
                }
                macros != null -> {
                    if (!fields.add("macros")) reader.warn(line, "Repeated Per100g row")
                    val rawCalories = macros.groupValues[1].toDoubleOrNull() ?: Double.POSITIVE_INFINITY
                    val calories = floor(rawCalories + 0.5)
                    if (!calories.isFinite() || calories != rawCalories) reader.warn(line, "Calories rounded or non-finite")
                    meals[index] = meal.copy(cals100 = calories,
                        p100 = reader.number(macros.groupValues[2], 0.0, 999.0, 0.0, line),
                        c100 = reader.number(macros.groupValues[3], 0.0, 999.0, 0.0, line),
                        f100 = reader.number(macros.groupValues[4], 0.0, 999.0, 0.0, line))
                }
                else -> reader.warn(line)
            }
        }
        val ids = mutableSetOf<String>()
        meals.forEachIndexed { index, meal ->
            if (!ids.add(meal.id)) reader.warn(starts[index], "Duplicate meal identifier: ${meal.id}")
        }
        return reader.document(meals.filter { it.name.isNotEmpty() })
    }

    fun routinesToMd(document: Document<List<Routine>>): String {
        require(document.canRewrite) { "Cannot serialize a diagnostic-bearing document; use its retained source" }
        return routinesToMd(document.value)
    }

    fun mealsToMd(document: Document<List<Meal>>): String {
        require(document.canRewrite) { "Cannot serialize a diagnostic-bearing document; use its retained source" }
        return mealsToMd(document.value)
    }

    fun routinesToMd(routines: List<Routine>): String {
        require(routines.map { it.id }.distinct().size == routines.size) { "Duplicate routine identifiers" }
        val lines = mutableListOf("# Routines", "")
        for (routine in routines) {
            require(recordId.matches(routine.id)) { "Invalid routine identifier" }
            inlineName(routine.name)
            require(!routine.name.equals("routines", true) && !routine.name.equals("weekly schedule", true)) { "Reserved routine name" }
            require(routine.items.map { it.exerciseId }.distinct().size == routine.items.size) { "Duplicate exercise identifiers" }
            val counts = routine.items.mapNotNull { it.superset }.groupingBy { it }.eachCount()
            require(counts.all { it.key.isNotEmpty() && it.value == 2 }) { "Supersets must contain exactly two exercises" }
            lines.add("## ${routine.name}")
            lines.add("- id: ${routine.id}")
            if (routine.liked) lines.add("- liked")
            if (routine.secondary) lines.add("- secondary")
            val groups = linkedMapOf<String, Int>()
            val members = mutableMapOf<String, Int>()
            for (item in routine.items) {
                require(item.exerciseId in validExerciseIds) { "Unknown exercise identifier" }
                require(item.sets in 1..20 && item.reps in 0..60) { "Routine counts outside JS parser limits" }
                require((item.mode == Mode.TIMED) == (item.unit != null)) { "Timed items require a unit; other modes cannot retain one" }
                lines.add(buildString {
                    append("${item.exerciseId} ${item.sets} * ${item.reps}")
                    when (item.mode) {
                        Mode.TIMED -> append(if (item.unit == Unit.SEC) " timed sec" else " timed min")
                        Mode.REPS -> append(" reps")
                        null -> kotlin.Unit
                    }
                    if (item.weighted) append(" weighted")
                    if (item.unweighted) append(" unweighted")
                    item.superset?.let { token ->
                        val group = groups.getOrPut(token) { groups.size + 1 }
                        val member = (members[token] ?: 0) + 1
                        members[token] = member
                        append(" ss$group$member")
                    }
                })
            }
            lines.add("")
        }
        val text = lines.joinToString("\n").trim() + "\n"
        val check = parseRoutinesMd(text)
        require(check.canRewrite && canonicalGroups(check.value) == canonicalGroups(routines)) { "Routine values cannot round-trip through JS format" }
        return text
    }

    fun mealsToMd(meals: List<Meal>): String {
        require(meals.map { it.id }.distinct().size == meals.size) { "Duplicate meal identifiers" }
        val lines = mutableListOf("# Meal Library", "")
        for (meal in meals) {
            require(recordId.matches(meal.id) && meal.id != "custom") { "Invalid or reserved meal identifier" }
            inlineName(meal.name)
            require(meal.defaultGrams.isFinite() && meal.defaultGrams in 1.0..5000.0) { "Invalid default grams" }
            require(meal.cals100.isFinite() && meal.cals100 >= 0.0 && floor(meal.cals100) == meal.cals100) { "Calories must be a finite nonnegative integer" }
            require(listOf(meal.p100, meal.c100, meal.f100).all { it.isFinite() && it in 0.0..999.0 }) { "Invalid per100 macros" }
            lines.add("${meal.name} (${number(meal.defaultGrams)}g)")
            lines.add("Per100g ${number(meal.cals100)}cal ${number(meal.p100)}pro ${number(meal.c100)}carb ${number(meal.f100)}fat")
            lines.add("- id: ${meal.id}")
            if (meal.liked) lines.add("- liked")
            lines.add("")
        }
        val text = lines.joinToString("\n").trim() + "\n"
        val check = parseMealsMd(text)
        require(check.canRewrite && check.value == meals) { "Meal values cannot round-trip through JS format" }
        return text
    }

    private fun inlineName(name: String) {
        require(name.isNotEmpty() && name.length <= 40 && name == name.trim() && '\r' !in name && '\n' !in name) { "Expected a trimmed single-line name of 1 to 40 characters" }
    }

    private fun number(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun canonicalGroups(routines: List<Routine>): List<Routine> = routines.map { routine ->
        val groups = linkedMapOf<String, String>()
        routine.copy(items = routine.items.map { item ->
            item.copy(superset = item.superset?.let { groups.getOrPut(it) { (groups.size + 1).toString() } })
        })
    }
}
