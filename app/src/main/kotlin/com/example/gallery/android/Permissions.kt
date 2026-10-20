package com.example.gallery.android

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    private fun granted(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** Full access to all photos ("Allow all"). Android 13+ uses READ_MEDIA_IMAGES; 11-12 use storage read. */
    fun fullAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(ctx, Manifest.permission.READ_MEDIA_IMAGES)
        else granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    /** Android 14+: the user picked "Select photos", which is not enough for a full gallery. */
    fun partialOnly(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            !fullAccess(ctx) &&
            granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun toRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Videos can be read (backup). Android 13+ asks for them separately; 11-12 share the storage permission. */
    fun videoAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(ctx, Manifest.permission.READ_MEDIA_VIDEO)
        else granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    /** True when original GPS data in photos is readable, so backed-up photos keep their location. */
    fun mediaLocation(ctx: Context): Boolean = granted(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION)

    /** What switching on Backup asks for beyond photo access: videos, and photo locations. */
    fun backupToRequest(ctx: Context): Array<String> = buildList {
        if (!videoAccess(ctx)) add(Manifest.permission.READ_MEDIA_VIDEO)
        if (!mediaLocation(ctx)) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    fun needsNotificationPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    /** True when the device has no PIN/pattern/password, so biometric/credential prompts can't protect the photos. */
    fun hasNoScreenLock(ctx: Context): Boolean {
        val km = ctx.getSystemService(KeyguardManager::class.java)
        return km == null || !km.isDeviceSecure
    }
}
