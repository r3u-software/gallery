package com.example.gallery.server

import java.io.InputStream

const val LARGE_FILE_BYTES: Long = 5L * 1024 * 1024

data class Photo(
    val id: Long,
    val name: String,
    /** Epoch millis; falls back to file modification time when EXIF date is missing. */
    val dateTaken: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val bucket: String,
    val mime: String,
    val isScreenshot: Boolean = false,
)

enum class SortOrder {
    NEWEST, OLDEST;

    companion object {
        fun parse(s: String?): SortOrder = if (s.equals("oldest", ignoreCase = true)) OLDEST else NEWEST
    }
}

enum class PhotoFilter {
    ALL, SCREENSHOTS, LARGE;

    fun matches(p: Photo): Boolean = when (this) {
        ALL -> true
        SCREENSHOTS -> p.isScreenshot
        LARGE -> p.size > LARGE_FILE_BYTES
    }

    companion object {
        fun parse(s: String?): PhotoFilter = when (s?.lowercase()) {
            "screenshots" -> SCREENSHOTS
            "large" -> LARGE
            else -> ALL
        }
    }
}

data class PhotoPage(val items: List<Photo>, val nextCursor: String?, val total: Int)

/** Everything the server needs from the device's photo storage. Implemented over MediaStore on Android. */
interface PhotoStore {
    /** Total byte length of the original file, or null if it can't be opened. */
    fun length(id: Long): Long?

    /** Opens the original file positioned at [offset]. Caller closes the stream. */
    fun open(id: Long, offset: Long): InputStream?

    /** Small JPEG thumbnail with EXIF orientation applied. */
    fun thumbnail(id: Long): ByteArray?

    /** Large JPEG rendition for formats browsers can't show (HEIC etc.). */
    fun renderLarge(id: Long): ByteArray?
}

/** Moves photos to the system trash. Needs user confirmation on the phone, so it is asynchronous. */
interface TrashGateway {
    /** Starts a trash request for [ids]. Returns false if it couldn't be started. */
    fun request(ids: List<Long>): Boolean

    /** One of: idle, pending, done, denied, timeout. */
    fun status(): String
}

/** Source of the bundled web UI files (Android assets in production, a map in tests). */
fun interface AssetProvider {
    /** [path] is relative to the web root, e.g. "index.html" or "js/main.js". */
    fun open(path: String): InputStream?
}
