package com.example.gallery.android

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.example.gallery.server.NotificationItem
import com.example.gallery.server.NotificationsProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Android only delivers other apps' notifications to a [NotificationListenerService] that the user has
 * enabled under Settings > Notification access. This service just feeds [NotificationHub].
 */
class GalleryNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationHub.attach(this)
    }

    override fun onListenerDisconnected() {
        NotificationHub.detach(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = NotificationHub.refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = NotificationHub.refresh()
}

object NotificationHub {
    @Volatile private var listener: GalleryNotificationListener? = null
    private val active = ConcurrentHashMap<String, StatusBarNotification>()
    private val labels = ConcurrentHashMap<String, String>()

    @Volatile var items: List<NotificationItem> = emptyList()
        private set

    val connected: Boolean get() = listener != null

    fun attach(l: GalleryNotificationListener) {
        listener = l
        refresh()
    }

    fun detach(l: GalleryNotificationListener) {
        if (listener === l) listener = null
        active.clear()
        items = emptyList()
    }

    fun refresh() {
        val l = listener ?: return
        val posted = try {
            l.activeNotifications ?: emptyArray()
        } catch (_: SecurityException) {
            return
        }
        active.clear()
        val out = ArrayList<NotificationItem>()
        for (sbn in posted) {
            val n = sbn.notification
            val isSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
            val isOngoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
            if (isSummary || isOngoing || sbn.packageName == l.packageName) continue
            active[sbn.key] = sbn
            val extras = n.extras
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            out.add(
                NotificationItem(
                    key = sbn.key,
                    app = sbn.packageName,
                    appName = label(l, sbn.packageName),
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    text = text,
                    time = sbn.postTime,
                    canReply = replyAction(sbn) != null,
                ),
            )
        }
        items = out.sortedByDescending { it.time }
    }

    fun dismiss(key: String): Boolean {
        val l = listener ?: return false
        if (!active.containsKey(key)) return false
        l.cancelNotification(key)
        refresh()
        return true
    }

    fun reply(context: Context, key: String, text: String): Boolean {
        val sbn = active[key] ?: return false
        val action = replyAction(sbn) ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle()
        for (input in inputs) results.putCharSequence(input.resultKey, text)
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(context, 0, intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun replyAction(sbn: StatusBarNotification): Notification.Action? =
        sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    private fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    /** True when the user has enabled this app under Settings > Notification access. */
    fun isAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun componentName(context: Context) = ComponentName(context, GalleryNotificationListener::class.java)
}

class NotificationsProviderImpl(private val context: Context) : NotificationsProvider {
    override fun isConnected(): Boolean = NotificationHub.connected
    override fun list(): List<NotificationItem> = NotificationHub.items
    override fun dismiss(key: String): Boolean = NotificationHub.dismiss(key)
    override fun reply(key: String, text: String): Boolean = NotificationHub.reply(context, key, text)
}
