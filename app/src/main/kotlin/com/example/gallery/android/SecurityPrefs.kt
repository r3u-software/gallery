package com.example.gallery.android

import android.content.Context
import android.util.Base64
import com.example.gallery.server.Feature
import com.example.gallery.server.FeatureFlags
import com.example.gallery.server.PasscodeVerifier
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Stores only a salted PBKDF2 hash of the web passcode, never the passcode itself. */
class PasscodeStore(context: Context) : PasscodeVerifier {
    private val prefs = context.applicationContext.getSharedPreferences("security", Context.MODE_PRIVATE)

    override val required: Boolean get() = prefs.contains(KEY_HASH)

    fun isSet(): Boolean = required

    fun set(code: String) {
        require(code.length >= MIN_LENGTH) { "passcode too short" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash(code, salt), Base64.NO_WRAP))
            .apply()
    }

    override fun verify(code: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs.getString(KEY_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(expected, hash(code, salt))
    }

    private fun hash(code: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    companion object {
        const val MIN_LENGTH = 6
        private const val ITERATIONS = 120_000
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
    }
}

/** Per-feature switches. Photos and status are on by default; everything sensitive is opt-in. */
class FeaturePrefs(context: Context) : FeatureFlags {
    private val prefs = context.applicationContext.getSharedPreferences("features", Context.MODE_PRIVATE)

    override fun isEnabled(feature: Feature): Boolean =
        feature == Feature.BACKUP || // automatic: it has no switch, it simply runs once it has a script to talk to
            prefs.getBoolean(feature.key, feature == Feature.PHOTOS || feature == Feature.STATUS)

    fun set(feature: Feature, enabled: Boolean) {
        prefs.edit().putBoolean(feature.key, enabled).apply()
    }
}
