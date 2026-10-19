package com.example.gallery.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeContacts(var permission: Boolean = true) : ContactsProvider {
    override fun hasPermission() = permission
    override fun list() = listOf(
        Contact(1, "Ana Reyes", listOf("+63 917 555 0142", "+63 2 8555 0190"), listOf("ana@example.com")),
        Contact(2, "Mama \"M\"", listOf("+63 917 555 0100"), emptyList()),
    )
}

private class FakeOpen : OpenProvider {
    var last: Pair<String, String>? = null
    override fun open(kind: String, value: String): Boolean { last = kind to value; return true }
}

class LayoutApiTest {
    private var srv: TestServer? = null

    @After
    fun tearDown() { srv?.stop() }

    private fun photos() = listOf(
        photo(1, "a.jpg", date = 1000),
        photo(2, "b.jpg", date = 2000),
        photo(3, "Screenshot_3.png", date = 3000, screenshot = true),
        photo(4, "Screenshot_4.png", date = 4000, screenshot = true),
        photo(5, "c.jpg", date = 5000),
    )

    private fun server(
        flags: FeatureFlags = FeatureFlags { true },
        contacts: ContactsProvider? = FakeContacts(),
        open: OpenProvider? = FakeOpen(),
    ): TestServer = TestServer(
        photos(),
        services = Services(contacts = contacts, open = open, features = flags),
    ).also { srv = it }

    // ------------------------------------------------------------ albums

    @Test
    fun `albums list folders biggest first with the total`() {
        val s = server()
        val r = s.get("/api/albums")
        assertEquals(200, r.code)
        assertTrue(r.text, r.text.contains("\"total\":5"))
        val camera = r.text.indexOf("\"name\":\"Camera\"")
        val shots = r.text.indexOf("\"name\":\"Screenshots\"")
        assertTrue(r.text, camera in 0 until shots)
        assertTrue(r.text.contains("\"count\":3"))
        assertTrue(r.text.contains("\"count\":2"))
    }

    @Test
    fun `bucket filter limits the photo list and total`() {
        val s = server()
        val r = s.get("/api/photos?bucket=Screenshots&limit=50")
        assertEquals(200, r.code)
        assertEquals(listOf("4", "3"), Regex("\"id\":(\\d+)").findAll(r.text).map { it.groupValues[1] }.toList())
        assertTrue(r.text.contains("\"total\":2"))
        val none = s.get("/api/photos?bucket=Nope")
        assertTrue(none.text.contains("\"total\":0"))
    }

    @Test
    fun `catalog albums group blank folders as Other`() {
        val c = PhotoCatalog { listOf(photo(1).copy(bucket = ""), photo(2).copy(bucket = ""), photo(3)) }
        val albums = c.albums()
        assertEquals(Album("Other", 2), albums[0])
        assertEquals(Album("Camera", 1), albums[1])
        assertEquals(3, c.total())
    }

    // ------------------------------------------------------------ contacts

    @Test
    fun `contacts are listed with numbers and emails`() {
        val s = server()
        val r = s.get("/api/contacts")
        assertEquals(200, r.code)
        assertTrue(r.text.contains("\"permission\":true"))
        assertTrue(r.text.contains("\"name\":\"Ana Reyes\""))
        assertTrue(r.text.contains("+63 2 8555 0190"))
        assertTrue(r.text.contains("ana@example.com"))
        assertTrue(r.text, r.text.contains("Mama \\\"M\\\""))
    }

    @Test
    fun `contacts report missing permission and respect the feature switch`() {
        val noPerm = server(contacts = FakeContacts(permission = false))
        val r = noPerm.get("/api/contacts")
        assertTrue(r.text, r.text.contains("\"permission\":false"))
        assertTrue(r.text.contains("\"items\":[]"))
        noPerm.stop()

        val off = server(flags = FeatureFlags { it != Feature.CONTACTS })
        assertEquals(403, off.get("/api/contacts").code)
        assertEquals(200, off.get("/api/albums").code)
        off.stop()

        val none = server(contacts = null)
        assertEquals(404, none.get("/api/contacts").code)
    }

    // ------------------------------------------------------------ open on phone

    @Test
    fun `open validation rules`() {
        assertNull(OpenRequests.validate("link", "https://example.com/a?b=1"))
        assertNull(OpenRequests.validate("link", "HTTP://example.com"))
        assertNotNull(OpenRequests.validate("link", "ftp://example.com"))
        assertNotNull(OpenRequests.validate("link", "javascript:alert(1)"))
        assertNotNull(OpenRequests.validate("link", "https://"))
        assertNotNull(OpenRequests.validate("link", "https://exa mple.com"))
        assertNotNull(OpenRequests.validate("link", ""))
        assertNotNull(OpenRequests.validate("link", "https://example.com/" + "a".repeat(2100)))
        assertNull(OpenRequests.validate("dial", "+63 917 555 0142"))
        assertNotNull(OpenRequests.validate("dial", "call me"))
        assertNull(OpenRequests.validate("map", "Ayala Avenue, Makati"))
        assertNotNull(OpenRequests.validate("map", ""))
        assertNotNull(OpenRequests.validate("nope", "x"))
        assertNotNull(OpenRequests.validate(null, "x"))
    }

    @Test
    fun `open endpoint passes valid requests to the phone and rejects bad ones`() {
        val provider = FakeOpen()
        val s = server(open = provider)
        assertEquals(200, s.post("/api/open", "{\"kind\":\"link\",\"value\":\"https://example.com\"}").code)
        assertEquals("link" to "https://example.com", provider.last)
        assertEquals(200, s.post("/api/open", "{\"kind\":\"dial\",\"value\":\"+63 917 555 0142\"}").code)
        assertEquals("dial" to "+63 917 555 0142", provider.last)

        provider.last = null
        val bad = s.post("/api/open", "{\"kind\":\"link\",\"value\":\"javascript:alert(1)\"}")
        assertEquals(400, bad.code)
        assertTrue(bad.text.contains("error"))
        assertEquals(400, s.post("/api/open", "{}").code)
        assertNull(provider.last)
    }

    @Test
    fun `open is a find-my-phone feature and needs the cookie`() {
        val off = server(flags = FeatureFlags { it != Feature.FIND })
        assertEquals(403, off.post("/api/open", "{\"kind\":\"map\",\"value\":\"Makati\"}").code)
        assertEquals(401, off.post("/api/open", "{\"kind\":\"map\",\"value\":\"Makati\"}", cookie = false).code)
        assertFalse(off.get("/api/session").text.contains("\"contacts\":false"))
    }
}
