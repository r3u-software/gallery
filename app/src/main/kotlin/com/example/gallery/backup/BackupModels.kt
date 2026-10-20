package com.example.gallery.backup

import java.io.InputStream
import java.net.URI
import java.net.URL

/** What the user typed into the dashboard: the Apps Script web address, the shared secret and the options. */
data class BackupConfig(
    val url: String,
    val secret: String,
    val photos: Boolean = true,
    val videos: Boolean = true,
    val wifiOnly: Boolean = true,
)

enum class MediaKind(val tag: String) { IMAGE("i"), VIDEO("v") }

/** One photo or video on the phone. */
data class BackupItem(
    val kind: MediaKind,
    val id: Long,
    val name: String,
    val mime: String,
    val size: Long,
    val takenMs: Long,
    val modifiedMs: Long,
    val album: String,
) {
    /** Changes when the file is edited, so an edited photo is backed up again as a new file. */
    val key: String get() = "${kind.tag}$id:$size:${modifiedMs / 1000}"
}

interface BackupSource {
    fun list(photos: Boolean, videos: Boolean): List<BackupItem>
    fun open(item: BackupItem, offset: Long): InputStream?

    /** The file's real size right now. MediaStore's figure can be stale; null when the file can't be read. */
    fun sizeOf(item: BackupItem): Long? = item.size
}

/** Remembers what is already in Drive, and the upload session of a file that was interrupted. */
interface BackupLedger {
    fun doneKeys(): Set<String>
    fun markDone(key: String)
    fun session(key: String): String?
    fun saveSession(key: String, uri: String)
    fun clearSession(key: String)
}

sealed class InitResult {
    /** Drive already holds a file with this name and size. */
    object Exists : InitResult()
    class Session(val uri: String) : InitResult()
    /** This one file can't be backed up; carry on with the next. */
    class Rejected(val why: String) : InitResult()
    /** The address or secret is wrong, or the script isn't deployed for "Anyone". Stop for now. */
    class ConfigProblem(val why: String) : InitResult()
    /** Offline, throttled or Drive is full. Stop for now and try again later. */
    class Transient(val why: String) : InitResult()
}

sealed class UploadStatus {
    object Complete : UploadStatus()
    /** Drive has the first [next] bytes. */
    class Incomplete(val next: Long) : UploadStatus()
    object SessionGone : UploadStatus()
    class Transient(val why: String) : UploadStatus()
    class Rejected(val why: String) : UploadStatus()
}

interface DriveTransport {
    fun init(config: BackupConfig, item: BackupItem): InitResult
    fun status(session: String, total: Long): UploadStatus
    fun put(session: String, total: Long, offset: Long, data: ByteArray, length: Int): UploadStatus
}

enum class RunResult { ALL_DONE, TIME_UP, STOPPED, NETWORK, CONFIG }

class RunReport(val result: RunResult, val uploaded: Int, val alreadyThere: Int, val skipped: Int)

/** Validation shared by the dashboard API and the uploader. Only Google addresses are ever contacted. */
object BackupRules {
    const val MIN_SECRET = 8
    const val MAX_SECRET = 200

    private val SCRIPT_PATH = Regex("^/[A-Za-z0-9_\\-./]+/exec$")

    /** Returns a message when [url] isn't a deployed Apps Script web app address, otherwise null. */
    fun validateUrl(url: String): String? {
        val u = url.trim()
        if (u.isEmpty() || u.length > 400) return "Paste the web app address from Apps Script."
        val uri = try { URI(u) } catch (_: Exception) { return "That isn't a web address." }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return "The address must start with https://"
        if (uri.host != SCRIPT_HOST) return "Use the address that ends in /exec from script.google.com."
        if (uri.query != null || uri.fragment != null || uri.userInfo != null || uri.port != -1) return "Use the plain address that ends in /exec."
        if (!SCRIPT_PATH.matches(uri.path ?: "")) return "Use the address that ends in /exec."
        return null
    }

    fun validateSecret(secret: String): String? = when {
        secret.length < MIN_SECRET -> "The secret must be at least $MIN_SECRET characters."
        secret.length > MAX_SECRET -> "The secret is too long."
        secret.any { it.code < 32 } -> "The secret can't contain control characters."
        else -> null
    }

    private const val SCRIPT_HOST = "script.google.com"

    /** Hosts the uploader may talk to: the script itself and Google's upload API. */
    fun isAllowedEndpoint(url: URL): Boolean {
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        val h = url.host.lowercase()
        return h == SCRIPT_HOST || h == "script.googleusercontent.com" || h == "googleapis.com" || h.endsWith(".googleapis.com")
    }

    /** "…ab12cd/exec": enough to recognise the address without showing the whole of it. */
    fun hint(url: String): String = if (url.length <= 14) url else "…" + url.takeLast(14)
}
