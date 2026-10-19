package com.example.gallery.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.gallery.server.Contact
import com.example.gallery.server.ContactsProvider
import com.example.gallery.server.OpenProvider

/** Reads the phone's contacts (name, numbers, emails). Needs the READ_CONTACTS runtime permission. */
class ContactsProviderImpl(context: Context) : ContactsProvider {
    private val app = context.applicationContext

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun list(): List<Contact> {
        val resolver = app.contentResolver
        val names = LinkedHashMap<Long, String>()
        val numbers = HashMap<Long, LinkedHashSet<String>>()
        val phoneProjection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, phoneProjection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext() && names.size < MAX_CONTACTS) {
                val id = c.getLong(0)
                names.putIfAbsent(id, c.getString(1).orEmpty())
                c.getString(2)?.takeIf { it.isNotBlank() }?.let { numbers.getOrPut(id) { LinkedHashSet() }.add(it.trim()) }
            }
        }

        val emails = HashMap<Long, LinkedHashSet<String>>()
        resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in names) c.getString(1)?.takeIf { it.isNotBlank() }?.let { emails.getOrPut(id) { LinkedHashSet() }.add(it.trim()) }
            }
        }
        return names.map { (id, name) ->
            Contact(id, name.ifBlank { numbers[id]?.firstOrNull().orEmpty() }, numbers[id]?.toList().orEmpty(), emails[id]?.toList().orEmpty())
        }
    }

    private companion object {
        const val MAX_CONTACTS = 3000
    }
}

/**
 * "Open on the phone": a link in the browser, the dialer with a number filled in, or a map search.
 * Android blocks apps from opening screens out of the background, so when this app isn't on screen the
 * request becomes a notification the user taps; when it is on screen it opens straight away.
 */
class OpenOnPhone(context: Context) : OpenProvider {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun open(kind: String, value: String): Boolean {
        val (intent, label) = when (kind) {
            "link" -> Intent(Intent.ACTION_VIEW, Uri.parse(value)) to "Open link"
            "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(value))) to "Call $value"
            "map" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(value))) to "Show on map"
            else -> return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post {
            val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (foreground) {
                try {
                    app.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Notifications.postOpenRequest(app, label, value, intent)
                }
            } else {
                Notifications.postOpenRequest(app, label, value, intent)
            }
        }
        return true
    }
}
