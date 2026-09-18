package com.thehhr.form.nativeapp.vault

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class SettingsClipboardCodec(private val catalog: Set<String>, private val equipment: Set<String> = emptySet()) {
    private val categories = setOf("waist", "upper legs", "back", "lower legs", "chest", "upper arms", "cardio", "shoulders", "lower arms", "neck")
    private val row = Regex("^([A-Za-z]+(?:\\([A-Za-z]+\\))?):[ \\t]*(.*)$")

    fun parseLogs(text: String, newId: () -> String = { UUID.randomUUID().toString() }, requireCatalog: Boolean = true): List<VaultTrainingLog> {
        val lines = checkedLines(text)
        require(!text.trimStart().startsWith("[")) { "JSON training imports are not supported: native vault logs cannot retain createdAt/timestamp and unknown JSON fields. Use the web plain-text export." }
        val blocks = mutableListOf<MutableMap<String, String>>()
        for (line in lines.filter { it.isNotBlank() }) {
            val match = requireNotNull(row.matchEntire(line.trim())) { "Unrecognized training row: $line" }
            val key = match.groupValues[1].lowercase(java.util.Locale.ROOT)
            if (key == "exercise") blocks.add(linkedMapOf())
            val block = requireNotNull(blocks.lastOrNull()) { "Each training entry must start with exercise:" }
            require(!block.containsKey(key)) { "Repeated training field: $key" }
            block[key] = match.groupValues[2]
        }
        require(blocks.isNotEmpty()) { "No training entries; use Clear data to clear history" }
        val logs = blocks.map { fields ->
            val allowed = setOf("exercise", "exerciseid", "date", "sets", "reps", "weight(kg)", "weight", "setreps", "int", "dur(sec)", "dur(min)", "dist(km)", "notes", "id")
            require(fields.keys.all { it in allowed }) { "Unsupported training fields: ${fields.keys - allowed}" }
            val exercise = fields.getValue("exerciseid").removePrefix("#")
            require(!requireCatalog || exercise in catalog) { "Unknown exercise ID: $exercise; import matching custom exercises first" }
            val date = fields.getValue("date").also(::date)
            val id = fields["id"] ?: newId()
            val notes = fields["notes"].orEmpty()
            require(notes.length <= 160 && notes == notes.trim() && !notes.contains(Regex("\\s{2,}"))) { "Notes must be trimmed, single-spaced text of at most 160 characters" }
            val timed = fields.keys.any { it in setOf("int", "dur(sec)", "dur(min)", "dist(km)") }
            val label = fields.getValue("exercise")
            require(label.isNotBlank() && label == label.trim()) { "Exercise label is empty or padded" }
            if (timed) {
                require(fields.keys.none { it in setOf("sets", "reps", "weight(kg)", "weight", "setreps") }) { "Mixed strength and timed fields" }
                require(!(fields.containsKey("dur(sec)") && fields.containsKey("dur(min)"))) { "Conflicting duration units" }
                val count = integer(requireNotNull(fields["int"]) { "Timed entries need an int: field with the interval count (1–20)" }, 1, 20)
                val unit = if (fields.containsKey("dur(sec)")) "sec" else if (fields.containsKey("dur(min)")) "min" else null
                val durations = fields["dur(sec)"]?.let { numbers(it, 36000.0, 2).map { value ->
                    val minutes = runCatching { BigDecimal.valueOf(value).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.UNNECESSARY) }
                    require(minutes.isSuccess) { "dur(sec) value ${n(value)} is not exactly representable in hundredths of a minute; use dur(min) or a whole multiple of 0.6 seconds" }
                    minutes.getOrThrow().toDouble()
                } } ?: fields["dur(min)"]?.let { numbers(it, 600.0, 2) }.orEmpty()
                val distances = fields["dist(km)"]?.let { numbers(it, 500.0, 1) }.orEmpty()
                require(durations.size <= count && distances.size <= count) { "Timed lists exceed interval count" }
                VaultTrainingLog(id, exercise, date, intervals = count, setDurations = durations, setDistances = distances, durUnit = unit, notes = notes, exerciseLabel = label)
            } else {
                val sets = integer(fields.getValue("sets"), 1, 20)
                val reps = fields.getValue("reps").split(',').map { integer(it.trim(), 1, 100) }
                val explicit = fields["setreps"]?.split(',')?.map { integer(it.trim(), 1, 100) }
                require(explicit == null || reps.size == 1 && explicit.firstOrNull() == reps.single()) { "Conflicting reps and setreps" }
                val weights = fields["weight(kg)"]?.let { numbers(it, 2000.0, 1) }.orEmpty()
                require(weights.isEmpty() || weights.size == sets) { "Per-set weights must have exactly one value per set" }
                val perSet = explicit ?: reps.takeIf { it.size > 1 }.orEmpty()
                require(perSet.isEmpty() || perSet.size == sets || sets == 1 && perSet.size == 1) { "Per-set reps must match set count" }
                require(!(fields.containsKey("weight") && weights.isNotEmpty())) { "Conflicting scalar and per-set weight" }
                VaultTrainingLog(id, exercise, date, sets, reps.first(), fields["weight"]?.let { number(it, 2000.0, 1) }, weights, perSet, notes = notes, exerciseLabel = label)
            }
        }
        unique(logs.map { it.id })
        VaultCodec.encodeTrainingLogs(logs)
        return logs
    }

    fun exportLogs(logs: List<VaultTrainingLog>): String {
        VaultCodec.encodeTrainingLogs(logs)
        val text = logs.joinToString("\n\n") { log ->
            VaultCodec.encodeTrainingLogs(listOf(log), dateStyle = VaultDateStyle.ISO).lines()
                .filter { it.isNotBlank() && !it.startsWith('#') }.map { it.trim().removePrefix("- ") }
                .toMutableList().apply { add(2, "date: ${log.date}") }.joinToString("\n")
        }
        if (logs.isNotEmpty()) require(parseLogs(text, requireCatalog = false) == logs) { "Training export would lose values" }
        return text.also { checkedLines(it) }
    }

    fun readLogs(source: String): List<VaultTrainingLog> {
        ClipboardCodec.readable(source, VaultKind.TRAINING_LOGS)
        val document = VaultCodec.parseTrainingLogs(source)
        require(document.canRewrite) { "Resolve training_logs.md diagnostics before transfer" }
        var currentDate: String? = null
        val text = source.lines().mapNotNull { raw ->
            val line = raw.trim()
            when {
                line == "# Training Log" || line.isEmpty() -> null
                line.startsWith("## ") -> { currentDate = VaultCodec.parseDateHeading(line.removePrefix("## ")); null }
                line.removePrefix("- ").startsWith("exercise:") -> line.removePrefix("- ") + "\ndate: " + requireNotNull(currentDate)
                else -> line.removePrefix("- ")
            }
        }.joinToString("\n")
        if (document.value.isNotEmpty()) {
            require(parseLogs(text, requireCatalog = false) == document.value) { "Training source has missing IDs or values that would be normalized" }
        }
        return document.value
    }

    fun parseDiary(text: String, newId: () -> String = numericIds()): Map<String, VaultDiaryDay> {
        val lines = checkedLines(text).filter { it.isNotBlank() }.map { it.trim() }
        require(!text.trimStart().startsWith("{")) { "JSON diary imports are not supported; use the web plain-text diary export to avoid dropping unknown fields" }
        val result = linkedMapOf<String, VaultDiaryDay>()
        var current: String? = null
        var waterSeen = false
        var index = 0
        val macro = Regex("^(\\d+(?:\\.\\d+)?)cal (\\d+(?:\\.\\d+)?)pro (\\d+(?:\\.\\d+)?)carb (\\d+(?:\\.\\d+)?)fat$")
        while (index < lines.size) {
            val line = lines[index++]
            when {
                line.startsWith("date: ") -> {
                    current = line.removePrefix("date: ").also(::date)
                    require(!result.containsKey(current)) { "Repeated diary date: $current" }
                    result[current] = VaultDiaryDay()
                    waterSeen = false
                }
                line.startsWith("water: ") -> {
                    val key = requireNotNull(current) { "Water needs a date" }
                    require(!waterSeen && result.getValue(key).meals.isEmpty() && line.endsWith(" ml")) { "Repeated, misplaced or invalid water row" }
                    result[key] = result.getValue(key).copy(water = number(line.removePrefix("water: ").removeSuffix(" ml"), 50000.0, 0))
                    waterSeen = true
                }
                else -> {
                    val key = requireNotNull(current) { "Every diary day must start with date:" }
                    require(!line.contains(':') && !line.startsWith('#') && !line.startsWith('-')) { "Ambiguous diary meal name: $line" }
                    val name = line.substringBeforeLast(" - ", line)
                    val category = if (line.contains(" - ")) line.substringAfterLast(" - ") else "Other"
                    require(ConfigCodec.validCustomExerciseName(name) && ConfigCodec.validCustomExerciseName(category)) { "Invalid meal name or category" }
                    val values = requireNotNull(macro.matchEntire(lines.getOrNull(index++).orEmpty())) { "Every meal needs one calories/macros row" }.groupValues.drop(1)
                    val id = if (lines.getOrNull(index)?.startsWith("id: ") == true) lines[index++].removePrefix("id: ") else newId()
                    require(id.matches(Regex("[0-9]{1,16}")) && BigDecimal(id) <= BigDecimal("9007199254740991")) { "Web diary IDs must be safe nonnegative integers; nonnumeric IDs cannot be transferred without changing identity" }
                    val meal = VaultDiaryMeal(id, name, number(values[0], 100000.0, 0), number(values[1], 100000.0, 1), number(values[2], 100000.0, 1), number(values[3], 100000.0, 1), category)
                    result[key] = result.getValue(key).copy(meals = result.getValue(key).meals + meal)
                }
            }
        }
        require(result.isNotEmpty() && result.values.all { it.water > 0 || it.meals.isNotEmpty() }) { "No diary data or empty day; use Clear data to clear the diary" }
        unique(result.values.flatMap { it.meals }.map { it.id })
        VaultCodec.encodeNutritionDiary(result)
        return result
    }

    fun exportDiary(diary: Map<String, VaultDiaryDay>): String {
        VaultCodec.encodeNutritionDiary(diary)
        val text = diary.toSortedMap().entries.joinToString("\n\n") { (date, day) ->
            (listOf("date: $date", "water: ${n(day.water)} ml") + day.meals.map { meal ->
                require(!meal.name.contains(" - ") && !meal.category.contains(" - ")) { "Ambiguous name/category separator" }
                "${meal.name}${if (meal.category == "Other") "" else " - ${meal.category}"}\n${n(meal.cals)}cal ${n(meal.p)}pro ${n(meal.c)}carb ${n(meal.f)}fat\nid: ${meal.id}"
            }).joinToString("\n\n")
        }
        if (diary.isNotEmpty()) require(parseDiary(text) == diary) { "Diary export would lose data" }
        return text.also { checkedLines(it) }
    }

    fun readDiary(source: String): Map<String, VaultDiaryDay> {
        ClipboardCodec.readable(source, VaultKind.NUTRITION_DIARY)
        val document = VaultCodec.parseNutritionDiary(source)
        require(document.canRewrite) { "Resolve nutrition_diary.md diagnostics before transfer" }
        val numeric = Regex("([0-9]+(?:\\.[0-9]+)?)(p|c|f)\\b")
        source.lines().forEach { line ->
            numeric.findAll(line).forEach { number(it.groupValues[1], 100000.0, 1) }
            if (line.trim().startsWith("water:")) number(line.trim().removePrefix("water:").trim().removeSuffix("ml").trim(), 50000.0, 0)
        }
        val ids = source.lines().filter { it.trim().removePrefix("- ").startsWith("id:") }.map { it.trim().removePrefix("- ").removePrefix("id:").trim() }
        require(ids == document.value.values.flatMap { it.meals }.map { it.id }) { "Diary needs exactly one explicit ID per meal" }
        require(source.lines().count { it.trim().startsWith("water:") } <= document.value.size) { "Repeated water rows" }
        val encoded = VaultCodec.encodeNutritionDiary(document.value)
        fun canonicalRows(value: String) = value.removePrefix("\uFEFF").lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            if (line.startsWith("## ")) VaultCodec.parseDateHeading(line.removePrefix("## "))?.let { "## $it" } ?: line else line
        }
        require(canonicalRows(source) == canonicalRows(encoded)) { "Diary source is noncanonical or would lose fields, precision, repeated rows or category spelling. Resolve the source before transfer/clear." }
        return document.value
    }

    fun parseCustoms(text: String, newId: () -> String = { "c-${System.currentTimeMillis()}-${UUID.randomUUID()}" }): List<VaultCustomExercise> {
        val lines = checkedLines(text).filter { it.isNotBlank() }.toMutableList()
        if (lines.firstOrNull()?.trim() == "## Custom Exercises") lines.removeAt(0)
        require(lines.none { it.trim().startsWith('#') }) { "Only a Custom Exercises clipboard section is accepted" }
        val records = mutableListOf<MutableMap<String, String>>()
        lines.forEach { raw ->
            val match = requireNotNull(Regex("^(name|id|category|target|equipment|description):[ \\t]*(.*)$").matchEntire(raw.trim().removePrefix("- "))) { "Unsupported custom exercise row: $raw" }
            val key = match.groupValues[1]
            if (key == "name") records.add(linkedMapOf())
            val record = requireNotNull(records.lastOrNull()) { "Custom exercises must start with name:" }
            require(record.put(key, match.groupValues[2]) == null) { "Duplicate custom exercise field: $key" }
        }
        require(records.isNotEmpty()) { "No custom exercises; use Clear data to clear them" }
        return records.map { fields ->
            VaultCustomExercise(fields["id"] ?: newId(), fields.getValue("name"), fields.getValue("category"), fields["target"] ?: fields.getValue("category"), fields.getValue("equipment"), fields["description"].orEmpty().replace(" / ", "\n"))
        }.also(::validateCustoms)
    }

    fun validateCustoms(records: List<VaultCustomExercise>) {
        ConfigCodec.replaceCustomExercises("# Config\n", records)
        records.forEach {
            require(it.id.matches(Regex("(?:[0-9]{5}|c-[0-9][A-Za-z0-9_-]*)"))) { "Web-compatible custom IDs must be five digits or start c- followed by a digit" }
            require(it.category in categories && it.target.isNotEmpty() && it.target == it.target.lowercase(java.util.Locale.ROOT)) { "Use a web category and lowercase, nonempty target" }
            require(it.equipment == "body weight" || it.equipment in equipment) { "Unknown equipment: ${it.equipment}; web would replace it with body weight" }
            require(it.description.length <= 300 && it.description == it.description.trim()) { "Web descriptions must be trimmed and at most 300 characters" }
        }
    }

    fun exportCustoms(records: List<VaultCustomExercise>): String {
        validateCustoms(records)
        val config = ConfigCodec.replaceCustomExercises("# Config\n", records)
        return config.substring(config.indexOf("## Custom Exercises")).trimEnd().also { checkedLines(it) }
    }

    fun mergeLogs(base: List<VaultTrainingLog>, incoming: List<VaultTrainingLog>, mode: TransferMode): List<VaultTrainingLog> =
        (if (mode == TransferMode.ADD) base + incoming else incoming).also { unique(it.map { log -> log.id }); VaultCodec.encodeTrainingLogs(it) }

    fun mergeDiary(base: Map<String, VaultDiaryDay>, incoming: Map<String, VaultDiaryDay>, mode: TransferMode): Map<String, VaultDiaryDay> {
        if (mode == TransferMode.REPLACE) return incoming
        val result = base.toMutableMap()
        incoming.forEach { (date, day) ->
            val before = result[date] ?: VaultDiaryDay()
            result[date] = VaultDiaryDay(maxOf(before.water, day.water), before.meals + day.meals)
        }
        unique(result.values.flatMap { it.meals }.map { it.id })
        VaultCodec.encodeNutritionDiary(result)
        return result
    }

    companion object {
        fun numericIds(): () -> String {
            var next = System.currentTimeMillis() * 1000 + java.security.SecureRandom().nextInt(500)
            return { (next++).toString() }
        }
        fun checkedLines(text: String): List<String> {
            require(text.toByteArray(Charsets.UTF_8).size <= ClipboardCodec.MAX_TEXT_BYTES) { "Text exceeds 200 KiB; use smaller selections" }
            require(text.none { it.isISOControl() && it !in "\n\r\t" || it == '\u2028' || it == '\u2029' }) { "Unsupported control characters" }
            return text.replace("\r\n", "\n").replace('\r', '\n').lines()
        }
        private fun unique(ids: List<String>) {
            require(ids.distinct().size == ids.size) { "ID collision: no records were skipped. Remove duplicates or explicitly use Replace." }
        }
        private fun date(value: String) { require(LocalDate.parse(value).toString() == value) { "Use an ISO date" } }
        private fun integer(raw: String, low: Int, high: Int): Int {
            val n = raw.toIntOrNull()
            require(n != null && n in low..high) { "Expected integer $low–$high: $raw" }
            return n
        }
        private fun number(raw: String, max: Double, scale: Int): Double {
            require(raw.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) { "Invalid number: $raw" }
            val exact = BigDecimal(raw)
            val value = exact.toDouble()
            require(value.isFinite() && value in 0.0..max && exact.stripTrailingZeros().scale() <= scale && BigDecimal.valueOf(value).compareTo(exact) == 0) { "Out-of-range or lossy number: $raw (maximum $max, $scale decimal places)" }
            return value
        }
        private fun numbers(raw: String, max: Double, scale: Int) = raw.split(',').map { number(it.trim(), max, scale) }
        private fun n(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}
