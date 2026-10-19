package com.example.gallery.android

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.gallery.server.FilesProvider
import com.example.gallery.server.MessagesProvider
import com.example.gallery.server.SharedFile
import com.example.gallery.server.SmsMessage
import com.example.gallery.server.SmsThread
import java.io.InputStream

/**
 * SMS through the system provider. Reading needs READ_SMS and sending needs SEND_SMS (both runtime
 * permissions). Note: Android only records messages in the provider for the default SMS app, so
 * messages sent from here are sent but may not appear in the history later.
 */
class SmsProviderImpl(context: Context) : MessagesProvider {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver
    private val names = HashMap<String, String>()

    override fun hasPermission(): Boolean = granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.SEND_SMS)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    override fun threads(): List<SmsThread> {
        val latest = LinkedHashMap<Long, SmsThread>()
        val unread = HashMap<Long, Int>()
        val args = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_SCAN)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(Telephony.Sms.DATE))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val projection = arrayOf(
            Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE,
        )
        resolver.query(Telephony.Sms.CONTENT_URI, projection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val thread = c.getLong(0)
                val address = c.getString(1).orEmpty()
                if (c.getInt(4) == 0 && c.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX) {
                    unread[thread] = (unread[thread] ?: 0) + 1
                }
                if (!latest.containsKey(thread) && latest.size < MAX_THREADS) {
                    latest[thread] = SmsThread(thread, address, nameFor(address), c.getString(2).orEmpty().take(120), c.getLong(3), 0)
                }
            }
        }
        return latest.values.map { it.copy(unread = unread[it.id] ?: 0) }
    }

    override fun messages(threadId: Long, limit: Int): List<SmsMessage> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${Telephony.Sms.THREAD_ID} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(threadId.toString()))
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(Telephony.Sms.DATE))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val out = ArrayList<SmsMessage>()
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
        resolver.query(Telephony.Sms.CONTENT_URI, projection, args, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(SmsMessage(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getInt(3) != Telephony.Sms.MESSAGE_TYPE_INBOX))
            }
        }
        out.reverse() // oldest first for display
        return out
    }

    override fun send(to: String, body: String): Boolean = try {
        val sms = app.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(body)
        if (parts.size > 1) sms.sendMultipartTextMessage(to, null, parts, null, null)
        else sms.sendTextMessage(to, null, body, null, null)
        true
    } catch (_: Exception) {
        false
    }

    private fun nameFor(address: String): String {
        if (address.isBlank() || !granted(Manifest.permission.READ_CONTACTS)) return address
        return names.getOrPut(address) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: address
            } catch (_: Exception) {
                address
            }
        }
    }

    private companion object {
        const val MAX_SCAN = 4000
        const val MAX_THREADS = 300
    }
}

/**
 * Files exchanged with the computer live in Download/R3U Phone Link. Files the app created itself are
 * readable without any storage permission, so no broad storage access is requested.
 */
class SharedFilesProvider(context: Context) : FilesProvider {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver
    private val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    private fun uriOf(id: Long): Uri = ContentUris.withAppendedId(collection, id)

    private val projection = arrayOf(
        MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE,
        MediaStore.Downloads.DATE_MODIFIED, MediaStore.Downloads.MIME_TYPE,
    )

    private fun query(selection: String, args: Array<String>): List<SharedFile> {
        val out = ArrayList<SharedFile>()
        resolver.query(collection, projection, selection, args, "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    SharedFile(
                        id = c.getLong(0),
                        name = c.getString(1).orEmpty(),
                        size = c.getLong(2),
                        modified = c.getLong(3) * 1000,
                        mime = c.getString(4) ?: "application/octet-stream",
                    ),
                )
            }
        }
        return out
    }

    override fun list(): List<SharedFile> =
        query("${MediaStore.Downloads.RELATIVE_PATH} = ?", arrayOf(FOLDER))

    override fun find(id: Long): SharedFile? =
        query("${MediaStore.Downloads._ID} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?", arrayOf(id.toString(), FOLDER)).firstOrNull()

    override fun open(id: Long): InputStream? = try {
        if (find(id) == null) null else resolver.openInputStream(uriOf(id))
    } catch (_: Exception) {
        null
    }

    override fun save(name: String, mime: String, input: InputStream): SharedFile? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out -> input.copyTo(out, 64 * 1024) } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
        return find(ContentUris.parseId(uri))
    }

    override fun delete(id: Long): Boolean = find(id) != null && resolver.delete(uriOf(id), null, null) > 0

    companion object {
        /** MediaStore RELATIVE_PATH values end with a slash. */
        const val FOLDER = "Download/R3U Phone Link/"
    }
}
