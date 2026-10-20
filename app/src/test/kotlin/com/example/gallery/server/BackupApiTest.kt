package com.example.gallery.server

import com.example.gallery.backup.BackupRules
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBackup(var reachable: Boolean = true) : BackupProvider {
    var url: String? = null
    var secret: String? = null
    var photos = true
    var videos = true
    var wifiOnly = true
    var tested = 0
    var builtIn = false

    override fun settings() = BackupSettings(builtIn || (url != null && secret != null), url?.let(BackupRules::hint).orEmpty(), photos, videos, wifiOnly, builtIn)
    override fun validateUrl(url: String) = BackupRules.validateUrl(url)
    override fun save(url: String?, secret: String?, photos: Boolean, videos: Boolean, wifiOnly: Boolean): Boolean {
        if (url != null) this.url = url
        if (secret != null) this.secret = secret
        this.photos = photos; this.videos = videos; this.wifiOnly = wifiOnly
        return builtIn || (this.url != null && this.secret != null)
    }
    override fun test(): Boolean { tested++; return reachable }
    override fun clear() { url = null; secret = null }
    override fun lastNote() = "1700000000000 waiting: not on Wi-Fi"
}

class BackupApiTest {
    private var srv: TestServer? = null

    @After
    fun tearDown() { srv?.stop() }

    private val goodUrl = "https://script.google.com/macros/s/AKfycbxExample123/exec"
    private val goodSecret = "a-long-secret-word"

    private fun server(provider: BackupProvider? = FakeBackup(), flags: FeatureFlags = FeatureFlags { true }): TestServer =
        TestServer(services = Services(backup = provider, features = flags)).also { srv = it }

    private fun save(s: TestServer, url: String = goodUrl, secret: String = goodSecret, extra: String = "") =
        s.post("/api/backup", "{\"url\":\"$url\",\"secret\":\"$secret\"$extra}")

    // ------------------------------------------------------------ what the dashboard may do

    @Test
    fun `saving stores the options and reports whether the script answered`() {
        val fake = FakeBackup(reachable = true)
        val s = server(fake)
        val r = save(s, extra = ",\"photos\":true,\"videos\":false,\"wifiOnly\":false")
        assertEquals(200, r.code)
        assertTrue(r.text, r.text.contains("\"reachable\":true"))
        assertEquals(goodUrl, fake.url)
        assertEquals(goodSecret, fake.secret)
        assertEquals(listOf(true, false, false), listOf(fake.photos, fake.videos, fake.wifiOnly))
        assertEquals(1, fake.tested)

        fake.reachable = false
        assertTrue(save(s).text.contains("\"reachable\":false"))
    }

    @Test
    fun `the secret is never sent back to the browser`() {
        val s = server()
        save(s)
        val r = s.get("/api/backup")
        assertEquals(200, r.code)
        assertTrue(r.text, r.text.contains("\"configured\":true"))
        assertFalse(r.text, r.text.contains(goodSecret))
        assertFalse(r.text, r.text.contains("secret"))
        assertFalse("only a hint of the address", r.text.contains(goodUrl))
        assertFalse(save(s).text.contains(goodSecret))
    }

    @Test
    fun `options can change without retyping the address or the secret`() {
        val fake = FakeBackup()
        val s = server(fake)
        save(s)
        val r = s.post("/api/backup", "{\"photos\":true,\"videos\":true,\"wifiOnly\":false}")
        assertEquals(200, r.code)
        assertEquals(goodUrl, fake.url)
        assertEquals(goodSecret, fake.secret)
        assertFalse(fake.wifiOnly)
    }

    @Test
    fun `a first save needs both the address and the secret`() {
        val s = server()
        assertEquals(400, s.post("/api/backup", "{\"url\":\"$goodUrl\"}").code)
        s.post("/api/backup/clear", "{}")
        assertEquals(400, s.post("/api/backup", "{\"secret\":\"$goodSecret\"}").code)
        s.post("/api/backup/clear", "{}")
        assertEquals(400, s.post("/api/backup", "{}").code)
    }

    @Test
    fun `addresses that are not an Apps Script web app are refused`() {
        val fake = FakeBackup()
        val s = server(fake)
        for (bad in listOf(
            "http://script.google.com/macros/s/AKfycbxExample123/exec",
            "https://evil.example.com/macros/s/AKfycbxExample123/exec",
            "https://script.google.com.evil.com/macros/s/AKfycbxExample123/exec",
            "https://script.google.com/macros/s/AKfycbxExample123/dev",
            "https://script.google.com/macros/s/AKfycbxExample123/exec?x=1",
            "https://user@script.google.com/macros/s/AKfycbxExample123/exec",
            "https://script.google.com:8443/macros/s/AKfycbxExample123/exec",
            "ftp://script.google.com/exec",
            "not a link",
        )) {
            assertEquals(bad, 400, save(s, url = bad).code)
        }
        assertNull(fake.url)
        assertEquals(200, save(s, url = "https://script.google.com/a/macros/example.com/s/AKfycbxExample123/exec").code)
    }

    @Test
    fun `weak secrets and empty choices are refused`() {
        val s = server()
        assertEquals(400, save(s, secret = "short").code)
        assertEquals(400, save(s, extra = ",\"photos\":false,\"videos\":false").code)
    }

    @Test
    fun `a built-in script needs no setup and the dashboard is told so`() {
        val fake = FakeBackup().also { it.builtIn = true }
        val s = server(fake)
        val r = s.get("/api/backup")
        assertTrue(r.text, r.text.contains("\"configured\":true"))
        assertTrue(r.text, r.text.contains("\"builtIn\":true"))
        // options can be changed with nothing typed in
        assertEquals(200, s.post("/api/backup", "{\"photos\":true,\"videos\":false,\"wifiOnly\":true}").code)
        assertFalse(fake.videos)
    }

    @Test
    fun `a hidden troubleshooting address says what the last pass did`() {
        val s = server()
        val r = s.get("/api/backup/diag")
        assertEquals(200, r.code)
        assertTrue(r.text, r.text.contains("waiting: not on Wi-Fi"))
        assertFalse(r.text.contains("secret"))
    }

    @Test
    fun `clearing forgets the setup`() {
        val fake = FakeBackup()
        val s = server(fake)
        save(s)
        assertEquals(200, s.post("/api/backup/clear", "{}").code)
        assertTrue(s.get("/api/backup").text.contains("\"configured\":false"))
    }

    // ------------------------------------------------------------ gates

    @Test
    fun `the feature switch and the login cookie are enforced`() {
        val off = server(flags = FeatureFlags { it != Feature.BACKUP })
        assertEquals(403, off.get("/api/backup").code)
        assertEquals(403, save(off).code)
        assertEquals(403, off.post("/api/backup/clear", "{}").code)
        assertEquals(401, off.get("/api/backup", cookie = false).code)
        assertEquals(200, off.get("/api/albums").code)
        assertTrue(off.get("/api/session").text.contains("\"backup\":false"))
    }

    @Test
    fun `without a provider the endpoint says unavailable`() {
        assertEquals(404, server(provider = null).get("/api/backup").code)
    }

    @Test
    fun `the backup switch exists and shows in the session`() {
        assertNotNull(Feature.values().firstOrNull { it.key == "backup" })
        val on = server()
        assertTrue(on.get("/api/session").text.contains("\"backup\":true"))
    }

    // ------------------------------------------------------------ rules

    @Test
    fun `address and secret rules`() {
        assertNull(BackupRules.validateUrl(goodUrl))
        assertNull(BackupRules.validateUrl("  $goodUrl  "))
        assertNotNull(BackupRules.validateUrl(""))
        assertNotNull(BackupRules.validateUrl("https://script.google.com/exec"))
        assertNotNull(BackupRules.validateUrl(goodUrl + "x".repeat(400)))
        assertNull(BackupRules.validateSecret("12345678"))
        assertNotNull(BackupRules.validateSecret("1234567"))
        assertNotNull(BackupRules.validateSecret("tab\tinside-secret"))
        assertNotNull(BackupRules.validateSecret("x".repeat(201)))
    }

    @Test
    fun `only Google hosts over https are ever contacted`() {
        assertTrue(BackupRules.isAllowedEndpoint(URL(goodUrl)))
        assertTrue(BackupRules.isAllowedEndpoint(URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=x")))
        assertTrue(BackupRules.isAllowedEndpoint(URL("https://script.googleusercontent.com/macros/echo?user_content_key=x")))
        assertFalse(BackupRules.isAllowedEndpoint(URL("http://www.googleapis.com/upload")))
        assertFalse(BackupRules.isAllowedEndpoint(URL("https://googleapis.com.evil.com/upload")))
        assertFalse(BackupRules.isAllowedEndpoint(URL("https://evilgoogleapis.com/upload")))
        assertFalse(BackupRules.isAllowedEndpoint(URL("https://192.168.1.5/upload")))
        assertFalse(BackupRules.isAllowedEndpoint(URL("https://script.google.com.evil.com/exec")))
    }

    @Test
    fun `the hint never shows the whole address`() {
        val hint = BackupRules.hint(goodUrl)
        assertTrue(hint, hint.startsWith("…"))
        assertTrue(hint.length < goodUrl.length)
        assertTrue(goodUrl.endsWith(hint.removePrefix("…")))
    }
}
