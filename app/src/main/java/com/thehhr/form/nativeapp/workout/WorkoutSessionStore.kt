package com.thehhr.form.nativeapp.workout

import android.content.Context
import com.thehhr.form.nativeapp.Exercise
import com.thehhr.form.nativeapp.vault.VaultRoutine
import com.thehhr.form.nativeapp.vault.VaultRoutineItem
import com.thehhr.form.nativeapp.vault.VaultTrainingLog
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class WorkoutSetRow(
    val weight: Double = 0.0,
    val reps: Int = 10,
    val durationMin: Double = 0.5,
    val distanceKm: Double = 0.0,
    val done: Boolean = false
)

data class WorkoutExerciseState(
    val exerciseId: String,
    val exerciseName: String,
    val sets: List<WorkoutSetRow> = emptyList(),
    val skipped: Boolean = false,
    val mode: String? = null,
    val unit: String? = null,
    val weighted: Boolean = false,
    val unweighted: Boolean = false,
    val superset: String? = null,
    val prescribedReps: Int = 10
)

data class WorkoutSession(
    val id: String,
    val date: String,
    val startedAt: Long,
    val routineId: String,
    val routineName: String,
    val paused: Boolean = false,
    val restDeadline: Long? = null,
    val exercises: List<WorkoutExerciseState> = emptyList(),
    val currentIndex: Int = 0,
    val finishing: Boolean = false,
    val secondaryIds: List<String> = emptyList(),
    val dormant: Map<String, WorkoutExerciseState> = emptyMap()
)

object WorkoutSessionStore {
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

    @Synchronized
    fun compareAndSave(context: Context, vaultUri: String, expected: WorkoutSession?, session: WorkoutSession?) {
        if (load(context, vaultUri) != expected) throw IOException("Workout changed from another control. Review the refreshed draft and try again.")
        save(context, vaultUri, session)
    }

    private const val KEY_PREFIX = "session:"
    private const val SCHEMA_VERSION = 2
    private const val LEGACY_SCHEMA_VERSION = 1
    private val exerciseIdPattern = Regex("(?:[0-9]{4,5}|c-[\\w-]+)")
    private val recordIdPattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    @Synchronized
    @Throws(IOException::class)
    fun load(context: Context, vaultUri: String): WorkoutSession? {
        try {
            require(vaultUri.isNotBlank()) { "Vault URI is empty" }
            val preferences = context.getSharedPreferences("workout", Context.MODE_PRIVATE)
            val raw = preferences.getString(KEY_PREFIX + vaultUri, null) ?: return null
            val reader = JSONTokener(raw)
            val root = reader.nextValue() as? JSONObject
                ?: throw IllegalArgumentException("Expected a workout session object")
            require(reader.nextClean() == '\u0000') { "Trailing session data" }
            val schemaVersion = root.integer("schemaVersion")
            require(schemaVersion == SCHEMA_VERSION || schemaVersion == LEGACY_SCHEMA_VERSION) { "Unsupported workout session schema" }
            return decode(root, schemaVersion).also { validate(it) }
        } catch (exception: Exception) {
            throw IOException("Unable to load workout session", exception)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun save(context: Context, vaultUri: String, session: WorkoutSession?) {
        try {
            require(vaultUri.isNotBlank()) { "Vault URI is empty" }
            val raw = session?.let {
                validate(it)
                encode(it).toString()
            }
            val preferences = context.getSharedPreferences("workout", Context.MODE_PRIVATE)
            val editor = preferences.edit()
            if (raw == null) editor.remove(KEY_PREFIX + vaultUri)
            else editor.putString(KEY_PREFIX + vaultUri, raw)
            if (!editor.commit()) throw IOException("Workout session commit failed")
            changes.value = System.currentTimeMillis()
        } catch (exception: IOException) {
            throw exception
        } catch (exception: Exception) {
            throw IOException("Unable to save workout session", exception)
        }
    }

    fun valid(session: WorkoutSession?, routine: VaultRoutine? = null): Boolean {
        if (session == null) return false
        return try {
            validate(session)
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: java.time.DateTimeException) {
            false
        }
    }

    fun today(): String = LocalDate.now().toString()

    fun newSessionId(): String = UUID.randomUUID().toString()

    fun buildSession(routine: VaultRoutine, catalog: Map<String, Exercise>, history: List<VaultTrainingLog>): WorkoutSession {
        require(routine.items.isNotEmpty()) { "Routine has no exercises" }
        require(routine.items.map { it.exerciseId }.distinct().size == routine.items.size) { "Duplicate routine exercise identifiers" }
        val exercises = routine.items.map { item ->
            val exercise = requireNotNull(catalog[item.exerciseId]) { "Missing catalog exercise: ${item.exerciseId}" }
            require(exercise.id == item.exerciseId) { "Catalog exercise identifier mismatch" }
            validateItem(item)
            ExerciseRules.exerciseState(item, exercise, history)
        }
        val startedAt = System.currentTimeMillis()
        return WorkoutSession(
            id = newSessionId(),
            date = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
            startedAt = startedAt,
            routineId = routine.id,
            routineName = routine.name,
            exercises = exercises
        ).also { validate(it) }
    }

    fun toLogs(session: WorkoutSession): List<VaultTrainingLog> {
        validate(session)
        return session.exercises.filter { !it.skipped }.mapNotNull { ExerciseRules.logForExercise(session, it) }
    }

    private fun validateItem(item: VaultRoutineItem) {
        require(exerciseIdPattern.matches(item.exerciseId)) { "Invalid exercise identifier" }
        require(item.sets in 1..20) { "Set count must be between 1 and 20" }
        require(item.reps in 0..100) { "Invalid routine repetitions or duration" }
        validateModifiers(item.mode, item.unit, item.superset)
    }

    private fun validateModifiers(mode: String?, unit: String?, superset: String?) {
        require(mode == null || mode.equals("timed", true) || mode.equals("reps", true)) { "Invalid workout mode" }
        require(unit == null || unit.equals("sec", true) || unit.equals("min", true)) { "Invalid workout duration unit" }
        require(superset == null || singleLine(superset)) { "Invalid superset identifier" }
    }

    private fun singleLine(value: String): Boolean =
        value.isNotBlank() && value == value.trim() && '\n' !in value && '\r' !in value

    private fun validate(session: WorkoutSession) {
        require(session.id.length == 36 && UUID.fromString(session.id).toString().equals(session.id, true)) { "Invalid session UUID" }
        require(datePattern.matches(session.date) && LocalDate.parse(session.date).toString() == session.date) { "Invalid session date" }
        require(session.startedAt >= 0) { "Invalid session start time" }
        require(session.restDeadline == null || session.restDeadline >= 0) { "Invalid rest deadline" }
        require(recordIdPattern.matches(session.routineId)) { "Invalid routine identifier" }
        require(singleLine(session.routineName)) { "Invalid routine name" }
        require(session.exercises.isNotEmpty()) { "Session has no exercises" }
        require(session.currentIndex in session.exercises.indices) { "Invalid current exercise index" }
        require(session.exercises.map { it.exerciseId }.distinct().size == session.exercises.size) { "Duplicate exercise identifiers" }
        require(session.secondaryIds.distinct().size == session.secondaryIds.size) { "Duplicate secondary routine identifier" }
        session.secondaryIds.forEach { id ->
            require(recordIdPattern.matches(id)) { "Invalid secondary routine identifier" }
            require(id != session.routineId) { "Secondary routine cannot be the primary routine" }
        }
        session.dormant.forEach { (key, value) ->
            require(key == value.exerciseId) { "Dormant exercise key mismatch" }
            require(session.exercises.none { it.exerciseId == key }) { "Dormant exercise conflicts with an active exercise" }
            validateExercise(value)
        }
        for (exercise in session.exercises) validateExercise(exercise)
    }

    private fun validateExercise(exercise: WorkoutExerciseState) {
        require(exerciseIdPattern.matches(exercise.exerciseId)) { "Invalid exercise identifier" }
        require(singleLine(exercise.exerciseName)) { "Invalid exercise name" }
        require(exercise.prescribedReps in 0..100) { "Invalid prescribed repetitions" }
        validateModifiers(exercise.mode, exercise.unit, exercise.superset)
        require(exercise.sets.size in 1..20) { "Set count must be between 1 and 20" }
        for (row in exercise.sets) {
            require(row.weight.isFinite() && row.weight in 0.0..2000.0) { "Invalid set weight" }
            require(row.reps in 1..100) { "Invalid set repetitions" }
            require(row.durationMin.isFinite() && row.durationMin in 0.0..600.0) { "Invalid set duration" }
            require(row.distanceKm.isFinite() && row.distanceKm in 0.0..500.0) { "Invalid set distance" }
        }
    }

    private fun encode(session: WorkoutSession): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("id", session.id)
        .put("date", session.date)
        .put("startedAt", session.startedAt)
        .put("routineId", session.routineId)
        .put("routineName", session.routineName)
        .put("paused", session.paused)
        .put("restDeadline", session.restDeadline ?: JSONObject.NULL)
        .put("currentIndex", session.currentIndex)
        .put("finishing", session.finishing)
        .put("secondaryIds", JSONArray().also { ids -> session.secondaryIds.forEach { ids.put(it) } })
        .put("dormant", JSONObject().also { dormant -> session.dormant.forEach { (key, value) -> dormant.put(key, encodeExercise(value)) } })
        .put("exercises", JSONArray().also { exercises ->
            session.exercises.forEach { exercise -> exercises.put(encodeExercise(exercise)) }
        })

    private fun encodeExercise(exercise: WorkoutExerciseState): JSONObject = JSONObject()
        .put("exerciseId", exercise.exerciseId)
        .put("exerciseName", exercise.exerciseName)
        .put("skipped", exercise.skipped)
        .put("mode", exercise.mode ?: JSONObject.NULL)
        .put("unit", exercise.unit ?: JSONObject.NULL)
        .put("weighted", exercise.weighted)
        .put("unweighted", exercise.unweighted)
        .put("superset", exercise.superset ?: JSONObject.NULL)
        .put("prescribedReps", exercise.prescribedReps)
        .put("sets", JSONArray().also { sets ->
            exercise.sets.forEach { row ->
                sets.put(JSONObject()
                    .put("weight", row.weight)
                    .put("reps", row.reps)
                    .put("durationMin", row.durationMin)
                    .put("distanceKm", row.distanceKm)
                    .put("done", row.done))
            }
        })

    private fun decode(root: JSONObject, schemaVersion: Int): WorkoutSession {
        val session = WorkoutSession(
            id = root.string("id"),
            date = root.string("date"),
            startedAt = root.long("startedAt"),
            routineId = root.string("routineId"),
            routineName = root.string("routineName"),
            paused = root.boolean("paused"),
            restDeadline = if (root.get("restDeadline") == JSONObject.NULL) null else root.long("restDeadline"),
            currentIndex = root.integer("currentIndex"),
            finishing = if (root.has("finishing")) root.boolean("finishing") else false,
            secondaryIds = root.optJSONArray("secondaryIds")?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList(),
            dormant = root.optJSONObject("dormant")?.let { dormant ->
                buildMap { dormant.keys().forEach { key -> put(key, decodeExercise(dormant.getJSONObject(key))) } }
            } ?: emptyMap(),
            exercises = root.objects("exercises").map { decodeExercise(it) }
        )
        return if (schemaVersion == LEGACY_SCHEMA_VERSION) ExerciseRules.migrateV1(session) else session
    }

    private fun decodeExercise(exercise: JSONObject): WorkoutExerciseState = WorkoutExerciseState(
        exerciseId = exercise.string("exerciseId"),
        exerciseName = exercise.string("exerciseName"),
        skipped = exercise.boolean("skipped"),
        mode = exercise.nullableString("mode"),
        unit = exercise.nullableString("unit"),
        weighted = exercise.boolean("weighted"),
        unweighted = exercise.boolean("unweighted"),
        superset = exercise.nullableString("superset"),
        prescribedReps = if (exercise.has("prescribedReps")) exercise.integer("prescribedReps") else 10,
        sets = exercise.objects("sets").map { row ->
            WorkoutSetRow(
                weight = row.number("weight"),
                reps = row.integer("reps"),
                durationMin = row.number("durationMin"),
                distanceKm = row.number("distanceKm"),
                done = row.boolean("done")
            )
        }
    )

    private fun JSONObject.string(key: String): String =
        get(key) as? String ?: throw IllegalArgumentException("Expected string: $key")

    private fun JSONObject.nullableString(key: String): String? =
        if (get(key) == JSONObject.NULL) null else string(key)

    private fun JSONObject.boolean(key: String): Boolean =
        get(key) as? Boolean ?: throw IllegalArgumentException("Expected boolean: $key")

    private fun JSONObject.number(key: String): Double {
        val value = get(key) as? Number ?: throw IllegalArgumentException("Expected number: $key")
        return value.toDouble().also { require(it.isFinite()) { "Non-finite number: $key" } }
    }

    private fun JSONObject.long(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "Expected integer: $key" }
        return (value as Number).toLong()
    }

    private fun JSONObject.integer(key: String): Int {
        val value = long(key)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Integer out of range: $key" }
        return value.toInt()
    }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = get(key) as? JSONArray ?: throw IllegalArgumentException("Expected array: $key")
        return List(array.length()) { index ->
            array.get(index) as? JSONObject ?: throw IllegalArgumentException("Expected object: $key[$index]")
        }
    }
}
