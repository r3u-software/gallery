package com.example.gallery.server

import java.io.InputStream

/** Switchable capabilities. Sensitive ones default to off in the app and are enforced here, server-side. */
enum class Feature(val key: String) {
    PHOTOS("photos"),
    NOTIFICATIONS("notifications"),
    MESSAGES("messages"),
    FILES("files"),
    STATUS("status"),
    FIND("find"),
}

fun interface FeatureFlags {
    fun isEnabled(feature: Feature): Boolean
}

data class DeviceStatus(
    val name: String,
    val model: String,
    val androidVersion: String,
    val batteryPercent: Int,
    val charging: Boolean,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
)

data class NotificationItem(
    val key: String,
    val app: String,
    val appName: String,
    val title: String,
    val text: String,
    val time: Long,
    val canReply: Boolean,
)

data class SmsThread(val id: Long, val address: String, val name: String, val snippet: String, val date: Long, val unread: Int)

data class SmsMessage(val id: Long, val body: String, val date: Long, val outgoing: Boolean)

data class SharedFile(val id: Long, val name: String, val size: Long, val modified: Long, val mime: String)

data class ClipboardEntry(val text: String, val time: Long)

interface StatusProvider {
    fun status(): DeviceStatus
}

interface NotificationsProvider {
    /** False until the user grants Notification access in Android settings. */
    fun isConnected(): Boolean
    fun list(): List<NotificationItem>
    fun dismiss(key: String): Boolean
    fun reply(key: String, text: String): Boolean
}

interface MessagesProvider {
    fun hasPermission(): Boolean
    fun threads(): List<SmsThread>
    fun messages(threadId: Long, limit: Int): List<SmsMessage>
    fun send(to: String, body: String): Boolean
}

interface FilesProvider {
    fun list(): List<SharedFile>
    fun find(id: Long): SharedFile?
    fun open(id: Long): InputStream?
    fun save(name: String, mime: String, input: InputStream): SharedFile?
    fun delete(id: Long): Boolean
}

interface ClipboardProvider {
    /** Latest text shared from the phone to the computer. */
    fun latest(): ClipboardEntry?
    fun setPhoneClipboard(text: String): Boolean
}

interface FindProvider {
    fun ring(): Boolean
    fun stop()
    fun isRinging(): Boolean
}

interface PasscodeVerifier {
    val required: Boolean
    fun verify(code: String): Boolean
}

/** Everything beyond photos. A missing provider answers 404 "unavailable". */
class Services(
    val status: StatusProvider? = null,
    val notifications: NotificationsProvider? = null,
    val messages: MessagesProvider? = null,
    val files: FilesProvider? = null,
    val clipboard: ClipboardProvider? = null,
    val find: FindProvider? = null,
    val passcode: PasscodeVerifier? = null,
    val features: FeatureFlags = FeatureFlags { true },
    val deviceName: () -> String = { "Phone" },
)

/**
 * Per-client login throttle: [maxFailures] free attempts, then a lockout that starts at [baseLockMs]
 * and doubles on every further failure up to [maxLockMs]. A success clears the record.
 */
class LoginLimiter(
    private val now: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = 5,
    private val baseLockMs: Long = 30_000,
    private val maxLockMs: Long = 15 * 60_000,
) {
    private class State(var fails: Int = 0, var lockedUntil: Long = 0)

    private val map = HashMap<String, State>()

    @Synchronized
    fun retryAfterMs(key: String): Long = maxOf(0L, (map[key]?.lockedUntil ?: 0L) - now())

    @Synchronized
    fun attemptsLeft(key: String): Int = maxOf(0, maxFailures - (map[key]?.fails ?: 0))

    @Synchronized
    fun recordFailure(key: String) {
        val s = map.getOrPut(key) { State() }
        s.fails++
        if (s.fails >= maxFailures) {
            val over = minOf(s.fails - maxFailures, 20)
            s.lockedUntil = now() + minOf(maxLockMs, baseLockMs shl over)
        }
    }

    @Synchronized
    fun recordSuccess(key: String) {
        map.remove(key)
    }
}
