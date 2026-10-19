package com.example.gallery.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.gallery.R
import com.example.gallery.ui.MainActivity
import com.example.gallery.ui.TrashConfirmActivity

object Notifications {
    const val CHANNEL_SERVER = "server"
    const val CHANNEL_TRASH = "trash"
    const val CHANNEL_OPEN = "open"
    const val ID_SERVER = 1
    const val ID_TRASH = 2
    const val ID_OPEN = 3

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVER, ctx.getString(R.string.channel_server), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OPEN, ctx.getString(R.string.channel_open), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRASH, ctx.getString(R.string.channel_trash), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** The link/token is deliberately NOT shown here: notifications can be seen from the lock screen. */
    fun server(ctx: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, GalleryService::class.java).setAction(GalleryService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_SERVER)
            .setSmallIcon(R.drawable.ic_stat_gallery)
            .setContentTitle("R3U | Phone Link is running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .addAction(0, "Stop server", stop)
            .build()
    }

    fun postTrashConfirm(ctx: Context, count: Int) {
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, TrashConfirmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = if (count == 1) "1 photo" else "$count photos"
        val n = NotificationCompat.Builder(ctx, CHANNEL_TRASH)
            .setSmallIcon(R.drawable.ic_stat_gallery)
            .setContentTitle("Confirm moving $label to trash")
            .setContentText("Tap to confirm. Photos stay recoverable for 30 days.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_TRASH, n)
        } catch (_: SecurityException) {
            // Notification permission denied: the user can still open the app to confirm.
        }
    }

    /** A request from the computer to open something on this phone; opens when the user taps it. */
    fun postOpenRequest(ctx: Context, title: String, detail: String, target: Intent) {
        val tap = PendingIntent.getActivity(
            ctx, (System.currentTimeMillis() and 0x7fffffff).toInt(), target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_OPEN)
            .setSmallIcon(R.drawable.ic_stat_gallery)
            .setContentTitle("From your computer: $title")
            .setContentText(detail)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_OPEN, n)
        } catch (_: SecurityException) {
            // Notification permission denied: nothing more we can do.
        }
    }

    fun cancelTrash(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_TRASH)
    }
}
