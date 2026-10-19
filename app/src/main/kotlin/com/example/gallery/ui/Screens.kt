package com.example.gallery.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gallery.android.ServerState

@Composable
private fun ScreenFrame(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .systemBarsPadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

@Composable
private fun Title(text: String) = Text(
    text,
    style = MaterialTheme.typography.headlineSmall,
    fontWeight = FontWeight.SemiBold,
    textAlign = TextAlign.Center,
)

@Composable
private fun Body(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
)

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit, onSettings: () -> Unit) = ScreenFrame {
    Title("Allow access to your photos")
    Body("R3U | Phone Link needs access to all your photos to show them on your computer. Choose “Allow all” when Android asks.")
    Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("Allow photo access") }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Open app settings") }
}

@Composable
fun PartialAccessScreen(onSettings: () -> Unit) = ScreenFrame {
    Title("Full photo access is required")
    Body("You allowed only some photos. R3U | Phone Link can’t show your whole library that way. Open settings, tap Photos and videos, and choose “Allow all”.")
    Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Fix in settings") }
}

@Composable
fun NoScreenLockScreen(onSettings: () -> Unit) = ScreenFrame {
    Title("Set a screen lock first")
    Body("R3U | Phone Link shares your phone over Wi-Fi, so it only runs on phones protected by a PIN, pattern, password, or fingerprint. Set one up, then come back.")
    Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Open security settings") }
}

@Composable
fun UnlockScreen(message: String?, onUnlock: () -> Unit) = ScreenFrame {
    Title("Unlock to continue")
    Body(message ?: "Confirm it’s you with your fingerprint, face, or passcode to start sharing.")
    Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
}

@Composable
fun StartingScreen() = ScreenFrame {
    CircularProgressIndicator()
    Spacer(Modifier.height(20.dp))
    Title("Starting server…")
}

@Composable
fun NoWifiScreen() = ScreenFrame {
    StatusPill("Not sharing", MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(20.dp))
    Title("Connect to Wi-Fi")
    Body("Your computer and this phone must be on the same Wi-Fi network. R3U | Phone Link will start sharing as soon as you connect.")
}

@Composable
fun StoppedScreen() = ScreenFrame {
    StatusPill("Stopped", MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(20.dp))
    Title("Server stopped")
    Body("Nothing is shared. Starts automatically when you open the app.")
}

@Composable
fun FailedScreen(message: String) = ScreenFrame {
    StatusPill("Problem", MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(20.dp))
    Title("Couldn’t start")
    Body(message)
}

@Composable
fun RunningScreen(state: ServerState.Running, onCopy: () -> Unit, onSettings: () -> Unit, onStop: () -> Unit) = ScreenFrame {
    StatusPill("Running", Color(0xFF2E9E5B))
    Spacer(Modifier.height(20.dp))
    Title("Open your gallery")
    Body("On your computer, scan this code or type the link into your browser.")

    val qr = remember(state.url) { qrBitmap(state.url) }
    Image(
        bitmap = qr.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier
            .size(240.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(10.dp)
            .semantics { contentDescription = "QR code for the gallery link" },
    )

    Spacer(Modifier.height(20.dp))
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            state.url,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }

    Spacer(Modifier.height(14.dp))
    Button(onClick = onCopy, modifier = Modifier.fillMaxWidth()) { Text("Copy link") }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Features and passcode") }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop server") }

    Text(
        "Starts automatically when you open the app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 18.dp),
    )
}

// ---------------------------------------------------------------- Phase 1: passcode + settings

@Composable
fun SetPasscodeScreen(isChange: Boolean, onSave: (String) -> Unit, onCancel: (() -> Unit)?) = ScreenFrame {
    var code by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = code.isNotEmpty() && code.length < com.example.gallery.android.PasscodeStore.MIN_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != code
    val ok = code.length >= com.example.gallery.android.PasscodeStore.MIN_LENGTH && code == confirm

    Title(if (isChange) "Change web passcode" else "Set a web passcode")
    Body(
        "Anyone opening your gallery in a browser must enter this passcode. Use at least " +
            "${com.example.gallery.android.PasscodeStore.MIN_LENGTH} characters. It is stored only as a hash on this phone.",
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("New passcode") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = tooShort,
        supportingText = { if (tooShort) Text("At least ${com.example.gallery.android.PasscodeStore.MIN_LENGTH} characters") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = confirm,
        onValueChange = { confirm = it },
        label = { Text("Repeat passcode") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = mismatch,
        supportingText = { if (mismatch) Text("Doesn’t match") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(18.dp))
    Button(onClick = { onSave(code) }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Save passcode") }
    if (onCancel != null) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

data class FeatureRow(
    val feature: com.example.gallery.server.Feature,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val problem: String?,
    val fixLabel: String?,
)

@Composable
fun SettingsScreen(
    rows: List<FeatureRow>,
    ringing: Boolean,
    onToggle: (FeatureRow, Boolean) -> Unit,
    onFix: (FeatureRow) -> Unit,
    onChangePasscode: () -> Unit,
    onStopRinging: () -> Unit,
    onBack: () -> Unit,
) = ScreenFrame {
    Title("What your computer can use")
    Body("Switch on only what you need. Each feature stays locked behind your web passcode.")
    rows.forEach { row ->
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = row.enabled, onCheckedChange = { onToggle(row, it) })
                }
                if (row.enabled && row.problem != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(row.problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (row.fixLabel != null) {
                        TextButton(onClick = { onFix(row) }) { Text(row.fixLabel) }
                    }
                }
            }
        }
    }
    if (ringing) {
        Button(onClick = onStopRinging, modifier = Modifier.fillMaxWidth()) { Text("Stop ringing") }
        Spacer(Modifier.height(10.dp))
    }
    OutlinedButton(onClick = onChangePasscode, modifier = Modifier.fillMaxWidth()) { Text("Change web passcode") }
    Spacer(Modifier.height(10.dp))
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}
