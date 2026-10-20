package com.example.gallery.backup

import com.example.gallery.server.Json
import com.example.gallery.server.JsonFields
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the user's Apps Script web app (to get an upload slot) and then straight to Google's resumable
 * upload endpoint (to send the bytes). Nothing here shows anything to the user: every problem is a result value.
 */
class HttpDriveTransport(
    private val allowUrl: (URL) -> Boolean = BackupRules::isAllowedEndpoint,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 90_000,
) : DriveTransport {

    /** True when the script answers and accepts the secret. Used once, when settings are saved. */
    fun ping(config: BackupConfig): Boolean = try {
        val reply = postScript(config.url, "{\"secret\":${Json.str(config.secret)},\"action\":\"ping\"}")
        isOk(reply.second)
    } catch (_: Exception) {
        false
    }

    override fun init(config: BackupConfig, item: BackupItem): InitResult {
        val body = "{\"secret\":${Json.str(config.secret)},\"action\":\"init\",\"name\":${Json.str(item.name)}," +
            "\"mime\":${Json.str(item.mime)},\"size\":${item.size},\"album\":${Json.str(item.album)}," +
            "\"modified\":${if (item.takenMs > 0) item.takenMs else item.modifiedMs}}"
        val reply = try {
            postScript(config.url, body)
        } catch (e: IOException) {
            return InitResult.Transient(e.message ?: "network")
        } catch (e: SecurityException) {
            return InitResult.ConfigProblem("blocked address")
        }
        val (code, text) = reply
        if (code >= 500 || code == 429 || code == 408) return InitResult.Transient("script http $code")
        if (code >= 400) return InitResult.ConfigProblem("script http $code")
        if (!text.contains("\"ok\"")) return InitResult.ConfigProblem("script did not answer with json")
        if (!isOk(text)) {
            return when (val err = JsonFields.string(text, "error").orEmpty()) {
                "unauthorized", "set_secret", "set_folder" -> InitResult.ConfigProblem(err)
                "bad_request" -> InitResult.Rejected(err)
                else -> InitResult.Transient(err.ifEmpty { "script error" })
            }
        }
        if (EXISTS.containsMatchIn(text)) return InitResult.Exists
        val session = JsonFields.string(text, "session") ?: return InitResult.Transient("no upload slot")
        val ok = try { allowUrl(URL(session)) } catch (_: Exception) { false }
        return if (ok) InitResult.Session(session) else InitResult.ConfigProblem("unexpected upload address")
    }

    override fun status(session: String, total: Long): UploadStatus =
        send(session, "bytes */$total", ByteArray(0), 0)

    override fun put(session: String, total: Long, offset: Long, data: ByteArray, length: Int): UploadStatus =
        send(session, "bytes $offset-${offset + length - 1}/$total", data, length)

    // ------------------------------------------------------------ http

    private fun postScript(address: String, body: String): Pair<Int, String> {
        val url = URL(address)
        if (!allowUrl(url)) throw SecurityException("address not allowed")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            // Apps Script answers a POST with a redirect to the page that holds the reply; follow it.
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun send(session: String, contentRange: String, data: ByteArray, length: Int): UploadStatus {
        val url = try { URL(session) } catch (_: Exception) { return UploadStatus.Rejected("bad session address") }
        if (!allowUrl(url)) return UploadStatus.Rejected("session address not allowed")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false // 308 means "resume", not "redirect"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(length)
            conn.setRequestProperty("Content-Range", contentRange)
            conn.outputStream.use { if (length > 0) it.write(data, 0, length) }
            val code = conn.responseCode
            when (code) {
                200, 201 -> { drain(conn); UploadStatus.Complete }
                308 -> {
                    val range = conn.getHeaderField("Range")
                    val last = range?.substringAfter('-', "")?.trim()?.toLongOrNull()
                    UploadStatus.Incomplete(if (last == null) 0L else last + 1)
                }
                404, 410 -> UploadStatus.SessionGone
                401, 403, 408, 429 -> UploadStatus.Transient("drive http $code")
                in 500..599 -> UploadStatus.Transient("drive http $code")
                else -> UploadStatus.Rejected("drive http $code")
            }
        } catch (e: IOException) {
            UploadStatus.Transient(e.message ?: "network")
        } finally {
            conn.disconnect()
        }
    }

    private fun drain(conn: HttpURLConnection) {
        try { conn.inputStream?.use { it.readBytes() } } catch (_: IOException) { }
    }

    private fun isOk(text: String) = OK.containsMatchIn(text)

    private companion object {
        val OK = Regex("\"ok\"\\s*:\\s*true")
        val EXISTS = Regex("\"exists\"\\s*:\\s*true")
    }
}
