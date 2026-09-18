package com.thehhr.form.nativeapp

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thehhr.form.nativeapp.vault.VaultConflictException
import com.thehhr.form.nativeapp.vault.VaultReloadRequiredException
import com.thehhr.form.nativeapp.vault.VaultRepository
import com.thehhr.form.nativeapp.vault.VaultRoutine
import com.thehhr.form.nativeapp.vault.VaultMeal
import com.thehhr.form.nativeapp.vault.VaultCustomExercise
import com.thehhr.form.nativeapp.vault.VaultSnapshot
import com.thehhr.form.nativeapp.vault.VaultCodec
import com.thehhr.form.nativeapp.vault.VaultFiles
import com.thehhr.form.nativeapp.vault.VaultWriteException
import com.thehhr.form.nativeapp.vault.SafVaultStore
import com.thehhr.form.nativeapp.workout.ExerciseRules
import com.thehhr.form.nativeapp.workout.WorkoutSession
import com.thehhr.form.nativeapp.workout.WorkoutSessionStore
import com.thehhr.form.nativeapp.workout.WorkoutSetRow
import com.thehhr.form.nativeapp.workout.WorkoutNotifications
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException

data class FormState(
    val exercises: List<Exercise> = emptyList(),
    val vault: VaultSnapshot? = null,
    val data: VaultData = VaultData(),
    val loading: Boolean = true,
    val vaultBusy: Boolean = true,
    val reloadRequired: Boolean = false,
    val error: String? = null,
    val workout: WorkoutSession? = null,
    val workoutBusy: Boolean = false,
    val workoutError: String? = null,
    val workoutLoadFailed: Boolean = false,
    val builtInExerciseIds: Set<String> = emptySet(),
    val builtInEquipment: Set<String> = emptySet()
) {
    val workoutPaused: Boolean
        get() = workout?.paused == true && workout?.finishing != true
}

class FormViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VaultRepository(application)
    private val mutableState = MutableStateFlow(FormState())
    private var builtIns = emptyList<Exercise>()
    private var workoutLoadFailed = false
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            WorkoutSessionStore.changes.collect {
                while (mutableState.value.loading || mutableState.value.vaultBusy || mutableState.value.workoutBusy) kotlinx.coroutines.delay(100)
                refreshWorkoutNotification()
            }
        }
        viewModelScope.launch {
            try {
                builtIns = withContext(Dispatchers.IO) {
                    val json = application.assets.open("exercises.json").bufferedReader().use { JSONArray(it.readText()) }
                    List(json.length()) { index ->
                        val item = json.getJSONObject(index)
                        val steps = item.optJSONObject("instruction_steps")?.optJSONArray("en") ?: JSONArray()
                        val secondaries = item.optJSONArray("secondary_muscles")?.let { array -> List(array.length()) { position -> array.getString(position) } } ?: emptyList()
                        Exercise(item.getString("id"), item.getString("name"), item.optString("category"), item.optString("target"), item.optString("equipment"), item.optString("image"), item.optString("gif_url"), List(steps.length()) { steps.getString(it) }, item.optString("attribution"), item.optString("muscle_group"), secondaries)
                    }
                }
                mutableState.update { it.copy(exercises = builtIns, loading = false, builtInExerciseIds = builtIns.map { exercise -> exercise.id }.toSet(), builtInEquipment = builtIns.map { exercise -> exercise.equipment }.toSet()) }
                publish(repository.restore())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, vaultBusy = false, reloadRequired = true, error = error.message ?: "Unable to load Form") }
            } finally {
                mutableState.update { it.copy(vaultBusy = false) }
            }
        }
    }

    fun updateRestEnabled(enabled: Boolean) = guardedVaultAction({ repository.updateRestEnabled(enabled) }, {})

    fun refreshWorkoutNotification() {
        val snapshot = mutableState.value
        if (snapshot.loading || snapshot.vaultBusy || snapshot.workoutBusy) return
        val uri = snapshot.vault?.uri ?: return
        mutableState.update { it.copy(workoutBusy = true) }
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    synchronized(WorkoutSessionStore) {
                        WorkoutSessionStore.load(getApplication(), uri).also {
                            WorkoutNotifications.update(getApplication(), it, uri)
                        }
                    }
                }
                workoutLoadFailed = false
                mutableState.update { it.copy(workout = session, workoutLoadFailed = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                workoutLoadFailed = true
                mutableState.update { it.copy(workoutLoadFailed = true, workoutError = "Unable to restore workout draft. Reload or discard the unreadable draft.") }
            } finally {
                mutableState.update { it.copy(workoutBusy = false) }
            }
        }
    }

    fun selectVault(uri: Uri) = vaultAction { repository.open(uri) }
    fun reloadVault() = vaultAction(reloadWorkout = true) { repository.reload() }
    fun updateAccent(value: String) {
        if (!mutableState.value.reloadRequired) vaultAction { repository.updateAccent(value) }
    }
    fun setExerciseLiked(id: String, liked: Boolean) {
        if (!mutableState.value.reloadRequired) vaultAction { repository.setExerciseLiked(id, liked) }
    }

    fun importLibrary(preview: com.thehhr.form.nativeapp.vault.TransferPreview, onSaved: () -> Unit = {}) =
        guardedVaultAction({
            val current = mutableState.value
            require(current.workout == null && !workoutLoadFailed && !current.workoutLoadFailed) {
                "Finish or discard the workout draft before importing"
            }
            require(current.vault?.uri == preview.uri) { "The preview belongs to another vault" }
            withContext(Dispatchers.IO) {
                synchronized(WorkoutSessionStore) {
                    require(WorkoutSessionStore.load(getApplication(), preview.uri) == null) {
                        "A persisted workout draft exists. Reload, then finish or discard it before importing."
                    }
                }
            }
            withContext(NonCancellable) { repository.importLibrary(preview, builtIns.map { it.id }.toSet()) }
        }, onSaved)

    fun saveRoutine(original: VaultRoutine?, replacement: VaultRoutine?, onSaved: () -> Unit = {}) =
        guardedVaultAction({ repository.saveRoutine(original, replacement) }, onSaved)

    fun saveDiaryEntry(date: String, original: com.thehhr.form.nativeapp.vault.VaultDiaryMeal?, replacement: com.thehhr.form.nativeapp.vault.VaultDiaryMeal?, onSaved: () -> Unit = {}) {
        val uri = mutableState.value.vault?.uri ?: return
        guardedVaultAction({ repository.saveDiaryEntry(uri, date, original, replacement) }, onSaved)
    }

    fun setDiaryWater(date: String, original: Double, desired: Double, onSaved: () -> Unit = {}) {
        val uri = mutableState.value.vault?.uri ?: return
        guardedVaultAction({ repository.setDiaryWater(uri, date, original, desired) }, onSaved)
    }

    fun saveMeal(original: VaultMeal?, replacement: VaultMeal?, onSaved: () -> Unit = {}) =
        guardedVaultAction({ repository.saveMeal(original, replacement) }, onSaved)

    fun saveConfigField(section: String, key: String, original: String?, value: String?, onSaved: () -> Unit = {}) {
        val uri = mutableState.value.vault?.uri ?: return
        guardedVaultAction({ repository.saveConfigField(uri, section, key, original, value) }, onSaved)
    }

    fun setWeeklySchedule(day: Int, original: String?, desired: String?, onSaved: () -> Unit = {}) =
        guardedVaultAction({ repository.setWeeklySchedule(day, original, desired) }, onSaved)

    fun saveCustomExercise(original: VaultCustomExercise?, replacement: VaultCustomExercise?, onSaved: () -> Unit = {}) =
        guardedVaultAction({ repository.saveCustomExercise(original, replacement) }, onSaved)

    fun setExerciseTags(id: String, original: List<String>?, tags: List<String>, onSaved: () -> Unit = {}) =
        guardedVaultAction({ repository.setExerciseTags(id, original, tags) }, onSaved)

    fun saveManualWorkoutLog(log: com.thehhr.form.nativeapp.vault.VaultTrainingLog, onSaved: () -> Unit = {}) =
        guardedVaultAction({
            requireTrainingHistoryEditable()
            repository.saveManualWorkoutLog(vaultUriForTrainingHistory(), log)
        }, onSaved)

    fun deleteWorkoutLog(original: com.thehhr.form.nativeapp.vault.VaultTrainingLog, onSaved: () -> Unit = {}) =
        guardedVaultAction({
            requireTrainingHistoryEditable()
            repository.deleteTrainingLog(vaultUriForTrainingHistory(), original)
        }, onSaved)

    private fun vaultUriForTrainingHistory(): String =
        mutableState.value.vault?.uri ?: throw IOException("Choose and load a vault before changing training history")

    private suspend fun requireTrainingHistoryEditable() {
        val current = mutableState.value
        require(current.workout == null && !workoutLoadFailed && !current.workoutLoadFailed) {
            "Finish or discard the workout draft before changing training history"
        }
        val uri = vaultUriForTrainingHistory()
        withContext(Dispatchers.IO) {
            synchronized(WorkoutSessionStore) {
                require(WorkoutSessionStore.load(getApplication(), uri) == null) {
                    "A persisted workout draft exists. Reload, then finish or discard it before changing training history."
                }
            }
        }
    }

    fun startWorkout(routine: VaultRoutine, onStarted: () -> Unit = {}) {
        val current = mutableState.value
        if (current.workout != null || workoutLoadFailed) return
        workoutAction { snapshot, uri ->
            val vault = requireNotNull(snapshot.vault) { "Choose and load a vault first" }
            require(snapshot.data.routines.singleOrNull { it.id == routine.id } == routine) {
                "This routine changed. Reload and select it again before starting."
            }
            val source = requireNotNull(vault.file(VaultFiles.TRAINING_LOGS)) { "Restore training_logs.md before starting a workout" }
            require(VaultCodec.parseTrainingLogs(source).canRewrite) { "Resolve training_logs.md diagnostics before starting a workout" }
            val catalog = snapshot.exercises.associateBy { it.id }
            require(routine.items.all { catalog.containsKey(it.exerciseId) }) { "This routine contains unavailable exercises" }
            val session = WorkoutSessionStore.buildSession(routine, catalog, snapshot.data.trainingLogs)
            saveWorkoutDraft(uri, session)
            onStarted()
        }
    }

    fun updateWorkoutRow(exerciseIndex: Int, setIndex: Int, row: WorkoutSetRow) {
        val exerciseId = mutableState.value.workout?.exercises?.getOrNull(exerciseIndex)?.exerciseId
        editWorkout(afterSave = { draft, uri -> exerciseId?.let { syncSessionLogs(uri, draft, listOf(it)) } }) { session ->
            require(!session.paused) { "Resume the workout before editing sets" }
            val exercise = requireNotNull(session.exercises.getOrNull(exerciseIndex)) { "Invalid exercise index" }
            require(!exercise.skipped) { "Undo skip before editing this exercise" }
            val previous = requireNotNull(exercise.sets.getOrNull(setIndex)) { "Invalid set index" }
            val sets = exercise.sets.mapIndexed { index, existing -> if (index == setIndex) row else existing }
            val exercises = session.exercises.mapIndexed { index, existing ->
                if (index == exerciseIndex) exercise.copy(sets = sets) else existing
            }
            val restDeadline = if (!previous.done && row.done) {
                restDeadlineFor(session.copy(exercises = exercises), exerciseIndex, sets)
            } else session.restDeadline
            session.copy(exercises = exercises, restDeadline = restDeadline)
        }
    }

    fun addWorkoutSet(exerciseIndex: Int) {
        val exerciseId = mutableState.value.workout?.exercises?.getOrNull(exerciseIndex)?.exerciseId
        editWorkout(afterSave = { draft, uri -> exerciseId?.let { syncSessionLogs(uri, draft, listOf(it)) } }) { session ->
            require(!session.paused) { "Resume the workout before editing sets" }
            val exercise = requireNotNull(session.exercises.getOrNull(exerciseIndex)) { "Invalid exercise index" }
            require(!exercise.skipped) { "Undo skip before editing this exercise" }
            val added = ExerciseRules.addedRow(exercise.sets, exercise) ?: return@editWorkout session
            session.copy(exercises = session.exercises.mapIndexed { index, existing ->
                if (index == exerciseIndex) existing.copy(sets = existing.sets + added) else existing
            })
        }
    }

    fun removeWorkoutSet(exerciseIndex: Int) {
        val exerciseId = mutableState.value.workout?.exercises?.getOrNull(exerciseIndex)?.exerciseId
        editWorkout(afterSave = { draft, uri -> exerciseId?.let { syncSessionLogs(uri, draft, listOf(it)) } }) { session ->
            require(!session.paused) { "Resume the workout before editing sets" }
            val exercise = requireNotNull(session.exercises.getOrNull(exerciseIndex)) { "Invalid exercise index" }
            require(!exercise.skipped) { "Undo skip before editing this exercise" }
            if (exercise.sets.size <= 1) return@editWorkout session
            session.copy(exercises = session.exercises.mapIndexed { index, existing ->
                if (index == exerciseIndex) existing.copy(sets = existing.sets.dropLast(1)) else existing
            })
        }
    }

    fun markAllWorkoutSetsDone(exerciseIndex: Int) {
        val exerciseId = mutableState.value.workout?.exercises?.getOrNull(exerciseIndex)?.exerciseId
        editWorkout(afterSave = { draft, uri -> exerciseId?.let { syncSessionLogs(uri, draft, listOf(it)) } }) { session ->
            require(!session.paused) { "Resume the workout before editing sets" }
            val exercise = requireNotNull(session.exercises.getOrNull(exerciseIndex)) { "Invalid exercise index" }
            require(!exercise.skipped) { "Undo skip before editing this exercise" }
            if (exercise.sets.all { it.done }) return@editWorkout session
            val sets = exercise.sets.map { it.copy(done = true) }
            val exercises = session.exercises.mapIndexed { index, existing ->
                if (index == exerciseIndex) exercise.copy(sets = sets) else existing
            }
            session.copy(exercises = exercises, restDeadline = restDeadlineFor(session.copy(exercises = exercises), exerciseIndex, sets))
        }
    }

    fun undoLastWorkoutSet(exerciseIndex: Int) {
        val exerciseId = mutableState.value.workout?.exercises?.getOrNull(exerciseIndex)?.exerciseId
        editWorkout(afterSave = { draft, uri -> exerciseId?.let { syncSessionLogs(uri, draft, listOf(it)) } }) { session ->
            require(!session.paused) { "Resume the workout before editing sets" }
            val exercise = requireNotNull(session.exercises.getOrNull(exerciseIndex)) { "Invalid exercise index" }
            require(!exercise.skipped) { "Undo skip before editing this exercise" }
            val lastIndex = exercise.sets.indexOfLast { it.done }
            if (lastIndex < 0) return@editWorkout session
            val sets = exercise.sets.mapIndexed { index, existing -> if (index == lastIndex) existing.copy(done = false) else existing }
            session.copy(exercises = session.exercises.mapIndexed { index, existing ->
                if (index == exerciseIndex) exercise.copy(sets = sets) else existing
            })
        }
    }

    fun setWorkoutIndex(index: Int) = editWorkout { session ->
        require(index in session.exercises.indices) { "Invalid exercise index" }
        session.copy(currentIndex = index)
    }

    fun skipWorkoutExercise(index: Int) = editWorkout { session ->
        require(!session.paused) { "Resume the workout before skipping an exercise" }
        val exercise = requireNotNull(session.exercises.getOrNull(index)) { "Invalid exercise index" }
        session.copy(
            exercises = session.exercises.mapIndexed { position, existing ->
                if (position == index) exercise.copy(skipped = !exercise.skipped) else existing
            },
            restDeadline = null
        )
    }

    fun pauseWorkout() = editWorkout { session -> session.copy(paused = !session.paused) }

    fun resumeWorkout() = editWorkout { session -> session.copy(paused = false) }

    fun toggleSecondaryRoutine(routineId: String) = editWorkout { session ->
        val current = mutableState.value
        val updated = ExerciseRules.withSecondaryToggled(
            session, routineId, current.data.routines,
            current.exercises.associateBy { it.id }, current.data.trainingLogs
        )
        requireNotNull(updated) { "This secondary routine is unavailable" }
    }

    fun adjustWorkoutRest(seconds: Int) = editWorkout { session ->
        require(!session.paused) { "Resume the workout before adjusting rest" }
        val now = System.currentTimeMillis()
        val deadline = session.restDeadline
        val adjusted = if (seconds == 0 || deadline == null) null else {
            val delta = seconds.toLong() * 1000L
            val value = if (delta > 0 && deadline > Long.MAX_VALUE - delta) Long.MAX_VALUE else deadline + delta
            value.takeIf { it > now }
        }
        session.copy(restDeadline = adjusted)
    }

    private fun restDeadlineFor(session: WorkoutSession, exerciseIndex: Int, sets: List<WorkoutSetRow>): Long? {
        val config = mutableState.value.data.config
        val exercises = session.exercises
        val exercise = exercises[exerciseIndex]
        val members = exercise.superset?.let { group -> exercises.indices.filter { exercises[it].superset == group } }.orEmpty()
        val partnerIndex = if (members.size == 2) members.single { it != exerciseIndex } else null
        val partnerPending = partnerIndex?.let { !exercises[it].skipped && exercises[it].sets.any { set -> !set.done } } == true
        val firstMember = members.firstOrNull() == exerciseIndex
        val complete = sets.all { it.done } && !partnerPending
        val pending = exercises.any { !it.skipped && it.sets.any { set -> !set.done } }
        if (!pending || (firstMember && partnerPending) || config.booleanPreference("rest-enabled") != true) return null
        val seconds = config.numberPreference(if (complete) "rest-between-exercises" else "rest-between-sets")
            ?.takeIf { it.isFinite() }?.coerceIn(30.0, 180.0) ?: if (complete) 90.0 else 60.0
        return System.currentTimeMillis() + (seconds * 1000).toLong()
    }

    fun finishWorkout() {
        if (mutableState.value.workout == null || workoutLoadFailed) return
        workoutAction { snapshot, uri ->
            val session = requireNotNull(snapshot.workout)
            val finishing = session.copy(finishing = true, paused = true, restDeadline = null)
            val logs = WorkoutSessionStore.toLogs(finishing)
            require(logs.isNotEmpty()) { "Mark at least one set done before finishing the workout" }
            saveWorkoutDraft(uri, finishing)
            runCatching {
                syncSessionLogs(uri, finishing, finishing.exercises.filter { !it.skipped }.map { it.exerciseId })
            }
            val saved = repository.saveWorkoutLogs(uri, logs)
            val document = saved.file(VaultFiles.TRAINING_LOGS)?.let(VaultCodec::parseTrainingLogs)
            if (saved.uri != uri || document == null || !document.canRewrite ||
                logs.any { log -> document.value.singleOrNull { it.id == log.id } != log }) {
                throw VaultReloadRequiredException("Unable to verify workout logs. Reload the vault before retrying finish.")
            }
            publish(saved)
            saveWorkoutDraft(uri, null)
        }
    }

    fun discardWorkout() {
        if (mutableState.value.workout?.finishing == true) return
        workoutAction(allowReloadRequired = true, allowLoadFailure = true) { snapshot, uri ->
            snapshot.workout?.let { session -> removeSessionLogs(uri, session) }
            saveWorkoutDraft(uri, null)
            workoutLoadFailed = false
        }
    }

    private suspend fun syncSessionLogs(uri: String, session: WorkoutSession, exerciseIds: List<String>) {
        if (exerciseIds.isEmpty()) return
        val sessionIds = exerciseIds.mapTo(mutableSetOf()) { ExerciseRules.logId(session.startedAt, it) }
        val fresh = exerciseIds.mapNotNull { id ->
            session.exercises.firstOrNull { it.exerciseId == id && !it.skipped }
        }.mapNotNull { ExerciseRules.logForExercise(session, it) }
        rewriteSessionLogs(uri) { logs -> logs.filterNot { it.id in sessionIds } + fresh }
    }

    private suspend fun removeSessionLogs(uri: String, session: WorkoutSession) {
        val ids = (session.exercises.asSequence() + session.dormant.values.asSequence())
            .map { it.exerciseId }.toSet()
            .mapTo(mutableSetOf()) { ExerciseRules.logId(session.startedAt, it) }
        rewriteSessionLogs(uri) { logs -> logs.filterNot { it.id in ids } }
    }

    private suspend fun rewriteSessionLogs(uri: String, update: (List<com.thehhr.form.nativeapp.vault.VaultTrainingLog>) -> List<com.thehhr.form.nativeapp.vault.VaultTrainingLog>) {
        val snapshot = mutableState.value.vault ?: throw IOException("Choose and load a vault before recording workout history")
        require(snapshot.uri == uri) { "The workout belongs to a different vault" }
        withContext(Dispatchers.IO) {
            val store = SafVaultStore(getApplication(), Uri.parse(uri))
            val read = store.read(VaultFiles.TRAINING_LOGS)
            val external = read.content ?: throw IOException(read.error ?: "Restore training_logs.md before recording workout history")
            val baseline = snapshot.files[VaultFiles.TRAINING_LOGS]
            if (baseline != null && external != baseline) {
                throw VaultConflictException("training_logs.md changed outside Form. Reload the vault; the workout draft is retained.")
            }
            val document = VaultCodec.parseTrainingLogs(external)
            if (!document.canRewrite) {
                throw VaultWriteException("Resolve training_logs.md diagnostics before recording workout history. The workout draft is retained.")
            }
            val merged = update(document.value)
            if (merged != document.value) {
                val patched = VaultCodec.encodeTrainingLogs(merged, document)
                if (store.read(VaultFiles.TRAINING_LOGS).content != external) {
                    throw VaultConflictException("training_logs.md changed while recording workout history. Reload the vault; the workout draft is retained.")
                }
                store.write(VaultFiles.TRAINING_LOGS, patched)
                if (store.read(VaultFiles.TRAINING_LOGS).content != patched) {
                    throw VaultReloadRequiredException("Unable to verify the workout record save. Reload the vault; the workout draft is retained.")
                }
                mutableState.update { current ->
                    current.copy(vault = current.vault?.let { it.copy(files = it.files + (VaultFiles.TRAINING_LOGS to patched)) })
                }
            }
        }
    }

    private fun editWorkout(
        afterSave: (suspend (WorkoutSession, String) -> Unit)? = null,
        transform: (WorkoutSession) -> WorkoutSession
    ) {
        val session = mutableState.value.workout ?: return
        if (session.finishing || workoutLoadFailed) return
        workoutAction { snapshot, uri ->
            val original = requireNotNull(snapshot.workout)
            val draft = transform(original)
            if (draft != original) {
                saveWorkoutDraft(uri, draft)
                afterSave?.invoke(draft, uri)
            }
        }
    }

    private suspend fun saveWorkoutDraft(uri: String, session: WorkoutSession?) = withContext(NonCancellable) {
        val expected = mutableState.value.workout
        withContext(Dispatchers.IO) {
            synchronized(WorkoutSessionStore) {
                if (workoutLoadFailed && session == null) WorkoutSessionStore.save(getApplication(), uri, null)
                else WorkoutSessionStore.compareAndSave(getApplication(), uri, expected, session)
                WorkoutNotifications.update(getApplication(), session, uri)
            }
        }
        mutableState.update { it.copy(workout = session, workoutError = null, workoutLoadFailed = false) }
    }

    private fun workoutAction(
        allowReloadRequired: Boolean = false,
        allowLoadFailure: Boolean = false,
        action: suspend (FormState, String) -> Unit
    ) {
        val current = mutableState.value
        if (current.loading || current.vaultBusy || current.workoutBusy ||
            (!allowReloadRequired && current.reloadRequired) || (!allowLoadFailure && workoutLoadFailed)) return
        val uri = current.vault?.uri ?: return
        mutableState.update { it.copy(workoutBusy = true) }
        viewModelScope.launch {
            try {
                action(current, uri)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        reloadRequired = it.reloadRequired || error is VaultReloadRequiredException,
                        workoutError = if (it.workout?.finishing == true) {
                            "${error.message ?: "Unable to finish workout"}. The finishing draft is locked; ${if (it.reloadRequired || error is VaultReloadRequiredException) "reload the vault, then retry finish" else "retry finish"}."
                        } else error.message ?: "Workout operation failed"
                    )
                }
            } finally {
                mutableState.update { it.copy(workoutBusy = false) }
            }
        }
    }

    private fun guardedVaultAction(action: suspend () -> VaultSnapshot, onSaved: () -> Unit) {
        if (mutableState.value.reloadRequired || mutableState.value.vaultBusy || mutableState.value.workoutBusy || mutableState.value.loading) return
        mutableState.update { it.copy(vaultBusy = true, error = null) }
        viewModelScope.launch {
            try {
                publish(action())
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(reloadRequired = it.reloadRequired || error is VaultReloadRequiredException, error = error.message ?: "Vault operation failed") }
            } finally {
                mutableState.update { it.copy(vaultBusy = false) }
            }
        }
    }

    fun dismissError() { mutableState.update { it.copy(error = null) } }

    private suspend fun publish(vault: VaultSnapshot?, reloadWorkout: Boolean = false) {
        var freshVault = vault
        if (freshVault != null && freshVault.files.containsKey(VaultFiles.TRAINING_LOGS)) {
            val read = withContext(Dispatchers.IO) {
                SafVaultStore(getApplication(), Uri.parse(freshVault.uri)).read(VaultFiles.TRAINING_LOGS)
            }
            val text = read.content
            if (!read.isError && text != null && text != freshVault.files[VaultFiles.TRAINING_LOGS]) {
                freshVault = freshVault.copy(files = freshVault.files + (VaultFiles.TRAINING_LOGS to text))
            }
        }
        val projection = withContext(Dispatchers.Default) { VaultProjection.from(freshVault, builtIns) }
        val current = mutableState.value
        val changed = current.vault?.uri != freshVault?.uri
        var workout = if (changed) null else current.workout
        var workoutError = if (changed) null else current.workoutError
        var loadFailed = if (changed) false else workoutLoadFailed
        if (changed || reloadWorkout) {
            try {
                workout = freshVault?.let { snapshot ->
                    withContext(Dispatchers.IO) {
                        synchronized(WorkoutSessionStore) {
                            WorkoutSessionStore.load(getApplication(), snapshot.uri).also {
                                WorkoutNotifications.update(getApplication(), it, snapshot.uri)
                            }
                        }
                    }
                }
                loadFailed = false
                workoutError = if (workout?.finishing == true) "This workout is finishing. Edits and discard are locked; retry finish to complete saving." else null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                loadFailed = true
                workoutError = "${error.message ?: "Unable to load workout draft"}. Reload or explicitly discard the local draft before starting another workout."
            }
        }
        workoutLoadFailed = loadFailed
        mutableState.update {
            it.copy(vault = freshVault, data = projection.data, exercises = projection.exercises, loading = false,
                reloadRequired = false, error = null, workout = workout, workoutError = workoutError, workoutLoadFailed = loadFailed)
        }
    }

    private fun vaultAction(reloadWorkout: Boolean = false, action: suspend () -> VaultSnapshot) {
        if (mutableState.value.vaultBusy || mutableState.value.workoutBusy || mutableState.value.loading) return
        mutableState.update { it.copy(vaultBusy = true) }
        viewModelScope.launch {
            try {
                publish(action(), reloadWorkout)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(vaultBusy = false, reloadRequired = it.reloadRequired || error is VaultReloadRequiredException, error = error.message ?: "Vault operation failed") }
            } finally {
                mutableState.update { it.copy(vaultBusy = false) }
            }
        }
    }
}
