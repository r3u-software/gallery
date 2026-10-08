package com.example.gallery.android

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.gallery.server.TrashGateway
import com.example.gallery.ui.TrashConfirmActivity

/**
 * Bridges the HTTP server and Android's system "move to trash" dialog.
 *
 * A service can't show that dialog, so [request] builds the MediaStore trash request, then either
 * launches [TrashConfirmActivity] right away (app visible) or posts a heads-up notification that opens it.
 * The browser polls [status] until the user answers on the phone.
 */
object TrashCoordinator : TrashGateway {
    private const val TIMEOUT_MS = 3 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var onTrashed: () -> Unit = {}

    @Volatile private var state = "idle"
    @Volatile private var startedAt = 0L
    @Volatile private var count = 0

    /** Consumed by [TrashConfirmActivity] to show the system dialog. */
    @Volatile var pending: PendingIntent? = null
        private set

    fun init(context: Context, onTrashed: () -> Unit) {
        app = context.applicationContext
        this.onTrashed = onTrashed
    }

    override fun request(ids: List<Long>): Boolean {
        if (!::app.isInitialized || ids.isEmpty()) return false
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val uris = ids.map { ContentUris.withAppendedId(collection, it) }
        val intent = try {
            MediaStore.createTrashRequest(app.contentResolver, uris, true)
        } catch (_: Exception) {
            return false
        }
        pending = intent
        count = ids.size
        startedAt = System.currentTimeMillis()
        state = "pending"
        main.post { surface() }
        return true
    }

    override fun status(): String {
        if (state == "pending" && System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
            pending = null
            state = "timeout"
            main.post { Notifications.cancelTrash(app) }
        }
        return state
    }

    /** Called by [TrashConfirmActivity] with the outcome of the system dialog. */
    fun onResult(approved: Boolean) {
        pending = null
        state = if (approved) "done" else "denied"
        if (approved) onTrashed()
        Notifications.cancelTrash(app)
    }

    private fun surface() {
        if (state != "pending") return
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground) {
            app.startActivity(Intent(app, TrashConfirmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Notifications.postTrashConfirm(app, count)
        }
    }
}
