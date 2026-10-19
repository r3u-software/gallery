package com.example.gallery.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.gallery.android.ClipboardHub
import com.example.gallery.android.FeaturePrefs
import com.example.gallery.android.SharedFilesProvider
import com.example.gallery.server.Feature
import com.example.gallery.server.GalleryServer

/**
 * Target of Android's Share sheet. Files and text shared here become available in the web
 * dashboard's Files tab ("send from phone to computer"). Only works when the Files feature is on.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = handle(intent)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun handle(intent: Intent): String {
        if (!FeaturePrefs(this).isEnabled(Feature.FILES)) {
            return "Turn on Files in R3U | Phone Link settings first"
        }
        val streams = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> uri(intent, Intent.EXTRA_STREAM)?.let { streams.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> streams.addAll(uris(intent))
        }

        var sentFiles = 0
        val files = SharedFilesProvider(this)
        for (u in streams) {
            val name = GalleryServer.cleanFileName(displayName(u) ?: "shared-file")
            val mime = contentResolver.getType(u) ?: GalleryServer.guessMime(name)
            val saved = try {
                contentResolver.openInputStream(u)?.use { files.save(name, mime, it) }
            } catch (_: Exception) {
                null
            }
            if (saved != null) sentFiles++
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (streams.isEmpty() && !text.isNullOrBlank()) {
            ClipboardHub.push(text)
            return "Sent to your computer"
        }
        return when {
            sentFiles == 0 -> "Couldn't send that to your computer"
            sentFiles == 1 -> "1 file ready on your computer"
            else -> "$sentFiles files ready on your computer"
        }
    }

    @Suppress("DEPRECATION")
    private fun uri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Uri::class.java) else intent.getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun uris(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment
}
