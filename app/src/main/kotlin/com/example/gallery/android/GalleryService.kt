package com.example.gallery.android

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import com.example.gallery.server.AssetProvider
import com.example.gallery.server.GalleryServer
import com.example.gallery.server.PhotoCatalog
import com.example.gallery.server.Services
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.security.SecureRandom

/**
 * Foreground service that owns the HTTP server. It follows the phone's Wi-Fi: serving only while there is a
 * LAN address, updating the link/QR when the address changes, and shutting down if access is revoked.
 */
class GalleryService : Service() {
    private val main = Handler(Looper.getMainLooper())

    private var running = false
    private var server: GalleryServer? = null
    private var token = ""
    private var port = 0
    private var ip: String? = null

    private var tracker: NetworkTracker? = null
    private var observer: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var catalog: PhotoCatalog? = null
    private var lastText = "Starting…"
    private var services: Services = Services()
    private var findProvider: RingProvider? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!running) begin() else enterForeground(Notifications.server(this, lastText)) // satisfy startForegroundService()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ------------------------------------------------------------ lifecycle

    private fun begin() {
        Notifications.createChannels(this)
        // Must happen within seconds of startForegroundService(), so do it before anything else.
        enterForeground(Notifications.server(this, "Starting…"))

        if (!Permissions.fullAccess(this) || Permissions.hasNoScreenLock(this) || !PasscodeStore(this).isSet()) {
            ServerStateHolder.state = ServerState.Failed("Full photo access, a screen lock and a web passcode are required.")
            shutdown(keepState = true)
            return
        }

        running = true
        ServerStateHolder.state = ServerState.Starting

        val store = MediaStorePhotoStore(this)
        val ring = RingProvider(this)
        findProvider = ring
        services = Services(
            status = DeviceStatusProvider(this),
            notifications = NotificationsProviderImpl(this),
            messages = SmsProviderImpl(this),
            files = SharedFilesProvider(this),
            clipboard = ClipboardHubProvider(this),
            find = ring,
            contacts = ContactsProviderImpl(this),
            open = OpenOnPhone(this),
            backup = BackupSettingsProvider(this),
            passcode = PasscodeStore(this),
            features = FeaturePrefs(this),
            deviceName = { deviceName(this) },
        )
        val cat = PhotoCatalog { store.loadAll() }
        catalog = cat
        this.store = store
        TrashCoordinator.init(applicationContext) { cat.invalidate() }

        observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                cat.invalidate()
                BackupScheduler.kick(this@GalleryService) // a new photo: back it up soon (does nothing unless Backup is on)
            }
        }.also { contentResolver.registerContentObserver(store.collection, true, it) }
        videoObserver = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = BackupScheduler.kick(this@GalleryService)
        }.also {
            contentResolver.registerContentObserver(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, it)
        }
        BackupScheduler.sync(this)

        acquireWifiLock()
        tracker = NetworkTracker(this) { newIp -> main.post { onNetwork(newIp) } }.also { it.start() }
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    private var store: MediaStorePhotoStore? = null

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            // Revoked photo access or a removed screen lock must stop sharing immediately.
            if (!Permissions.fullAccess(this@GalleryService) || Permissions.hasNoScreenLock(this@GalleryService)) {
                shutdown()
            } else {
                main.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    private fun onNetwork(newIp: String?) {
        if (!running) return
        ip = newIp
        if (newIp == null) {
            stopServer()
            ServerStateHolder.state = ServerState.NoWifi
            updateNotification("Connect to Wi-Fi to share your photos")
            return
        }
        if (server == null && !startServer()) {
            ServerStateHolder.state = ServerState.Failed("Couldn't open a network port.")
            updateNotification("Couldn't start the server")
            return
        }
        publishRunning()
    }

    private fun publishRunning() {
        val address = ip ?: return
        ServerStateHolder.state = ServerState.Running("http://$address:$port/?t=$token", address, port)
        if (com.example.gallery.BuildConfig.DEBUG) {
            android.util.Log.i("R3U_E2E", "LINK port=$port token=$token") // used by the emulator smoke test only
        }
        updateNotification("Open $address:$port on your computer")
    }

    // ------------------------------------------------------------ server

    private fun startServer(): Boolean {
        val cat = catalog ?: return false
        val photoStore = store ?: return false
        val assetProvider = AssetProvider { path ->
            try { assets.open("web/$path") } catch (_: IOException) { null }
        }
        token = newToken()
        val candidates = (PORT_FIRST..PORT_LAST).toList() + 0 // 0 = any free port
        for (candidate in candidates) {
            val srv = GalleryServer(candidate, token, cat, photoStore, TrashCoordinator, assetProvider, services = services)
            try {
                srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            } catch (_: IOException) {
                continue
            }
            server = srv
            port = srv.listeningPort
            return true
        }
        return false
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // ------------------------------------------------------------ plumbing

    private fun enterForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.ID_SERVER, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_SERVER, notification)
        }
    }

    private fun updateNotification(text: String) {
        lastText = text
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_SERVER, Notifications.server(this, text))
    }

    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Gallery:server").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun cleanup() {
        running = false
        main.removeCallbacksAndMessages(null)
        tracker?.stop()
        tracker = null
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        videoObserver?.let { contentResolver.unregisterContentObserver(it) }
        videoObserver = null
        stopServer()
        findProvider?.stop()
        findProvider = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun shutdown(keepState: Boolean = false) {
        cleanup()
        if (!keepState) ServerStateHolder.state = ServerState.Stopped
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.example.gallery.action.START"
        const val ACTION_STOP = "com.example.gallery.action.STOP"
        private const val PORT_FIRST = 8080
        private const val PORT_LAST = 8090
        private const val WATCHDOG_MS = 5_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GalleryService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GalleryService::class.java).setAction(ACTION_STOP))
        }
    }
}
