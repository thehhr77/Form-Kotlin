package com.thehhr.form.nativeapp.vault

import java.util.UUID

enum class ClearCategory(val label: String) {
    LOGS("Training history"), ROUTINES("Routines and weekly schedule"), LIKES("Liked exercises"),
    DIARY("Nutrition diary and water"), MEALS("Meal library"), CUSTOMS("Custom exercises and routine references (retain history)"), TAGS("Exercise tags")
}

class VaultBatchPlan internal constructor(
    val uri: String,
    val operation: String,
    expected: Map<String, String>,
    desired: Map<String, String>,
    val summary: List<String>,
    val operationId: String = UUID.randomUUID().toString()
) {
    val expected: Map<String, String> = expected.toMap()
    val desired: Map<String, String> = desired.filter { (file, text) -> expected[file] != text }.toMap()
    internal var consumed = false
}

class SettingsDataPlanner(private val builtInIds: Set<String>, private val equipment: Set<String> = emptySet()) {
    private fun source(snapshot: VaultSnapshot, kind: VaultKind): String = requireNotNull(snapshot.file(kind.fileName)) {
        "Restore ${kind.fileName} and reload before preview"
    }.also { ClipboardCodec.readable(it, kind) }

    fun preview(snapshot: VaultSnapshot, kind: VaultKind, text: String, mode: TransferMode): TransferPreview {
        val config = source(snapshot, VaultKind.CONFIG)
        val customs = ConfigCodec.customExerciseRecords(config)
        require(customs.none { it.id in builtInIds }) { "Custom ID collides with the built-in catalog" }
        val catalog = builtInIds + customs.map { it.id }
        val codec = SettingsClipboardCodec(catalog, equipment)
        val base = source(snapshot, kind)
        val expected = linkedMapOf(VaultFiles.CONFIG to config, kind.fileName to base)
        val desired = linkedMapOf<String, String>()
        val summary = mutableListOf<String>()
        val names: List<String>
        val before: Int
        val count: Int
        when (kind) {
            VaultKind.TRAINING_LOGS -> {
                val old = codec.readLogs(base)
                val incoming = codec.parseLogs(text)
                val merged = codec.mergeLogs(old, incoming, mode)
                val document = VaultCodec.parseTrainingLogs(base)
                val encoded = VaultCodec.encodeTrainingLogs(merged, document)
                if (encoded != base) desired[kind.fileName] = encoded
                before = old.size; count = incoming.size
                names = incoming.map { "${it.date} · ${it.exerciseId} · ${it.id}" }
                summary += "Training entries: ${old.size} → ${merged.size}"
            }
            VaultKind.NUTRITION_DIARY -> {
                val old = codec.readDiary(base)
                val incoming = codec.parseDiary(text)
                val merged = codec.mergeDiary(old, incoming, mode)
                val document = VaultCodec.parseNutritionDiary(base)
                val encoded = VaultCodec.encodeNutritionDiary(merged, document)
                if (encoded != base) desired[kind.fileName] = encoded
                before = old.size; count = incoming.size
                names = incoming.map { (date, day) -> "$date · ${day.meals.size} meals · ${day.water} ml water" }
                summary += "Diary days: ${old.size} → ${merged.size}; meals: ${old.values.sumOf { it.meals.size }} → ${merged.values.sumOf { it.meals.size }}"
                summary += "Add uses the greater water value per date, not a sum. Any meal ID collision is an error."
            }
            VaultKind.CONFIG -> {
                val incoming = codec.parseCustoms(text)
                val merged = if (mode == TransferMode.ADD) customs + incoming else incoming
                require(merged.none { it.id in builtInIds }) { "Custom ID collides with a built-in exercise" }
                codec.validateCustoms(merged)
                var patched = ConfigCodec.replaceCustomExercises(config, merged)
                val removed = customs.map { it.id }.toSet() - merged.map { it.id }.toSet()
                if (removed.isNotEmpty()) {
                    val routines = source(snapshot, VaultKind.ROUTINES)
                    val logs = source(snapshot, VaultKind.TRAINING_LOGS)
                    expected[VaultFiles.ROUTINES] = routines
                    expected[VaultFiles.TRAINING_LOGS] = logs
                    val oldRoutines = ClipboardCodec(catalog).readRoutines(routines)
                    val cleaned = cleanupRoutines(oldRoutines, removed)
                    checkScheduleReferences(config, oldRoutines)
                    val encodedRoutines = VaultCodec.encodeRoutines(cleaned, VaultCodec.parseRoutines(routines))
                    if (encodedRoutines != routines) desired[VaultFiles.ROUTINES] = encodedRoutines
                    val oldLogs = codec.readLogs(logs)
                    val keptLogs = oldLogs.filterNot { it.exerciseId in removed }
                    val encodedLogs = VaultCodec.encodeTrainingLogs(keptLogs, VaultCodec.parseTrainingLogs(logs))
                    if (encodedLogs != logs) desired[VaultFiles.TRAINING_LOGS] = encodedLogs
                    val likes = ConfigCodec.checkedLikes(config)
                    patched = ConfigCodec.patchPreference(patched, patched, "liked", likes.filterNot { it in removed }.joinToString(", "))
                    summary += "Removed custom IDs: ${removed.size}; routine rows removed: ${oldRoutines.sumOf { it.items.size } - cleaned.sumOf { it.items.size }}; training entries removed: ${oldLogs.size - keptLogs.size}"
                    summary += "Routine IDs/names and schedule references stay unchanged. Orphaned superset partners become standalone. Tags are retained unless explicitly cleared."
                }
                desired[VaultFiles.CONFIG] = patched
                before = customs.size; count = incoming.size
                names = incoming.map { "${it.name} · ${it.id}" }
                summary += "Custom exercises: ${customs.size} → ${merged.size}"
            }
            else -> throw IllegalArgumentException("Use the routine/meal clipboard codec")
        }
        val plan = VaultBatchPlan(snapshot.uri, "${mode.name.lowercase()}-${kind.name.lowercase()}", expected, desired, summary)
        validate(plan)
        return TransferPreview(snapshot.uri, kind, mode, base, config, emptyList(), emptyList(), before, plan, count, names)
    }

    fun clear(snapshot: VaultSnapshot, selected: Set<ClearCategory>): TransferPreview {
        require(selected.isNotEmpty()) { "Select at least one category" }
        val expected = linkedMapOf<String, String>()
        val desired = linkedMapOf<String, String>()
        val summary = mutableListOf<String>()
        fun capture(kind: VaultKind): String = source(snapshot, kind).also { expected[kind.fileName] = it }
        val configNeeded = selected.any { it in setOf(ClearCategory.ROUTINES, ClearCategory.LIKES, ClearCategory.CUSTOMS, ClearCategory.TAGS) }
        val config = if (configNeeded) capture(VaultKind.CONFIG) else null
        var patchedConfig = config
        val customs = if (ClearCategory.CUSTOMS in selected) ConfigCodec.customExerciseRecords(requireNotNull(config)) else emptyList()
        val catalog = builtInIds + customs.map { it.id }
        val codec = SettingsClipboardCodec(catalog, equipment)
        if (ClearCategory.LOGS in selected) {
            val logs = codec.readLogs(capture(VaultKind.TRAINING_LOGS))
            desired[VaultFiles.TRAINING_LOGS] = VaultCodec.encodeTrainingLogs(emptyList())
            summary += "Delete ${logs.size} training entries"
        }
        if (ClearCategory.DIARY in selected) {
            val diary = codec.readDiary(capture(VaultKind.NUTRITION_DIARY))
            desired[VaultFiles.NUTRITION_DIARY] = VaultCodec.encodeNutritionDiary(emptyMap())
            summary += "Delete ${diary.size} diary days, ${diary.values.sumOf { it.meals.size }} meals and all diary water"
        }
        if (ClearCategory.MEALS in selected) {
            val meals = ClipboardCodec(catalog).readMeals(capture(VaultKind.MEALS))
            desired[VaultFiles.MEALS] = VaultCodec.encodeMeals(emptyList())
            summary += "Delete ${meals.size} library meals"
        }
        if (ClearCategory.ROUTINES in selected || ClearCategory.CUSTOMS in selected) {
            val routinesSource = capture(VaultKind.ROUTINES)
            val configCustoms = ConfigCodec.customExerciseRecords(requireNotNull(config))
            val routines = ClipboardCodec(builtInIds + configCustoms.map { it.id }).readRoutines(routinesSource)
            if (ClearCategory.ROUTINES in selected) {
                ConfigCodec.checkedSchedule(config)
                for (day in 0..6) patchedConfig = ConfigCodec.patchSchedule(requireNotNull(patchedConfig), day, ConfigCodec.scheduleValue(requireNotNull(patchedConfig), day), null)
                desired[VaultFiles.ROUTINES] = VaultCodec.encodeRoutines(emptyList())
                summary += "Delete ${routines.size} routines and all weekly schedule assignments"
            } else {
                checkScheduleReferences(config, routines)
                val cleaned = cleanupRoutines(routines, customs.map { it.id }.toSet())
                desired[VaultFiles.ROUTINES] = VaultCodec.encodeRoutines(cleaned)
                summary += "Remove ${routines.sumOf { it.items.size } - cleaned.sumOf { it.items.size }} custom routine rows; retain routine IDs, names and schedule"
            }
        }
        if (ClearCategory.CUSTOMS in selected) {
            if (customs.isNotEmpty()) {
                patchedConfig = ConfigCodec.replaceCustomExercises(requireNotNull(patchedConfig), emptyList())
                val likes = ConfigCodec.checkedLikes(patchedConfig)
                patchedConfig = ConfigCodec.patchPreference(patchedConfig, patchedConfig, "liked", likes.filterNot { id -> customs.any { it.id == id } }.joinToString(", "))
            }
            summary += "Delete ${customs.size} custom exercises and their likes; ${if (ClearCategory.LOGS in selected) "history is also selected for deletion" else "retain ALL training history"}"
        }
        if (ClearCategory.LIKES in selected) {
            val likes = ConfigCodec.checkedLikes(requireNotNull(patchedConfig))
            patchedConfig = ConfigCodec.patchPreference(patchedConfig, patchedConfig, "liked", "")
            summary += "Clear ${likes.size} liked exercise IDs (routine and meal flags are retained)"
        }
        if (ClearCategory.TAGS in selected) {
            patchedConfig = ConfigCodec.clearExerciseTags(requireNotNull(patchedConfig))
            summary += "Clear all exercise tag assignments"
        }
        if (patchedConfig != null) desired[VaultFiles.CONFIG] = patchedConfig
        summary += "Profile, targets, unrelated preferences and vault files are retained. No files are deleted."
        val plan = VaultBatchPlan(snapshot.uri, "clear-data", expected, desired, summary)
        validate(plan)
        return TransferPreview(snapshot.uri, VaultKind.CONFIG, TransferMode.REPLACE, config.orEmpty(), config, emptyList(), emptyList(), 0, plan, selected.size, selected.map { it.label })
    }

    companion object {
        fun cleanupRoutines(routines: List<VaultRoutine>, removed: Set<String>): List<VaultRoutine> = routines.map { routine ->
            val kept = routine.items.filterNot { it.exerciseId in removed }
            val groups = kept.mapNotNull { it.superset }.groupingBy { it }.eachCount()
            routine.copy(items = kept.map { if (it.superset != null && groups[it.superset] != 2) it.copy(superset = null) else it })
        }
        private fun checkScheduleReferences(config: String, routines: List<VaultRoutine>) {
            ConfigCodec.checkedSchedule(config).values.forEach { reference ->
                require(routines.count { it.id == reference || it.name == reference } == 1) { "Ambiguous or unavailable scheduled routine: $reference" }
            }
        }
        fun validate(plan: VaultBatchPlan) {
            require(plan.expected.keys.containsAll(plan.desired.keys)) { "Every output needs captured original bytes" }
            plan.expected.forEach { (file, text) ->
                require(VaultFiles.isKnownVaultFile(file) && !VaultFormat.looksEmpty(text) && VaultFormat.headingsCompatible(file, text)) { "Invalid source: $file" }
            }
            plan.desired.forEach { (file, text) ->
                require(text.toByteArray(Charsets.UTF_8).size <= VaultFiles.MAX_FILE_BYTES && VaultFormat.headingsCompatible(file, text)) { "Invalid or oversized output: $file" }
                if (file != VaultFiles.CONFIG) require(VaultCodec.parse(file, text).canRewrite) { "Invalid generated $file" }
            }
        }
    }
}
