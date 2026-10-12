package com.example.gallery.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

private class FakeVerifier(var secret: String? = "hunter22") : PasscodeVerifier {
    override val required: Boolean get() = secret != null
    override fun verify(code: String): Boolean = code == secret
}

private class FakeNotifications(var connected: Boolean = true) : NotificationsProvider {
    val items = mutableListOf(
        NotificationItem("k1", "com.chat", "Chat", "Ana", "Lunch at 12?", 1000, true),
        NotificationItem("k2", "com.news", "News", "Headline", "Something \"quoted\"", 2000, false),
    )
    var replied: Pair<String, String>? = null
    override fun isConnected() = connected
    override fun list() = items.toList()
    override fun dismiss(key: String) = items.removeAll { it.key == key }
    override fun reply(key: String, text: String): Boolean {
        val n = items.firstOrNull { it.key == key } ?: return false
        if (!n.canReply) return false
        replied = key to text
        return true
    }
}

private class FakeMessages(var permission: Boolean = true) : MessagesProvider {
    var sent: Pair<String, String>? = null
    override fun hasPermission() = permission
    override fun threads() = listOf(SmsThread(7, "+15551234", "Ana", "See you", 5000, 1))
    override fun messages(threadId: Long, limit: Int) =
        if (threadId == 7L) listOf(SmsMessage(1, "Hi", 4000, false), SmsMessage(2, "See you", 5000, true)) else emptyList()
    override fun send(to: String, body: String): Boolean { sent = to to body; return true }
}

private class FakeFiles : FilesProvider {
    val data = LinkedHashMap<Long, Pair<SharedFile, ByteArray>>()
    private var next = 1L
    override fun list() = data.values.map { it.first }
    override fun find(id: Long) = data[id]?.first
    override fun open(id: Long): InputStream? = data[id]?.let { ByteArrayInputStream(it.second) }
    override fun save(name: String, mime: String, input: InputStream): SharedFile? {
        val bytes = input.readBytes()
        val f = SharedFile(next++, name, bytes.size.toLong(), 9000, mime)
        data[f.id] = f to bytes
        return f
    }
    override fun delete(id: Long) = data.remove(id) != null
}

private class FakeClipboard : ClipboardProvider {
    var phone: String? = null
    override fun latest() = ClipboardEntry("from phone", 123)
    override fun setPhoneClipboard(text: String): Boolean { phone = text; return true }
}

private class FakeFind : FindProvider {
    var ringing = false
    override fun ring(): Boolean { ringing = true; return true }
    override fun stop() { ringing = false }
    override fun isRinging() = ringing
}

class Phase1Test {
    private var srv: TestServer? = null

    @After
    fun tearDown() { srv?.stop() }

    private fun server(
        verifier: PasscodeVerifier? = null,
        flags: FeatureFlags = FeatureFlags { true },
        notifications: NotificationsProvider? = FakeNotifications(),
        messages: MessagesProvider? = FakeMessages(),
        files: FilesProvider? = FakeFiles(),
        clipboard: ClipboardProvider? = FakeClipboard(),
        find: FindProvider? = FakeFind(),
    ): TestServer {
        val status = object : StatusProvider {
            override fun status() = DeviceStatus("Reu's phone", "Pixel", "14", 53, true, 1_000, 4_000)
        }
        return TestServer(
            listOf(photo(1)),
            services = Services(status, notifications, messages, files, clipboard, find, verifier, flags) { "Reu's phone" },
        ).also { srv = it }
    }

    private fun unlockCookie(s: TestServer): String {
        val r = s.send("POST", "/api/login", s.sessionCookie, "{\"passcode\":\"hunter22\"}")
        assertEquals(200, r.code)
        val set = r.header("Set-Cookie")!!
        return set.substringBefore(';')
    }

    // ------------------------------------------------------------ passcode gate

    @Test
    fun `without a passcode everything behaves as before`() {
        val s = server(verifier = null)
        assertEquals(200, s.get("/api/photos").code)
        assertTrue(s.get("/api/session").text.contains("\"passcodeRequired\":false"))
    }

    @Test
    fun `passcode locks the api with 423 but session and static files stay reachable`() {
        val s = server(FakeVerifier())
        assertEquals(423, s.get("/api/photos").code)
        assertEquals(423, s.get("/api/status").code)
        assertEquals(423, s.post("/api/trash", "{\"ids\":[1]}").code)
        assertEquals(200, s.get("/").code)
        val session = s.get("/api/session")
        assertEquals(200, session.code)
        assertTrue(session.text.contains("\"passcodeRequired\":true"))
        assertTrue(session.text.contains("\"unlocked\":false"))
    }

    @Test
    fun `correct passcode sets an HttpOnly SameSite Strict unlock cookie`() {
        val s = server(FakeVerifier())
        val r = s.send("POST", "/api/login", s.sessionCookie, "{\"passcode\":\"hunter22\"}")
        assertEquals(200, r.code)
        val cookie = r.header("Set-Cookie")!!
        assertTrue(cookie.startsWith("${GalleryServer.UNLOCK_COOKIE}="))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Strict"))

        val both = "${s.sessionCookie}; ${cookie.substringBefore(';')}"
        assertEquals(200, s.send("GET", "/api/photos", both).code)
        assertTrue(s.send("GET", "/api/session", both).text.contains("\"unlocked\":true"))
        // unlock cookie alone is not enough: the session token cookie is still required
        assertEquals(401, s.send("GET", "/api/photos", cookie.substringBefore(';')).code)
        // the session cookie alone is still locked
        assertEquals(423, s.send("GET", "/api/photos", s.sessionCookie).code)
    }

    @Test
    fun `wrong passcode is refused and counts down attempts`() {
        val s = server(FakeVerifier())
        val r = s.send("POST", "/api/login", s.sessionCookie, "{\"passcode\":\"nope\"}")
        assertEquals(403, r.code)
        assertTrue(r.text, r.text.contains("\"attemptsLeft\":4"))
        assertNull(r.header("Set-Cookie"))
        assertEquals(403, s.send("POST", "/api/login", s.sessionCookie, "{}").code)
    }

    @Test
    fun `five wrong attempts lock the client out even for the right passcode`() {
        val s = server(FakeVerifier())
        repeat(4) { assertEquals(403, s.send("POST", "/api/login", s.sessionCookie, "{\"passcode\":\"x$it\"}").code) }
        val fifth = s.send("POST", "/api/login", s.sessionCookie, "{\"passcode\":\"bad\"}")
        assertEquals(429, fifth.code)
        assertTrue(fifth.text.contains("retryAfterSec"))
        assertNotNull(fifth.header("Retry-After"))
        val right = s.send("POST", "/api/login", s.sessionCookie, "{\"passcode\":\"hunter22\"}")
        assertEquals(429, right.code)
        assertNull(right.header("Set-Cookie"))
    }

    @Test
    fun `login limiter doubles the lockout and a success clears it`() {
        var now = 0L
        val l = LoginLimiter(now = { now }, maxFailures = 3, baseLockMs = 1_000, maxLockMs = 8_000)
        repeat(2) { l.recordFailure("a") }
        assertEquals(0, l.retryAfterMs("a"))
        assertEquals(1, l.attemptsLeft("a"))
        l.recordFailure("a")
        assertEquals(1_000, l.retryAfterMs("a"))
        now += 1_001
        assertEquals(0, l.retryAfterMs("a"))
        l.recordFailure("a")
        assertEquals(2_000, l.retryAfterMs("a"))
        repeat(10) { now += 100_000; l.recordFailure("a") }
        assertEquals(8_000, l.retryAfterMs("a"))
        assertEquals(0, l.retryAfterMs("b"))
        l.recordSuccess("a")
        assertEquals(0, l.retryAfterMs("a"))
        assertEquals(3, l.attemptsLeft("a"))
    }

    @Test
    fun `logout clears the unlock cookie`() {
        val s = server(FakeVerifier())
        val r = s.send("POST", "/api/logout", s.sessionCookie, "{}")
        assertEquals(200, r.code)
        assertTrue(r.header("Set-Cookie")!!.contains("Max-Age=0"))
    }

    @Test
    fun `json field reader handles escapes`() {
        assertEquals("a\"b\\c\nd\u00e9", JsonFields.string("{\"x\":\"a\\\"b\\\\c\\nd\\u00e9\"}", "x"))
        assertNull(JsonFields.string("{\"y\":\"1\"}", "x"))
        assertNull(JsonFields.string(null, "x"))
    }

    // ------------------------------------------------------------ feature switches

    @Test
    fun `disabled features answer 403 and never reach the provider`() {
        val offNotifs = FeatureFlags { it != Feature.NOTIFICATIONS && it != Feature.MESSAGES }
        val s = server(flags = offNotifs)
        val r = s.get("/api/notifications")
        assertEquals(403, r.code)
        assertTrue(r.text.contains("feature_disabled"))
        assertEquals(403, s.get("/api/sms/threads").code)
        assertEquals(200, s.get("/api/status").code)
        assertEquals(200, s.get("/api/photos").code)
        val session = s.get("/api/session").text
        assertTrue(session, session.contains("\"notifications\":false"))
        assertTrue(session, session.contains("\"photos\":true"))
    }

    @Test
    fun `missing providers answer 404 unavailable`() {
        val s = server(notifications = null, files = null)
        assertEquals(404, s.get("/api/notifications").code)
        assertEquals(404, s.get("/api/files").code)
    }

    // ------------------------------------------------------------ status

    @Test
    fun `status payload`() {
        val s = server()
        val r = s.get("/api/status")
        assertEquals(200, r.code)
        assertTrue(r.text, r.text.contains("\"batteryPercent\":53"))
        assertTrue(r.text.contains("\"charging\":true"))
        assertTrue(r.text.contains("\"name\":\"Reu's phone\""))
    }

    // ------------------------------------------------------------ notifications

    @Test
    fun `notifications list dismiss and reply`() {
        val n = FakeNotifications()
        val s = server(notifications = n)
        val list = s.get("/api/notifications")
        assertEquals(200, list.code)
        assertTrue(list.text.contains("\"connected\":true"))
        assertTrue(list.text.contains("Lunch at 12?"))
        assertTrue(list.text.contains("Something \\\"quoted\\\""))

        assertEquals(200, s.post("/api/notifications/reply", "{\"key\":\"k1\",\"text\":\"Sure!\"}").code)
        assertEquals("k1" to "Sure!", n.replied)
        assertEquals(404, s.post("/api/notifications/reply", "{\"key\":\"k2\",\"text\":\"x\"}").code)
        assertEquals(400, s.post("/api/notifications/reply", "{\"key\":\"k1\"}").code)

        assertEquals(200, s.post("/api/notifications/dismiss", "{\"key\":\"k1\"}").code)
        assertEquals(404, s.post("/api/notifications/dismiss", "{\"key\":\"k1\"}").code)
        assertFalse(s.get("/api/notifications").text.contains("Lunch"))
    }

    @Test
    fun `notifications report not connected when access is not granted`() {
        val s = server(notifications = FakeNotifications(connected = false))
        val r = s.get("/api/notifications")
        assertTrue(r.text, r.text.contains("\"connected\":false"))
        assertTrue(r.text.contains("\"items\":[]"))
    }

    // ------------------------------------------------------------ messages

    @Test
    fun `sms threads history and send`() {
        val m = FakeMessages()
        val s = server(messages = m)
        assertTrue(s.get("/api/sms/threads").text.contains("\"name\":\"Ana\""))
        val msgs = s.get("/api/sms/threads/7")
        assertEquals(200, msgs.code)
        assertTrue(msgs.text.contains("\"outgoing\":true"))

        assertEquals(200, s.post("/api/sms/send", "{\"to\":\"+1 555-1234\",\"body\":\"On my way\"}").code)
        assertEquals("+1 555-1234" to "On my way", m.sent)
    }

    @Test
    fun `sms validation and permission`() {
        val m = FakeMessages()
        val s = server(messages = m)
        assertEquals(400, s.post("/api/sms/send", "{\"to\":\"abc\",\"body\":\"x\"}").code)
        assertEquals(400, s.post("/api/sms/send", "{\"to\":\"12345\",\"body\":\"\"}").code)
        assertEquals(400, s.post("/api/sms/send", "{\"to\":\"12345\",\"body\":\"${"x".repeat(1001)}\"}").code)
        assertNull(m.sent)

        m.permission = false
        assertEquals(403, s.post("/api/sms/send", "{\"to\":\"12345\",\"body\":\"hi\"}").code)
        assertEquals(403, s.get("/api/sms/threads/7").code)
        assertTrue(s.get("/api/sms/threads").text.contains("\"permission\":false"))
    }

    // ------------------------------------------------------------ files

    private fun multipart(boundary: String, name: String, filename: String, content: String): String =
        "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n$content\r\n--$boundary--\r\n"

    @Test
    fun `file upload list download delete`() {
        val files = FakeFiles()
        val s = server(files = files)
        val b = "XBOUNDARYX"
        val up = s.post("/api/files/upload", multipart(b, "file", "hello world.txt", "contents!"), "multipart/form-data; boundary=$b")
        assertEquals(up.text, 200, up.code)
        assertTrue(up.text, up.text.contains("hello world.txt"))
        assertEquals(1, files.data.size)

        val list = s.get("/api/files")
        assertTrue(list.text.contains("hello world.txt"))
        val dl = s.get("/api/files/1/download")
        assertEquals(200, dl.code)
        assertEquals("contents!", dl.text)
        assertTrue(dl.header("Content-Disposition")!!.contains("hello world.txt"))

        assertEquals(200, s.send("DELETE", "/api/files/1", s.sessionCookie).code)
        assertEquals(404, s.get("/api/files/1/download").code)
    }

    @Test
    fun `upload rejects empty requests and sanitizes names`() {
        val files = FakeFiles()
        val s = server(files = files)
        assertEquals(400, s.post("/api/files/upload", "nothing", "application/json").code)
        val b = "B2"
        val up = s.post("/api/files/upload", multipart(b, "file", "..\\..\\evil/../name.txt", "x"), "multipart/form-data; boundary=$b")
        assertEquals(up.text, 200, up.code)
        assertEquals("name.txt", files.data.values.first().first.name)
    }

    @Test
    fun `file name cleaning`() {
        assertEquals("a.txt", GalleryServer.cleanFileName("/etc/a.txt"))
        assertEquals("b.txt", GalleryServer.cleanFileName("C:\\x\\b.txt"))
        assertEquals("file", GalleryServer.cleanFileName("..."))
        assertEquals("a_b.txt", GalleryServer.cleanFileName("a|b.txt"))
        assertEquals(120, GalleryServer.cleanFileName("x".repeat(500)).length)
    }

    // ------------------------------------------------------------ clipboard + find

    @Test
    fun `clipboard both directions`() {
        val c = FakeClipboard()
        val s = server(clipboard = c)
        assertTrue(s.get("/api/clipboard").text.contains("from phone"))
        assertEquals(200, s.post("/api/clipboard", "{\"text\":\"line1\\nline2\"}").code)
        assertEquals("line1\nline2", c.phone)
        assertEquals(400, s.post("/api/clipboard", "{}").code)
    }

    @Test
    fun `find my phone`() {
        val f = FakeFind()
        val s = server(find = f)
        assertTrue(s.get("/api/find").text.contains("\"ringing\":false"))
        assertEquals(200, s.post("/api/find/ring", "{}").code)
        assertTrue(f.ringing)
        assertTrue(s.get("/api/find").text.contains("\"ringing\":true"))
        assertEquals(200, s.post("/api/find/stop", "{}").code)
        assertFalse(f.ringing)
    }

    @Test
    fun `new endpoints need the session cookie`() {
        val s = server()
        listOf("/api/status", "/api/notifications", "/api/sms/threads", "/api/files", "/api/clipboard", "/api/find", "/api/session").forEach {
            assertEquals(it, 401, s.get(it, cookie = false).code)
        }
        assertEquals(401, s.post("/api/login", "{\"passcode\":\"x\"}", cookie = false).code)
    }
}
