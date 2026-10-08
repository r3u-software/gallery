package com.example.gallery.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.gallery.android.TrashCoordinator

/**
 * Invisible activity whose only job is to host the system "Move to trash?" dialog
 * (a service can't show it). Reports the user's answer back to [TrashCoordinator].
 */
class TrashConfirmActivity : ComponentActivity() {
    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            TrashCoordinator.onResult(result.resultCode == Activity.RESULT_OK)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = TrashCoordinator.pending
        if (pending == null) {
            // Already answered, expired, or the process restarted: nothing to confirm.
            finish()
            return
        }
        if (savedInstanceState == null) {
            launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }
}
