package io.nebula.mobile.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nebula.mobile.core.SettingsStore
import java.io.File

/**
 * Real notification plumbing: a channel whose importance follows the user's setting, and
 * a notification posted when a patched build finishes.
 *
 * Android channel importance is immutable once a channel exists, so changing the setting
 * recreates the channel. Posting is skipped (and reported as such) when the user has not
 * granted POST_NOTIFICATIONS on Android 13+.
 */
object Notifications {

    const val CHANNEL_ID = "nebula.patches"
    const val NOTIFICATION_ID = 4201

    fun importanceOf(preference: Int): Int = when (preference.coerceIn(1, 4)) {
        1 -> NotificationManager.IMPORTANCE_MIN
        2 -> NotificationManager.IMPORTANCE_LOW
        3 -> NotificationManager.IMPORTANCE_DEFAULT
        else -> NotificationManager.IMPORTANCE_HIGH
    }

    fun ensureChannel(context: Context, preference: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val wanted = importanceOf(preference)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.importance == wanted) return
        if (existing != null) manager.deleteNotificationChannel(CHANNEL_ID)
        val channel = NotificationChannel(CHANNEL_ID, "Patch results", wanted).apply {
            description = "Shown when a patched Discord build is ready."
            enableVibration(wanted >= NotificationManager.IMPORTANCE_DEFAULT)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun patchComplete(context: Context, file: File, sizeBytes: Long, settings: SettingsStore): Boolean {
        if (!settings.notifyOnPatch) return false
        if (!hasPermission(context)) return false
        ensureChannel(context, settings.notificationImportance)
        val megabytes = sizeBytes / (1024.0 * 1024.0)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Patched Discord is ready")
            .setContentText(String.format("%s · %.1f MB · ready to install", file.name, megabytes))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
