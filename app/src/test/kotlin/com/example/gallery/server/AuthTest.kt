package com.example.gallery.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {
    private var srv: TestServer? = null

    @After
    fun tearDown() { srv?.stop() }

    private fun server(allowed: (String?) -> Boolean = { true }) =
        TestServer(listOf(photo(1)), allowed = allowed).also { srv = it }

    @Test
    fun `no cookie means 401 everywhere`() {
        val s = server()
        assertEquals(401, s.get("/", cookie = false).code)
        assertEquals(401, s.get("/api/photos", cookie = false).code)
        assertEquals(401, s.get("/api/photos/1/thumb", cookie = false).code)
        assertEquals(401, s.get("/js/app.js", cookie = false).code)
        assertEquals(401, s.post("/api/trash", "{\"ids\":[1]}", cookie = false).code)
        assertEquals(401, s.post("/api/download", "{\"ids\":[1]}", cookie = false).code)
    }

    @Test
    fun `wrong cookie value is rejected`() {
        val s = server()
        val r = s.get("/api/photos", cookie = false, headers = mapOf("Cookie" to "${GalleryServer.COOKIE_NAME}=nope"))
        assertEquals(401, r.code)
    }

    @Test
    fun `token in query is not accepted on api routes`() {
        val s = server()
        assertEquals(401, s.get("/api/photos?t=$TEST_TOKEN", cookie = false).code)
        assertEquals(401, s.get("/js/app.js?t=$TEST_TOKEN", cookie = false).code)
    }

    @Test
    fun `wrong token on root is 401 and sets no cookie`() {
        val s = server()
        val r = s.get("/?t=wrong", cookie = false)
        assertEquals(401, r.code)
        assertFalse(r.headers.containsKey("set-cookie"))
    }

    @Test
    fun `valid token sets HttpOnly SameSite Strict cookie and redirects to root`() {
        val s = server()
        val r = s.get("/?t=$TEST_TOKEN", cookie = false)
        assertEquals(303, r.code)
        assertEquals("/", r.header("Location"))
        val cookie = r.header("Set-Cookie")
        assertNotNull(cookie)
        assertTrue(cookie!!.startsWith("${GalleryServer.COOKIE_NAME}=$TEST_TOKEN"))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Strict"))
        assertTrue(cookie.contains("Path=/"))
    }

    @Test
    fun `with cookie root serves the web ui and static files`() {
        val s = server()
        val index = s.get("/")
        assertEquals(200, index.code)
        assertTrue(index.text.contains("<h1>hi</h1>"))
        assertTrue(index.header("Content-Security-Policy")!!.contains("default-src 'self'"))
        val js = s.get("/js/app.js")
        assertEquals(200, js.code)
        assertTrue(js.header("Content-Type")!!.startsWith("text/javascript"))
    }

    @Test
    fun `path traversal is rejected`() {
        val s = server()
        assertEquals(404, s.get("/..%2F..%2Fsecret").code)
        assertEquals(404, s.get("/js/../../x").code)
    }

    @Test
    fun `clients outside private ranges get 403 even with a valid cookie`() {
        val s = server(allowed = { false })
        assertEquals(403, s.get("/api/photos").code)
        assertEquals(403, s.get("/?t=$TEST_TOKEN", cookie = false).code)
    }

    @Test
    fun `private network classification`() {
        listOf("10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.20", "127.0.0.1", "169.254.1.1", "::1", "fd00::1", "fe80::1%wlan0", "::ffff:192.168.1.5")
            .forEach { assertTrue("$it should be private", PrivateNetwork.isPrivate(it)) }
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.1.1", "11.0.0.1", "100.64.0.1", "2001:4860:4860::8888", "", null, "example.com", "192.168.1.1.evil.com")
            .forEach { assertFalse("$it should not be private", PrivateNetwork.isPrivate(it)) }
    }
}
