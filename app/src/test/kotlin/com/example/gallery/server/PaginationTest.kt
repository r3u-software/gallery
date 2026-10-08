package com.example.gallery.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationTest {
    private var srv: TestServer? = null

    @After
    fun tearDown() { srv?.stop() }

    private fun idsOf(json: String): List<Long> =
        Regex("\"id\":(\\d+)").findAll(json).map { it.groupValues[1].toLong() }.toList()

    private fun nextCursor(json: String): String? =
        Regex("\"nextCursor\":(null|\"([^\"]*)\")").find(json)!!.groupValues[2].ifEmpty { null }

    private fun total(json: String): Int = Regex("\"total\":(\\d+)").find(json)!!.groupValues[1].toInt()

    private fun walk(s: TestServer, query: String, limit: Int): List<Long> {
        val all = ArrayList<Long>()
        var cursor: String? = null
        var guard = 0
        do {
            val r = s.get("/api/photos?limit=$limit&$query" + (cursor?.let { "&cursor=$it" } ?: ""))
            assertEquals(200, r.code)
            all += idsOf(r.text)
            cursor = nextCursor(r.text)
            assertTrue("pagination did not terminate", ++guard < 100_000)
        } while (cursor != null)
        return all
    }

    @Test
    fun `pages cover everything newest first without duplicates`() {
        val s = TestServer((1L..25L).map { photo(it) }).also { srv = it }
        val ids = walk(s, "sort=newest", 10)
        assertEquals((25L downTo 1L).toList(), ids)
    }

    @Test
    fun `oldest first reverses the order`() {
        val s = TestServer((1L..25L).map { photo(it) }).also { srv = it }
        assertEquals((1L..25L).toList(), walk(s, "sort=oldest", 7))
    }

    @Test
    fun `equal timestamps are ordered by id and never skipped`() {
        val photos = (1L..30L).map { photo(it, date = 5000) }
        val s = TestServer(photos).also { srv = it }
        assertEquals((30L downTo 1L).toList(), walk(s, "sort=newest", 4))
        assertEquals((1L..30L).toList(), walk(s, "sort=oldest", 4))
    }

    @Test
    fun `filters apply and total reflects the filter`() {
        val photos = (1L..20L).map { id ->
            photo(id, screenshot = id % 4 == 0L, size = if (id % 5 == 0L) 6L * 1024 * 1024 else 1000)
        }
        val s = TestServer(photos).also { srv = it }

        val shots = s.get("/api/photos?filter=screenshots&limit=100")
        assertEquals(listOf(20L, 16L, 12L, 8L, 4L), idsOf(shots.text))
        assertEquals(5, total(shots.text))

        val large = s.get("/api/photos?filter=large&limit=100")
        assertEquals(listOf(20L, 15L, 10L, 5L), idsOf(large.text))
        assertEquals(4, total(large.text))

        assertEquals(20, total(s.get("/api/photos?filter=all&limit=1").text))
    }

    @Test
    fun `last page has null cursor and empty library works`() {
        val s = TestServer((1L..3L).map { photo(it) }).also { srv = it }
        val r = s.get("/api/photos?limit=10")
        assertNull(nextCursor(r.text))

        val empty = TestServer(emptyList())
        try {
            val e = empty.get("/api/photos")
            assertEquals(200, e.code)
            assertEquals(0, total(e.text))
            assertNull(nextCursor(e.text))
        } finally { empty.stop() }
    }

    @Test
    fun `malformed cursor is a 400`() {
        val s = TestServer((1L..3L).map { photo(it) }).also { srv = it }
        assertEquals(400, s.get("/api/photos?cursor=garbage").code)
    }

    @Test
    fun `cursor continues strictly after the last item of the previous page`() {
        val photos = (1L..10L).toMutableList()
        val s = TestServer(photos.map { photo(it) }).also { srv = it }
        val first = s.get("/api/photos?limit=3")
        val cursor = nextCursor(first.text)!!
        val second = s.get("/api/photos?limit=3&cursor=$cursor")
        assertEquals(listOf(7L, 6L, 5L), idsOf(second.text))
    }

    @Test
    fun `ten thousand photos page through quickly`() {
        val photos = (1L..12_000L).map { photo(it, date = (it * 37) % 9_999, screenshot = it % 10 == 0L) }
        val s = TestServer(photos).also { srv = it }
        val started = System.nanoTime()
        val ids = walk(s, "sort=newest", 1000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(12_000, ids.size)
        assertEquals(12_000, ids.toSet().size)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 20_000)
        assertEquals(1200, total(s.get("/api/photos?filter=screenshots&limit=1").text))
    }

    @Test
    fun `limit is clamped`() {
        val s = TestServer((1L..5L).map { photo(it) }).also { srv = it }
        assertEquals(1, idsOf(s.get("/api/photos?limit=0").text).size)
        assertEquals(5, idsOf(s.get("/api/photos?limit=99999").text).size)
    }

    @Test
    fun `invalidate reloads from the loader`() {
        var data = listOf(photo(1), photo(2))
        val catalog = PhotoCatalog { data }
        assertEquals(2, catalog.page(null, 10, SortOrder.NEWEST, PhotoFilter.ALL).total)
        data = listOf(photo(1))
        assertEquals(2, catalog.page(null, 10, SortOrder.NEWEST, PhotoFilter.ALL).total)
        catalog.invalidate()
        assertEquals(1, catalog.page(null, 10, SortOrder.NEWEST, PhotoFilter.ALL).total)
    }
}
