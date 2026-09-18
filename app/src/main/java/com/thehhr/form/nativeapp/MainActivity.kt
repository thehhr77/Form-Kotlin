package com.thehhr.form.nativeapp

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thehhr.form.nativeapp.ui.theme.FormExpressiveTheme
import com.thehhr.form.nativeapp.vault.ConfigCodec
import com.thehhr.form.nativeapp.vault.VaultCustomExercise
import com.thehhr.form.nativeapp.vault.VaultTrainingLog
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FormApp() }
    }
}

private val accents = linkedMapOf("red" to Color(0xFFB3261E), "blue" to Color(0xFF245EA7), "green" to Color(0xFF326B38), "orange" to Color(0xFF8D4E00), "purple" to Color(0xFF71549B), "pink" to Color(0xFF9C416B))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FormApp(model: FormViewModel = viewModel()) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", 0) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var notificationPermissionGranted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    var notificationPermissionDenied by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPermissionGranted = granted
        notificationPermissionDenied = !granted
        if (granted) model.refreshWorkoutNotification()
    }
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeUnless { base -> base === it } }
            .filterIsInstance<ComponentActivity>().firstOrNull()
    }
    DisposableEffect(activity, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                today = LocalDate.now()
                notificationPermissionGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (notificationPermissionGranted) notificationPermissionDenied = false
                model.refreshWorkoutNotification()
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            today = LocalDate.now()
        }
    }
    val scheduledName = state.data.config.schedule[today.dayOfWeek.value % 7]
    val scheduledRoutine = if (state.vault != null && state.workout == null && state.data.config.preferences["workout-reminder"] != "false") {
        state.data.routines.singleOrNull { it.name == scheduledName }
            ?.takeIf { routine -> state.data.routines.count { it.id == routine.id } == 1 }
    } else null
    val reminderKey = state.vault?.uri?.let { uri ->
        scheduledRoutine?.let { "workout-reminder-dismissed:${uri.length}:$uri:$today:${it.id}" }
    }
    var reminderDismissed by remember(reminderKey) {
        mutableStateOf(reminderKey?.let { preferences.getBoolean(it, false) } ?: false)
    }
    var localAccent by rememberSaveable { mutableStateOf(preferences.getString("accent", "red") ?: "red") }
    val vaultAccent = state.data.config.preferences["accent"]
    val writeBlockReason = if (state.workoutBusy) "A workout operation is in progress. Changes are temporarily disabled." else state.configWriteBlockReason()
    val accent = if (state.vault != null) vaultAccent?.takeIf { it in accents } ?: "red" else localAccent
    var theme by rememberSaveable { mutableStateOf(preferences.getString("theme", "System") ?: "System") }
    var black by rememberSaveable { mutableStateOf(preferences.getBoolean("black", false)) }
    var tab by rememberSaveable { mutableIntStateOf(2) }
    var libraryFilters by rememberSaveable(state.vault?.uri, stateSaver = LibraryFilterStateSaver) { mutableStateOf(LibraryFilterState()) }
    val effectiveLibraryFilters = if (state.loading || state.vaultBusy) libraryFilters else libraryFilters.reconcile(state.data)
    LaunchedEffect(effectiveLibraryFilters) { libraryFilters = effectiveLibraryFilters }
    var fuelLibrary by rememberSaveable(state.vault?.uri) { mutableStateOf(false) }
    var nutritionSettings by rememberSaveable(state.vault?.uri) { mutableStateOf(false) }
    var transfers by rememberSaveable(state.vault?.uri) { mutableStateOf(false) }
    BackHandler(enabled = tab == 4 && nutritionSettings) {
        if (!state.loading && !state.vaultBusy && !state.workoutBusy) nutritionSettings = false
    }
    var selectedId by remember(state.vault?.uri) { mutableStateOf<String?>(null) }
    val selected = state.exercises.firstOrNull { it.id == selectedId }
    var customEditor by remember(state.vault?.uri) { mutableStateOf<CustomExerciseEditorSession?>(null) }
    var tagEditor by remember(state.vault?.uri) { mutableStateOf<ExerciseTagsEditorSession?>(null) }
    var deletingCustom by remember(state.vault?.uri) { mutableStateOf<VaultCustomExercise?>(null) }
    var manualLogSession by remember(state.vault?.uri) { mutableStateOf<ManualLoggingSession?>(null) }
    var deletingLog by remember(state.vault?.uri) { mutableStateOf<VaultTrainingLog?>(null) }
    val busy = state.loading || state.vaultBusy || state.workoutBusy
    val dark = theme == "Dark" || (theme == "System" && isSystemInDarkTheme())
    val seed = accents[accent] ?: accents.getValue("red")
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(model::selectVault) }
    val labels = listOf("Stats", "Plans", "Workout", "Fuel", "Settings")
    val icons = listOf(Icons.Default.DateRange, Icons.Default.List, Icons.Default.PlayArrow, Icons.Default.Favorite, Icons.Default.Settings)
    val haptic = LocalHapticFeedback.current
    FormExpressiveTheme(accentSeed = seed, darkTheme = dark, blackTheme = black) {
        Scaffold(
            topBar = { LargeTopAppBar(title = { Text(if (tab == 2 && (state.workout == null || state.workoutPaused)) "Exercises" else labels[tab]) }) },
            bottomBar = {
                NavigationBar {
                    labels.forEachIndexed { index, name ->
                        NavigationBarItem(selected = tab == index, enabled = !(transfers && busy), onClick = { tab = index; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, icon = { Icon(icons[index], contentDescription = null) }, label = { Text(name) })
                    }
                }
            }
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                when (tab) {
                    1 -> VaultPlansScreen(
                        state,
                        onOpenExercise = { selectedId = it.id },
                        onReload = model::reloadVault,
                        onSaveRoutine = model::saveRoutine,
                        onSaveMeal = model::saveMeal,
                        onSetSchedule = model::setWeeklySchedule,
                        onStartWorkout = { routine ->
                            if (state.workout != null) tab = 2
                            else if (!busy && !state.reloadRequired) model.startWorkout(routine) { tab = 2 }
                        }
                    )
                    2 -> when {
                        state.workout == null && state.workoutError == null -> Column(Modifier.fillMaxSize()) {
                            if (scheduledRoutine != null && reminderKey != null && !reminderDismissed) {
                                ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Today's scheduled workout", style = MaterialTheme.typography.titleMedium)
                                        Text(scheduledRoutine.name)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = {
                                                if (!busy && !state.reloadRequired) model.startWorkout(scheduledRoutine) { tab = 2 }
                                            }, enabled = !busy && !state.reloadRequired && scheduledRoutine.items.isNotEmpty()) { Text("Start workout") }
                                            TextButton(onClick = {
                                                preferences.edit().putBoolean(reminderKey, true).apply()
                                                reminderDismissed = true
                                            }) { Text("Dismiss for today") }
                                        }
                                    }
                                }
                            } else if (scheduledRoutine != null && reminderKey != null && reminderDismissed) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Today's workout banner is hidden for ${scheduledRoutine.name}.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = {
                                        preferences.edit().remove(reminderKey).apply()
                                        reminderDismissed = false
                                    }) { Text("Restore banner") }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                ExerciseLibrary(
                                    state, effectiveLibraryFilters, onFiltersChange = { libraryFilters = it },
                                    onOpen = { selectedId = it.id }, onReload = { if (!busy) model.reloadVault() },
                                    likeEnabled = writeBlockReason == null,
                                    onToggleLiked = { exercise ->
                                        if (writeBlockReason == null) model.setExerciseLiked(exercise.id, exercise.id !in state.data.config.liked)
                                    },
                                    onCreateCustom = {
                                        if (writeBlockReason == null) customEditor = CustomExerciseEditorSession(null, VaultCustomExercise("c-${UUID.randomUUID()}", ""))
                                    }
                                )
                            }
                        }
                        state.workoutPaused -> Column(Modifier.fillMaxSize()) {
                            ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Workout paused", style = MaterialTheme.typography.titleMedium)
                                    Text("${state.workout?.routineName} · sets keep their state and the rest timer keeps running.")
                                    Button(onClick = { if (!state.workoutBusy) model.resumeWorkout() }, enabled = !state.workoutBusy) { Text("Resume workout") }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                ExerciseLibrary(
                                    state, effectiveLibraryFilters, onFiltersChange = { libraryFilters = it },
                                    onOpen = { selectedId = it.id }, onReload = { if (!busy) model.reloadVault() },
                                    likeEnabled = writeBlockReason == null,
                                    onToggleLiked = { exercise ->
                                        if (writeBlockReason == null) model.setExerciseLiked(exercise.id, exercise.id !in state.data.config.liked)
                                    },
                                    onCreateCustom = {
                                        if (writeBlockReason == null) customEditor = CustomExerciseEditorSession(null, VaultCustomExercise("c-${UUID.randomUUID()}", ""))
                                    }
                                )
                            }
                        }
                        else -> ActiveWorkoutScreen(
                            state, model::updateWorkoutRow, model::setWorkoutIndex,
                            model::skipWorkoutExercise, model::pauseWorkout, model::adjustWorkoutRest,
                            model::finishWorkout, model::discardWorkout, model::reloadVault,
                            onAddSet = model::addWorkoutSet,
                            onRemoveSet = model::removeWorkoutSet,
                            onMarkAllDone = model::markAllWorkoutSetsDone,
                            onUndoLast = model::undoLastWorkoutSet,
                            onToggleSecondary = model::toggleSecondaryRoutine,
                            onOpenExercise = { selectedId = it }
                        )
                    }
                    3 -> if (fuelLibrary) Column(Modifier.fillMaxSize()) {
                        TextButton(onClick = { fuelLibrary = false }) { Text("Back to diary") }
                        Box(Modifier.weight(1f)) { VaultMealsScreen(state, onReload = model::reloadVault, onSaveMeal = model::saveMeal) }
                    } else FuelDiaryScreen(state, model::reloadVault, model::saveDiaryEntry, model::setDiaryWater, onLibrary = { fuelLibrary = true })
                    4 -> if (transfers) TransferScreen(
                        state, model::reloadVault, model::importLibrary, onBack = { if (!busy) transfers = false }
                    ) else if (nutritionSettings) NutritionSettingsScreen(
                        state, model::reloadVault, model::saveConfigField, onBack = { nutritionSettings = false }
                    ) else LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            OutlinedButton(onClick = { nutritionSettings = true }, enabled = !busy) {
                                Text("Profile, nutrition and preferences")
                            }
                            OutlinedButton(onClick = { transfers = true }, enabled = !busy) {
                                Text("Import and export")
                            }
                        }
                        item {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Markdown vault", style = MaterialTheme.typography.titleLarge)
                                    Text(state.vault?.folderName ?: "No vault selected")
                                    Text("Create and edit routines, saved meals, custom exercises and exercise tags in your Markdown folder. Recorded history is preserved.")
                                    Button(onClick = { picker.launch(null) }, enabled = !busy) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Choose vault folder") }
                                    if (state.vault != null) OutlinedButton(onClick = model::reloadVault, enabled = !busy) { Text("Reload from disk") }
                                    VaultStatus(state, onReload = model::reloadVault)
                                    state.vault?.files?.keys?.sorted()?.forEach { Text("Available: $it", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        item {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Workout rest", style = MaterialTheme.typography.titleLarge)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text("Enable rest timers", Modifier.weight(1f))
                                        Switch(
                                            checked = state.data.config.booleanPreference("rest-enabled") == true,
                                            onCheckedChange = { if (writeBlockReason == null && !busy) model.updateRestEnabled(it) },
                                            enabled = writeBlockReason == null && !busy
                                        )
                                    }
                                    writeBlockReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    OutlinedButton(onClick = {
                                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }, enabled = !notificationPermissionGranted) { Text("Enable rest notifications") }
                                    if (notificationPermissionGranted) {
                                        Text("Notification permission granted. Rest notifications also depend on Android notification settings.", style = MaterialTheme.typography.bodySmall)
                                    } else if (notificationPermissionDenied) {
                                        Text("Notification permission denied. Workouts and rest timers still work. You can enable notifications in Android app settings.", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        item { Text("Appearance", style = MaterialTheme.typography.titleLarge) }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("System", "Light", "Dark").forEach { value -> FilterChip(selected = theme == value, onClick = { theme = value; preferences.edit().putString("theme", value).apply() }, label = { Text(value) }) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Pure-black dark mode", Modifier.padding(top = 14.dp))
                                Switch(checked = black, onCheckedChange = { black = it; preferences.edit().putBoolean("black", it).apply() })
                            }
                        }
                        item {
                            Text(if (state.vault == null) "Accent is saved on this device until a vault is selected." else writeBlockReason ?: "Accent is saved to config.md.", style = MaterialTheme.typography.bodySmall)
                        }
                        items(accents.keys.toList()) { name ->
                            FilterChip(selected = accent == name, enabled = !busy && !state.reloadRequired && (state.vault == null || writeBlockReason == null), onClick = { if (state.vault != null) model.updateAccent(name) else { localAccent = name; preferences.edit().putString("accent", name).apply() } }, label = { Text(name.replaceFirstChar { it.uppercase() }) }, leadingIcon = { Box(Modifier.size(18.dp).background(accents.getValue(name), RoundedCornerShape(9.dp))) })
                        }
                        item { Text("Form · Native preview 0.1.0\nMade by TheHHR", style = MaterialTheme.typography.bodySmall) }
                    }
                    else -> StatsScreen(state, model::reloadVault, onOpenExercise = { selectedId = it })
                }
            }
        }
        selected?.let { exercise ->
            ModalBottomSheet(onDismissRequest = { selectedId = null }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    var playing by remember(exercise.id) { mutableStateOf(exercise.animation.isNotBlank()) }
                    val mediaPath = if (playing) exercise.animation.ifBlank { exercise.image } else exercise.image.ifBlank { exercise.animation }
                    var mediaExpanded by remember(exercise.id) { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth()) {
                        ExerciseMedia(mediaPath, Modifier.fillMaxWidth().height(280.dp), animated = playing)
                        if (mediaPath.isNotBlank()) {
                            TextButton(onClick = { mediaExpanded = true }, modifier = Modifier.align(Alignment.TopEnd)) { Text("View full size") }
                        }
                    }
                    if (exercise.animation.isNotBlank()) TextButton(onClick = { playing = !playing }) { Text(if (playing) "Pause animation" else "Play animation") }
                    if (mediaExpanded) {
                        Dialog(onDismissRequest = { mediaExpanded = false }) {
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ExerciseMedia(mediaPath, Modifier.fillMaxWidth().height(460.dp), animated = playing)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { mediaExpanded = false }) { Text("Close") }
                                    }
                                }
                            }
                        }
                    }
                    Text(exercise.name, style = MaterialTheme.typography.headlineSmall)
                    Text(listOf(exercise.category, exercise.target, exercise.equipment).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Custom exercise" })
                    if (exercise.secondaryMuscles.isNotEmpty()) {
                        Text("Secondary muscles", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            exercise.secondaryMuscles.forEach { muscle ->
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        muscle.replaceFirstChar { it.uppercase() },
                                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                    val liked = exercise.id in state.data.config.liked
                    FilterChip(selected = liked, enabled = writeBlockReason == null, onClick = { model.setExerciseLiked(exercise.id, !liked) }, label = { Text(if (liked) "Saved · remove favorite" else "Save favorite") }, leadingIcon = { Icon(Icons.Default.Favorite, null) })
                    writeBlockReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (state.reloadRequired && state.vault != null) TextButton(onClick = model::reloadVault, enabled = !busy) { Text("Reload from disk") }
                    Text("Tags", style = MaterialTheme.typography.titleMedium)
                    Text(state.data.config.exerciseTags[exercise.id].orEmpty().joinToString(" · ").ifBlank { "No saved tags" })
                    TextButton(onClick = {
                        if (writeBlockReason == null) tagEditor = ExerciseTagsEditorSession(exercise.id, state.data.config.exerciseTags[exercise.id].orEmpty().toList())
                    }, enabled = writeBlockReason == null) { Text("Edit tags") }
                    state.data.config.customExercises.singleOrNull { it.id == exercise.id }?.let { custom ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                if (writeBlockReason == null) customEditor = CustomExerciseEditorSession(custom, custom)
                            }, enabled = writeBlockReason == null) { Text("Edit custom exercise") }
                            TextButton(onClick = {
                                if (writeBlockReason == null) deletingCustom = custom
                            }, enabled = writeBlockReason == null) { Text("Delete") }
                        }
                    }
                    ManualLoggingSection(
                        exercise, state, busy,
                        onLog = {
                            if (ManualLogging.blockReason(state) == null) {
                                manualLogSession = ManualLoggingSession(
                                    ManualLogging.seedDraft(exercise, state.data.trainingLogs, today.toString())
                                )
                            }
                        },
                        onDelete = { if (ManualLogging.blockReason(state) == null) deletingLog = it }
                    )
                    run {
                        val regions = MuscleMapMath.regionsForExercise(exercise.target, exercise.secondaryMuscles)
                        if (regions.isNotEmpty()) {
                            Text("Muscle map", style = MaterialTheme.typography.titleMedium)
                            MuscleMapView(
                                MuscleMapMath.exerciseEntries(regions, MuscleMapMath.accentRgb(accent)),
                                MuscleMapMath.genderFor(state.data.config.sex),
                                Modifier.fillMaxWidth().height(240.dp)
                            )
                        }
                    }
                    Text("How to perform", style = MaterialTheme.typography.titleLarge)
                    if (exercise.instructions.isEmpty()) Text("No instructions provided.")
                    exercise.instructions.forEachIndexed { index, text -> Text("${index + 1}. $text") }
                    if (exercise.attribution.isNotBlank()) Text(exercise.attribution, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        customEditor?.let { session ->
            key(session) {
                CustomExerciseEditorDialog(
                    session, writeBlockReason, state.error, busy,
                    onDismiss = { customEditor = null },
                    onReload = if (state.reloadRequired && state.vault != null) model::reloadVault else null,
                    onSave = { replacement ->
                        if (writeBlockReason == null) model.saveCustomExercise(session.original, replacement) {
                            if (customEditor === session) customEditor = null
                        }
                    }
                )
            }
        }
        tagEditor?.let { session ->
            key(session) {
                ExerciseTagsEditorDialog(
                    session, writeBlockReason, state.error, busy,
                    onDismiss = { tagEditor = null },
                    onReload = if (state.reloadRequired && state.vault != null) model::reloadVault else null,
                    onSave = { tags ->
                        if (writeBlockReason == null) model.setExerciseTags(session.id, session.original, tags) {
                            if (tagEditor === session) tagEditor = null
                        }
                    }
                )
            }
        }
        deletingCustom?.let { original ->
            AlertDialog(
                onDismissRequest = { if (!busy) deletingCustom = null },
                title = { Text("Delete custom exercise?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("\"${original.name}\" will be removed from custom exercises. Recorded training history is preserved. Deletion is blocked while a routine references this exercise; edit those routines first. The saved-favorite marker for this exercise is removed.")
                        writeBlockReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.reloadRequired && state.vault != null) TextButton(onClick = model::reloadVault, enabled = !busy) { Text("Reload from disk") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (writeBlockReason == null) model.saveCustomExercise(original, null) {
                            if (deletingCustom === original) {
                                deletingCustom = null
                                if (selectedId == original.id) selectedId = null
                            }
                        }
                    }, enabled = writeBlockReason == null) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deletingCustom = null }, enabled = !busy) { Text("Cancel") } }
            )
        }
        manualLogSession?.let { session ->
            key(session) {
                ManualLoggingDialog(
                    session,
                    state.exercises.firstOrNull { it.id == session.draft.exerciseId },
                    state.data.trainingLogs,
                    ManualLogging.blockReason(state), state.error, busy, today.toString(),
                    onDismiss = { if (!busy) manualLogSession = null },
                    onReload = if (state.reloadRequired && state.vault != null) model::reloadVault else null,
                    onSave = { log ->
                        model.saveManualWorkoutLog(log) {
                            if (manualLogSession === session) manualLogSession = null
                        }
                    }
                )
            }
        }
        deletingLog?.let { original ->
            val logBlockReason = ManualLogging.blockReason(state)
            AlertDialog(
                onDismissRequest = { if (!busy) deletingLog = null },
                title = { Text("Delete progress entry?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Delete the ${original.date} entry for \"${state.exercises.firstOrNull { it.id == original.exerciseId }?.name ?: original.exerciseId}\"? It is removed from training_logs.md and cannot be recovered.")
                        logBlockReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.reloadRequired && state.vault != null) TextButton(onClick = model::reloadVault, enabled = !busy) { Text("Reload from disk") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (logBlockReason == null) model.deleteWorkoutLog(original) {
                            if (deletingLog === original) deletingLog = null
                        }
                    }, enabled = logBlockReason == null) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { if (!busy) deletingLog = null }, enabled = !busy) { Text("Cancel") } }
            )
        }
        state.error?.let { error ->
            AlertDialog(onDismissRequest = model::dismissError, title = { Text("Unable to complete action") }, text = { Text(error) }, confirmButton = { TextButton(onClick = model::dismissError) { Text("OK") } })
        }
    }
}

@Composable
private fun ExerciseLibrary(
    state: FormState,
    filters: LibraryFilterState,
    onFiltersChange: (LibraryFilterState) -> Unit,
    onOpen: (Exercise) -> Unit,
    onReload: () -> Unit,
    likeEnabled: Boolean,
    onToggleLiked: (Exercise) -> Unit,
    onCreateCustom: (() -> Unit)? = null
) {
    val catalog = remember(state.exercises, state.data) { LibraryFilterCatalog(state.exercises, state.data) }
    val filtered = remember(catalog, filters) { catalog.evaluate(filters) }
    val results = filtered.exercises
    val preferences = catalog.preferences
    val likedIds = remember(state.data.config.liked) { state.data.config.liked.toSet() }
    val latestLogs = remember(state.data.trainingLogs) {
        val latest = linkedMapOf<String, VaultTrainingLog>()
        state.data.trainingLogs.forEachIndexed { index, log ->
            val current = latest[log.exerciseId]
            if (current == null || log.date >= current.date) latest[log.exerciseId] = log
        }
        latest
    }
    LazyVerticalGrid(modifier = Modifier.fillMaxSize(), columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(filters.query, { onFiltersChange(filters.copy(query = it)) }, Modifier.fillMaxWidth(), placeholder = { Text("Search exercises") }, singleLine = true, shape = RoundedCornerShape(28.dp), leadingIcon = { Icon(Icons.Default.Search, null) })
            }
            item(key = "filter-controls", span = { GridItemSpan(maxLineSpan) }) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(filters.descending, { onFiltersChange(filters.copy(descending = !filters.descending)) }, enabled = filters.sort == LibrarySort.NAME, label = { Text(if (filters.descending) "Z–A" else "A–Z") }) }
                    item {
                        var sortMenu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { sortMenu = true }) { Text("Sort: ${if (filters.sort == LibrarySort.CUSTOM) "Routine order" else filters.sort.label}") }
                            DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf(LibrarySort.NAME, LibrarySort.DATASET, LibrarySort.BODY_PART).forEach { option ->
                                    DropdownMenuItem(text = { Text(option.label) }, onClick = { onFiltersChange(filters.copy(sort = option)); sortMenu = false })
                                }
                                if (filters.sort == LibrarySort.CUSTOM) DropdownMenuItem(text = { Text("Routine order (selected routine)") }, enabled = false, onClick = {})
                            }
                        }
                    }
                    item { TextButton(onClick = { onFiltersChange(filters.copy(expanded = !filters.expanded)) }, enabled = preferences.canExpand) { Text(if (filters.expanded) "Collapse filters" else "Expand filters") } }
                    item { TextButton(onClick = { onFiltersChange(filters.reset()) }) { Text("Reset") } }
                }
            }
            item(key = "filter-summary", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${results.size} exercises", style = MaterialTheme.typography.labelMedium)
                    val summary = filters.summary(state.data)
                    if (summary.isNotBlank()) Text("Active filters: $summary", style = MaterialTheme.typography.bodySmall)
                }
            }
            preferences.visibleRows(filters.expanded).forEach { row ->
                val pills = filtered.rowPills(row)
                if (pills.isNotEmpty()) item(key = "filter-row:${row.key}", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(row.label, style = MaterialTheme.typography.labelMedium)
                        LibraryPillRow(pills) { onFiltersChange(filters.toggle(it.dimension, it.value)) }
                    }
                }
            }
            item(key = "vault-status", span = { GridItemSpan(maxLineSpan) }) { VaultStatus(state, onReload = onReload) }
            if (onCreateCustom != null) item(key = "create-custom", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCreateCustom, enabled = !state.workoutBusy && state.configWriteBlockReason() == null) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Create custom exercise")
                    }
                    state.configWriteBlockReason()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (!state.loading && results.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text("No matching exercises. Clear a selected pill or use Reset.", Modifier.padding(4.dp))
            }
            itemsIndexed(results, key = { index, exercise -> "exercise:$index:${exercise.id}" }) { _, exercise ->
                val latestLog = latestLogs[exercise.id]
                val subtitle = if (latestLog != null && (filters.logged || filters.routine.isNotEmpty())) "${ManualLogging.formatLog(latestLog)} · ${exercise.target}" else exercise.target
                val liked = exercise.id in likedIds
                ElevatedCard(onClick = { onOpen(exercise) }, modifier = Modifier.fillMaxWidth()) {
                    ExerciseMedia(exercise.image, Modifier.fillMaxWidth().height(150.dp))
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(exercise.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onToggleLiked(exercise) }, enabled = likeEnabled) {
                                Icon(
                                    if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (liked) "Remove from saved exercises" else "Add to saved exercises"
                                )
                            }
                        }
                        Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
    }
}

private class CustomExerciseEditorSession(val original: VaultCustomExercise?, initialDraft: VaultCustomExercise) {
    var draft by mutableStateOf(initialDraft)
}

private class ExerciseTagsEditorSession(val id: String, val original: List<String>) {
    var draft by mutableStateOf(original.joinToString(", "))
}

private fun Char.isExerciseLineControl(): Boolean = isISOControl() || this == '\u2028' || this == '\u2029'

private fun customExerciseFieldError(value: String, required: Boolean = false): String? = when {
    value.any { it.isExerciseLineControl() } -> "Use a single line without control characters."
    required && !ConfigCodec.validCustomExerciseName(value.trim()) -> "Enter a name of 1–40 characters."
    value.trim().length > 40 -> "Use at most 40 characters."
    else -> null
}

@Composable
private fun CustomExerciseEditorDialog(
    session: CustomExerciseEditorSession,
    blockReason: String?,
    saveError: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onReload: (() -> Unit)?,
    onSave: (VaultCustomExercise) -> Unit
) {
    val draft = session.draft
    val nameError = customExerciseFieldError(draft.name, required = true)
    val categoryError = customExerciseFieldError(draft.category)
    val targetError = customExerciseFieldError(draft.target)
    val equipmentError = customExerciseFieldError(draft.equipment)
    val descriptionError = when {
        draft.description.length > 400 -> "Use at most 400 characters."
        draft.description.any { it.isExerciseLineControl() && it != '\n' && it != '\r' && it != '\t' } -> "Remove unsupported control characters."
        else -> null
    }
    val valid = listOf(nameError, categoryError, targetError, equipmentError, descriptionError).all { it == null }
    val enabled = blockReason == null && !busy
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (session.original == null) "New custom exercise" else "Edit custom exercise") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ID: ${draft.id} (cannot be changed)", style = MaterialTheme.typography.bodySmall)
                CustomExerciseTextField("Name", draft.name, nameError, enabled) { session.draft = session.draft.copy(name = it) }
                CustomExerciseTextField("Category", draft.category, categoryError, enabled) { session.draft = session.draft.copy(category = it) }
                CustomExerciseTextField("Target", draft.target, targetError, enabled) { session.draft = session.draft.copy(target = it) }
                CustomExerciseTextField("Equipment", draft.equipment, equipmentError, enabled) { session.draft = session.draft.copy(equipment = it) }
                OutlinedTextField(
                    draft.description, { session.draft = session.draft.copy(description = it) }, Modifier.fillMaxWidth(),
                    label = { Text("Description / instructions") }, minLines = 3, maxLines = 6,
                    enabled = enabled, isError = descriptionError != null,
                    supportingText = { Text(descriptionError ?: "${draft.description.length}/400 characters · multiple lines allowed") }
                )
                blockReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onReload != null) TextButton(onClick = onReload, enabled = !busy) { Text("Reload from disk") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (enabled && valid) onSave(draft.copy(
                    name = draft.name.trim(), category = draft.category.trim(),
                    target = draft.target.trim(), equipment = draft.equipment.trim()
                ))
            }, enabled = enabled && valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
private fun CustomExerciseTextField(label: String, value: String, error: String?, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        enabled = enabled, isError = error != null,
        supportingText = { Text(error ?: "${value.trim().length}/40 characters") }
    )
}

@Composable
private fun ExerciseTagsEditorDialog(
    session: ExerciseTagsEditorSession,
    blockReason: String?,
    saveError: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onReload: (() -> Unit)?,
    onSave: (List<String>) -> Unit
) {
    val tags = if (session.draft.isBlank()) emptyList() else session.draft.split(',').map(String::trim).distinctBy { it.lowercase(java.util.Locale.ROOT) }
    val error = when {
        session.draft.any { it == '|' || it == '#' || it.isExerciseLineControl() } -> "Tags cannot contain |, #, control characters or line breaks."
        tags.size > 12 -> "Use at most 12 unique tags."
        tags.any { it.length > 24 } -> "Each tag must be at most 24 characters."
        !ConfigCodec.validTags(tags) -> "Enter nonempty tags separated by commas, or leave blank to clear all tags."
        else -> null
    }
    val enabled = blockReason == null && !busy
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Edit exercise tags") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Separate tags with commas. Up to 12 tags, each up to 24 characters. Duplicates are merged ignoring case; leave blank to clear tags.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    session.draft, { session.draft = it }, Modifier.fillMaxWidth(), label = { Text("Tags") },
                    singleLine = true, enabled = enabled, isError = error != null
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (error == null) Text("${tags.size}/12 tags", style = MaterialTheme.typography.bodySmall)
                blockReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onReload != null) TextButton(onClick = onReload, enabled = !busy) { Text("Reload from disk") }
            }
        },
        confirmButton = { TextButton(onClick = { if (enabled && error == null) onSave(tags) }, enabled = enabled && error == null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

private data class ExerciseMediaState(val loading: Boolean = false, val drawable: Drawable? = null)

@Composable
private fun ExerciseMedia(path: String, modifier: Modifier, animated: Boolean = false) {
    val context = LocalContext.current
    key(path) {
        val media by produceState(ExerciseMediaState(loading = path.isNotBlank()), path, context) {
            if (path.isBlank()) return@produceState
            val drawable = withContext(Dispatchers.IO) {
                try {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, path))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            value = ExerciseMediaState(drawable = drawable)
        }
        val drawable = media.drawable
        DisposableEffect(drawable, animated) {
            val animation = drawable as? AnimatedImageDrawable
            if (animated) animation?.start() else animation?.stop()
            onDispose { animation?.stop() }
        }
        if (drawable == null) {
            Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Text(when {
                    path.isBlank() -> "No media provided for this exercise"
                    media.loading -> "Loading media…"
                    else -> "Media unavailable. Exercise details are still available below."
                }, Modifier.padding(16.dp))
            }
        } else {
            AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO } }, modifier = modifier.background(Color.White), update = { it.setImageDrawable(drawable) }, onRelease = { (it.drawable as? AnimatedImageDrawable)?.stop(); it.setImageDrawable(null) })
        }
    }
}
