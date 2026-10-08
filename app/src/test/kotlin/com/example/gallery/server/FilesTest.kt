package com.example.gallery.server

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class FilesTest {
    private var srv: TestServer? = null

    @After
    fun tearDown() { srv?.stop() }

    private val bytes = ByteArray(100) { it.toByte() }

    private fun server(extra: (FakeStore) -> Unit = {}): TestServer {
        val store = FakeStore()
        store.files[1] = bytes
        store.files[2] = "second".toByteArray()
        store.files[3] = "third".toByteArray()
        extra(store)
        val photos = listOf(photo(1, "a.jpg"), photo(2, "b.jpg"), photo(3, "a.jpg"))
        return TestServer(photos, store).also { srv = it }
    }

    // ------------------------------------------------------------ range requests

    @Test
    fun `full request advertises range support`() {
        val r = server().get("/api/photos/1/full")
        assertEquals(200, r.code)
        assertEquals("bytes", r.header("Accept-Ranges"))
        assertArrayEquals(bytes, r.body)
    }

    @Test
    fun `bounded range returns 206 with the exact slice`() {
        val r = server().get("/api/photos/1/full", headers = mapOf("Range" to "bytes=10-19"))
        assertEquals(206, r.code)
        assertEquals("bytes 10-19/100", r.header("Content-Range"))
        assertArrayEquals(bytes.copyOfRange(10, 20), r.body)
    }

    @Test
    fun `open ended and suffix ranges`() {
        val s = server()
        val open = s.get("/api/photos/1/full", headers = mapOf("Range" to "bytes=90-"))
        assertEquals(206, open.code)
        assertArrayEquals(bytes.copyOfRange(90, 100), open.body)

        val suffix = s.get("/api/photos/1/full", headers = mapOf("Range" to "bytes=-5"))
        assertEquals(206, suffix.code)
        assertEquals("bytes 95-99/100", suffix.header("Content-Range"))
        assertArrayEquals(bytes.copyOfRange(95, 100), suffix.body)
    }

    @Test
    fun `end beyond file is clamped and start beyond file is 416`() {
        val s = server()
        val clamped = s.get("/api/photos/1/full", headers = mapOf("Range" to "bytes=95-9999"))
        assertEquals(206, clamped.code)
        assertEquals("bytes 95-99/100", clamped.header("Content-Range"))

        val bad = s.get("/api/photos/1/full", headers = mapOf("Range" to "bytes=100-"))
        assertEquals(416, bad.code)
        assertEquals("bytes */100", bad.header("Content-Range"))
    }

    @Test
    fun `range parser edge cases`() {
        assertEquals(RangeResult.None, RangeParser.parse(null, 10))
        assertEquals(RangeResult.None, RangeParser.parse("items=0-1", 10))
        assertEquals(RangeResult.None, RangeParser.parse("bytes=abc", 10))
        assertEquals(RangeResult.None, RangeParser.parse("bytes=5-2", 10))
        assertEquals(RangeResult.None, RangeParser.parse("bytes=0-1,4-5", 10))
        assertEquals(RangeResult.Unsatisfiable, RangeParser.parse("bytes=-0", 10))
        assertEquals(RangeResult.Unsatisfiable, RangeParser.parse("bytes=0-1", 0))
        assertEquals(RangeResult.Ok(0, 9), RangeParser.parse("bytes=-50", 10))
        assertEquals(RangeResult.Ok(3, 3), RangeParser.parse("bytes=3-3", 10))
    }

    // ------------------------------------------------------------ single download, thumbs

    @Test
    fun `download sets attachment disposition with original filename`() {
        val r = server().get("/api/photos/2/download")
        assertEquals(200, r.code)
        val cd = r.header("Content-Disposition")!!
        assertTrue(cd, cd.startsWith("attachment;"))
        assertTrue(cd, cd.contains("b.jpg"))
        assertEquals("second", r.text)
    }

    @Test
    fun `content disposition handles unicode and quotes safely`() {
        val cd = GalleryServer.contentDisposition("Fête \"été\".jpg")
        assertTrue(cd, cd.contains("filename*=UTF-8''F%C3%AAte%20_%C3%A9t%C3%A9_.jpg"))
        assertTrue(cd, !cd.contains("\"été\""))
    }

    @Test
    fun `thumbnail and missing photos`() {
        val s = server()
        val t = s.get("/api/photos/1/thumb")
        assertEquals(200, t.code)
        assertEquals("image/jpeg", t.header("Content-Type"))
        assertEquals(404, s.get("/api/photos/999/thumb").code)
        assertEquals(404, s.get("/api/photos/999/full").code)
        assertEquals(404, s.get("/api/photos/999/download").code)
    }

    @Test
    fun `heic is served as a jpeg rendition for the viewer but downloads the original`() {
        val store = FakeStore().apply { files[7] = "heic-bytes".toByteArray() }
        val s = TestServer(listOf(photo(7, "x.heic", mime = "image/heic")), store).also { srv = it }
        val full = s.get("/api/photos/7/full")
        assertEquals("image/jpeg", full.header("Content-Type"))
        assertArrayEquals(byteArrayOf(9, 9), full.body)
        assertEquals("heic-bytes", s.get("/api/photos/7/download").text)
    }

    // ------------------------------------------------------------ bulk download / zip

    private fun unzip(body: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(body)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                out[e.name] = zin.readBytes()
            }
        }
        return out
    }

    @Test
    fun `single id returns the file directly`() {
        val r = server().post("/api/download", "{\"ids\":[2]}")
        assertEquals(200, r.code)
        assertEquals("second", r.text)
        assertTrue(r.header("Content-Disposition")!!.contains("b.jpg"))
    }

    @Test
    fun `several ids stream a dated zip with deduplicated names`() {
        val r = server().post("/api/download", "{\"ids\":[1,2,3]}")
        assertEquals(200, r.code)
        assertEquals("application/zip", r.header("Content-Type"))
        assertTrue(r.header("Content-Disposition")!!.contains("gallery-2026-01-01.zip"))
        val entries = unzip(r.body)
        assertEquals(listOf("a.jpg", "b.jpg", "a (1).jpg"), entries.keys.toList())
        assertArrayEquals(bytes, entries["a.jpg"])
        assertEquals("second", String(entries["b.jpg"]!!))
        assertEquals("third", String(entries["a (1).jpg"]!!))
    }

    @Test
    fun `form encoded ids work for native browser downloads`() {
        val r = server().post("/api/download", "ids=2,3", contentType = "application/x-www-form-urlencoded")
        assertEquals(200, r.code)
        assertEquals(setOf("b.jpg", "a.jpg"), unzip(r.body).keys)
    }

    @Test
    fun `bad download requests`() {
        val s = server()
        assertEquals(400, s.post("/api/download", "{\"ids\":[]}").code)
        assertEquals(400, s.post("/api/download", "nonsense").code)
        assertEquals(404, s.post("/api/download", "{\"ids\":[998,999]}").code)
    }

    @Test
    fun `zip streaming does not buffer the whole archive and stops when the client leaves`() {
        val finished = CountDownLatch(1)
        val produced = java.util.concurrent.atomic.AtomicLong()
        // 400 MB of source data: far more than the pipe buffer. If this were buffered we'd see it all produced.
        val infinite = object : InputStream() {
            override fun read(): Int { produced.incrementAndGet(); return 7 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                produced.addAndGet(len.toLong())
                if (produced.get() > 400L * 1024 * 1024) return -1
                java.util.Arrays.fill(b, off, off + len, 7)
                return len
            }
            override fun close() { finished.countDown() }
        }
        val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        val zin = ZipStreamer(pool).stream(listOf(ZipSource("big.bin", 0) { infinite }))

        val head = ByteArray(1024)
        var read = 0
        while (read < head.size) {
            val n = zin.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        assertEquals(1024, read)
        Thread.sleep(300)
        assertTrue("producer ran ahead: ${produced.get()}", produced.get() < 20L * 1024 * 1024)

        zin.close() // client disconnects
        assertTrue("source was not released", finished.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun `unique names helper`() {
        assertEquals(
            listOf("a.jpg", "a (1).jpg", "A (2).JPG", "noext", "noext (1)"),
            ZipStreamer.uniqueNames(listOf("a.jpg", "a.jpg", "A.JPG", "noext", "noext")),
        )
        assertEquals(listOf("x_y.jpg"), ZipStreamer.uniqueNames(listOf("x/y.jpg")))
    }

    // ------------------------------------------------------------ trash

    @Test
    fun `trash request is accepted and reports status`() {
        val s = server()
        val r = s.post("/api/trash", "{\"ids\":[1,2]}")
        assertEquals(202, r.code)
        assertTrue(r.text.contains("\"pending\""))
        assertEquals(listOf(1L, 2L), s.trash.requested)

        assertTrue(s.get("/api/trash/status").text.contains("\"pending\""))
        s.trash.current = "done"
        assertTrue(s.get("/api/trash/status").text.contains("\"done\""))
        s.trash.current = "denied"
        assertTrue(s.get("/api/trash/status").text.contains("\"denied\""))
    }

    @Test
    fun `trash ignores unknown ids and reports failures`() {
        val s = server()
        assertEquals(404, s.post("/api/trash", "{\"ids\":[555]}").code)
        assertEquals(400, s.post("/api/trash", "{}").code)
        assertNull(s.trash.requested)
        s.trash.accept = false
        assertEquals(500, s.post("/api/trash", "{\"ids\":[1]}").code)
    }

    // ------------------------------------------------------------ json helpers

    @Test
    fun `json escaping and id parsing`() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", Json.str("a\"b\\c\n\u0001"))
        assertEquals(listOf(1L, 22L, 333L), Json.parseIds("{\"ids\":[1, 22,333]}"))
        assertEquals(listOf(4L, 5L), Json.parseIds("4,5,4"))
        assertEquals(emptyList<Long>(), Json.parseIds(null))
    }
}
