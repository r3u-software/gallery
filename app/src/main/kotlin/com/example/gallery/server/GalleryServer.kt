package com.example.gallery.server

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
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            text(Status.INTERNAL_ERROR, "Something went wrong")
        }
    }

    // ---------------------------------------------------------------- routing

    private fun route(session: IHTTPSession): Response {
        if (!clientAllowed(session.remoteIpAddress)) {
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

        if (!hasValidCookie(session)) return unauthorized()

        return if (uri.startsWith("/api/")) api(session, uri, method) else staticFile(uri, method)
    }

    private fun api(session: IHTTPSession, uri: String, method: Method): Response {
        if (uri == "/api/photos" && method == Method.GET) return listPhotos(session)

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

    private fun tokenMatches(supplied: String): Boolean = constantTimeEquals(supplied, token)

    private fun hasValidCookie(session: IHTTPSession): Boolean {
        val header = session.headers["cookie"] ?: return false
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq).trim() == COOKIE_NAME && constantTimeEquals(part.substring(eq + 1).trim(), token)) {
                return true
            }
        }
        return false
    }

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

    private fun readIds(session: IHTTPSession): List<Long> {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: session.parameters["ids"]?.joinToString(",")
        return Json.parseIds(raw)
    }

    private fun json(status: Status, body: String): Response =
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
        private const val MAX_IDS = 5000
        private val PHOTO_ROUTE = Regex("^/api/photos/(\\d{1,18})/(thumb|full|download)$")

        private const val CSP =
            "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; " +
                "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"

        private const val UNAUTHORIZED_HTML =
            "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
                "<title>Gallery</title><body style=\"font-family:system-ui;max-width:32rem;margin:15vh auto;padding:0 1.5rem;" +
                "color:#222\"><h1>Open the link from the app</h1><p>This page needs the private link shown in the Gallery " +
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
