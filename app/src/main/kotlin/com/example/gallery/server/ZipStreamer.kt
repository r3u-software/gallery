package com.example.gallery.server

import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executor
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ZipSource(val name: String, val time: Long, val open: () -> InputStream?)

/**
 * Streams a zip archive through a pipe: a worker thread writes entries while the HTTP
 * layer reads, so memory use is bounded by the pipe buffer, not by the archive size.
 * Photos are already compressed, so entries are written without compression (fast).
 */
class ZipStreamer(private val executor: Executor) {

    fun stream(sources: List<ZipSource>): InputStream {
        val input = PipedInputStream(PIPE_BUFFER)
        val output = PipedOutputStream(input)
        executor.execute {
            try {
                ZipOutputStream(output).use { zip ->
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    val names = uniqueNames(sources.map { it.name })
                    val buf = ByteArray(COPY_BUFFER)
                    sources.forEachIndexed { i, src ->
                        val stream = src.open() ?: return@forEachIndexed
                        stream.use { ins ->
                            val entry = ZipEntry(names[i])
                            if (src.time > 0) entry.time = src.time
                            zip.putNextEntry(entry)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                zip.write(buf, 0, n)
                            }
                            zip.closeEntry()
                        }
                    }
                }
            } catch (_: Exception) {
                // Client went away (pipe closed): nothing more to do.
                try { output.close() } catch (_: IOException) {}
            }
        }
        return input
    }

    companion object {
        private const val PIPE_BUFFER = 256 * 1024
        private const val COPY_BUFFER = 64 * 1024

        /** "a.jpg", "a.jpg" -> "a.jpg", "a (1).jpg" */
        fun uniqueNames(names: List<String>): List<String> {
            val seen = HashSet<String>()
            return names.map { raw ->
                val safe = raw.replace('\\', '_').replace('/', '_').ifBlank { "photo" }
                var candidate = safe
                var n = 1
                while (!seen.add(candidate.lowercase())) {
                    val dot = safe.lastIndexOf('.')
                    candidate = if (dot > 0) "${safe.substring(0, dot)} ($n)${safe.substring(dot)}" else "$safe ($n)"
                    n++
                }
                candidate
            }
        }
    }
}
