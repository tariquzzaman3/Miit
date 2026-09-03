package com.miit.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PasswordVisualTransformation
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.miit.app.band.AuthKeyParser
import com.miit.app.band.BandConnectionState
import com.miit.app.band.BandDevice
import com.miit.app.band.BandDisplay
import com.miit.app.band.BandScanner
import com.miit.app.band.MiitTestLog
import com.miit.app.band.MiFitnessAuthKeyExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MiitScreen { CONNECTION, BAND, EDITOR }

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> MiitTestLog.add("Permissions result: ${result.entries.joinToString { "${it.key}=${it.value}" }}") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MiitTestLog.add("App started")
        val requested = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissions.launch(requested)
        setContent { MiitApp() }
    }

    @Deprecated("Use Activity Result APIs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        BandScanner.dispatchCompanionResult(requestCode, resultCode, data)
    }
}

@Composable
private fun MiitApp() {
    val context = LocalContext.current
    val scanner = remember { BandScanner(context) }
    val devices by scanner.devices.collectAsState()
    val connectionState by scanner.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("miit_pairing", Context.MODE_PRIVATE) }
    var authKeyText by remember { mutableStateOf(prefs.getString("auth_key", "") ?: "") }
    var screen by remember { mutableStateOf(MiitScreen.CONNECTION) }
    var connectedBand by remember { mutableStateOf<BandDevice?>(null) }
    var editingDisplay by remember { mutableStateOf<BandDisplay?>(null) }
    var authStatus by remember { mutableStateOf<String?>(null) }
    var authCandidates by remember { mutableStateOf<List<MiFitnessAuthKeyExtractor.Candidate>>(emptyList()) }
    var showAuthHelp by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun acceptCandidates(found: List<MiFitnessAuthKeyExtractor.Candidate>) {
        when {
            found.isEmpty() -> authStatus = "No auth key found automatically. Select the wearablelog ZIP or use manual fallback."
            found.size == 1 -> {
                authKeyText = found.first().key
                prefs.edit().putString("auth_key", found.first().key).apply()
                authStatus = "Auth key found in Mi Fitness export ✓"
            }
            else -> {
                authCandidates = found
                authStatus = "Multiple possible keys found. Choose the matching key."
            }
        }
    }

    val authFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        authStatus = "Reading selected wearablelog ZIP…"
        scope.launch {
            val found = withContext(Dispatchers.IO) { MiFitnessAuthKeyExtractor.findFromUri(context, uri) }
            acceptCandidates(found)
        }
    }

    val authenticatedDevice = devices.firstOrNull { it.authenticated }
    LaunchedEffect(authenticatedDevice) {
        if (authenticatedDevice != null) {
            connectedBand = authenticatedDevice
            screen = MiitScreen.BAND
        }
    }

    BackHandler(enabled = screen != MiitScreen.CONNECTION) {
        if (screen == MiitScreen.EDITOR) screen = MiitScreen.BAND
    }
    DisposableEffect(Unit) { onDispose { scanner.close() } }

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            MiitScreen.CONNECTION -> ConnectionScreen(
                scanner = scanner,
                devices = devices,
                state = connectionState,
                authKeyText = authKeyText,
                onAuthKeyChange = {
                    authKeyText = it
                    prefs.edit().putString("auth_key", it.trim()).apply()
                },
                authStatus = authStatus,
                onFindAuthKey = {
                    authStatus = "Searching Download/wearablelog/<timestamp>log.zip…"
                    scope.launch {
                        val found = withContext(Dispatchers.IO) { MiFitnessAuthKeyExtractor.find(context) }
                        acceptCandidates(found)
                    }
                },
                onPickZip = { authFilePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                onOpenHelp = { showAuthHelp = true }
            )
            MiitScreen.BAND -> connectedBand?.let { band ->
                BandScreen(
                    band = band,
                    onSettings = { showSettings = true },
                    onEdit = { display -> editingDisplay = display; screen = MiitScreen.EDITOR },
                    onCustomDisplay = { editingDisplay = null; screen = MiitScreen.EDITOR }
                )
            }
            MiitScreen.EDITOR -> EditorScreen(
                display = editingDisplay,
                device = connectedBand,
                onBack = { screen = MiitScreen.BAND },
                onAction = { action ->
                    when (action) {
                        "save" -> Toast.makeText(context, "Project saved on this phone.", Toast.LENGTH_SHORT).show()
                        "export" -> Toast.makeText(context, "Export packaging is the next compiler step.", Toast.LENGTH_SHORT).show()
                        "band" -> Toast.makeText(context, "Direct installation is not implemented yet.", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
        FloatingLogButton(Modifier.align(Alignment.BottomEnd).padding(16.dp).zIndex(10f))
    }

    if (showAuthHelp) AuthKeyInstructionsDialog(onDismiss = { showAuthHelp = false })
    if (authCandidates.size > 1) {
        AuthKeyCandidatesDialog(
            candidates = authCandidates,
            onSelect = { candidate ->
                authKeyText = candidate.key
                prefs.edit().putString("auth_key", candidate.key).apply()
                authCandidates = emptyList()
                authStatus = "Auth key selected ✓"
            },
            onDismiss = { authCandidates = emptyList() }
        )
    }
    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionScreen(
    scanner: BandScanner,
    devices: List<BandDevice>,
    state: BandConnectionState,
    authKeyText: String,
    onAuthKeyChange: (String) -> Unit,
    authStatus: String?,
    onFindAuthKey: () -> Unit,
    onPickZip: () -> Unit,
    onOpenHelp: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("Connect your Xiaomi Band") }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Automatic — Recommended", style = MaterialTheme.typography.titleLarge)
                        Text("MIIT first checks Download/wearablelog, opens the exported ZIP, then searches the contained log files for the auth key.", color = Color.Gray)
                        Button(onClick = onFindAuthKey, modifier = Modifier.fillMaxWidth()) { Text("Get auth key from Mi Fitness") }
                        OutlinedButton(onClick = onPickZip, modifier = Modifier.fillMaxWidth()) { Text("Select wearablelog ZIP") }
                        authStatus?.let { Text(it, fontSize = 12.sp) }
                        OutlinedButton(onClick = onOpenHelp, modifier = Modifier.fillMaxWidth()) { Text("How it works") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Manual fallback", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = authKeyText,
                            onValueChange = onAuthKeyChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Xiaomi auth key") },
                            supportingText = { Text("32 hexadecimal characters") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scanner.startScan() }) { Text(if (state == BandConnectionState.Scanning) "Scanning…" else "Scan for band") }
                            if (state == BandConnectionState.Scanning) OutlinedButton(onClick = { scanner.stopScan() }) { Text("Stop") }
                        }
                    }
                }
            }
            if (devices.isNotEmpty()) {
                item { Text("Nearby Xiaomi bands", style = MaterialTheme.typography.titleLarge) }
                items(devices, key = { it.address }) { device ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (device.authenticated) "✓ Connected and authenticated" else "Available")
                            Text("Signal: ${device.rssi} dBm")
                            Button(onClick = {
                                val key = AuthKeyParser.parse(authKeyText)
                                if (key == null) Toast.makeText(context, "Enter a valid 32-character hexadecimal auth key first.", Toast.LENGTH_LONG).show()
                                else scanner.connect(device, key)
                            }, enabled = !device.connected && !device.authenticated) { Text("Connect") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandScreen(
    band: BandDevice,
    onSettings: () -> Unit,
    onEdit: (BandDisplay) -> Unit,
    onCustomDisplay: () -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text(band.name) }, actions = { IconButton(onClick = onSettings) { Text("⚙", fontSize = 22.sp) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Band information", style = MaterialTheme.typography.titleLarge)
                        buildList {
                            band.model?.let { add("Model: $it") }
                            band.firmware?.let { add("Firmware: $it") }
                            band.hardware?.let { add("Hardware: $it") }
                            band.batteryPercentage?.let { add("Battery: $it%") }
                            band.serialNumber?.let { add("Serial: $it") }
                        }.forEach { Text(it) }
                    }
                }
            }
            item {
                Text("Band screens", style = MaterialTheme.typography.titleLarge)
                Text("Workout, Sleep, Timer, Alarm and similar entries are system/menu metadata, not watchface image files.", color = Color.Gray, fontSize = 12.sp)
            }
            if (band.displays.isEmpty()) item { Text("No Band screen metadata received yet.", color = Color.Gray) }
            else items(band.displays, key = { it.stableId }) { BandMenuItemCard(it) }

            item {
                Spacer(Modifier.height(6.dp))
                Text("Watch faces", style = MaterialTheme.typography.titleLarge)
                Text("Only real watchface inventory entries appear here. No demo face is substituted for a missing resource.", color = Color.Gray, fontSize = 12.sp)
            }
            if (band.watchfaces.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("No watchface resource available", style = MaterialTheme.typography.titleMedium)
                            Text("A faithful preview needs the Band watchface resource/download protocol. Menu metadata alone is not an image.", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            } else items(band.watchfaces, key = { it.stableId }) { RuntimeWatchfaceCard(it, onEdit) }
            item { Button(onClick = onCustomDisplay, Modifier.fillMaxWidth()) { Text("＋ Create new watch face") } }
        }
    }
}

@Composable
private fun BandMenuItemCard(display: BandDisplay) {
    val name = display.name?.takeIf { it.isNotBlank() } ?: display.code?.takeIf { it.isNotBlank() } ?: "Unnamed screen"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            display.code?.let { Text("Code: $it", color = Color.Gray, fontSize = 11.sp) }
            val flags = buildList {
                if (display.active) add("Current")
                if (display.inMoreSection) add("More")
                if (display.disabled) add("Disabled")
            }
            if (flags.isNotEmpty()) Text(flags.joinToString(" • "), color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RuntimeWatchfaceCard(display: BandDisplay, onEdit: (BandDisplay) -> Unit) {
    val name = display.name?.takeIf { it.isNotBlank() } ?: display.code?.takeIf { it.isNotBlank() } ?: "Unnamed watch face"
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(76.dp).height(120.dp).background(Color.Black, androidx.compose.foundation.shape.RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Text("No image", color = Color.Gray, fontSize = 10.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                display.code?.let { Text(it, color = Color.Gray, fontSize = 11.sp) }
                if (display.active) Text("Current watch face", color = Color(0xFF55D8C7), fontSize = 11.sp)
                OutlinedButton(onClick = { onEdit(display) }) { Text("Edit") }
            }
        }
    }
}

@Composable
private fun EditorScreen(display: BandDisplay?, device: BandDevice?, onBack: () -> Unit, onAction: (String) -> Unit) =
    MiitWatchFaceEditor(display, device, onBack, onAction)

@Composable
private fun AuthKeyInstructionsDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Find the auth key from Mi Fitness") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("1. In Mi Fitness open Profile → About this app.")
            Text("2. Repeatedly tap the Mi Fitness logo to generate the wearable log export.")
            Text("3. The usual location is Download/wearablelog/<timestamp>log.zip.")
            Text("4. MIIT opens that ZIP and searches every contained log file for token/authKey/encryptKey values with a 32-character hexadecimal key.")
            Text("5. When Android hides the folder, use Select wearablelog ZIP.")
        }
    }, confirmButton = { Button(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun AuthKeyCandidatesDialog(
    candidates: List<MiFitnessAuthKeyExtractor.Candidate>,
    onSelect: (MiFitnessAuthKeyExtractor.Candidate) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose the Mi Fitness key") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            candidates.forEachIndexed { index, candidate ->
                OutlinedButton(onClick = { onSelect(candidate) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Candidate ${index + 1}\n${candidate.source.take(100)}")
                }
            }
            Text("The complete key is kept hidden.", color = Color.Gray)
        }
    }, confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Miit settings") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Device-aware watchface editor")
            Text("Runtime Band values are collected after Xiaomi authentication")
            Text("Authentication keys remain on this phone")
        }
    }, confirmButton = { Button(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun FloatingLogButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    FloatingActionButton(
        onClick = {
            val log = MiitTestLog.text(context)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Miit testing logs", log))
            Toast.makeText(context, "Testing log copied.", Toast.LENGTH_LONG).show()
        },
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) { Text("LOG", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

private fun connectionInstruction(state: BandConnectionState): String = when (state) {
    BandConnectionState.Idle -> "Get the auth key from Mi Fitness first, or use manual fallback, then scan for the Band."
    BandConnectionState.Scanning -> "Keep the Band nearby and awake, then select it from the list."
    BandConnectionState.Connecting -> "Complete Android pairing and wait for Xiaomi authentication."
    BandConnectionState.Connected -> "Bluetooth transport connected; authentication is continuing."
    BandConnectionState.AwaitingXiaomiBinding -> "Android pairing is complete; Xiaomi binding is continuing."
    BandConnectionState.Authenticating -> "Authenticating with the Band…"
    BandConnectionState.Authenticated -> "Connection complete. Runtime Band data is being collected."
    BandConnectionState.Disconnected -> "The Band disconnected. Scan and connect again."
    BandConnectionState.Error -> "Connection failed. Check the auth key and try again."
}
