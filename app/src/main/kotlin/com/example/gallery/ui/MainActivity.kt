package com.example.gallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import com.example.gallery.android.GalleryService
import com.example.gallery.android.Permissions
import com.example.gallery.android.ServerState
import com.example.gallery.android.ServerStateHolder

/** Survives rotation, but not the activity being destroyed, so every fresh launch asks for unlock again. */
class MainViewModel : ViewModel() {
    var authenticated by mutableStateOf(false)
    var authError by mutableStateOf<String?>(null)
    var promptShowing = false
    var askedPermission = false
    var askedNotifications = false
    var serverRequested = false
}

private enum class Screen { Checking, NeedPermission, Partial, NoLock, Unlock, Server }

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()
    private var screen by mutableStateOf(Screen.Checking)

    private val photoPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GalleryTheme { Root() }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------ UI

    @Composable
    private fun Root() {
        when (screen) {
            Screen.Checking -> StartingScreen()
            Screen.NeedPermission -> PermissionScreen(
                onGrant = { photoPermission.launch(Permissions.toRequest()) },
                onSettings = ::openAppSettings,
            )
            Screen.Partial -> PartialAccessScreen(onSettings = ::openAppSettings)
            Screen.NoLock -> NoScreenLockScreen(onSettings = ::openSecuritySettings)
            Screen.Unlock -> UnlockScreen(
                message = vm.authError,
                onUnlock = {
                    vm.authError = null
                    showPrompt()
                },
            )
            Screen.Server -> ServerContent()
        }
    }

    @Composable
    private fun ServerContent() {
        when (val s = ServerStateHolder.state) {
            is ServerState.Running -> RunningScreen(s, onCopy = { copyLink(s.url) }, onStop = { GalleryService.stop(this) })
            ServerState.Starting -> StartingScreen()
            ServerState.NoWifi -> NoWifiScreen()
            ServerState.Stopped -> if (vm.serverRequested) StoppedScreen() else StartingScreen()
            is ServerState.Failed -> FailedScreen(s.message)
        }
    }

    // ------------------------------------------------------------ flow

    /**
     * Permission -> screen lock -> unlock prompt -> start server. Re-evaluated on every resume so that
     * fixing something in Settings (or revoking access) is picked up immediately.
     */
    private fun refresh() {
        val next = when {
            Permissions.partialOnly(this) -> Screen.Partial
            !Permissions.fullAccess(this) -> Screen.NeedPermission
            Permissions.hasNoScreenLock(this) -> Screen.NoLock
            !vm.authenticated -> Screen.Unlock
            else -> Screen.Server
        }
        screen = next

        when (next) {
            Screen.NeedPermission -> if (!vm.askedPermission) {
                vm.askedPermission = true
                photoPermission.launch(Permissions.toRequest())
            }
            Screen.Unlock -> if (!vm.promptShowing && vm.authError == null) showPrompt()
            Screen.Server -> startServerOnce()
            else -> Unit
        }
    }

    private fun showPrompt() {
        if (vm.promptShowing) return
        val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            vm.authError = "This phone can’t verify you with a fingerprint, face, or passcode right now."
            return
        }
        vm.promptShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    vm.promptShowing = false
                    vm.authError = null
                    vm.authenticated = true
                    refresh()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    vm.promptShowing = false
                    vm.authError = errString.toString()
                    screen = Screen.Unlock
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Gallery")
                .setSubtitle("Confirm it’s you to start sharing your photos")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    private fun startServerOnce() {
        if (vm.serverRequested) return
        // Ask for notification permission (Android 13+) once, so the trash confirmation heads-up can appear.
        if (Permissions.needsNotificationPermission(this) && !vm.askedNotifications) {
            vm.askedNotifications = true
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        vm.serverRequested = true
        ServerStateHolder.state = ServerState.Starting
        GalleryService.start(this)
    }

    // ------------------------------------------------------------ helpers

    private fun copyLink(url: String) {
        val clip = ClipData.newPlainText("Gallery link", url).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    private fun openSecuritySettings() {
        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }
}
