package com.thehhr.form.nativeapp.workout

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import java.util.concurrent.Executors

private const val REST_ACTION = "com.thehhr.form.nativeapp.workout.REST_ACTION"
private const val EXTRA_VAULT_URI = "vaultUri"
private const val EXTRA_SESSION_ID = "sessionId"
private const val EXTRA_REST_DEADLINE = "restDeadline"
private const val EXTRA_ACTION = "action"
private const val ACTION_EXTEND = 30
private const val ACTION_SKIP = 0
private const val EXTENSION_MILLIS = 30_000L

object WorkoutNotifications {
    const val NOTIFICATION_ID = 1001
    private const val CHANNEL_ID = "workout_rest"

    fun ensureChannel(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Workout rest", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        } catch (_: SecurityException) {
        }
    }

    @Synchronized
    fun update(context: Context, session: WorkoutSession?, vaultUri: String) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val now = System.currentTimeMillis()
            val deadline = session?.restDeadline
            if (session == null || session.paused || session.finishing || deadline == null || deadline <= now || vaultUri.isBlank()) {
                manager.cancel(NOTIFICATION_ID)
                return
            }
            ensureChannel(context)
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
                !manager.areNotificationsEnabled()) return
            val remainingMillis = deadline - now
            val icon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_lock_idle_alarm
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle("Rest · ${session.routineName}")
                .setContentText("Rest remaining")
                .setCategory(Notification.CATEGORY_WORKOUT)
                .setWhen(deadline)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(remainingMillis)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, android.R.drawable.ic_input_add),
                        "+30s", actionIntent(context, vaultUri, session.id, deadline, ACTION_EXTEND)
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, android.R.drawable.ic_media_next),
                        "Skip rest", actionIntent(context, vaultUri, session.id, deadline, ACTION_SKIP)
                    ).build()
                )
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun actionIntent(context: Context, vaultUri: String, sessionId: String, restDeadline: Long, action: Int): PendingIntent {
        val intent = Intent(context, RestActionReceiver::class.java)
            .setAction(REST_ACTION)
            .setData(
                Uri.Builder().scheme("form-workout").authority("rest")
                    .appendPath(vaultUri).appendPath(sessionId)
                    .appendPath(restDeadline.toString()).appendPath(action.toString()).build()
            )
            .putExtra(EXTRA_VAULT_URI, vaultUri)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .putExtra(EXTRA_REST_DEADLINE, restDeadline)
            .putExtra(EXTRA_ACTION, action)
        return PendingIntent.getBroadcast(context, action, intent, PendingIntent.FLAG_IMMUTABLE)
    }
}

class RestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != REST_ACTION) return
        val application = context.applicationContext
        val pendingResult = goAsync()
        try {
            executor.execute {
                try {
                    val action = intent.getIntExtra(EXTRA_ACTION, -1)
                    if (action != ACTION_EXTEND && action != ACTION_SKIP) return@execute
                    val vaultUri = intent.getStringExtra(EXTRA_VAULT_URI)?.takeIf { it.isNotBlank() } ?: return@execute
                    val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return@execute
                    val expectedDeadline = intent.getLongExtra(EXTRA_REST_DEADLINE, -1L)
                    val session = WorkoutSessionStore.load(application, vaultUri) ?: return@execute
                    val deadline = session.restDeadline ?: return@execute
                    if (session.id != sessionId || session.paused || session.finishing ||
                        deadline <= System.currentTimeMillis() || deadline != expectedDeadline) return@execute
                    val updated = session.copy(
                        restDeadline = when (action) {
                            ACTION_EXTEND -> deadline.coerceAtMost(Long.MAX_VALUE - EXTENSION_MILLIS) + EXTENSION_MILLIS
                            ACTION_SKIP -> null
                            else -> return@execute
                        }
                    )
                    synchronized(WorkoutSessionStore) {
                        WorkoutSessionStore.compareAndSave(application, vaultUri, session, updated)
                        WorkoutNotifications.update(application, updated, vaultUri)
                    }
                } catch (_: Exception) {
                } finally {
                    pendingResult.finish()
                }
            }
        } catch (_: RuntimeException) {
            pendingResult.finish()
        }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
