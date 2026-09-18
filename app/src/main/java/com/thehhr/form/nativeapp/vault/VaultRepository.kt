package com.thehhr.form.nativeapp.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class VaultReloadRequiredException(message: String, cause: Throwable? = null) : IOException(message, cause)

class VaultRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var current: VaultSnapshot? = null

    suspend fun restore(): VaultSnapshot? {
        val uri = preferences.getString("uri", null) ?: return null
        return open(Uri.parse(uri), initialize = false)
    }

    suspend fun open(uri: Uri, initialize: Boolean = true): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = SafVaultStore(context, uri)
            val entries = store.list()
            val names = entries.map { it.name }
            if (names.size != names.toSet().size) throw IOException("Duplicate filenames found. Resolve them before opening this vault.")
            if (entries.isEmpty()) {
                if (!initialize) throw IOException("Vault is now empty. Re-select the folder to create a new vault.")
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                for (name in VaultFiles.ALL) {
                    if (store.list().any { it.name == name }) throw IOException("Folder changed during initialization. Reload before continuing.")
                    store.write(name, VaultFormat.canonicalContent(name))
                }
            } else if (entries.none { VaultFiles.isKnownVaultFile(it.name) }) {
                throw IOException("This folder contains no Form vault files. Choose a compatible vault or an empty folder.")
            }
            val snapshot = readSnapshot(store, uri)
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (!preferences.edit().putString("uri", uri.toString()).commit()) throw IOException("Cannot remember vault selection")
            current = snapshot
            snapshot
        }
    }

    suspend fun reload(): VaultSnapshot {
        val uri = current?.uri ?: preferences.getString("uri", null) ?: throw IOException("Choose a vault first")
        return open(Uri.parse(uri), initialize = false)
    }

    suspend fun updateRestEnabled(enabled: Boolean): VaultSnapshot = savePreference("rest-enabled") { enabled.toString() }

    suspend fun updateAccent(value: String): VaultSnapshot {
        require(value in setOf("red", "blue", "green", "orange", "purple", "pink"))
        return savePreference("accent") { value }
    }

    suspend fun setExerciseLiked(id: String, liked: Boolean): VaultSnapshot =
        savePreference("liked") { base -> ConfigCodec.likedPreferenceValue(base, id, liked) }

    suspend fun saveCustomExercise(original: VaultCustomExercise?, replacement: VaultCustomExercise?): VaultSnapshot = withContext(Dispatchers.IO) {
        require(original != null || replacement != null) { "No custom exercise change supplied" }
        require(original == null || replacement == null || original.id == replacement.id) { "A custom exercise ID cannot be changed" }
        mutex.withLock {
            val snapshot = current ?: throw IOException("Choose and load a vault first")
            val store = SafVaultStore(context, Uri.parse(snapshot.uri))
            val external = requiredContent(store, VaultFiles.CONFIG)
            var patched = ConfigCodec.saveCustomExercise(external, original, replacement)
            if (original != null && replacement == null) {
                val likedRaw = runCatching { ConfigCodec.configFieldValue(external, "Preferences", "liked") }.getOrNull()
                val likedHere = likedRaw?.split(',')?.map { it.trim().removePrefix("#") }?.contains(original.id) == true
                if (likedHere) patched = ConfigCodec.patchPreference(external, patched, "liked", ConfigCodec.likedPreferenceValue(external, original.id, false))
            }
            val routines = if (original != null && replacement == null) requiredContent(store, VaultFiles.ROUTINES) else null
            if (routines != null && original != null) {
                val document = VaultCodec.parseRoutines(routines)
                if (!document.canRewrite) throw VaultWriteException("Resolve routines.md diagnostics before deleting a custom exercise.")
                if (document.value.any { routine -> routine.items.any { it.exerciseId == original.id } }) {
                    throw VaultWriteException("A routine uses this custom exercise. Remove it from that routine before deleting it.")
                }
            }
            if (patched != external) saveFile(store, snapshot.uri, VaultFiles.CONFIG, external, patched) {
                if (routines != null && requiredContent(store, VaultFiles.ROUTINES) != routines) {
                    throw VaultConflictException("Routines changed while saving. Reload before trying again.")
                }
            }
            snapshot.copy(files = snapshot.files + (VaultFiles.CONFIG to patched)).also { current = it }
        }
    }

    suspend fun setExerciseTags(id: String, original: List<String>?, tags: List<String>): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = current ?: throw IOException("Choose and load a vault first")
            val store = SafVaultStore(context, Uri.parse(snapshot.uri))
            val external = requiredContent(store, VaultFiles.CONFIG)
            val patched = ConfigCodec.patchTags(external, id, original, tags)
            if (patched != external) saveFile(store, snapshot.uri, VaultFiles.CONFIG, external, patched)
            snapshot.copy(files = snapshot.files + (VaultFiles.CONFIG to patched)).also { current = it }
        }
    }

    suspend fun saveConfigField(uri: String, section: String, key: String, original: String?, value: String?): VaultSnapshot = withContext(Dispatchers.IO) {
        ConfigCodec.validateConfigField(section, key, value)
        mutex.withLock {
            val snapshot = current ?: throw IOException("Choose and load a vault first")
            require(snapshot.uri == uri) { "This configuration belongs to another vault" }
            val base = snapshot.files[VaultFiles.CONFIG] ?: throw IOException("Restore config.md and reload before saving")
            if (VaultFormat.looksEmpty(base)) throw VaultWriteException("config.md has no readable content. Restore it before editing.")
            if (ConfigCodec.configFieldValue(base, section, key) != original) {
                throw VaultConflictException("This field changed after the editor was opened. Reload and reopen the editor.")
            }
            val store = SafVaultStore(context, Uri.parse(uri))
            val external = requiredContent(store, VaultFiles.CONFIG)
            val patched = ConfigCodec.patchConfigField(external, section, key, original, value)
            if (patched != external) saveFile(store, uri, VaultFiles.CONFIG, external, patched)
            snapshot.copy(files = snapshot.files + (VaultFiles.CONFIG to patched)).also { current = it }
        }
    }

    suspend fun setWeeklySchedule(day: Int, original: String?, desired: String?): VaultSnapshot = withContext(Dispatchers.IO) {
        require(day in 0..6) { "Invalid schedule day" }
        mutex.withLock {
            val snapshot = current ?: throw IOException("Choose and load a vault first")
            val base = snapshot.files[VaultFiles.CONFIG] ?: throw IOException("config.md is missing; restore it before editing the schedule")
            if (VaultFormat.looksEmpty(base)) throw VaultWriteException("config.md has no readable content. Restore it before editing the schedule.")
            val store = SafVaultStore(context, Uri.parse(snapshot.uri))
            val external = requiredContent(store, VaultFiles.CONFIG)
            val patched = ConfigCodec.patchSchedule(external, day, original, desired)
            if (patched != external) {
                saveFile(store, snapshot.uri, VaultFiles.CONFIG, external, patched)
                snapshot.copy(files = snapshot.files + (VaultFiles.CONFIG to patched)).also { current = it }
            } else snapshot
        }
    }

    suspend fun saveMeal(original: VaultMeal?, replacement: VaultMeal?): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(original != null || replacement != null) { "No meal change supplied" }
            require(original == null || replacement == null || original.id == replacement.id) { "A meal ID cannot be changed" }
            val snapshot = current ?: throw IOException("Choose and load a vault first")
            val source = snapshot.files[VaultFiles.MEALS] ?: throw IOException("Restore meals.md before editing")
            val base = VaultCodec.parseMeals(source)
            if (!base.canRewrite) throw VaultWriteException("meals.md contains unsupported content. Resolve its diagnostics before editing.")
            if (original != null && base.value.singleOrNull { it.id == original.id } != original) {
                throw VaultConflictException("This meal changed after the editor was opened. Reload and reopen the editor.")
            }
            val store = SafVaultStore(context, Uri.parse(snapshot.uri))
            val external = requiredContent(store, VaultFiles.MEALS)
            val document = VaultCodec.parseMeals(external)
            if (!document.canRewrite) throw VaultWriteException("External meals.md contains unsupported content. No changes were saved.")
            val existing = original?.let { record -> document.value.singleOrNull { it.id == record.id } }
            if (original != null && existing != original && existing != replacement) {
                throw VaultConflictException("This meal was changed or removed outside Form. Reload and reopen the editor before saving.")
            }
            if (original == null && document.value.any { it.id == replacement?.id }) {
                throw VaultConflictException("This meal ID already exists. Reload before creating another meal.")
            }
            replacement?.let { record ->
                require(record.name.isNotBlank() && record.name == record.name.trim() && record.name.length <= 40 &&
                    record.name.none { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' }) { "Use a single-line meal name of 1–40 characters" }
                require(record.defaultGrams in 1.0..5000.0) { "Default portion must be between 1 and 5000 grams" }
                require(record.cals100 >= 0 && record.p100 in 0.0..999.0 && record.c100 in 0.0..999.0 && record.f100 in 0.0..999.0) { "Nutrition values are out of range" }
                require(document.value.none { it.id != record.id && it.name.equals(record.name, true) }) { "A meal with this name already exists" }
            }
            val records = if (original == null) document.value + requireNotNull(replacement) else document.value.mapNotNull {
                if (it.id == original.id) replacement else it
            }
            val patched = VaultCodec.encodeMeals(records, document)
            if (patched != external) saveFile(store, snapshot.uri, VaultFiles.MEALS, external, patched)
            snapshot.copy(files = snapshot.files + (VaultFiles.MEALS to patched)).also { current = it }
        }
    }

    suspend fun saveRoutine(original: VaultRoutine?, replacement: VaultRoutine?): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(original != null || replacement != null) { "No routine change supplied" }
            require(original == null || replacement == null || original.id == replacement.id) { "A routine ID cannot be changed" }
            val snapshot = current ?: throw IOException("Choose and load a vault first")
            val source = snapshot.files[VaultFiles.ROUTINES] ?: throw IOException("Restore routines.md before editing")
            val base = VaultCodec.parseRoutines(source)
            if (!base.canRewrite) throw VaultWriteException("routines.md contains unsupported content. Resolve its diagnostics before editing.")
            if (original != null && base.value.singleOrNull { it.id == original.id } != original) {
                throw VaultConflictException("This routine changed after the editor was opened. Reload and reopen the editor.")
            }
            val store = SafVaultStore(context, Uri.parse(snapshot.uri))
            val external = requiredContent(store, VaultFiles.ROUTINES)
            val document = VaultCodec.parseRoutines(external)
            if (!document.canRewrite) throw VaultWriteException("External routines.md contains unsupported content. No changes were saved.")
            val existing = original?.let { record -> document.value.singleOrNull { it.id == record.id } }
            if (original != null && existing != original && existing != replacement) {
                throw VaultConflictException("This routine was changed or removed outside Form. Reload and reopen the editor before saving.")
            }
            if (original == null && document.value.any { it.id == replacement?.id }) {
                throw VaultConflictException("This routine ID already exists. Reload before creating another routine.")
            }
            replacement?.let { record ->
                require(record.name.isNotBlank() && record.name == record.name.trim() && record.name.length <= 40 &&
                    record.name.none { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' }) { "Use a single-line routine name of 1–40 characters" }
                require(document.value.none { it.id != record.id && it.name.equals(record.name, true) }) { "A routine with this name already exists" }
            }
            val records = if (original == null) document.value + requireNotNull(replacement) else document.value.mapNotNull {
                if (it.id == original.id) replacement else it
            }
            val patched = VaultCodec.encodeRoutines(records, document)
            val changesName = original != null && (replacement == null || replacement.name != original.name)
            val config = if (changesName) requiredContent(store, VaultFiles.CONFIG) else null
            val schedulePatches = if (config != null && original != null) {
                val parsed = VaultCodec.parseConfig(config)
                if (!parsed.canRewrite) throw VaultWriteException("Resolve config.md diagnostics before renaming or deleting routines.")
                val schedule = try {
                    ConfigCodec.checkedSchedule(config)
                } catch (error: IllegalArgumentException) {
                    throw VaultWriteException("Resolve the weekly schedule in config.md before renaming or deleting routines.")
                }
                RoutineMath.schedulePatch(schedule, original.name, replacement?.name)
            } else emptyMap()
            if (patched != external) saveFile(store, snapshot.uri, VaultFiles.ROUTINES, external, patched) {
                if (config != null && requiredContent(store, VaultFiles.CONFIG) != config) {
                    throw VaultConflictException("The weekly schedule changed while saving. Reload before trying again.")
                }
            }
            var patchedConfig = config
            schedulePatches.forEach { (day, desired) ->
                patchedConfig = ConfigCodec.patchSchedule(requireNotNull(patchedConfig), day, ConfigCodec.scheduleValue(requireNotNull(patchedConfig), day), desired)
            }
            if (patchedConfig != config) {
                saveFile(store, snapshot.uri, VaultFiles.CONFIG, requireNotNull(config), requireNotNull(patchedConfig)) {
                    if (requiredContent(store, VaultFiles.ROUTINES) != patched) {
                        throw VaultConflictException("Routines changed while saving. Reload before trying again.")
                    }
                }
            }
            snapshot.copy(files = snapshot.files + (VaultFiles.ROUTINES to patched) +
                if (config != null) mapOf(VaultFiles.CONFIG to requireNotNull(patchedConfig)) else emptyMap()).also { current = it }
        }
    }

    suspend fun importLibrary(preview: TransferPreview, builtInIds: Set<String>): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = current ?: throw IOException("Reload the vault before importing")
            val store = SafVaultStore(context, Uri.parse(snapshot.uri))
            val batch = preview.batch
            if (batch != null) {
                require(snapshot.uri == batch.uri && batch.expected.all { (file, content) -> snapshot.file(file) == content }) {
                    "Loaded vault differs from preview. Reload and explicitly create a new preview."
                }
                VaultBatchExecutor.execute(store, batch, recoveryJournal(batch), {
                    synchronized(com.thehhr.form.nativeapp.workout.WorkoutSessionStore) {
                        require(com.thehhr.form.nativeapp.workout.WorkoutSessionStore.load(context, batch.uri) == null) {
                            "Finish or discard the persisted workout before changing Settings data"
                        }
                    }
                }, { current = null })
                return@withLock snapshot.copy(files = snapshot.files + batch.desired).also { current = it }
            }
            val file = preview.kind.fileName
            require(preview.kind == VaultKind.ROUTINES || preview.kind == VaultKind.MEALS) { "Unsupported transfer" }
            val external = requiredContent(store, file)
            ClipboardCodec.requireBase(preview.uri, preview.original, snapshot.uri, snapshot.file(file), external)
            val config = if (preview.kind == VaultKind.ROUTINES) requiredContent(store, VaultFiles.CONFIG) else null
            if (config != null) {
                ClipboardCodec.requireBase(preview.uri, requireNotNull(preview.configOriginal), snapshot.uri, snapshot.file(VaultFiles.CONFIG), config)
            }
            val customIds = config?.let(ConfigCodec::customExerciseRecords).orEmpty().map { it.id }
            require(customIds.none { it in builtInIds }) { "Ambiguous exercise catalog IDs" }
            val codec = ClipboardCodec(builtInIds + customIds)
            val patched = if (preview.kind == VaultKind.ROUTINES) {
                val base = codec.readRoutines(external)
                val desired = codec.mergeRoutines(base, preview.routines, preview.mode)
                ClipboardCodec.checkSchedule(requireNotNull(config), base, desired)
                VaultCodec.encodeRoutines(desired, VaultCodec.parseRoutines(external))
            } else {
                val base = codec.readMeals(external)
                val desired = codec.mergeMeals(base, preview.meals, preview.mode)
                VaultCodec.encodeMeals(desired, VaultCodec.parseMeals(external))
            }
            if (patched != external) saveFile(store, snapshot.uri, file, external, patched) {
                if (config != null && requiredContent(store, VaultFiles.CONFIG) != config) {
                    throw VaultConflictException("config.md changed during import. Reload before trying again.")
                }
            }
            snapshot.copy(files = snapshot.files + (file to patched)).also { current = it }
        }
    }

    suspend fun saveWorkoutLogs(vaultUri: String, logs: List<VaultTrainingLog>): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = current ?: throw IOException("Reload the vault before saving workout logs")
            require(snapshot.uri == vaultUri) { "The workout belongs to a different vault" }
            require(logs.isNotEmpty() && logs.map { it.id }.distinct().size == logs.size) { "Invalid workout log identifiers" }
            VaultCodec.encodeTrainingLogs(logs)
            val store = SafVaultStore(context, Uri.parse(vaultUri))
            val external = requiredContent(store, VaultFiles.TRAINING_LOGS)
            val document = VaultCodec.parseTrainingLogs(external)
            if (!document.canRewrite) throw VaultWriteException("Resolve training_logs.md diagnostics before finishing. Your workout draft is retained.")
            val existing = document.value.associateBy { it.id }
            logs.forEach { log ->
                if (existing[log.id] != null && existing[log.id] != log) {
                    throw VaultConflictException("A workout log changed externally. No records were overwritten; the finishing draft is retained.")
                }
            }
            val merged = document.value + logs.filterNot { it.id in existing }
            val patched = VaultCodec.encodeTrainingLogs(merged, document)
            if (patched != external) saveFile(store, vaultUri, VaultFiles.TRAINING_LOGS, external, patched)
            snapshot.copy(files = snapshot.files + (VaultFiles.TRAINING_LOGS to patched)).also { current = it }
        }
    }

    suspend fun saveManualWorkoutLog(vaultUri: String, log: VaultTrainingLog): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = current ?: throw IOException("Reload the vault before saving workout logs")
            require(snapshot.uri == vaultUri) { "The workout belongs to a different vault" }
            VaultCodec.encodeTrainingLogs(listOf(log))
            val store = SafVaultStore(context, Uri.parse(vaultUri))
            val external = requiredContent(store, VaultFiles.TRAINING_LOGS)
            val document = VaultCodec.parseTrainingLogs(external)
            if (!document.canRewrite) throw VaultWriteException("Resolve training_logs.md diagnostics before logging. No changes were saved.")
            val existing = document.value.lastOrNull { it.id == log.id }
            if (existing != null) {
                if (existing == log) return@withLock snapshot
                throw VaultConflictException("A workout log with this identifier already exists. Reload the vault before logging again.")
            }
            val patched = VaultCodec.encodeTrainingLogs(document.value + log, document)
            if (patched != external) saveFile(store, vaultUri, VaultFiles.TRAINING_LOGS, external, patched)
            snapshot.copy(files = snapshot.files + (VaultFiles.TRAINING_LOGS to patched)).also { current = it }
        }
    }

    suspend fun deleteTrainingLog(vaultUri: String, original: VaultTrainingLog): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = current ?: throw IOException("Reload the vault before deleting workout logs")
            require(snapshot.uri == vaultUri) { "The workout belongs to a different vault" }
            VaultCodec.encodeTrainingLogs(listOf(original))
            val store = SafVaultStore(context, Uri.parse(vaultUri))
            val external = requiredContent(store, VaultFiles.TRAINING_LOGS)
            val document = VaultCodec.parseTrainingLogs(external)
            if (!document.canRewrite) throw VaultWriteException("Resolve training_logs.md diagnostics before deleting. No changes were saved.")
            val existing = document.value.firstOrNull { it.id == original.id }
                ?: throw VaultConflictException("This workout log was already removed. Reload the vault before deleting again.")
            if (existing != original) {
                throw VaultConflictException("This workout log changed outside Form. Reload the vault before deleting it.")
            }
            val patched = VaultCodec.encodeTrainingLogs(document.value.filterNot { it.id == original.id }, document)
            if (patched != external) saveFile(store, vaultUri, VaultFiles.TRAINING_LOGS, external, patched)
            snapshot.copy(files = snapshot.files + (VaultFiles.TRAINING_LOGS to patched)).also { current = it }
        }
    }

    suspend fun saveDiaryEntry(vaultUri: String, date: String, original: VaultDiaryMeal?, replacement: VaultDiaryMeal?): VaultSnapshot =
        updateDiary(vaultUri, date) { diary ->
            require(original != null || replacement != null) { "No diary entry supplied" }
            require(original == null || replacement == null || original.id == replacement.id) { "Diary entry IDs cannot change" }
            val id = original?.id ?: requireNotNull(replacement).id
            val matches = diary.flatMap { (day, value) -> value.meals.filter { it.id == id }.map { day to it } }
            require(matches.size <= 1) { "Duplicate diary entry ID" }
            val match = matches.singleOrNull()
            if (match != null && match.first != date) throw VaultConflictException("This diary entry exists on another date. Reload before saving.")
            val existing = match?.second
            if (existing != original && existing != replacement) throw VaultConflictException("This diary entry changed outside Form. Reload before saving.")
            replacement?.let { meal ->
                require(meal.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid diary entry ID" }
                require(meal.name.isNotBlank() && meal.name.length <= 40 && meal.name == meal.name.trim() &&
                    meal.name.none { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' }) { "Use a single-line meal name of 1–40 characters" }
                require(listOf(meal.cals, meal.p, meal.c, meal.f).all { it.isFinite() && it in 0.0..100000.0 }) { "Nutrition values are out of range" }
                require(meal.category in setOf("Breakfast", "Lunch", "Dinner", "Snacks") || meal.category == original?.category) { "Choose a meal category" }
            }
            if (existing == replacement) diary else {
                val day = diary[date] ?: VaultDiaryDay()
                val meals = if (existing == null) day.meals + requireNotNull(replacement) else day.meals.mapNotNull {
                    if (it.id == id) replacement else it
                }
                diary + (date to day.copy(meals = meals))
            }
        }

    suspend fun setDiaryWater(vaultUri: String, date: String, original: Double, desired: Double): VaultSnapshot =
        updateDiary(vaultUri, date) { diary ->
            require(original.isFinite() && original in 0.0..50000.0 && desired.isFinite() && desired in 0.0..50000.0) { "Water must be between 0 and 50000 ml" }
            val day = diary[date] ?: VaultDiaryDay()
            if (day.water != original && day.water != desired) throw VaultConflictException("Water for this date changed outside Form. Reload before saving.")
            if (day.water == desired) diary else diary + (date to day.copy(water = desired))
        }

    private suspend fun updateDiary(vaultUri: String, date: String, update: (Map<String, VaultDiaryDay>) -> Map<String, VaultDiaryDay>): VaultSnapshot = withContext(Dispatchers.IO) {
        require(java.time.LocalDate.parse(date).toString() == date) { "Invalid diary date" }
        mutex.withLock {
            val snapshot = current ?: throw IOException("Choose and load a vault first")
            require(snapshot.uri == vaultUri) { "This diary belongs to another vault" }
            require(snapshot.files.containsKey(VaultFiles.NUTRITION_DIARY)) { "Restore nutrition_diary.md and reload before saving" }
            val store = SafVaultStore(context, Uri.parse(vaultUri))
            val external = requiredContent(store, VaultFiles.NUTRITION_DIARY)
            val document = VaultCodec.parseNutritionDiary(external)
            if (!document.canRewrite) throw VaultWriteException("Resolve nutrition_diary.md diagnostics before saving. No existing content was changed.")
            val patched = VaultCodec.encodeNutritionDiary(update(document.value), document)
            if (patched != external) saveFile(store, vaultUri, VaultFiles.NUTRITION_DIARY, external, patched)
            snapshot.copy(files = snapshot.files + (VaultFiles.NUTRITION_DIARY to patched)).also { current = it }
        }
    }

    private fun recoveryJournal(plan: VaultBatchPlan): VaultBatchJournal = object : VaultBatchJournal {
        private val directory = java.io.File(context.noBackupFilesDir, "vault-recovery/${plan.operationId}")
        override val location: String get() = directory.absolutePath
        private fun durable(name: String, text: String) {
            val file = android.util.AtomicFile(java.io.File(directory, name))
            val stream = file.startWrite()
            try {
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                file.finishWrite(stream)
                require(file.openRead().use { it.readBytes() }.contentEquals(text.toByteArray(Charsets.UTF_8))) { "Recovery verification failed" }
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
        override suspend fun prepare(plan: VaultBatchPlan) {
            require(!directory.exists()) { "Recovery operation already exists; create a new preview" }
            require(directory.mkdirs()) { "Cannot create operation recovery directory" }
            plan.expected.forEach { (file, text) -> durable("original-$file", text) }
            plan.desired.forEach { (file, text) -> durable("desired-$file", text) }
            durable("manifest", "operation=${plan.operation}\nuri=${plan.uri}\noriginals=${plan.expected.keys.joinToString(",")}\noutputs=${plan.desired.keys.joinToString(",")}\n")
            mark("prepared")
        }
        override suspend fun mark(status: String) = durable("status", status)
    }

    private suspend fun requiredContent(store: VaultStore, fileName: String): String {
        val result = store.read(fileName)
        if (result.isError) throw IOException(result.error)
        val content = result.content ?: throw VaultConflictException("$fileName is missing. Restore it and reload before editing.")
        if (!VaultFormat.headingsCompatible(fileName, content)) throw VaultWriteException("$fileName has an unsupported header. No changes were saved.")
        return content
    }

    private suspend fun saveFile(store: VaultStore, uri: String, fileName: String, external: String, patched: String, postWriteCheck: (suspend () -> Unit)? = null) {
        val directory = java.io.File(context.noBackupFilesDir, "vault-recovery")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create recovery directory")
        val key = java.util.UUID.nameUUIDFromBytes(uri.toByteArray(Charsets.UTF_8))
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val backup = android.util.AtomicFile(java.io.File(directory, "$key-$safeName"))
        val stream = backup.startWrite()
        try {
            stream.write(external.toByteArray(Charsets.UTF_8))
            backup.finishWrite(stream)
        } catch (error: Exception) {
            backup.failWrite(stream)
            throw error
        }
        if (patched.toByteArray(Charsets.UTF_8).size > VaultFiles.MAX_FILE_BYTES) throw VaultWriteException("$fileName exceeds the 4 MB safety limit")
        postWriteCheck?.invoke()
        if (requiredContent(store, fileName) != external) throw VaultConflictException("$fileName changed while saving. Reload before trying again.")
        try {
            store.write(fileName, patched)
            postWriteCheck?.invoke()
            if (requiredContent(store, fileName) != patched) throw IOException("Save verification failed. A recovery copy is retained on this device.")
        } catch (error: Exception) {
            current = null
            throw VaultReloadRequiredException("Unable to verify the save. Reload the vault before editing again. A recovery copy is retained.", error)
        }
    }

    private suspend fun savePreference(key: String, desiredValue: (String) -> String): VaultSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = current ?: throw IOException("Choose and load a vault first")
            val base = snapshot.files[VaultFiles.CONFIG] ?: throw IOException("config.md is missing; restore it before changing preferences")
            val value = desiredValue(base)
            val store = SafVaultStore(context, Uri.parse(snapshot.uri))
            val external = store.read(VaultFiles.CONFIG).content ?: throw IOException("config.md was removed externally")
            val patched = ConfigCodec.patchPreference(base, external, key, value)
            if (patched != external) saveConfig(store, snapshot.uri, external, patched)
            snapshot.copy(files = snapshot.files + (VaultFiles.CONFIG to patched)).also { current = it }
        }
    }

    private suspend fun saveConfig(store: VaultStore, uri: String, external: String, patched: String) =
        saveFile(store, uri, VaultFiles.CONFIG, external, patched)

    private suspend fun readSnapshot(store: VaultStore, uri: Uri): VaultSnapshot {
        val files = mutableMapOf<String, String>()
        val warnings = mutableListOf<String>()
        for (name in VaultFiles.ALL) {
            val result = store.read(name)
            if (result.isError) throw IOException(result.error)
            val text = result.content
            if (text == null) warnings += "Missing $name; not created automatically."
            else {
                if (!VaultFormat.headingsCompatible(name, text)) throw IOException("$name has an unsupported header. No existing files were modified.")
                files[name] = text
            }
        }
        return VaultSnapshot(store.displayName(), uri.toString(), files, warnings)
    }
}
