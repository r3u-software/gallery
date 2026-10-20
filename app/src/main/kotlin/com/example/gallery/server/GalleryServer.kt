package com.example.gallery.server

import com.example.gallery.backup.BackupRules
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Embedded HTTP server: token handshake, cookie auth, JSON API, streaming downloads, static web UI.
 *
 * Security model:
 *  - A random [token] is minted per server start and shown only in the app (link / QR).
 *  - `GET /?t=<token>` sets an HttpOnly + SameSite=Strict cookie and redirects to `/`.
 *  - Everything else (API and static files) requires that cookie, otherwise 401.
 *  - Clients outside private IP ranges are rejected with 403 before anything else.
 */
class GalleryServer(
    port: Int,
    private val token: String,
    private val catalog: PhotoCatalog,
    private val store: PhotoStore,
    private val trash: TrashGateway,
    private val assets: AssetProvider,
    private val zipStreamer: ZipStreamer = ZipStreamer(Executors.newCachedThreadPool()),
    private val clientAllowed: (String?) -> Boolean = PrivateNetwork::isPrivate,
    private val today: () -> Date = { Date() },
    private val services: Services = Services(),
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        currentBody.remove()
        return try {
            route(session)
        } catch (e: Exception) {
            text(Status.INTERNAL_ERROR, "Something went wrong")
        }
    }

    // ---------------------------------------------------------------- routing

    private fun route(session: IHTTPSession): Response {
        if (!clientAllowed(session.remoteIpAddress)) {
            drainBody(session)
            return text(Status.FORBIDDEN, "Forbidden: this server only accepts devices on your local network.")
        }
        val uri = session.uri.substringBefore('?')
        val method = session.method

        // Token handshake: the only unauthenticated route.
        if (uri == "/" && method == Method.GET) {
            val supplied = session.parameters["t"]?.firstOrNull()
            if (supplied != null) {
                return if (tokenMatches(supplied)) redirectWithCookie() else unauthorized()
            }
        }

        if (!hasValidCookie(session)) {
            drainBody(session)
            return unauthorized()
        }

        return if (uri.startsWith("/api/")) api(session, uri, method) else staticFile(uri, method)
    }

    private fun api(session: IHTTPSession, uri: String, method: Method): Response {
        drainBody(session) // consume any request body up front; handlers reuse the parsed result
        // Session / login endpoints work with the token cookie alone.
        if (uri == "/api/session" && method == Method.GET) return sessionInfo(session)
        if (uri == "/api/login" && method == Method.POST) return login(session)
        if (uri == "/api/logout" && method == Method.POST) {
            drainBody(session)
            return logout()
        }

        // Passcode gate: 423 (not 401) so the web UI shows its lock screen instead of "session ended".
        if (passcodeRequired() && !hasUnlockCookie(session)) {
            drainBody(session)
            return json(LOCKED, "{\"error\":\"locked\"}")
        }
        featureFor(uri)?.let { feature ->
            if (!services.features.isEnabled(feature)) {
                drainBody(session)
                return json(Status.FORBIDDEN, "{\"error\":\"feature_disabled\",\"feature\":${Json.str(feature.key)}}")
            }
        }
        deviceApi(session, uri, method)?.let { return it }

        if (uri == "/api/photos" && method == Method.GET) return listPhotos(session)
        if (uri == "/api/albums" && method == Method.GET) return json(Status.OK, JsonExtras.albums(catalog.total(), catalog.albums()))

        PHOTO_ROUTE.matchEntire(uri)?.let { m ->
            if (method != Method.GET) return text(Status.METHOD_NOT_ALLOWED, "Method not allowed")
            val photo = catalog.find(m.groupValues[1].toLong()) ?: return notFound()
            return when (m.groupValues[2]) {
                "thumb" -> thumb(photo)
                "full" -> full(session, photo)
                else -> download(photo)
            }
        }

        if (uri == "/api/download" && method == Method.POST) return downloadMany(session)
        if (uri == "/api/trash" && method == Method.POST) return trashRequest(session)
        if (uri == "/api/trash/status" && method == Method.GET) {
            return json(Status.OK, "{\"status\":${Json.str(trash.status())}}")
        }
        return notFound()
    }

    // ---------------------------------------------------------------- API handlers

    private fun listPhotos(session: IHTTPSession): Response {
        val p = session.parameters
        val limit = p["limit"]?.firstOrNull()?.toIntOrNull() ?: PhotoCatalog.DEFAULT_LIMIT
        val cursor = p["cursor"]?.firstOrNull()
        val page = try {
            catalog.page(
                cursor = cursor,
                limit = limit,
                sort = SortOrder.parse(p["sort"]?.firstOrNull()),
                filter = PhotoFilter.parse(p["filter"]?.firstOrNull()),
                bucket = p["bucket"]?.firstOrNull()?.takeIf { it.isNotEmpty() },
            )
        } catch (_: IllegalArgumentException) {
            return json(Status.BAD_REQUEST, "{\"error\":\"bad cursor\"}")
        }
        return json(Status.OK, Json.page(page))
    }

    private fun thumb(photo: Photo): Response {
        val bytes = store.thumbnail(photo.id) ?: return notFound()
        return bytesResponse("image/jpeg", bytes).apply {
            addHeader("Cache-Control", "private, max-age=86400")
        }
    }

    private fun full(session: IHTTPSession, photo: Photo): Response {
        if (!isBrowserSafe(photo.mime)) {
            // HEIC/HEIF etc.: serve a JPEG rendition so the viewer can show it.
            val bytes = store.renderLarge(photo.id) ?: return notFound()
            return bytesResponse("image/jpeg", bytes).apply {
                addHeader("Cache-Control", "private, max-age=3600")
            }
        }
        return servedFile(session, photo, attachment = false)
    }

    private fun download(photo: Photo): Response = servedFile(null, photo, attachment = true)

    /** Serves the original file, with Range support when [session] is given. */
    private fun servedFile(session: IHTTPSession?, photo: Photo, attachment: Boolean): Response {
        val total = store.length(photo.id) ?: return notFound()
        val mime = photo.mime.ifBlank { "application/octet-stream" }
        val range = if (session == null) RangeResult.None
        else RangeParser.parse(session.headers["range"], total)

        val resp: Response = when (range) {
            is RangeResult.Unsatisfiable -> {
                newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, "text/plain", "").apply {
                    addHeader("Content-Range", "bytes */$total")
                }
            }
            is RangeResult.Ok -> {
                val ins = store.open(photo.id, range.start) ?: return notFound()
                newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, LimitedInputStream(ins, range.length), range.length)
                    .apply { addHeader("Content-Range", "bytes ${range.start}-${range.end}/$total") }
            }
            RangeResult.None -> {
                val ins = store.open(photo.id, 0) ?: return notFound()
                newFixedLengthResponse(Status.OK, mime, ins, total)
            }
        }
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("Cache-Control", "private, max-age=3600")
        if (attachment) resp.addHeader("Content-Disposition", contentDisposition(photo.name))
        return resp
    }

    private fun downloadMany(session: IHTTPSession): Response {
        val ids = readIds(session)
        if (ids.isEmpty()) return json(Status.BAD_REQUEST, "{\"error\":\"no ids\"}")
        if (ids.size > MAX_IDS) return json(Status.BAD_REQUEST, "{\"error\":\"too many ids\"}")
        val photos = ids.mapNotNull { catalog.find(it) }
        if (photos.isEmpty()) return notFound()
        if (photos.size == 1) return download(photos[0])

        val sources = photos.map { p -> ZipSource(p.name, p.dateTaken) { store.open(p.id, 0) } }
        val name = "gallery-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(today()) + ".zip"
        return newChunkedResponse(Status.OK, "application/zip", zipStreamer.stream(sources)).apply {
            addHeader("Content-Disposition", contentDisposition(name))
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun trashRequest(session: IHTTPSession): Response {
        val ids = readIds(session)
        if (ids.isEmpty()) return json(Status.BAD_REQUEST, "{\"error\":\"no ids\"}")
        if (ids.size > MAX_IDS) return json(Status.BAD_REQUEST, "{\"error\":\"too many ids\"}")
        val existing = ids.filter { catalog.find(it) != null }
        if (existing.isEmpty()) return notFound()
        return if (trash.request(existing)) {
            json(Status.ACCEPTED, "{\"status\":\"pending\",\"count\":${existing.size}}")
        } else {
            json(Status.INTERNAL_ERROR, "{\"error\":\"could not start trash request\"}")
        }
    }

    // ---------------------------------------------------------------- Phase 1: session, passcode, features

    private val unlockToken: String = newRandomToken()
    private val limiter = LoginLimiter()

    private fun passcodeRequired(): Boolean = services.passcode?.required == true

    private fun sessionInfo(session: IHTTPSession): Response {
        val flags = Feature.values().associate { it.key to services.features.isEnabled(it) }
        val unlocked = !passcodeRequired() || hasUnlockCookie(session)
        return json(Status.OK, Json.session(passcodeRequired(), unlocked, services.deviceName(), flags))
    }

    private fun login(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress ?: "unknown"
        val raw = readRaw(session)
        val verifier = services.passcode
        if (verifier == null || !verifier.required) return unlockedResponse()

        val wait = limiter.retryAfterMs(ip)
        if (wait > 0) return lockedOut(wait)

        val code = JsonFields.string(raw, "passcode").orEmpty()
        if (verifier.verify(code)) {
            limiter.recordSuccess(ip)
            return unlockedResponse()
        }
        limiter.recordFailure(ip)
        val after = limiter.retryAfterMs(ip)
        if (after > 0) return lockedOut(after)
        return json(Status.FORBIDDEN, "{\"error\":\"wrong_passcode\",\"attemptsLeft\":${limiter.attemptsLeft(ip)}}")
    }

    private fun lockedOut(waitMs: Long): Response {
        val secs = (waitMs + 999) / 1000
        return newFixedLengthResponse(TOO_MANY, "application/json; charset=utf-8", "{\"error\":\"locked_out\",\"retryAfterSec\":$secs}").apply {
            addHeader("Retry-After", secs.toString())
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun unlockedResponse(): Response =
        json(Status.OK, "{\"ok\":true}").apply {
            addHeader("Set-Cookie", "$UNLOCK_COOKIE=$unlockToken; Path=/; HttpOnly; SameSite=Strict")
        }

    private fun logout(): Response =
        json(Status.OK, "{\"ok\":true}").apply {
            addHeader("Set-Cookie", "$UNLOCK_COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
        }

    private fun hasUnlockCookie(session: IHTTPSession): Boolean =
        cookieValue(session, UNLOCK_COOKIE)?.let { constantTimeEquals(it, unlockToken) } == true

    private fun featureFor(uri: String): Feature? = when {
        uri == "/api/photos" || uri.startsWith("/api/photos/") || uri == "/api/albums" || uri == "/api/download" || uri.startsWith("/api/trash") -> Feature.PHOTOS
        uri.startsWith("/api/notifications") -> Feature.NOTIFICATIONS
        uri.startsWith("/api/sms") -> Feature.MESSAGES
        uri == "/api/contacts" -> Feature.CONTACTS
        uri.startsWith("/api/files") || uri.startsWith("/api/clipboard") -> Feature.FILES
        uri == "/api/status" -> Feature.STATUS
        uri.startsWith("/api/find") || uri == "/api/open" -> Feature.FIND
        uri.startsWith("/api/backup") -> Feature.BACKUP
        else -> null
    }

    private fun unavailable(): Response = json(Status.NOT_FOUND, "{\"error\":\"unavailable\"}")

    // ---------------------------------------------------------------- Phase 1: device features

    private fun deviceApi(session: IHTTPSession, uri: String, method: Method): Response? {
        if (uri == "/api/status" && method == Method.GET) {
            val p = services.status ?: return unavailable()
            return json(Status.OK, Json.status(p.status()))
        }

        // notifications
        if (uri == "/api/notifications" && method == Method.GET) {
            val p = services.notifications ?: return unavailable()
            return json(Status.OK, Json.notifications(p.isConnected(), if (p.isConnected()) p.list() else emptyList()))
        }
        if (uri == "/api/notifications/dismiss" && method == Method.POST) {
            val p = services.notifications ?: return unavailable()
            val key = JsonFields.string(readRaw(session), "key") ?: return json(Status.BAD_REQUEST, "{\"error\":\"key required\"}")
            return json(if (p.dismiss(key)) Status.OK else Status.NOT_FOUND, "{\"ok\":${p.isConnected()}}")
        }
        if (uri == "/api/notifications/reply" && method == Method.POST) {
            val p = services.notifications ?: return unavailable()
            val raw = readRaw(session)
            val key = JsonFields.string(raw, "key")
            val text = JsonFields.string(raw, "text")
            if (key.isNullOrEmpty() || text.isNullOrBlank() || text.length > MAX_TEXT) {
                return json(Status.BAD_REQUEST, "{\"error\":\"key and text required\"}")
            }
            return if (p.reply(key, text)) json(Status.OK, "{\"ok\":true}") else json(Status.NOT_FOUND, "{\"error\":\"cannot reply\"}")
        }

        // messages
        if (uri == "/api/sms/threads" && method == Method.GET) {
            val p = services.messages ?: return unavailable()
            val ok = p.hasPermission()
            return json(Status.OK, Json.threads(ok, if (ok) p.threads() else emptyList()))
        }
        SMS_THREAD.matchEntire(uri)?.let { m ->
            if (method != Method.GET) return text(Status.METHOD_NOT_ALLOWED, "Method not allowed")
            val p = services.messages ?: return unavailable()
            if (!p.hasPermission()) return json(Status.FORBIDDEN, "{\"error\":\"sms_permission\"}")
            val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 500) ?: 100
            return json(Status.OK, Json.messages(p.messages(m.groupValues[1].toLong(), limit)))
        }
        if (uri == "/api/sms/send" && method == Method.POST) {
            val p = services.messages ?: return unavailable()
            if (!p.hasPermission()) return json(Status.FORBIDDEN, "{\"error\":\"sms_permission\"}")
            val raw = readRaw(session)
            val to = JsonFields.string(raw, "to")?.trim()
            val body = JsonFields.string(raw, "body")
            if (to == null || !PHONE_NUMBER.matches(to) || body.isNullOrBlank() || body.length > MAX_SMS) {
                return json(Status.BAD_REQUEST, "{\"error\":\"valid number and body required\"}")
            }
            return if (p.send(to, body)) json(Status.OK, "{\"ok\":true}") else json(Status.INTERNAL_ERROR, "{\"error\":\"send failed\"}")
        }

        // files
        if (uri == "/api/files" && method == Method.GET) {
            val p = services.files ?: return unavailable()
            return json(Status.OK, Json.files(p.list()))
        }
        if (uri == "/api/files/upload" && method == Method.POST) return uploadFiles(session)
        FILE_ROUTE.matchEntire(uri)?.let { m ->
            val p = services.files ?: return unavailable()
            val id = m.groupValues[1].toLong()
            val file = p.find(id) ?: return notFound()
            return when {
                m.groupValues[2] == "/download" && method == Method.GET -> {
                    val ins = p.open(id) ?: return notFound()
                    newFixedLengthResponse(Status.OK, file.mime.ifBlank { "application/octet-stream" }, ins, file.size).apply {
                        addHeader("Content-Disposition", contentDisposition(file.name))
                        addHeader("Cache-Control", "private, no-store")
                    }
                }
                m.groupValues[2].isEmpty() && method == Method.DELETE ->
                    json(if (p.delete(id)) Status.OK else Status.INTERNAL_ERROR, "{\"ok\":true}")
                else -> text(Status.METHOD_NOT_ALLOWED, "Method not allowed")
            }
        }

        // clipboard
        if (uri == "/api/clipboard" && method == Method.GET) {
            val p = services.clipboard ?: return unavailable()
            return json(Status.OK, Json.clipboard(p.latest()))
        }
        if (uri == "/api/clipboard" && method == Method.POST) {
            val p = services.clipboard ?: return unavailable()
            val text = JsonFields.string(readRaw(session), "text")
            if (text == null || text.length > MAX_CLIPBOARD) return json(Status.BAD_REQUEST, "{\"error\":\"text required\"}")
            return if (p.setPhoneClipboard(text)) json(Status.OK, "{\"ok\":true}") else json(Status.INTERNAL_ERROR, "{\"error\":\"failed\"}")
        }

        // contacts
        if (uri == "/api/contacts" && method == Method.GET) {
            val p = services.contacts ?: return unavailable()
            val ok = p.hasPermission()
            return json(Status.OK, JsonExtras.contacts(ok, if (ok) p.list() else emptyList()))
        }

        // open a link, the dialer or a map search on the phone
        if (uri == "/api/open" && method == Method.POST) {
            val p = services.open ?: return unavailable()
            val raw = readRaw(session)
            val kind = JsonFields.string(raw, "kind")
            val value = JsonFields.string(raw, "value")?.trim()
            val problem = OpenRequests.validate(kind, value)
            if (problem != null) return json(Status.BAD_REQUEST, JsonExtras.error(problem))
            return if (p.open(kind!!, value!!)) json(Status.OK, "{\"ok\":true}") else json(Status.INTERNAL_ERROR, JsonExtras.error("Could not open it on the phone."))
        }

        // backup to Google Drive: settings only. Progress and errors are deliberately never exposed.
        if (uri == "/api/backup" && method == Method.GET) {
            val p = services.backup ?: return unavailable()
            return json(Status.OK, JsonExtras.backup(p.settings()))
        }
        if (uri == "/api/backup" && method == Method.POST) {
            val p = services.backup ?: return unavailable()
            val raw = readRaw(session)
            val url = JsonFields.string(raw, "url")?.trim()?.takeIf { it.isNotEmpty() }
            val secret = JsonFields.string(raw, "secret")?.takeIf { it.isNotEmpty() }
            val photos = JsonFields.bool(raw, "photos") ?: true
            val videos = JsonFields.bool(raw, "videos") ?: true
            val wifiOnly = JsonFields.bool(raw, "wifiOnly") ?: true
            val problem = url?.let { p.validateUrl(it) } ?: secret?.let { BackupRules.validateSecret(it) }
            if (problem != null) return json(Status.BAD_REQUEST, JsonExtras.error(problem))
            if (!photos && !videos) return json(Status.BAD_REQUEST, JsonExtras.error("Pick photos, videos or both."))
            if (!p.save(url, secret, photos, videos, wifiOnly)) {
                return json(Status.BAD_REQUEST, JsonExtras.error("Enter the script address and the secret."))
            }
            return json(Status.OK, "{\"ok\":true,\"reachable\":${p.test()}}")
        }
        if (uri == "/api/backup/diag" && method == Method.GET) {
            // Not used by the web page. Open it by hand if backup ever seems stuck.
            val p = services.backup ?: return unavailable()
            return json(Status.OK, "{\"last\":" + Json.str(p.lastNote()) + "}")
        }
        if (uri == "/api/backup/clear" && method == Method.POST) {
            val p = services.backup ?: return unavailable()
            drainBody(session)
            p.clear()
            return json(Status.OK, "{\"ok\":true}")
        }

        // find my phone
        if (uri == "/api/find" && method == Method.GET) {
            val p = services.find ?: return unavailable()
            return json(Status.OK, "{\"ringing\":${p.isRinging()}}")
        }
        if (uri == "/api/find/ring" && method == Method.POST) {
            val p = services.find ?: return unavailable()
            drainBody(session)
            return if (p.ring()) json(Status.OK, "{\"ringing\":true}") else json(Status.INTERNAL_ERROR, "{\"error\":\"cannot ring\"}")
        }
        if (uri == "/api/find/stop" && method == Method.POST) {
            val p = services.find ?: return unavailable()
            drainBody(session)
            p.stop()
            return json(Status.OK, "{\"ringing\":false}")
        }
        return null
    }

    private fun uploadFiles(session: IHTTPSession): Response {
        val p = services.files ?: return unavailable()
        val temp = parsed(session).files
        val saved = ArrayList<SharedFile>()
        try {
            for ((field, path) in temp) {
                if (field == "postData") continue
                val original = session.parameters[field]?.firstOrNull() ?: continue
                val name = cleanFileName(original)
                java.io.File(path).inputStream().use { ins ->
                    p.save(name, guessMime(name), ins)?.let { saved.add(it) }
                }
            }
        } finally {
            for ((field, path) in temp) if (field != "postData") java.io.File(path).delete()
        }
        if (saved.isEmpty()) return json(Status.BAD_REQUEST, "{\"error\":\"no file received\"}")
        return json(Status.OK, Json.files(saved))
    }

    // ---------------------------------------------------------------- static files

    private fun staticFile(uri: String, method: Method): Response {
        if (method != Method.GET && method != Method.HEAD) return text(Status.METHOD_NOT_ALLOWED, "Method not allowed")
        val path = (if (uri == "/") "index.html" else uri.removePrefix("/"))
        if (path.contains("..") || path.contains('\\') || path.startsWith("/")) return notFound()
        val ins = assets.open(path) ?: return notFound()
        val resp = newChunkedResponse(Status.OK, mimeFor(path), ins)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Content-Security-Policy", CSP)
        resp.addHeader("X-Content-Type-Options", "nosniff")
        resp.addHeader("Referrer-Policy", "no-referrer")
        return resp
    }

    // ---------------------------------------------------------------- auth helpers

    /** Reads and discards an unread request body so a keep-alive connection isn't left mid-request. */
    private fun drainBody(session: IHTTPSession) {
        if (session.method == Method.POST || session.method == Method.PUT) parsed(session)
    }

    private fun tokenMatches(supplied: String): Boolean = constantTimeEquals(supplied, token)

    private fun cookieValue(session: IHTTPSession, name: String): String? {
        val header = session.headers["cookie"] ?: return null
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq).trim() == name) return part.substring(eq + 1).trim()
        }
        return null
    }

    private fun hasValidCookie(session: IHTTPSession): Boolean =
        cookieValue(session, COOKIE_NAME)?.let { constantTimeEquals(it, token) } == true

    private fun redirectWithCookie(): Response {
        val r = newFixedLengthResponse(Status.REDIRECT_SEE_OTHER, "text/plain", "")
        r.addHeader("Location", "/")
        r.addHeader("Set-Cookie", "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Strict")
        r.addHeader("Cache-Control", "no-store")
        r.addHeader("Referrer-Policy", "no-referrer")
        return r
    }

    private fun unauthorized(): Response =
        newFixedLengthResponse(Status.UNAUTHORIZED, "text/html; charset=utf-8", UNAUTHORIZED_HTML).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------------- request/response helpers

    private class Parsed(val raw: String?, val files: Map<String, String>)

    // NanoHTTPD can parse a request body only once, and it reuses one session object for every request on a
    // keep-alive connection. Each connection is served by one thread, one request at a time, so the parsed body
    // is cached per thread and cleared at the start of every request.
    private val currentBody = ThreadLocal<Parsed?>()

    private fun parsed(session: IHTTPSession): Parsed {
        currentBody.get()?.let { return it }
        val files = HashMap<String, String>()
        val result = try {
            session.parseBody(files)
            Parsed(files["postData"], files)
        } catch (_: Exception) {
            Parsed(null, files)
        }
        currentBody.set(result)
        return result
    }

    /** The raw request body (JSON), or null. Safe to call any number of times. */
    private fun readRaw(session: IHTTPSession): String? = parsed(session).raw

    private fun readIds(session: IHTTPSession): List<Long> {
        val raw = parsed(session).raw ?: session.parameters["ids"]?.joinToString(",")
        return Json.parseIds(raw)
    }

    private fun json(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun text(status: Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun notFound(): Response = text(Status.NOT_FOUND, "Not found")

    private fun bytesResponse(mime: String, bytes: ByteArray): Response =
        newFixedLengthResponse(Status.OK, mime, ByteArrayInputStream(bytes) as InputStream, bytes.size.toLong())

    companion object {
        const val COOKIE_NAME = "gallery_session"
        const val UNLOCK_COOKIE = "gallery_unlock"
        private const val MAX_TEXT = 4_000
        private const val MAX_SMS = 1_000
        private const val MAX_CLIPBOARD = 100_000
        private val SMS_THREAD = Regex("""^/api/sms/threads/(\d{1,18})$""")
        private val FILE_ROUTE = Regex("""^/api/files/(\d{1,18})(/download)?$""")
        private val PHONE_NUMBER = Regex("""^[+0-9 ()\-]{3,24}$""")

        private val LOCKED = object : Response.IStatus {
            override fun getRequestStatus(): Int = 423
            override fun getDescription(): String = "423 Locked"
        }
        private val TOO_MANY = object : Response.IStatus {
            override fun getRequestStatus(): Int = 429
            override fun getDescription(): String = "429 Too Many Requests"
        }

        fun newRandomToken(): String {
            val bytes = ByteArray(24)
            java.security.SecureRandom().nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun cleanFileName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val safe = base.map { if (it.code < 32 || it in "<>:\"|?*") '_' else it }.joinToString("").trim().trim('.')
            return safe.take(120).ifBlank { "file" }
        }

        fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt", "log", "md" -> "text/plain"
            "zip" -> "application/zip"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "doc", "docx" -> "application/msword"
            else -> "application/octet-stream"
        }

        private const val MAX_IDS = 5000
        private val PHOTO_ROUTE = Regex("^/api/photos/(\\d{1,18})/(thumb|full|download)$")

        private const val CSP =
            "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; " +
                "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"

        private const val UNAUTHORIZED_HTML =
            "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
                "<title>R3U | Phone Link</title><body style=\"font-family:system-ui;max-width:32rem;margin:15vh auto;padding:0 1.5rem;" +
                "color:#222\"><h1>Open the link from the app</h1><p>This page needs the private link shown in the R3U | Phone Link " +
                "app on your phone. Open that link (or scan its QR code) to get in.</p></body>"

        fun isBrowserSafe(mime: String): Boolean = when (mime.lowercase()) {
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp", "image/bmp", "image/avif" -> true
            else -> false
        }

        fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }

        /** RFC 6266 / 5987 header with an ASCII fallback and the UTF-8 original name. */
        fun contentDisposition(filename: String): String {
            val clean = filename.replace(Regex("[\\r\\n\"\\\\]"), "_")
            val ascii = clean.map { if (it.code in 32..126) it else '_' }.joinToString("")
            val encoded = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
        }
    }
}
