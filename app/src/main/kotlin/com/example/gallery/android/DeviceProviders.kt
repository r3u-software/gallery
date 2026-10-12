package com.example.gallery.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import com.example.gallery.server.ClipboardEntry
import com.example.gallery.server.ClipboardProvider
import com.example.gallery.server.DeviceStatus
import com.example.gallery.server.FindProvider
import com.example.gallery.server.StatusProvider

/** Friendly device name: the user's own "device name" setting, else the model. */
fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

class DeviceStatusProvider(private val context: Context) : StatusProvider {
    override fun status(): DeviceStatus {
        val bm = context.getSystemService(BatteryManager::class.java)
        val stat = StatFs(Environment.getDataDirectory().path)
        return DeviceStatus(
            name = deviceName(context),
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            batteryPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = bm.isCharging,
            storageFreeBytes = stat.availableBytes,
            storageTotalBytes = stat.totalBytes,
        )
    }
}

/** Text shared from the phone to the computer arrives here (via the Share sheet). */
object ClipboardHub {
    @Volatile var latest: ClipboardEntry? = null
        private set

    fun push(text: String) {
        latest = ClipboardEntry(text.take(100_000), System.currentTimeMillis())
    }
}

class ClipboardHubProvider(context: Context) : ClipboardProvider {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun latest(): ClipboardEntry? = ClipboardHub.latest

    override fun setPhoneClipboard(text: String): Boolean {
        main.post {
            val cm = app.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("From computer", text))
        }
        return true
    }
}

/** Rings the phone at full alarm volume so it can be found; stops on request or after a minute. */
class RingProvider(context: Context) : FindProvider {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var previousVolume = -1
    private val autoStop = Runnable { stop() }

    @Synchronized
    override fun ring(): Boolean {
        if (player != null) return true
        return try {
            val audio = app.getSystemService(AudioManager::class.java)
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (_: SecurityException) {
                // Do Not Disturb policy can block volume changes; ring at the current volume instead.
            }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(app, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
            main.postDelayed(autoStop, AUTO_STOP_MS)
            true
        } catch (_: Exception) {
            player?.release()
            player = null
            false
        }
    }

    @Synchronized
    override fun stop() {
        main.removeCallbacks(autoStop)
        player?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        player = null
        if (previousVolume >= 0) {
            try {
                app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
            } catch (_: SecurityException) {}
            previousVolume = -1
        }
    }

    @Synchronized
    override fun isRinging(): Boolean = player != null

    private companion object {
        const val AUTO_STOP_MS = 60_000L
    }
}
