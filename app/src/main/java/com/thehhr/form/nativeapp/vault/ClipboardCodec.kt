package com.thehhr.form.nativeapp.vault

import java.math.BigDecimal
import java.util.Locale
import java.util.UUID

class TransferPreview internal constructor(
    val uri: String,
    val kind: VaultKind,
    val mode: TransferMode,
    val original: String,
    val configOriginal: String?,
    val routines: List<VaultRoutine>,
    val meals: List<VaultMeal>,
    val beforeCount: Int,
    val batch: VaultBatchPlan? = null,
    private val incomingCount: Int = 0,
    private val incomingNames: List<String> = emptyList()
) {
    val count: Int get() = if (batch != null) incomingCount else if (kind == VaultKind.ROUTINES) routines.size else meals.size
    val names: List<String> get() = if (batch != null) incomingNames else if (kind == VaultKind.ROUTINES) routines.map { it.name } else meals.map { it.name }
}

enum class TransferMode { ADD, REPLACE }

class ClipboardCodec(validExerciseIds: Set<String>) {
    private val catalog = validExerciseIds.toSet()
    private val strict = RoutineMealCodec(catalog)
    private val itemRow = Regex("^(\\S+)\\s+(\\d+)\\s*\\*\\s*(\\d+)(?:\\s+(.+))?$")
    private val mealHeader = Regex("^(.+) \\((\\d+(?:\\.\\d+)?)g\\)$", RegexOption.IGNORE_CASE)
    private val macroRow = Regex("^Per100g (\\d+(?:\\.\\d+)?)cal (\\d+(?:\\.\\d+)?)pro (\\d+(?:\\.\\d+)?)carb (\\d+(?:\\.\\d+)?)fat$", RegexOption.IGNORE_CASE)
    private val idRow = Regex("^id: ([A-Za-z0-9_-]{1,64})$", RegexOption.IGNORE_CASE)

    fun parseRoutines(text: String, newId: () -> String = { UUID.randomUUID().toString() }): List<VaultRoutine> {
        val records = mutableListOf<VaultRoutine>()
        var boundary = true
        val members = mutableMapOf<String, MutableList<Int>>()
        fun finish() {
            require(members.values.all { it == listOf(1, 2) }) { "Supersets require exactly two members, numbered 1 then 2" }
            members.clear()
        }
        for ((index, raw) in lines(text).withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) { boundary = true; continue }
            val match = itemRow.matchEntire(line)
            if (match == null) {
                require(boundary) { "Line ${index + 1}: unrecognized row or ambiguous routine name; separate routines with a blank line" }
                finish()
                clipboardName(line)
                records += VaultRoutine(newId(), line)
            } else {
                require(records.isNotEmpty()) { "Line ${index + 1}: missing routine name" }
                val tokens = match.groupValues[4].lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
                require(tokens.distinct().size == tokens.size) { "Line ${index + 1}: repeated modifier" }
                require(tokens.all { it in setOf("timed", "reps", "sec", "min", "weighted", "unweighted") || it.matches(Regex("ss[1-9][0-9]*[12]")) }) { "Line ${index + 1}: unknown modifier" }
                require(!("timed" in tokens && "reps" in tokens) && !("sec" in tokens && "min" in tokens)) { "Line ${index + 1}: conflicting modes or units" }
                val mode = tokens.singleOrNull { it == "timed" || it == "reps" }
                val unit = tokens.singleOrNull { it == "sec" || it == "min" }
                require((mode == "timed") == (unit != null)) { "Line ${index + 1}: only timed mode requires sec or min" }
                val ss = tokens.filter { it.startsWith("ss") }
                require(ss.size <= 1) { "Line ${index + 1}: repeated superset" }
                val group = ss.singleOrNull()?.drop(2)?.dropLast(1)
                if (group != null) members.getOrPut(group) { mutableListOf() }.add(ss.single().last().digitToInt())
                val item = VaultRoutineItem(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: -1,
                    match.groupValues[3].toIntOrNull() ?: -1, mode, unit, "weighted" in tokens, "unweighted" in tokens, group)
                records[records.lastIndex] = records.last().copy(items = records.last().items + item)
            }
            boundary = false
        }
        finish()
        require(records.isNotEmpty()) { "Clipboard text is empty; it cannot clear a library" }
        validateRoutines(records)
        return records.toList()
    }

    fun parseMeals(text: String, newId: () -> String = { UUID.randomUUID().toString() }): List<VaultMeal> {
        val records = mutableListOf<VaultMeal>()
        var macros = false
        var id = false
        fun finish() { require(records.isEmpty() || macros) { "Every meal needs exactly one Per100g row" } }
        for ((index, raw) in lines(text).withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val header = mealHeader.matchEntire(line)
            val macro = macroRow.matchEntire(line)
            val identifier = idRow.matchEntire(line)
            when {
                header != null -> {
                    finish()
                    clipboardName(header.groupValues[1])
                    records += VaultMeal(newId(), header.groupValues[1], decimal(header.groupValues[2]))
                    macros = false
                    id = false
                }
                macro != null && records.isNotEmpty() -> {
                    require(!macros && !id) { "Line ${index + 1}: repeated or misplaced Per100g row" }
                    val values = (1..4).map { decimal(macro.groupValues[it]) }
                    records[records.lastIndex] = records.last().copy(cals100 = values[0], p100 = values[1], c100 = values[2], f100 = values[3])
                    macros = true
                }
                identifier != null && records.isNotEmpty() -> {
                    require(!id && macros) { "Line ${index + 1}: repeated or misplaced meal ID" }
                    records[records.lastIndex] = records.last().copy(id = identifier.groupValues[1])
                    id = true
                }
                else -> throw VaultWriteException("Line ${index + 1}: unrecognized meal row. Use clipboard text, not Markdown vault content.")
            }
        }
        finish()
        require(records.isNotEmpty()) { "Clipboard text is empty; it cannot clear a library" }
        validateMeals(records)
        return records.toList()
    }

    fun exportRoutines(records: List<VaultRoutine>): String {
        validateRoutines(records)
        return records.joinToString("\n\n") { routine ->
            clipboardName(routine.name)
            val groups = linkedMapOf<String, Int>()
            val members = mutableMapOf<String, Int>()
            (listOf(routine.name) + routine.items.map { item ->
                buildString {
                    append("${item.exerciseId} ${item.sets} * ${item.reps}")
                    item.mode?.let { append(" $it") }
                    item.unit?.let { append(" $it") }
                    if (item.weighted) append(" weighted")
                    if (item.unweighted) append(" unweighted")
                    item.superset?.let {
                        val group = groups.getOrPut(it) { groups.size + 1 }
                        val member = (members[it] ?: 0) + 1
                        members[it] = member
                        append(" ss$group$member")
                    }
                }
            }).joinToString("\n")
        }
    }

    fun exportMeals(records: List<VaultMeal>): String {
        validateMeals(records)
        return records.joinToString("\n\n") {
            clipboardName(it.name)
            "${it.name} (${number(it.defaultGrams)}g)\nPer100g ${number(it.cals100)}cal ${number(it.p100)}pro ${number(it.c100)}carb ${number(it.f100)}fat\nid: ${it.id}"
        }
    }

    fun readRoutines(source: String): List<VaultRoutine> {
        readable(source, VaultKind.ROUTINES)
        val document = VaultCodec.parseRoutines(source)
        val check = strict.parseRoutinesMd(source)
        require(document.canRewrite && check.canRewrite) { "Resolve routines.md diagnostics before transfer" }
        require(check.value.map { it.id } == document.value.map { it.id }) { "Routines need explicit stable IDs before transfer" }
        val clipboard = source.lines().mapNotNull { raw ->
            val row = raw.trim()
            when {
                row.equals("# Routines", true) -> null
                row.matches(Regex("^-?\\s*(id:.*|liked(?:: true)?|secondary(?:: true)?)$", RegexOption.IGNORE_CASE)) -> null
                row.startsWith("## ") -> "\n" + row.removePrefix("## ")
                else -> row
            }
        }.joinToString("\n")
        if (document.value.isNotEmpty()) {
            val parsed = parseRoutines(clipboard)
            require(parsed.map { it.copy(id = "", liked = false, secondary = false) } ==
                document.value.map { it.copy(id = "", liked = false, secondary = false) }) { "Routine source would lose data" }
        }
        validateRoutines(document.value)
        return document.value
    }

    fun readMeals(source: String): List<VaultMeal> {
        readable(source, VaultKind.MEALS)
        val document = VaultCodec.parseMeals(source)
        val check = strict.parseMealsMd(source)
        require(document.canRewrite && check.canRewrite) { "Resolve meals.md diagnostics before transfer" }
        require(check.value.map { it.id } == document.value.map { it.id }) { "Meals need explicit stable IDs before transfer" }
        val clipboard = source.lines().filterNot { it.trim() == "# Meal Library" || it.trim() in setOf("- liked", "liked", "liked: true", "- liked: true") }
            .joinToString("\n") { it.trim().removePrefix("- ") }
        if (document.value.isNotEmpty()) {
            require(parseMeals(clipboard).map { it.copy(liked = false) } == document.value.map { it.copy(liked = false) }) { "Meal source would lose data" }
        }
        validateMeals(document.value)
        return document.value
    }

    fun mergeRoutines(base: List<VaultRoutine>, incoming: List<VaultRoutine>, mode: TransferMode): List<VaultRoutine> {
        validateRoutines(base)
        validateRoutines(incoming)
        return (if (mode == TransferMode.ADD) base + incoming else incoming).also(::validateRoutines)
    }

    fun mergeMeals(base: List<VaultMeal>, incoming: List<VaultMeal>, mode: TransferMode): List<VaultMeal> {
        validateMeals(base)
        validateMeals(incoming)
        return (if (mode == TransferMode.ADD) base + incoming else incoming).also(::validateMeals)
    }

    fun preview(snapshot: VaultSnapshot, kind: VaultKind, text: String, mode: TransferMode): TransferPreview {
        require(kind == VaultKind.ROUTINES || kind == VaultKind.MEALS) { "Only routines and meal library transfers are supported" }
        val source = requireNotNull(snapshot.file(kind.fileName)) { "Restore ${kind.fileName} and reload before importing" }
        if (kind == VaultKind.ROUTINES) {
            val base = readRoutines(source)
            val incoming = parseRoutines(text)
            val desired = mergeRoutines(base, incoming, mode)
            val config = requireNotNull(snapshot.file(VaultFiles.CONFIG)) { "Restore config.md before importing routines" }
            checkSchedule(config, base, desired)
            return TransferPreview(snapshot.uri, kind, mode, source, config, incoming, emptyList(), base.size)
        }
        val base = readMeals(source)
        val incoming = parseMeals(text)
        mergeMeals(base, incoming, mode)
        return TransferPreview(snapshot.uri, kind, mode, source, null, emptyList(), incoming, base.size)
    }

    private fun validateRoutines(records: List<VaultRoutine>) {
        unique(records.map { it.id }, records.map { it.name })
        records.forEach { routine ->
            routine.items.forEach {
                require(it.exerciseId in catalog) { "Unknown exercise ID: ${it.exerciseId}; add the matching custom exercise first if needed" }
                require((it.mode == "timed") == (it.unit != null)) { "Timed mode requires a unit; other modes cannot retain one" }
            }
        }
        val encoded = VaultCodec.encodeRoutines(records)
        require(strict.parseRoutinesMd(encoded).canRewrite) { "Routine values are ambiguous or outside vault limits" }
    }

    private fun validateMeals(records: List<VaultMeal>) {
        unique(records.map { it.id }, records.map { it.name })
        records.forEach {
            require(it.defaultGrams.isFinite() && it.defaultGrams in 1.0..5000.0) { "Portions must be 1–5000 grams" }
            require(it.cals100.isFinite() && it.cals100 >= 0 && it.cals100 <= 9007199254740991.0 && it.cals100 % 1.0 == 0.0) { "Calories must be a representable nonnegative whole number" }
            require(listOf(it.p100, it.c100, it.f100).all { n -> n.isFinite() && n in 0.0..999.0 }) { "Macros must be 0–999 per 100g" }
        }
        VaultCodec.encodeMeals(records)
    }

    private fun unique(ids: List<String>, names: List<String>) {
        require(ids.distinct().size == ids.size) { "ID conflict: a record already exists. Adding an export back to its library does not create duplicates; use Replace only if intended." }
        require(names.map { it.lowercase(Locale.ROOT) }.distinct().size == names.size) { "Name conflict (case-insensitive): a record already exists. Rename the incoming record or explicitly use Replace." }
        names.forEach { require(ConfigCodec.validCustomExerciseName(it)) { "Names must be trimmed, single-line text of 1–40 characters" } }
    }

    private fun clipboardName(name: String) {
        require(ConfigCodec.validCustomExerciseName(name) && name.none { it in "*:#" } && !name.startsWith("-") && !name.startsWith("//") && !name.startsWith("<") && !name.startsWith("{") && !name.startsWith("[")) {
            "Ambiguous name or non-clipboard content. Use a plain 1–40 character name without Markdown, field syntax or * : #."
        }
    }

    private fun lines(text: String): List<String> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "Text exceeds the 200 KiB transfer limit; transfer smaller selections" }
        require(text.none { (it.isISOControl() && it !in "\r\n\t") || it == '\u2028' || it == '\u2029' }) { "Unsupported control character in clipboard text" }
        return text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    }

    private fun decimal(raw: String): Double {
        val value = raw.toDoubleOrNull()
        require(value != null && value.isFinite() && BigDecimal(raw).compareTo(BigDecimal.valueOf(value)) == 0) { "Decimal cannot be represented without losing precision: $raw" }
        return value
    }

    private fun number(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    companion object {
        const val MAX_TEXT_BYTES = 200 * 1024

        fun requireBase(uri: String, original: String, currentUri: String, current: String?, external: String) {
            if (uri != currentUri || current != original || external != original) {
                throw VaultConflictException("The vault or file changed after preview. Nothing was overwritten. Reload, then explicitly create a new preview.")
            }
        }

        fun readable(source: String, kind: VaultKind) {
            require(!VaultFormat.looksEmpty(source) && VaultFormat.headingsCompatible(kind.fileName, source)) { "Restore readable ${kind.fileName} before transfer" }
        }

        fun checkSchedule(config: String, base: List<VaultRoutine>, desired: List<VaultRoutine>) {
            readable(config, VaultKind.CONFIG)
            val document = VaultCodec.parseConfig(config)
            require(document.canRewrite) { "Resolve config.md diagnostics before importing routines" }
            val schedule = (0..6).mapNotNull { day -> ConfigCodec.scheduleValue(config, day)?.let { day to it } }.toMap()
            require(schedule == document.value.schedule.filterValues { it.isNotBlank() }) { "Ambiguous schedule syntax; resolve config.md before importing routines" }
            for (reference in schedule.values) {
                val matches = base.filter { it.id == reference || it.name == reference }
                require(matches.size == 1) { "Ambiguous or unavailable scheduled routine: $reference. Clear the relevant schedule first." }
                require(desired.singleOrNull { it.id == matches.single().id } == matches.single()) {
                    "A scheduled routine would be removed or changed. Clear its weekly schedule assignments, reload and create a new preview first. Schedule files are never changed by import."
                }
            }
        }
    }
}
