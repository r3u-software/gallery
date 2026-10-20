package com.example.gallery.backup

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stand-in for Google: an "Apps Script" at /exec that answers a POST with a redirect to the page holding the
 * reply (as the real one does), and a resumable-upload endpoint at /upload/{id} that speaks 308 / Range.
 */
private class FakeGoogle : NanoHTTPD(0) {
    class Slot(val total: Long) { val received = ByteArrayOutputStream() }

    val slots = HashMap<String, Slot>()
    val scriptRequests = ArrayList<String>()
    private val replies = HashMap<String, String>()
    private var counter = 0

    var secret = "right-secret"
    var scriptStatus = 200
    var scriptBody: ((String) -> String)? = null
    var sessionHost: String? = null
    var failPutNumber = 0
    var putCount = 0
    var expireSessions = false

    val base: String get() = "http://127.0.0.1:$listeningPort"

    private val resume = object : Response.IStatus {
        override fun getRequestStatus() = 308
        override fun getDescription() = "308 Resume Incomplete"
    }
    private val found = object : Response.IStatus {
        override fun getRequestStatus() = 302
        override fun getDescription() = "302 Found"
    }

    init { start(5000, true) }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        return when {
            path == "/exec" && session.method == Method.POST -> {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val body = files["postData"].orEmpty()
                scriptRequests.add(body)
                val reply = scriptBody?.invoke(body) ?: defaultReply(body)
                val id = "r${counter++}"
                replies[id] = reply
                newFixedLengthResponse(found, "text/plain", "").apply { addHeader("Location", "$base/reply/$id") }
            }
            path.startsWith("/reply/") && session.method == Method.GET ->
                newFixedLengthResponse(Status.lookup(scriptStatus) ?: Status.OK, "application/json", replies[path.removePrefix("/reply/")].orEmpty())
            path.startsWith("/upload/") && session.method == Method.PUT -> upload(session, path.removePrefix("/upload/"))
            else -> newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "nf")
        }
    }

    private fun defaultReply(body: String): String {
        if (!body.contains("\"secret\":\"$secret\"")) return "{\"ok\":false,\"error\":\"unauthorized\"}"
        if (body.contains("\"action\":\"ping\"")) return "{\"ok\":true}"
        val name = Regex("\"name\":\"([^\"]*)\"").find(body)!!.groupValues[1]
        val size = Regex("\"size\":(\\d+)").find(body)!!.groupValues[1].toLong()
        val id = "s${counter++}"
        slots[id] = Slot(size)
        names[id] = name
        return "{\"ok\":true,\"session\":\"${sessionHost ?: base}/upload/$id\"}"
    }

    val names = HashMap<String, String>()

    private fun upload(session: IHTTPSession, id: String): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val slot = slots[id]
        if (slot == null || expireSessions) return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "gone")
        val range = session.headers["content-range"].orEmpty()
        // NanoHTTPD gives an empty path (not an empty file) when the body has no bytes, as in a status probe.
        val data = files["content"]?.takeIf { it.isNotEmpty() }?.let { File(it).readBytes() } ?: ByteArray(0)
        val isProbe = range.startsWith("bytes */")
        if (!isProbe) {
            putCount++
            if (putCount == failPutNumber) return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "boom")
            val start = Regex("bytes (\\d+)-").find(range)!!.groupValues[1].toLong()
            if (start == slot.received.size().toLong()) slot.received.write(data)
        }
        val have = slot.received.size().toLong()
        if (have == slot.total) return newFixedLengthResponse(Status.OK, "application/json", "{\"id\":\"file\"}")
        return newFixedLengthResponse(resume, "text/plain", "").apply {
            if (have > 0) addHeader("Range", "bytes=0-${have - 1}")
        }
    }
}

class HttpDriveTransportTest {
    private var google: FakeGoogle? = null

    @After
    fun tearDown() { google?.stop() }

    private fun fake(): FakeGoogle = FakeGoogle().also { google = it }

    private fun config(g: FakeGoogle, secret: String = "right-secret") = BackupConfig("${g.base}/exec", secret)

    private fun item(name: String = "IMG_1.jpg", size: Long = 1000) =
        BackupItem(MediaKind.IMAGE, 1, name, "image/jpeg", size, 5_000, 5_000, "Camera")

    private fun anyLocal(): (URL) -> Boolean = { it.host == "127.0.0.1" }

    @Test
    fun `ping says whether the script accepts the secret, following its redirect`() {
        val g = fake()
        val t = HttpDriveTransport(anyLocal())
        assertTrue(t.ping(config(g)))
        assertFalse(t.ping(config(g, secret = "wrong")))
        assertFalse(t.ping(BackupConfig("http://127.0.0.1:1/exec", "x"))) // nothing listening
    }

    @Test
    fun `init asks the script for a slot and describes the file`() {
        val g = fake()
        val result = HttpDriveTransport(anyLocal()).init(config(g), item(name = "a \"quoted\".jpg", size = 4321))
        assertTrue(result is InitResult.Session)
        val sent = g.scriptRequests.single()
        assertTrue(sent, sent.contains("\"action\":\"init\""))
        assertTrue(sent, sent.contains("\"size\":4321"))
        assertTrue(sent, sent.contains("\"mime\":\"image/jpeg\""))
        assertTrue(sent, sent.contains("\"album\":\"Camera\""))
        assertTrue(sent, sent.contains("a \\\"quoted\\\".jpg"))
        assertTrue(sent, sent.contains("\"modified\":5000"))
    }

    @Test
    fun `script answers map to the right kind of result`() {
        val g = fake()
        val t = HttpDriveTransport(anyLocal())
        fun answer(body: String, status: Int = 200): InitResult {
            g.scriptBody = { body }
            g.scriptStatus = status
            return t.init(config(g), item())
        }
        assertTrue(answer("{\"ok\":true,\"exists\":true}") is InitResult.Exists)
        assertTrue(answer("{\"ok\":false,\"error\":\"unauthorized\"}") is InitResult.ConfigProblem)
        assertTrue(answer("{\"ok\":false,\"error\":\"set_secret\"}") is InitResult.ConfigProblem)
        assertTrue(answer("{\"ok\":false,\"error\":\"set_folder\"}") is InitResult.ConfigProblem)
        assertTrue(answer("{\"ok\":false,\"error\":\"bad_request\"}") is InitResult.Rejected)
        assertTrue(answer("{\"ok\":false,\"error\":\"quota\"}") is InitResult.Transient)
        assertTrue(answer("{\"ok\":false,\"error\":\"drive_full\"}") is InitResult.Transient)
        assertTrue(answer("<html>Sign in</html>") is InitResult.ConfigProblem)
        assertTrue(answer("oops", status = 500) is InitResult.Transient)
        assertTrue(answer("denied", status = 403) is InitResult.ConfigProblem)
        assertTrue(answer("{\"ok\":true}") is InitResult.Transient) // no slot given
    }

    @Test
    fun `an upload address outside the allowed hosts is refused`() {
        val g = fake()
        g.sessionHost = "http://evil.example"
        val t = HttpDriveTransport(allowUrl = { it.host == "127.0.0.1" })
        assertTrue(t.init(config(g), item()) is InitResult.ConfigProblem)
    }

    @Test
    fun `a script address that is not allowed is never contacted`() {
        val g = fake()
        val t = HttpDriveTransport(allowUrl = { false })
        assertTrue(t.init(config(g), item()) is InitResult.ConfigProblem)
        assertTrue(g.scriptRequests.isEmpty())
        assertFalse(t.ping(config(g)))
    }

    @Test
    fun `chunks are sent with their range, and a fresh slot reports nothing received`() {
        val g = fake()
        val t = HttpDriveTransport(anyLocal())
        val session = (t.init(config(g), item(size = 600_000)) as InitResult.Session).uri
        val data = ByteArray(600_000) { (it % 251).toByte() }

        val probe = t.status(session, 600_000)
        assertTrue(probe is UploadStatus.Incomplete && probe.next == 0L)

        val first = t.put(session, 600_000, 0, data.copyOfRange(0, 262_144), 262_144)
        assertTrue(first is UploadStatus.Incomplete && first.next == 262_144L)

        val ask = t.status(session, 600_000)
        assertTrue(ask is UploadStatus.Incomplete && ask.next == 262_144L)

        val rest = data.copyOfRange(262_144, 600_000)
        val last = t.put(session, 600_000, 262_144, rest, rest.size)
        assertTrue(last is UploadStatus.Complete)
        assertArrayEquals(data, g.slots.values.single().received.toByteArray())
    }

    @Test
    fun `server errors are transient and an expired slot is gone`() {
        val g = fake()
        val t = HttpDriveTransport(anyLocal())
        val session = (t.init(config(g), item(size = 10)) as InitResult.Session).uri
        g.failPutNumber = 1
        assertTrue(t.put(session, 10, 0, ByteArray(10), 10) is UploadStatus.Transient)
        g.expireSessions = true
        assertTrue(t.status(session, 10) is UploadStatus.SessionGone)
    }

    @Test
    fun `engine and transport together survive a failed chunk`() {
        val g = fake()
        g.failPutNumber = 2
        val data = ByteArray(700_000) { (it % 239).toByte() }
        val it0 = item(size = data.size.toLong())
        val source = object : BackupSource {
            override fun list(photos: Boolean, videos: Boolean) = listOf(it0)
            override fun open(item: BackupItem, offset: Long) = data.inputStream().also { s -> s.skip(offset) }
        }
        val ledger = FakeLedger()
        val engine = BackupEngine(source, ledger, HttpDriveTransport(anyLocal()), 262_144, { 0L }) { }
        val report = engine.run(config(g), Long.MAX_VALUE)
        assertEquals(RunResult.ALL_DONE, report.result)
        assertEquals(1, report.uploaded)
        assertArrayEquals(data, g.slots.values.single().received.toByteArray())
        assertEquals(1, ledger.done.size)
    }
}
