package com.example.gallery.android

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.example.gallery.server.Photo
import com.example.gallery.server.PhotoStore
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** [PhotoStore] over MediaStore images. */
class MediaStorePhotoStore(context: Context) : PhotoStore {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val thumbCache = object : LruCache<Long, ByteArray>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: ByteArray): Int = value.size
    }

    fun uriOf(id: Long): Uri = ContentUris.withAppendedId(collection, id)

    /** Reads lightweight metadata for every image (a few MB even for 50k photos). */
    fun loadAll(): List<Photo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val out = ArrayList<Photo>()
        resolver.query(collection, projection, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val iOri = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)

            while (c.moveToNext()) {
                val name = c.getString(iName) ?: "photo"
                val bucket = c.getString(iBucket) ?: ""
                val path = c.getString(iPath) ?: ""
                val taken = c.getLong(iTaken)
                val modified = c.getLong(iMod) * 1000
                var w = c.getInt(iW)
                var h = c.getInt(iH)
                val orientation = c.getInt(iOri)
                if (orientation == 90 || orientation == 270) {
                    val t = w; w = h; h = t
                }
                out.add(
                    Photo(
                        id = c.getLong(iId),
                        name = name,
                        dateTaken = if (taken > 0) taken else modified,
                        size = c.getLong(iSize),
                        width = w,
                        height = h,
                        bucket = bucket,
                        mime = c.getString(iMime) ?: "image/jpeg",
                        isScreenshot = name.startsWith("Screenshot", ignoreCase = true) ||
                            path.contains("Screenshot", ignoreCase = true) ||
                            bucket.contains("Screenshot", ignoreCase = true),
                    ),
                )
            }
        }
        return out
    }

    override fun length(id: Long): Long? = try {
        resolver.openFileDescriptor(uriOf(id), "r")?.use { it.statSize }
    } catch (_: Exception) {
        null
    }

    override fun open(id: Long, offset: Long): InputStream? = try {
        val pfd = resolver.openFileDescriptor(uriOf(id), "r")
        if (pfd == null) null else {
            val fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            if (offset > 0) fis.channel.position(offset)
            BufferedInputStream(fis as FileInputStream, 64 * 1024)
        }
    } catch (_: Exception) {
        null
    }

    override fun thumbnail(id: Long): ByteArray? {
        thumbCache.get(id)?.let { return it }
        val bitmap = try {
            // loadThumbnail applies EXIF orientation and decodes HEIC.
            resolver.loadThumbnail(uriOf(id), Size(THUMB_SIZE, THUMB_SIZE), null)
        } catch (_: Exception) {
            return null
        }
        val bytes = jpeg(bitmap, 82)
        bitmap.recycle()
        thumbCache.put(id, bytes)
        return bytes
    }

    override fun renderLarge(id: Long): ByteArray? = try {
        val source = ImageDecoder.createSource(resolver, uriOf(id))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > LARGE_SIZE) {
                val k = LARGE_SIZE.toFloat() / longest
                decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            }
        }
        val bytes = jpeg(bitmap, 88)
        bitmap.recycle()
        bytes
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private companion object {
        const val THUMB_SIZE = 480
        const val LARGE_SIZE = 2560
        const val THUMB_CACHE_BYTES = 24 * 1024 * 1024
    }
}
