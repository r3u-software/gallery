package com.example.gallery.server

/**
 * In-memory index of photo metadata with keyset (cursor) pagination.
 *
 * The loader is called once after construction / [invalidate]; with 10k+ photos the
 * metadata is a few MB, and pages are then served by binary search, so paging cost
 * does not grow with the cursor position.
 */
class PhotoCatalog(private val loader: () -> List<Photo>) {

    private val lock = Any()
    private var newest: List<Photo>? = null
    private var byId: Map<Long, Photo> = emptyMap()
    private val views = HashMap<Pair<SortOrder, PhotoFilter>, List<Photo>>()

    fun invalidate() = synchronized(lock) {
        newest = null
        byId = emptyMap()
        views.clear()
    }

    fun find(id: Long): Photo? = synchronized(lock) {
        ensureLoaded()
        byId[id]
    }

    fun page(cursor: String?, limit: Int, sort: SortOrder, filter: PhotoFilter): PhotoPage =
        synchronized(lock) {
            val view = view(sort, filter)
            val start = if (cursor.isNullOrEmpty()) 0 else firstAfter(view, parseCursor(cursor), sort)
            val size = limit.coerceIn(1, MAX_LIMIT)
            val end = minOf(view.size, start + size)
            val items = if (start >= end) emptyList() else view.subList(start, end).toList()
            val next = if (end < view.size && items.isNotEmpty()) cursorOf(items.last()) else null
            PhotoPage(items, next, view.size)
        }

    // --- internals (call with lock held) ---

    private fun ensureLoaded(): List<Photo> {
        newest?.let { return it }
        val sorted = loader().sortedWith(NEWEST_FIRST)
        newest = sorted
        byId = sorted.associateBy { it.id }
        return sorted
    }

    private fun view(sort: SortOrder, filter: PhotoFilter): List<Photo> {
        val all = ensureLoaded()
        return views.getOrPut(sort to filter) {
            val filtered = if (filter == PhotoFilter.ALL) all else all.filter { filter.matches(it) }
            if (sort == SortOrder.NEWEST) filtered else filtered.asReversed().toList()
        }
    }

    /** Index of the first element that sorts strictly after [key] in the given order. */
    private fun firstAfter(view: List<Photo>, key: Pair<Long, Long>, sort: SortOrder): Int {
        val cmp: (Photo) -> Int = { p ->
            val c = compareValues(p.dateTaken, key.first).takeIf { it != 0 } ?: compareValues(p.id, key.second)
            if (sort == SortOrder.NEWEST) -c else c
        }
        var lo = 0
        var hi = view.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cmp(view[mid]) > 0) hi = mid else lo = mid + 1
        }
        return lo
    }

    companion object {
        const val MAX_LIMIT = 2000
        const val DEFAULT_LIMIT = 500

        private val NEWEST_FIRST: Comparator<Photo> =
            compareByDescending<Photo> { it.dateTaken }.thenByDescending { it.id }

        fun cursorOf(p: Photo): String = "${p.dateTaken}_${p.id}"

        fun parseCursor(c: String): Pair<Long, Long> {
            val parts = c.split('_')
            require(parts.size == 2) { "bad cursor" }
            return parts[0].toLong() to parts[1].toLong()
        }
    }
}
