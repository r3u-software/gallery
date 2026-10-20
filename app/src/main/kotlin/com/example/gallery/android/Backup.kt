package com.example.gallery.android

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.gallery.BuildConfig
import com.example.gallery.backup.BackupConfig
import com.example.gallery.backup.BackupEngine
import com.example.gallery.backup.BackupItem
import com.example.gallery.backup.BackupLedger
import com.example.gallery.backup.BackupRules
import com.example.gallery.backup.BackupSource
import com.example.gallery.backup.HttpDriveTransport
import com.example.gallery.backup.MediaKind
import com.example.gallery.backup.RunResult
import com.example.gallery.server.BackupProvider
import com.example.gallery.server.BackupSettings
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Where uploads may go. Debug builds also allow the emulator's host machine, for the end-to-end test only. */
object BackupAndroid {
    fun allowUrl(url: URL): Boolean =
        BackupRules.isAllowedEndpoint(url) || (BuildConfig.DEBUG && url.host == "10.0.2.2")

    fun transport() = HttpDriveTransport(::allowUrl)
}

/** The script address, secret and options. App-private storage; the secret is never sent to the browser. */
class BackupPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("backup", Context.MODE_PRIVATE)

    private val savedUrl: String? get() = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }
    private val savedSecret: String? get() = prefs.getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() }

    /** The script address and secret this build carries (empty in debug and in builds made without them). */
    private val builtInUrl: String? get() = BuildConfig.BACKUP_URL.takeIf { it.isNotBlank() }
    private val builtInSecret: String? get() = BuildConfig.BACKUP_SECRET.takeIf { it.isNotBlank() }

    /** Someone typed their own pair into the dashboard; it wins over the built-in one. */
    private fun hasSavedPair() = savedUrl != null && savedSecret != null

    /** True when the app is using the pair it was built with. */
    fun builtIn(): Boolean = !hasSavedPair() && builtInUrl != null && builtInSecret != null

    val url: String? get() = if (hasSavedPair()) savedUrl else if (builtIn()) builtInUrl else savedUrl
    val photos: Boolean get() = prefs.getBoolean(KEY_PHOTOS, true)
    val videos: Boolean get() = prefs.getBoolean(KEY_VIDEOS, true)
    val wifiOnly: Boolean get() = prefs.getBoolean(KEY_WIFI, true)

    fun configured(): Boolean = hasSavedPair() || builtIn()

    fun config(): BackupConfig? {
        val (u, s) = when {
            hasSavedPair() -> savedUrl!! to savedSecret!!
            builtIn() -> builtInUrl!! to builtInSecret!!
            else -> return null
        }
        return BackupConfig(u, s, photos, videos, wifiOnly)
    }

    fun save(url: String?, secret: String?, photos: Boolean, videos: Boolean, wifiOnly: Boolean) {
        prefs.edit().apply {
            if (url != null) putString(KEY_URL, url)
            if (secret != null) putString(KEY_SECRET, secret)
            putBoolean(KEY_PHOTOS, photos)
            putBoolean(KEY_VIDEOS, videos)
            putBoolean(KEY_WIFI, wifiOnly)
        }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_SECRET = "secret"
        const val KEY_PHOTOS = "photos"
        const val KEY_VIDEOS = "videos"
        const val KEY_WIFI = "wifi"
    }
}

/** Remembers what is in Drive already, and the upload session of a file that was interrupted. */
class SqliteBackupLedger(context: Context) : BackupLedger {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "backup.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE done (k TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE session (k TEXT PRIMARY KEY, uri TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override fun doneKeys(): Set<String> {
        val out = HashSet<String>()
        helper.readableDatabase.rawQuery("SELECT k FROM done", null).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    override fun markDone(key: String) {
        helper.writableDatabase.insertWithOnConflict("done", null, ContentValues().apply { put("k", key) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun session(key: String): String? =
        helper.readableDatabase.rawQuery("SELECT uri FROM session WHERE k = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    override fun saveSession(key: String, uri: String) {
        helper.writableDatabase.insertWithOnConflict(
            "session", null, ContentValues().apply { put("k", key); put("uri", uri) }, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun clearSession(key: String) {
        helper.writableDatabase.delete("session", "k = ?", arrayOf(key))
    }
}

/** Photos and videos from MediaStore. Photos keep their GPS data when the location permission is granted. */
class MediaBackupSource(context: Context) : BackupSource {
    private val resolver = context.applicationContext.contentResolver
    private val keepLocation =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun collection(kind: MediaKind): Uri = when (kind) {
        MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }

    override fun list(photos: Boolean, videos: Boolean): List<BackupItem> {
        val out = ArrayList<BackupItem>()
        if (photos) query(MediaKind.IMAGE, out)
        if (videos) query(MediaKind.VIDEO, out)
        return out
    }

    private fun query(kind: MediaKind, out: MutableList<BackupItem>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        resolver.query(collection(kind), projection, "${MediaStore.MediaColumns.IS_PENDING} = 0", null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val modified = c.getLong(iMod) * 1000
                val taken = c.getLong(iTaken)
                out.add(
                    BackupItem(
                        kind = kind,
                        id = c.getLong(iId),
                        name = c.getString(iName) ?: continue,
                        mime = c.getString(iMime)?.takeIf { it.isNotBlank() } ?: if (kind == MediaKind.IMAGE) "image/jpeg" else "video/mp4",
                        size = c.getLong(iSize),
                        takenMs = if (taken > 0) taken else modified,
                        modifiedMs = modified,
                        album = c.getString(iBucket) ?: "",
                    ),
                )
            }
        }
    }

    /** MediaStore's SIZE can lag behind the file; ask the file itself so a backup is never cut short. */
    override fun sizeOf(item: BackupItem): Long? = try {
        resolver.openFileDescriptor(uriOf(item), "r")?.use { it.statSize }?.takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

    private fun uriOf(item: BackupItem): Uri {
        val uri = ContentUris.withAppendedId(collection(item.kind), item.id)
        return if (item.kind == MediaKind.IMAGE && keepLocation) MediaStore.setRequireOriginal(uri) else uri
    }

    override fun open(item: BackupItem, offset: Long): InputStream? {
        val uri = uriOf(item)
        val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r") ?: return null
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (offset > 0) (stream as FileInputStream).channel.position(offset)
        return stream
    }
}

/** What the web dashboard reads and writes. It can never read the secret or see any progress. */
class BackupSettingsProvider(context: Context) : BackupProvider {
    private val app = context.applicationContext
    private val prefs = BackupPrefs(app)

    override fun settings() = BackupSettings(
        configured = prefs.configured(),
        urlHint = if (prefs.builtIn()) "" else prefs.url?.let(BackupRules::hint).orEmpty(),
        photos = prefs.photos,
        videos = prefs.videos,
        wifiOnly = prefs.wifiOnly,
        builtIn = prefs.builtIn(),
    )

    override fun validateUrl(url: String): String? =
        if (BuildConfig.DEBUG && url.startsWith("http://10.0.2.2:")) null else BackupRules.validateUrl(url)

    override fun save(url: String?, secret: String?, photos: Boolean, videos: Boolean, wifiOnly: Boolean): Boolean {
        prefs.save(url, secret, photos, videos, wifiOnly)
        val ready = prefs.configured()
        if (ready) {
            BackupScheduler.sync(app)
            BackupScheduler.kick(app, delaySeconds = 0)
        }
        return ready
    }

    override fun test(): Boolean = prefs.config()?.let { BackupAndroid.transport().ping(it) } ?: false

    override fun lastNote(): String = BackupRunner.lastNote(app)

    override fun clear() {
        prefs.clear()
        BackupScheduler.sync(app)
    }
}

/** What the last run did, kept privately on the phone for troubleshooting. It is never shown in the app. */
private class BackupDiag(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("backup_diag", Context.MODE_PRIVATE)
    fun record(text: String) {
        prefs.edit().putString("note", "${System.currentTimeMillis()} $text").apply()
        if (BuildConfig.DEBUG) Log.i("R3U_BACKUP", text)
    }
    fun last(): String = prefs.getString("note", null) ?: "never ran"
}

/** The result of one try, and whether it is worth trying again straight away. */
class BackupRunNote(val again: Boolean, val text: String)

/**
 * One backup pass. Used by the loop that lives in the always-on service and by the background job that is the
 * fallback when the service isn't running, so only one of them works at a time.
 */
object BackupRunner {
    private val running = AtomicBoolean(false)

    fun run(context: Context, budgetMs: Long, shouldStop: () -> Boolean): BackupRunNote {
        val app = context.applicationContext
        val diag = BackupDiag(app)
        fun skip(why: String) = BackupRunNote(false, why).also { diag.record(why) }

        val config = BackupPrefs(app).config() ?: return skip("waiting: no script address")
        if (!running.compareAndSet(false, true)) return BackupRunNote(false, "another pass is running")
        try {
            if (config.wifiOnly && !onWifi(app)) return skip("waiting: not on Wi-Fi")
            if (batteryLow(app)) return skip("waiting: battery low")
            val photos = config.photos && Permissions.fullAccess(app)
            val videos = config.videos && Permissions.videoAccess(app)
            if (!photos && !videos) return skip("waiting: no permission to read media")
            val report = BackupEngine(MediaBackupSource(app), SqliteBackupLedger(app), BackupAndroid.transport()).run(
                config.copy(photos = photos, videos = videos),
                deadlineMs = System.currentTimeMillis() + budgetMs,
                shouldStop = shouldStop,
            )
            val text = "${report.result} uploaded=${report.uploaded} existed=${report.alreadyThere} skipped=${report.skipped}" +
                (if (config.photos && !photos) " (photos not allowed)" else "") + (if (config.videos && !videos) " (videos not allowed)" else "")
            diag.record(text)
            return BackupRunNote(report.result == RunResult.TIME_UP, text)
        } catch (e: Exception) {
            return skip("error ${e.javaClass.simpleName}")
        } finally {
            running.set(false)
        }
    }

    fun lastNote(context: Context): String = BackupDiag(context).last()

    /** "Wi-Fi only" means Wi-Fi (or a cable), not "a network Android happens to call unmetered". */
    private fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private fun batteryLow(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return !charging && level >= 0 && level * 100 / scale < 15
    }
}

/**
 * Backup mostly runs inside the always-on service that already serves the dashboard, so it starts as soon as the
 * app is open and does not depend on Android's job scheduling. This is its loop; [nudge] wakes it early.
 */
object BackupLoop {
    private val nudges = LinkedBlockingQueue<Unit>()
    @Volatile private var thread: Thread? = null
    @Volatile private var stopping = false

    fun nudge() {
        nudges.offer(Unit)
    }

    @Synchronized
    fun start(context: Context) {
        if (thread?.isAlive == true) return
        stopping = false
        val app = context.applicationContext
        thread = Thread({
            while (!stopping) {
                val note = try {
                    BackupRunner.run(app, LOOP_BUDGET_MS) { stopping }
                } catch (_: Exception) {
                    BackupRunNote(false, "error")
                }
                if (stopping) break
                if (note.again) continue
                try {
                    nudges.poll(PAUSE_MS, TimeUnit.MILLISECONDS)
                    nudges.clear()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "backup").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        stopping = true
        thread?.interrupt()
        thread = null
    }

    private const val LOOP_BUDGET_MS = 30 * 60_000L
    private const val PAUSE_MS = 5 * 60_000L
}

/**
 * The fallback: ordinary background work that Android runs about every 15 minutes, for when the service isn't
 * running. No notification, nothing on screen.
 */
object BackupScheduler {
    private const val PERIODIC = "r3u-backup-periodic"
    private const val NOW = "r3u-backup-now"

    /** Backup has no switch: it runs by itself as soon as the app has a script to talk to. */
    fun isActive(context: Context): Boolean = BackupPrefs(context).configured()

    // Only "has a connection". Whether that connection is Wi-Fi is checked when the pass runs.
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Schedules the regular run, or cancels it when there is nothing to talk to. */
    fun sync(context: Context) {
        val app = context.applicationContext
        val work = WorkManager.getInstance(app)
        if (!isActive(app)) {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(NOW)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES).setConstraints(constraints()).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        BackupLoop.nudge()
    }

    /** Asks for a pass soon (for example because a new photo appeared). Repeated asks collapse into one. */
    fun kick(context: Context, delaySeconds: Long = 60) {
        val app = context.applicationContext
        if (!isActive(app)) return
        BackupLoop.nudge()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (BackupScheduler.isActive(applicationContext)) BackupRunner.run(applicationContext, BUDGET_MS) { isStopped }
        return Result.success()
    }

    private companion object {
        const val BUDGET_MS = 8 * 60_000L // Android ends background jobs after about 10 minutes; the next pass resumes
    }
}
