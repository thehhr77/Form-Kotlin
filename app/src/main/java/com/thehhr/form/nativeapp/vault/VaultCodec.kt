package com.thehhr.form.nativeapp.vault

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.floor

object VaultCodec {
    private val heading = Regex("^#{1,6}\\s+(.+)$")
    private val field = Regex("^([A-Za-z][\\w-]*)\\s*(?:\\(([^)]*)\\))?\\s*:\\s*(.*)$")
    private val exerciseId = Regex("(?:[0-9]{4,5}|c-[\\w-]+)")
    private val recordId = Regex("[A-Za-z0-9_-]{1,64}")
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val days = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
    private val profileLimits = linkedMapOf(
        "age" to Triple(10.0, 110.0, 22.0), "height" to Triple(50.0, 300.0, 178.0),
        "current-weight" to Triple(20.0, 500.0, 75.0), "start-weight" to Triple(20.0, 500.0, 75.0),
        "goal-weight" to Triple(20.0, 500.0, 78.0), "activity" to Triple(1.0, 3.0, 1.55),
        "strategy" to Triple(-1000.0, 1000.0, 250.0), "protein-rate" to Triple(0.5, 5.0, 2.0)
    )
    private val booleanPrefs = setOf("workout-reminder", "rest-enabled", "show-secondary-pills")
    private val enumPrefs = mapOf(
        "accent" to listOf("red", "blue", "green", "orange", "purple", "pink"),
        "default-view" to listOf("week", "month", "all"),
        "pill-routine" to listOf("default", "pin", "hidden"),
        "pill-category" to listOf("default", "pin", "hidden"),
        "pill-target" to listOf("default", "pin", "hidden"),
        "pill-equipment" to listOf("default", "pin", "hidden"),
        "pill-tags-host" to listOf("equipment", "routine", "category", "target"),
        "pill-toggles" to listOf("equipment", "routine", "category", "target")
    )

    private data class Line(val number: Int, val text: String) {
        val body: String get() = text.removePrefix("-").trim()
    }

    private class Reader(val source: String, val kind: VaultKind) {
        val diagnostics = mutableListOf<VaultDiagnostic>()
        val lines = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
            .split('\n').mapIndexedNotNull { index, raw ->
                val text = raw.trim()
                when {
                    text.isEmpty() -> null
                    text.startsWith("//") || (text.startsWith("<!--") && text.endsWith("-->")) -> {
                        diagnostics.add(VaultDiagnostic(index + 1, "Comment preserved in source; typed replacement would discard it"))
                        null
                    }
                    else -> Line(index + 1, text)
                }
            }
        init {
            if (lines.isNotEmpty() && !lines.first().text.equals("# ${kind.heading}", true)) {
                warn(lines.first(), "Missing or noncanonical document heading")
            }
        }
        fun warn(line: Line, message: String = "Unsupported content preserved in source") {
            diagnostics.add(VaultDiagnostic(line.number, message))
        }
        fun number(raw: String?, low: Double, high: Double, fallback: Double, line: Line): Double {
            val n = raw?.let { if (it.isBlank()) 0.0 else it.toDoubleOrNull() }
            val value = n?.takeIf { it.isFinite() }?.coerceIn(low, high) ?: fallback
            if (raw != null && (n == null || !n.isFinite() || n != value)) warn(line, "Numeric value normalized: $raw")
            return value
        }
        fun name(raw: String, line: Line, max: Int = 40): String {
            val value = raw.trim().take(max)
            if (value != raw || value.isEmpty()) warn(line, "Name normalized or empty")
            return value
        }
        fun id(raw: String?, prefix: String, seed: String, line: Line, clean: Boolean = false): String {
            if (raw.isNullOrBlank()) return stableId(prefix, seed)
            val result = if (clean) raw.replace(Regex("[^A-Za-z0-9_-]"), "").take(64) else raw
            if (result != raw || result.isEmpty()) warn(line, "Identifier normalized")
            return result.ifEmpty { stableId(prefix, seed) }
        }
        fun <T> document(value: T): VaultDocument<T> = VaultDocument(kind, source, value, diagnostics.toList())
    }

    private fun stableId(prefix: String, seed: String): String =
        "$prefix-${UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))}"

    fun parseDateHeading(text: String): String? {
        val raw = text.trim()
        val iso = Regex("^(\\d{4}-\\d{2}-\\d{2})").find(raw)?.groupValues?.get(1)
        if (iso != null) return validDate(iso)
        val match = Regex("^(?:Sun|Mon|Tue|Wed|Thu|Fri|Sat),?\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(\\d{1,2}),?\\s+(\\d{4})$", RegexOption.IGNORE_CASE)
            .matchEntire(raw) ?: return null
        return try {
            LocalDate.of(match.groupValues[3].toInt(), months.indexOfFirst { it.equals(match.groupValues[1], true) } + 1,
                match.groupValues[2].toInt()).toString()
        } catch (_: RuntimeException) { null }
    }

    private fun validDate(value: String): String? = try {
        LocalDate.parse(value).toString().takeIf { it == value }
    } catch (_: RuntimeException) { null }

    fun dateHeading(date: String, style: VaultDateStyle = VaultDateStyle.READABLE): String {
        requireWrite(validDate(date) != null, "Invalid date: $date")
        return if (style == VaultDateStyle.ISO) date else LocalDate.parse(date)
            .format(DateTimeFormatter.ofPattern("EEE, MMM d, uuuu", Locale.ENGLISH))
    }

    fun <T> encode(document: VaultDocument<T>): String = document.source

    fun parse(fileName: String, text: String): VaultDocument<*> = when (fileName) {
        VaultKind.ROUTINES.fileName -> parseRoutines(text)
        VaultKind.MEALS.fileName -> parseMeals(text)
        VaultKind.TRAINING_LOGS.fileName -> parseTrainingLogs(text)
        VaultKind.NUTRITION_DIARY.fileName -> parseNutritionDiary(text)
        VaultKind.CONFIG.fileName -> parseConfig(text)
        else -> throw IllegalArgumentException("Unknown vault file: $fileName")
    }

    fun parseRoutines(text: String): VaultDocument<List<VaultRoutine>> {
        val r = Reader(text, VaultKind.ROUTINES)
        val result = mutableListOf<VaultRoutine>()
        var current: VaultRoutine? = null
        var start = Line(1, "")
        fun flush() {
            val routine = current ?: return
            val counts = routine.items.mapNotNull { it.superset }.groupingBy { it }.eachCount()
            val items = routine.items.map { item ->
                if (item.superset != null && counts[item.superset] != 2) {
                    r.warn(start, "Superset must have exactly two members")
                    item.copy(superset = null)
                } else item
            }
            result.add(routine.copy(items = items))
            current = null
        }
        for (line in r.lines) {
            val h = heading.matchEntire(line.text)?.groupValues?.get(1)
            if (h != null) {
                flush()
                if (h.equals("Routines", true)) continue
                if (h.equals("Weekly Schedule", true)) { r.warn(line); continue }
                start = line
                current = VaultRoutine(stableId("r", "$h:${result.size}"), r.name(h, line))
                continue
            }
            val routine = current
            if (routine == null) { r.warn(line); continue }
            val body = line.body
            val f = field.matchEntire(body)
            when {
                body.equals("liked", true) || Regex("^liked:\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(body) -> current = routine.copy(liked = true)
                body.equals("secondary", true) || Regex("^secondary:\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(body) -> current = routine.copy(secondary = true)
                body.equals("liked: false", true) || body.equals("secondary: false", true) -> Unit
                f != null && f.groupValues[1].equals("id", true) -> current = routine.copy(id = r.id(f.groupValues[3], "r", routine.id, line, true))
                else -> {
                    val m = Regex("^(\\d{4,5}|c-[\\w-]+)\\s+(\\d+)\\s*\\*\\s*(\\d+)(?:\\s+(\\S.*))?$").matchEntire(body.removePrefix("#").trim())
                    if (m == null) { r.warn(line); continue }
                    val id = m.groupValues[1]
                    if (routine.items.any { it.exerciseId == id }) { r.warn(line, "Duplicate exercise identifier"); continue }
                    val suffix = m.groupValues[4]
                    fun token(value: String) = Regex("\\b$value\\b", RegexOption.IGNORE_CASE).containsMatchIn(suffix)
                    val ss = Regex("\\bss(\\d+)(\\d)\\b", RegexOption.IGNORE_CASE).find(suffix)?.groupValues?.get(1)
                    if (suffix.split(Regex("\\s+")).any { it.isNotEmpty() && !it.matches(Regex("timed|reps|weighted|unweighted|sec|min|ss\\d{2,}", RegexOption.IGNORE_CASE)) }) r.warn(line, "Unknown routine modifier")
                    val item = VaultRoutineItem(id,
                        r.number(m.groupValues[2], 1.0, 20.0, 3.0, line).toInt(),
                        r.number(m.groupValues[3], 0.0, 60.0, 3.0, line).toInt(),
                        if (token("timed")) "timed" else if (token("reps")) "reps" else null,
                        if (token("sec")) "sec" else if (token("min")) "min" else null,
                        token("weighted"), token("unweighted"), ss)
                    current = routine.copy(items = routine.items + item)
                }
            }
        }
        flush()
        if (result.map { it.id }.distinct().size != result.size) r.warn(start, "Duplicate routine identifier")
        return r.document(result.toList())
    }

    fun parseMeals(text: String): VaultDocument<List<VaultMeal>> {
        val r = Reader(text, VaultKind.MEALS)
        val result = mutableListOf<VaultMeal>()
        var current: VaultMeal? = null
        fun flush() { current?.let { result.add(it) }; current = null }
        val macro = Regex("^Per100g\\s+(\\d+(?:\\.\\d+)?)\\s*cal\\s+(\\d+(?:\\.\\d+)?)\\s*pro\\s+(\\d+(?:\\.\\d+)?)\\s*carb\\s+(\\d+(?:\\.\\d+)?)\\s*fat$", RegexOption.IGNORE_CASE)
        for (line in r.lines) {
            if (line.text.equals("# Meal Library", true)) continue
            val h = Regex("^(.+)\\s*\\(\\s*(\\d+(?:\\.\\d+)?)\\s*g\\s*\\)$", RegexOption.IGNORE_CASE).matchEntire(line.text)
            if (h != null) {
                flush()
                val name = r.name(h.groupValues[1].trim(), line)
                current = VaultMeal(stableId("m", "$name:${result.size}"), name, r.number(h.groupValues[2], 1.0, 5000.0, 100.0, line))
                continue
            }
            if (heading.matches(line.text)) { flush(); r.warn(line); continue }
            val meal = current
            if (meal == null) { r.warn(line); continue }
            val f = field.matchEntire(line.body)
            val m = macro.matchEntire(line.body)
            when {
                line.body.equals("liked", true) || Regex("^liked:\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(line.body) -> current = meal.copy(liked = true)
                line.body.equals("liked: false", true) -> Unit
                f != null && f.groupValues[1].equals("id", true) -> current = meal.copy(id = r.id(f.groupValues[3], "m", meal.id, line, true))
                m != null -> current = meal.copy(cals100 = rounded(r.number(m.groupValues[1], 0.0, Double.MAX_VALUE, 0.0, line), 1.0),
                    p100 = r.number(m.groupValues[2], 0.0, 999.0, 0.0, line), c100 = r.number(m.groupValues[3], 0.0, 999.0, 0.0, line), f100 = r.number(m.groupValues[4], 0.0, 999.0, 0.0, line))
                else -> r.warn(line)
            }
        }
        flush()
        if (result.map { it.id }.distinct().size != result.size) r.warn(Line(1, ""), "Duplicate meal identifier")
        return r.document(result.toList())
    }

    fun parseTrainingLogs(text: String): VaultDocument<List<VaultTrainingLog>> {
        val r = Reader(text, VaultKind.TRAINING_LOGS)
        val result = mutableListOf<VaultTrainingLog>()
        var date: String? = null
        var block = linkedMapOf<String, String>()
        var start = Line(1, "")
        val known = setOf("exercise", "exerciseid", "date", "sets", "reps", "setreps", "weight", "weight(kg)", "setweights", "int", "interval", "intervals", "dur(sec)", "dur(min)", "dur", "duration", "dist(km)", "dist", "distance", "notes", "id")
        fun flush() {
            if (block.isEmpty()) return
            val id = block["exerciseid"]?.removePrefix("#")?.trim()
            val logDate = block["date"] ?: date
            if (id == null || !exerciseId.matches(id) || logDate == null || validDate(logDate) == null) {
                r.warn(start, "Training block requires a string exercise identifier and valid date")
                block = linkedMapOf()
                return
            }
            fun numbers(raw: String?, max: Double, min: Double = 0.0): List<Double> {
                val values = raw?.split(',')?.filter { it.isNotBlank() }?.map { r.number(it.trim(), min, max, min, start) } ?: emptyList()
                if (values.size > 20) r.warn(start, "Per-set list truncated to 20 entries")
                return values.take(20)
            }
            val rawDur = block["dur(sec)"] ?: block["dur(min)"] ?: block["dur"] ?: block["duration"]
            val unit = if (block.containsKey("dur(sec)")) "sec" else if (rawDur != null) "min" else null
            val durations = numbers(rawDur, if (unit == "sec") 36000.0 else 600.0).map { rounded(if (unit == "sec") it / 60 else it, 100.0) }
            val distances = numbers(block["dist(km)"] ?: block["dist"] ?: block["distance"], 500.0).map { rounded(it, 10.0) }
            val intervalsRaw = block["int"] ?: block["intervals"] ?: block["interval"]
            val timed = (intervalsRaw?.toDoubleOrNull() ?: 0.0) > 0 || durations.isNotEmpty() || distances.isNotEmpty()
            val notes = block["notes"].orEmpty().take(160)
            if (notes != block["notes"].orEmpty()) r.warn(start, "Notes truncated to 160 characters")
            val logId = r.id(block["id"], "log", "$logDate:$id:${result.size}", start)
            if (timed) {
                val count = r.number(intervalsRaw, 1.0, 20.0, (durations.size.takeIf { it > 0 } ?: distances.size.takeIf { it > 0 } ?: 1).toDouble(), start).toInt()
                if (block.keys.any { it in setOf("sets", "reps", "weight", "weight(kg)", "setweights", "setreps") }) r.warn(start, "Mixed timed and strength fields")
                if (durations.size > count || distances.size > count) r.warn(start, "Timed values exceed interval count")
                result.add(VaultTrainingLog(logId, id, logDate, intervals = count,
                    setDurations = durations.take(count).takeIf { it.any { v -> v > 0 } } ?: emptyList(),
                    setDistances = distances.take(count).takeIf { it.any { v -> v > 0 } } ?: emptyList(), durUnit = unit, notes = notes, exerciseLabel = block["exercise"] ?: "\"Unknown exercise\""))
            } else {
                val weights = numbers(block["weight(kg)"] ?: block["setweights"], 2000.0)
                val sets = r.number(block["sets"], 1.0, 20.0, 1.0, start).toInt()
                val capacity = weights.size.takeIf { it > 0 } ?: sets
                val rawReps = numbers(block["setreps"] ?: block["reps"], 100.0, 1.0)
                if (rawReps.size > capacity) r.warn(start, "Per-set reps exceed set count")
                if (rawReps.any { it != it.toInt().toDouble() }) r.warn(start, "Per-set reps normalized")
                val reps = rawReps.firstOrNull()?.toInt() ?: 1
                val repsList = rawReps.map { it.toInt() }.take(capacity).takeIf { rawReps.size > 1 || block.containsKey("setreps") } ?: emptyList()
                result.add(VaultTrainingLog(logId, id, logDate, sets, reps,
                    block["weight"]?.let { r.number(it, 0.0, 2000.0, 0.0, start) }, weights,
                    repsList, notes = notes, exerciseLabel = block["exercise"] ?: "\"Unknown exercise\""))
            }
            block = linkedMapOf()
        }
        for (line in r.lines) {
            if (line.text.equals("# Training Log", true)) continue
            val h = heading.matchEntire(line.text)?.groupValues?.get(1)
            if (h != null) {
                flush()
                date = parseDateHeading(h)
                if (date == null) r.warn(line, "Unrecognized date heading")
                continue
            }
            if (date == null) { r.warn(line, "Training content outside a date heading"); continue }
            val f = field.matchEntire(line.body)
            if (f != null && f.groupValues[1].equals("exercise", true)) { flush(); start = line }
            if (f != null && (block.isNotEmpty() || f.groupValues[1].equals("exercise", true))) {
                val key = f.groupValues[1].lowercase(Locale.ROOT) + f.groupValues[2].lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }?.let { "($it)" }.orEmpty()
                if (key !in known || block.containsKey(key)) r.warn(line, "Unknown or repeated training field: $key")
                block[key] = f.groupValues[3].trim()
                continue
            }
            flush()
            val parts = line.body.split('|').map { it.trim() }
            if (parts.size < 2 || !exerciseId.matches(parts[0].removePrefix("#"))) { r.warn(line); continue }
            start = line
            block["exerciseid"] = parts[0].removePrefix("#")
            val data = parts.drop(1).takeWhile { !it.startsWith("notes:", true) && !it.startsWith("id:", true) }.joinToString(" | ")
            val timed = Regex("\\bintervals?\\b|\\bmin\\b", RegexOption.IGNORE_CASE).containsMatchIn(data)
            if (timed) {
                block["int"] = Regex("(\\d+)\\s*intervals?", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1) ?: "1"
                for ((suffix, key) in listOf("min" to "dur(min)", "sec" to "dur(sec)", "km" to "dist(km)")) {
                    Regex("([\\d.,\\s]+)\\s*$suffix", RegexOption.IGNORE_CASE).find(data)?.let { block[key] = it.groupValues[1].trim() }
                }
            } else {
                block["sets"] = Regex("(\\d+)\\s*sets?", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1) ?: "1"
                val explicitReps = parts.firstOrNull { Regex("\\breps?\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.replace(Regex("reps?", RegexOption.IGNORE_CASE), "")?.trim()
                if (explicitReps != null) block["setreps"] = explicitReps
                else block["reps"] = Regex("(\\d+)\\s*x\\s*(\\d+)").find(data)?.groupValues?.get(2) ?: "10"
                parts.firstOrNull { it.contains("kg", true) }?.let { block["weight(kg)"] = it.replace(Regex("kg", RegexOption.IGNORE_CASE), "").trim() }
            }
            Regex("notes:\\s*(.*?)(?:\\s*\\|\\s*id:|$)", RegexOption.IGNORE_CASE).find(line.body)?.let { block["notes"] = it.groupValues[1].trim() }
            Regex("id:\\s*([^\\s|]+)\\s*$", RegexOption.IGNORE_CASE).find(line.body)?.let { block["id"] = it.groupValues[1] }
            r.warn(line, "Legacy pipe log preserved; use source editing to retain all legacy fields")
            flush()
        }
        flush()
        if (result.map { it.id }.distinct().size != result.size) r.warn(start, "Duplicate training identifier")
        return r.document(result.toList())
    }

    fun parseNutritionDiary(text: String): VaultDocument<Map<String, VaultDiaryDay>> {
        val r = Reader(text, VaultKind.NUTRITION_DIARY)
        val result = linkedMapOf<String, VaultDiaryDay>()
        var date: String? = null
        var category = "Snacks"
        var lastMeal = false
        val bold = Regex("^(.*?)\\s+(\\d+)\\s+kcal\\s+·\\s+\\*\\*([\\d.]+)p\\s+·\\s+([\\d.]+)c\\s+·\\s+([\\d.]+)f\\*\\*\\s*$", RegexOption.IGNORE_CASE)
        val legacy = Regex("^(.*?)\\s*\\|\\s*(\\d+)\\s*kcal\\s*\\|\\s*([\\d.]+)\\s*p\\s*·\\s*([\\d.]+)\\s*c\\s*·\\s*([\\d.]+)\\s*f(?:\\s*\\|\\s*id:\\s*(\\S+))?$", RegexOption.IGNORE_CASE)
        for (line in r.lines) {
            if (line.text.equals("# Nutrition Diary", true)) continue
            val h = heading.matchEntire(line.text)?.groupValues?.get(1)
            if (h != null) {
                lastMeal = false
                val parsed = parseDateHeading(h)
                if (parsed != null) { date = parsed; category = "Snacks"; result.putIfAbsent(parsed, VaultDiaryDay()) }
                else if (Regex("^\\d{4}-\\d{2}-\\d{2}|^(Sun|Mon|Tue|Wed|Thu|Fri|Sat),?\\s", RegexOption.IGNORE_CASE).containsMatchIn(h)) { date = null; r.warn(line, "Invalid date heading") }
                else if (date != null) category = h.replace(Regex("\\b\\w")) { it.value.uppercase(Locale.ROOT) }
                else r.warn(line)
                continue
            }
            val key = date
            if (key == null) { r.warn(line); continue }
            val day = result.getValue(key)
            val water = Regex("^water:\\s*(\\d+(?:\\.\\d+)?)\\s*ml$", RegexOption.IGNORE_CASE).matchEntire(line.body)
            if (water != null) { result[key] = day.copy(water = r.number(water.groupValues[1], 0.0, 50000.0, 0.0, line)); continue }
            val f = field.matchEntire(line.body)
            if (f != null && f.groupValues[1].equals("id", true)) {
                if (lastMeal && day.meals.isNotEmpty()) result[key] = day.copy(meals = day.meals.dropLast(1) + day.meals.last().copy(id = f.groupValues[3]))
                else r.warn(line, "Orphan diary identifier")
                continue
            }
            val m = bold.matchEntire(line.body) ?: legacy.matchEntire(line.body)
            if (m == null) { r.warn(line); continue }
            fun macro(index: Int, scale: Double) = rounded(r.number(m.groupValues[index], 0.0, 100000.0, 0.0, line), scale)
            val name = r.name(m.groupValues[1].trim(), line)
            val id = r.id(m.groupValues.getOrNull(6), "meal-log", "$key:$name:${day.meals.size}", line)
            result[key] = day.copy(meals = day.meals + VaultDiaryMeal(id, name, macro(2, 1.0), macro(3, 10.0), macro(4, 10.0), macro(5, 10.0), category))
            lastMeal = true
        }
        val ids = result.values.flatMap { it.meals }.map { it.id }
        if (ids.distinct().size != ids.size) r.warn(Line(1, ""), "Duplicate diary identifier")
        return r.document(result.toMap())
    }

    fun parseConfig(text: String): VaultDocument<VaultConfig> {
        val r = Reader(text, VaultKind.CONFIG)
        val profile = linkedMapOf<String, Double>()
        var sex: String? = null
        val overrides = linkedMapOf<String, Double>()
        val schedule = linkedMapOf<Int, String>()
        var liked = emptyList<String>()
        val prefs = linkedMapOf<String, String>()
        val custom = mutableListOf<VaultCustomExercise>()
        val tags = linkedMapOf<String, List<String>>()
        var tagId: String? = null
        var section = ""
        val sections = setOf("config", "profile", "targets", "macro overrides", "weekly schedule", "preferences", "custom exercises", "exercise tags")
        for (line in r.lines) {
            val h = heading.matchEntire(line.body)?.groupValues?.get(1)
            if (h != null) {
                section = h.lowercase(Locale.ROOT)
                tagId = null
                if (section !in sections) r.warn(line, "Unknown config section: $h")
                continue
            }
            val f = field.matchEntire(line.body)
            if (f == null) { r.warn(line); continue }
            val key = f.groupValues[1]
            val raw = f.groupValues[3].trim()
            when (section) {
                "profile" -> {
                    val limits = profileLimits[key]
                    when {
                        key == "sex" -> {
                            sex = if (Regex("^[mf]", RegexOption.IGNORE_CASE).containsMatchIn(raw)) "m" else "f"
                            if (sex != raw) r.warn(line, "Sex normalized by the original web format: $raw -> $sex")
                        }
                        limits != null -> profile[key] = r.number(raw, limits.first, limits.second, limits.third, line)
                        else -> r.warn(line)
                    }
                }
                "targets", "macro overrides" -> {
                    val target = when (key) { "calories", "cals" -> "cals"; "protein", "p" -> "p"; "carbs", "c" -> "c"; "fats", "f" -> "f"; "water" -> "water"; else -> null }
                    if (target == null) r.warn(line)
                    else if (raw.toDoubleOrNull()?.isFinite() == false || raw.toDoubleOrNull() == null) r.warn(line, "Invalid target")
                    else overrides[target] = r.number(raw, if (target in setOf("cals", "water")) 500.0 else 0.0, if (target in setOf("cals", "water")) 10000.0 else 1000.0, 0.0, line)
                }
                "weekly schedule" -> if (key in days) schedule[days.indexOf(key)] = raw else r.warn(line)
                "preferences" -> when {
                    key == "liked" -> {
                        liked = raw.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
                        if (liked.any { !exerciseId.matches(it) }) r.warn(line, "Unrecognized liked identifier retained")
                    }
                    key in booleanPrefs -> prefs[key] = Regex("^(true|yes|1|on)", RegexOption.IGNORE_CASE).containsMatchIn(raw).toString()
                    key in enumPrefs -> prefs[key] = raw.takeIf { it in enumPrefs.getValue(key) } ?: enumPrefs.getValue(key).first()
                    key == "week-start" -> prefs[key] = (raw.toDoubleOrNull()?.takeIf { it in listOf(0.0, 1.0, 6.0) }?.toInt() ?: 1).toString()
                    key == "rest-between-sets" || key == "rest-between-exercises" -> prefs[key] = num(r.number(raw, 30.0, 180.0, if (key == "rest-between-sets") 60.0 else 90.0, line))
                    else -> r.warn(line)
                }
                "custom exercises" -> {
                    if (key == "name") custom.add(VaultCustomExercise(stableId("c", "$raw:${custom.size}"), raw))
                    else if (custom.isEmpty()) r.warn(line, "Orphan custom exercise field")
                    else {
                        val last = custom.last()
                        custom[custom.lastIndex] = when (key) {
                            "id" -> last.copy(id = raw)
                            "category" -> last.copy(category = raw)
                            "target" -> last.copy(target = raw)
                            "equipment" -> last.copy(equipment = raw)
                            "description" -> last.copy(description = raw.replace(" / ", "\n"))
                            else -> { r.warn(line); last }
                        }
                    }
                }
                "exercise tags" -> when {
                    key == "id" -> { tagId = raw.removePrefix("#").trim(); tags.putIfAbsent(tagId, emptyList()) }
                    key == "tags" && tagId != null -> {
                        val values = raw.split(',').map { it.replace(Regex("[,|#]"), " ").replace(Regex("\\s+"), " ").trim().take(24) }.filter { it.isNotEmpty() }
                        tags[tagId] = (tags.getValue(tagId) + values).distinctBy { it.lowercase(Locale.ROOT) }
                    }
                    else -> r.warn(line)
                }
                else -> r.warn(line)
            }
        }
        if (custom.map { it.id }.distinct().size != custom.size) r.warn(Line(1, ""), "Duplicate custom exercise identifier")
        return r.document(VaultConfig(profile.toMap(), sex, overrides.toMap(), schedule.toMap(), liked, prefs.toMap(), custom.toList(), tags.toMap()))
    }

    private fun rounded(value: Double, scale: Double): Double = floor(value * scale + 0.5) / scale
    private fun num(value: Double): String {
        requireWrite(value.isFinite(), "Non-finite number")
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
    private fun requireWrite(condition: Boolean, message: String) {
        if (!condition) throw VaultWriteException(message)
    }
    private fun inline(value: String): String {
        requireWrite(!value.contains('\n') && !value.contains('\r') && value == value.trim(), "Expected trimmed single-line text")
        return value
    }
    private fun identifier(value: String, exercise: Boolean = false): String {
        requireWrite(if (exercise) exerciseId.matches(value) else recordId.matches(value), "Unsupported identifier: $value")
        return value
    }
    private fun <T> write(value: T, original: VaultDocument<T>?, kind: VaultKind, parse: (String) -> VaultDocument<T>, render: () -> String): String {
        if (original != null) {
            requireWrite(original.kind == kind, "Document kind mismatch")
            if (value == original.value) return original.source
            requireWrite(original.canRewrite, "Typed replacement rejected: ${original.diagnostics.joinToString { "line ${it.line}: ${it.message}" }}")
        }
        val text = render()
        val check = parse(text)
        requireWrite(check.canRewrite, "Generated document contains unsupported content: ${check.diagnostics}")
        requireWrite(check.value == value, "Values cannot round-trip through the original format without normalization; normalize explicitly before writing")
        return text
    }

    fun encodeRoutines(value: List<VaultRoutine>, original: VaultDocument<List<VaultRoutine>>? = null): String =
        write(value, original, VaultKind.ROUTINES, ::parseRoutines) {
            buildString {
                append("# Routines\n")
                for (routine in value) {
                    append("\n## ${inline(routine.name)}\n- id: ${identifier(routine.id)}\n")
                    if (routine.liked) append("- liked\n")
                    if (routine.secondary) append("- secondary\n")
                    val groups = linkedMapOf<String, Int>()
                    for (item in routine.items) {
                        append("${identifier(item.exerciseId, true)} ${item.sets} * ${item.reps}")
                        item.mode?.let { requireWrite(it == "timed" || it == "reps", "Unknown routine mode"); append(" $it") }
                        item.unit?.let { requireWrite(it == "sec" || it == "min", "Unknown time unit"); append(" $it") }
                        if (item.weighted) append(" weighted")
                        if (item.unweighted) append(" unweighted")
                        item.superset?.let {
                            requireWrite(it.matches(Regex("[1-9][0-9]*")), "Superset groups must use their exported numeric string labels")
                            val member = (groups[it] ?: 0) + 1
                            groups[it] = member
                            append(" ss$it$member")
                        }
                        append('\n')
                    }
                }
            }
        }

    fun encodeMeals(value: List<VaultMeal>, original: VaultDocument<List<VaultMeal>>? = null): String =
        write(value, original, VaultKind.MEALS, ::parseMeals) {
            buildString {
                append("# Meal Library\n")
                for (meal in value) {
                    requireWrite(meal.id != "custom", "The web format reserves custom as a non-library meal")
                    append("\n${inline(meal.name)} (${num(meal.defaultGrams)}g)\n")
                    append("Per100g ${num(meal.cals100)}cal ${num(meal.p100)}pro ${num(meal.c100)}carb ${num(meal.f100)}fat\n")
                    append("- id: ${identifier(meal.id)}\n")
                    if (meal.liked) append("- liked\n")
                }
            }
        }

    fun encodeTrainingLogs(value: List<VaultTrainingLog>, original: VaultDocument<List<VaultTrainingLog>>? = null, dateStyle: VaultDateStyle = VaultDateStyle.READABLE): String =
        write(value, original, VaultKind.TRAINING_LOGS, ::parseTrainingLogs) {
            buildString {
                append("# Training Log\n")
                var date: String? = null
                for (log in value) {
                    if (date != log.date) { date = log.date; append("\n## ${dateHeading(log.date, dateStyle)}\n") }
                    append("\n- exercise: ${inline(log.exerciseLabel)}\n\t- exerciseId: #${identifier(log.exerciseId, true)}\n")
                    if (log.timed) {
                        append("\t- int: ${log.intervals ?: 1}\n")
                        if (log.setDurations.isNotEmpty()) {
                            val unit = log.durUnit ?: "min"
                            requireWrite(unit == "sec" || unit == "min", "Unknown duration unit")
                            append("\t- dur($unit): ${log.setDurations.joinToString(", ") { num(rounded(it * if (unit == "sec") 60 else 1, 100.0)) }}\n")
                        }
                        if (log.setDistances.isNotEmpty()) append("\t- dist(km): ${log.setDistances.joinToString(", ") { num(it) }}\n")
                    } else {
                        append("\t- sets: ${log.sets}\n")
                        if (log.setWeights.isNotEmpty()) append("\t- weight(kg): ${log.setWeights.joinToString(", ") { num(it) }}\n")
                        if (log.weight != null) append("\t- weight: ${num(log.weight)}\n")
                        if (log.setReps.size > 1) append("\t- reps: ${log.setReps.joinToString(", ")}\n") else append("\t- reps: ${log.reps}\n")
                        if (log.setReps.size == 1) append("\t- setreps: ${log.setReps.single()}\n")
                    }
                    if (log.notes.isNotEmpty()) append("\t- notes: ${inline(log.notes)}\n")
                    append("\t- id: ${identifier(log.id)}\n")
                }
            }
        }

    fun encodeNutritionDiary(value: Map<String, VaultDiaryDay>, original: VaultDocument<Map<String, VaultDiaryDay>>? = null, dateStyle: VaultDateStyle = VaultDateStyle.READABLE): String =
        write(value, original, VaultKind.NUTRITION_DIARY, ::parseNutritionDiary) {
            buildString {
                append("# Nutrition Diary\n")
                for ((date, day) in value) {
                    append("\n## ${dateHeading(date, dateStyle)}\nwater: ${num(day.water)} ml\n")
                    var category: String? = null
                    for (meal in day.meals) {
                        if (category != meal.category) { category = meal.category; append("### ${inline(meal.category)}\n") }
                        append("- ${inline(meal.name)} ${num(meal.cals)} kcal · **${num(meal.p)}p · ${num(meal.c)}c · ${num(meal.f)}f**\n")
                        append("\t- id: ${identifier(meal.id)}\n")
                    }
                }
            }
        }

    fun encodeConfig(value: VaultConfig, original: VaultDocument<VaultConfig>? = null): String =
        write(value, original, VaultKind.CONFIG, ::parseConfig) {
            buildString {
                append("# Config\n\n## Profile\n")
                for ((key, number) in value.profile) { requireWrite(key in profileLimits, "Unknown profile field"); append("$key: ${num(number)}\n") }
                value.sex?.let { append("sex: ${inline(it)}\n") }
                append("\n## Targets\n")
                for ((key, number) in value.overrides) { requireWrite(key in setOf("cals", "p", "c", "f", "water"), "Unknown target"); append("$key: ${num(number)}\n") }
                append("\n## Weekly Schedule\n")
                for ((day, routine) in value.schedule) { requireWrite(day in 0..6, "Invalid schedule day"); append("${days[day]}: ${inline(routine)}\n") }
                append("\n## Preferences\nliked: ${value.liked.joinToString(", ") { identifier(it, true) }}\n")
                for ((key, pref) in value.preferences) { requireWrite(key in enumPrefs || key in booleanPrefs || key in setOf("week-start", "rest-between-sets", "rest-between-exercises"), "Unknown preference"); append("$key: ${inline(pref)}\n") }
                append("\n## Custom Exercises\n")
                for (exercise in value.customExercises) {
                    append("- name: ${inline(exercise.name)}\n  id: ${identifier(exercise.id, true)}\n  category: ${inline(exercise.category)}\n  target: ${inline(exercise.target)}\n  equipment: ${inline(exercise.equipment)}\n")
                    if (exercise.description.isNotEmpty()) append("  description: ${inline(exercise.description.replace("\n", " / "))}\n")
                }
                append("\n## Exercise Tags\n")
                for ((id, tags) in value.exerciseTags) {
                    append("- id: ${identifier(id, true)}\n  tags: ${tags.joinToString(", ") { inline(it) }}\n")
                }
            }
        }
}
