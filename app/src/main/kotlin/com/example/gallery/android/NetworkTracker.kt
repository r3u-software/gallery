package com.example.gallery.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/**
 * Watches Wi-Fi networks and reports the phone's current LAN IPv4 address (or null when there is none).
 * [onChange] is called from a binder thread; callers should hop to the thread they need.
 */
class NetworkTracker(context: Context, private val onChange: (String?) -> Unit) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val ips = LinkedHashMap<Network, String?>()
    private var reported = false
    private var last: String? = null
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            synchronized(this@NetworkTracker) { ips[network] = ipv4(linkProperties) }
            publish()
        }

        override fun onLost(network: Network) {
            synchronized(this@NetworkTracker) { ips.remove(network) }
            publish()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.registerNetworkCallback(request, callback)
        registered = true
        // With no Wi-Fi at all the callback never fires, so report "no network" after a short grace period.
        handler.postDelayed({ publish() }, INITIAL_GRACE_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            try { cm.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) {}
            registered = false
        }
    }

    private fun publish() {
        val ip = synchronized(this) {
            val current = ips.values.firstOrNull { it != null }
            if (reported && current == last) return
            reported = true
            last = current
            current
        }
        onChange(ip)
    }

    private fun ipv4(lp: LinkProperties): String? =
        lp.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress

    private companion object {
        const val INITIAL_GRACE_MS = 1500L
    }
}
