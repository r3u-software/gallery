package com.example.gallery.server

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

object PrivateNetwork {
    /**
     * True for loopback and private/link-local ranges: 10/8, 172.16/12, 192.168/16, 169.254/16,
     * 127/8, ::1, fc00::/7 and fe80::/10 (plus IPv4-mapped forms of the above).
     */
    fun isPrivate(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val clean = address.substringBefore('%').removePrefix("/")
        // Never resolve hostnames: only literal IPs are accepted.
        val isV4Literal = clean.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        val isV6Literal = clean.contains(':') && clean.matches(Regex("^[0-9a-fA-F:.]+$"))
        if (!isV4Literal && !isV6Literal) return false
        val addr = try { InetAddress.getByName(clean) } catch (_: Exception) { return false }
        return when (addr) {
            is Inet4Address -> isPrivateV4(addr.address)
            is Inet6Address -> {
                val b = addr.address
                addr.isLoopbackAddress ||
                    (b[0].toInt() and 0xFE) == 0xFC ||
                    (b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xC0) == 0x80
            }
            else -> false
        }
    }

    private fun isPrivateV4(b: ByteArray): Boolean {
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        return a == 10 ||
            a == 127 ||
            (a == 172 && c in 16..31) ||
            (a == 192 && c == 168) ||
            (a == 169 && c == 254)
    }
}
