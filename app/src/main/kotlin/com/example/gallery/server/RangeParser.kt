package com.example.gallery.server

import java.io.FilterInputStream
import java.io.InputStream

sealed interface RangeResult {
    /** No (usable) Range header: serve the whole file with 200. */
    data object None : RangeResult

    /** Valid but outside the file: respond 416. */
    data object Unsatisfiable : RangeResult

    /** Inclusive byte range. */
    data class Ok(val start: Long, val end: Long) : RangeResult {
        val length: Long get() = end - start + 1
    }
}

object RangeParser {
    /** Parses a single `bytes=` range. Multi-range requests are served as the full file (allowed by RFC 7233). */
    fun parse(header: String?, size: Long): RangeResult {
        if (header == null) return RangeResult.None
        val h = header.trim()
        if (!h.startsWith("bytes=", ignoreCase = true)) return RangeResult.None
        val spec = h.substring(6).trim()
        if (spec.contains(',')) return RangeResult.None
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.None
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()

        if (a.isEmpty()) {
            // suffix range: last n bytes
            val n = b.toLongOrNull() ?: return RangeResult.None
            if (n <= 0 || size == 0L) return RangeResult.Unsatisfiable
            return RangeResult.Ok(maxOf(0L, size - n), size - 1)
        }
        val start = a.toLongOrNull() ?: return RangeResult.None
        if (start < 0) return RangeResult.None
        if (start >= size) return RangeResult.Unsatisfiable
        val end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return RangeResult.None)
        if (end < start) return RangeResult.None
        return RangeResult.Ok(start, minOf(end, size - 1))
    }
}

/** Reads at most [limit] bytes from [delegate]; closing it closes the delegate. */
class LimitedInputStream(delegate: InputStream, private var remaining: Long) : FilterInputStream(delegate) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
}
