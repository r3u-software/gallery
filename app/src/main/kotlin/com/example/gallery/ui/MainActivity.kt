package com.example.gallery.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import com.example.gallery.android.FeaturePrefs
import com.example.gallery.android.GalleryService
import com.example.gallery.android.NotificationHub
import com.example.gallery.android.PasscodeStore
import com.example.gallery.android.Permissions
import com.example.gallery.android.ServerState
import com.example.gallery.android.ServerStateHolder
import com.example.gallery.android.TrashCoordinator
import com.example.gallery.server.Feature

/** Survives rotation, but not the activity being destroyed, so every fresh launch asks for unlock again. */
class MainViewModel : ViewModel() {
    var authenticated by mutableStateOf(false)
    var authError by mutableStateOf<String?>(null)
    var promptShowing = false
    var askedPermission = false
    var askedNotifications = false
    var serverRequested = false
}

private enum class Screen { Checking, NeedPermission, Partial, NoLock, Unlock, SetPasscode, Server }

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()
    private var screen by mutableStateOf(Screen.Checking)
    private var showSettings by mutableStateOf(false)
    private var changingPasscode by mutableStateOf(false)

    /** Bumped on resume and after permission results so permission-dependent labels recompute. */
    private var refreshTick by mutableIntStateOf(0)

    private lateinit var passcode: PasscodeStore
    private lateinit var features: FeaturePrefs

    private val photoPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val smsPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        passcode = PasscodeStore(this)
        features = FeaturePrefs(this)
        enableEdgeToEdge()
        setContent {
            GalleryTheme { Root() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
        refresh()
        // A trash request is waiting for the user's answer (e.g. the heads-up notification was missed or
        // notifications are off): surface the system dialog now that the app is in front.
        if (TrashCoordinator.pending != null) {
            startActivity(Intent(this, TrashConfirmActivity::class.java))
        }
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
            Screen.SetPasscode -> SetPasscodeScreen(
                isChange = false,
                onSave = { code ->
                    passcode.set(code)
                    refresh()
                },
                onCancel = null,
            )
            Screen.Server -> when {
                changingPasscode -> SetPasscodeScreen(
                    isChange = true,
                    onSave = { code ->
                        passcode.set(code)
                        changingPasscode = false
                        Toast.makeText(this, "Passcode changed", Toast.LENGTH_SHORT).show()
                    },
                    onCancel = { changingPasscode = false },
                )
                showSettings -> SettingsContent()
                else -> ServerContent()
            }
        }
    }

    @Composable
    private fun ServerContent() {
        when (val s = ServerStateHolder.state) {
            is ServerState.Running -> RunningScreen(
                s,
                onCopy = { copyLink(s.url) },
                onSettings = { showSettings = true },
                onStop = { GalleryService.stop(this) },
            )
            ServerState.Starting -> StartingScreen()
            ServerState.NoWifi -> NoWifiScreen()
            ServerState.Stopped -> if (vm.serverRequested) StoppedScreen() else StartingScreen()
            is ServerState.Failed -> FailedScreen(s.message)
        }
    }

    @Composable
    private fun SettingsContent() {
        @Suppress("UNUSED_VARIABLE") val tick = refreshTick // recompute rows after returning from Settings
        val rows = listOf(
            row(Feature.PHOTOS, "Photos", "Browse, download and trash photos", null, null),
            row(Feature.STATUS, "Phone status", "Battery, storage and device name", null, null),
            row(
                Feature.NOTIFICATIONS, "Notifications", "See, dismiss and reply to notifications",
                if (NotificationHub.isAccessGranted(this)) null else "Needs Notification access in Android settings",
                "Open settings",
            ),
            row(
                Feature.MESSAGES, "Messages", "Read and send text messages",
                if (smsGranted()) null else "Needs permission to read and send SMS and read contacts",
                "Grant permission",
            ),
            row(Feature.FILES, "Files and clipboard", "Send files and text between phone and computer", null, null),
            row(Feature.FIND, "Find my phone", "Ring this phone from the browser", null, null),
        )
        SettingsScreen(
            rows = rows,
            ringing = false,
            onToggle = { r, on ->
                features.set(r.feature, on)
                if (on) onFeatureEnabled(r.feature)
                refreshTick++
            },
            onFix = { r -> onFeatureEnabled(r.feature, force = true) },
            onChangePasscode = { changingPasscode = true },
            onStopRinging = {},
            onBack = { showSettings = false },
        )
    }

    private fun row(feature: Feature, title: String, subtitle: String, problem: String?, fix: String?) =
        FeatureRow(feature, title, subtitle, features.isEnabled(feature), problem, fix)

    private fun smsGranted(): Boolean = listOf(
        Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS,
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun onFeatureEnabled(feature: Feature, force: Boolean = false) {
        when (feature) {
            Feature.NOTIFICATIONS ->
                if (force || !NotificationHub.isAccessGranted(this)) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            Feature.MESSAGES ->
                if (!smsGranted()) {
                    smsPermissions.launch(
                        arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS),
                    )
                }
            else -> Unit
        }
    }

    // ------------------------------------------------------------ flow

    /**
     * Permission -> screen lock -> unlock prompt -> web passcode -> start server. Re-evaluated on every
     * resume so that fixing something in Settings (or revoking access) is picked up immediately.
     */
    private fun refresh() {
        val next = when {
            Permissions.partialOnly(this) -> Screen.Partial
            !Permissions.fullAccess(this) -> Screen.NeedPermission
            Permissions.hasNoScreenLock(this) -> Screen.NoLock
            !vm.authenticated -> Screen.Unlock
            !passcode.isSet() -> Screen.SetPasscode
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
                .setTitle("Unlock R3U Gallery")
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
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
