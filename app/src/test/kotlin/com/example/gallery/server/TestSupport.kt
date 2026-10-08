package com.example.gallery.server

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.concurrent.Executors

const val TEST_TOKEN = "test-token-abc123"

fun photo(id: Long, name: String = "IMG_$id.jpg", date: Long = id * 1000, size: Long = 1000, screenshot: Boolean = false, mime: String = "image/jpeg") =
    Photo(id, name, date, size, 4000, 3000, if (screenshot) "Screenshots" else "Camera", mime, screenshot)

class FakeStore(val files: MutableMap<Long, ByteArray> = HashMap()) : PhotoStore {
    var openedStreams = 0
    override fun length(id: Long): Long? = files[id]?.size?.toLong()
    override fun open(id: Long, offset: Long): InputStream? {
        val data = files[id] ?: return null
        openedStreams++
        return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }
    override fun thumbnail(id: Long): ByteArray? = if (files.containsKey(id)) byteArrayOf(1, 2, 3) else null
    override fun renderLarge(id: Long): ByteArray? = if (files.containsKey(id)) byteArrayOf(9, 9) else null
}

class FakeTrash : TrashGateway {
    var requested: List<Long>? = null
    var current = "idle"
    var accept = true
    override fun request(ids: List<Long>): Boolean {
        requested = ids
        if (accept) current = "pending"
        return accept
    }
    override fun status(): String = current
}

class MapAssets(private val files: Map<String, String>) : AssetProvider {
    override fun open(path: String): InputStream? = files[path]?.let { ByteArrayInputStream(it.toByteArray()) }
}

/** Boots a real server on an ephemeral loopback port. */
class TestServer(
    photos: List<Photo> = emptyList(),
    val store: FakeStore = FakeStore(),
    val trash: FakeTrash = FakeTrash(),
    allowed: (String?) -> Boolean = { true },
    assets: AssetProvider = MapAssets(mapOf("index.html" to "<h1>hi</h1>", "js/app.js" to "console.log(1)")),
) {
    val catalog = PhotoCatalog { photos }
    val server = GalleryServer(
        port = 0,
        token = TEST_TOKEN,
        catalog = catalog,
        store = store,
        trash = trash,
        assets = assets,
        zipStreamer = ZipStreamer(Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }),
        clientAllowed = allowed,
        today = { Date(1_767_225_600_000L) }, // 2026-01-01 UTC
    )

    init {
        server.start(5000, true)
    }

    val base: String get() = "http://127.0.0.1:${server.listeningPort}"
    fun stop() = server.stop()

    fun get(path: String, cookie: Boolean = true, headers: Map<String, String> = emptyMap()) =
        request("GET", path, null, null, cookie, headers)

    fun post(path: String, body: String, contentType: String = "application/json", cookie: Boolean = true) =
        request("POST", path, body, contentType, cookie, emptyMap())

    private fun request(
        method: String, path: String, body: String?, contentType: String?, cookie: Boolean, headers: Map<String, String>,
    ): Reply {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        if (cookie) conn.setRequestProperty("Cookie", "${GalleryServer.COOKIE_NAME}=$TEST_TOKEN")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val bytes = stream?.use { s ->
            val out = ByteArrayOutputStream()
            s.copyTo(out)
            out.toByteArray()
        } ?: ByteArray(0)
        return Reply(code, bytes, conn.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() })
    }

    class Reply(val code: Int, val body: ByteArray, val headers: Map<String, List<String>>) {
        val text: String get() = String(body)
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    }
}
