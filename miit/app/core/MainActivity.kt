package com.miit.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectDragGestures
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
import kotlin.math.roundToInt

private enum class MiitScreen { CONNECTION, BAND, EDITOR }

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        MiitTestLog.add("Permissions result: ${result.entries.joinToString { "${it.key}=${it.value}" }}")
    }

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
    var showSettings by remember { mutableStateOf(false) }
    var showAuthKeyHelp by remember { mutableStateOf(false) }
    var editingDisplay by remember { mutableStateOf<BandDisplay?>(null) }
    var authSearchStatus by remember { mutableStateOf<String?>(null) }
    var authCandidates by remember { mutableStateOf<List<MiFitnessAuthKeyExtractor.Candidate>>(emptyList()) }
    val scope = rememberCoroutineScope()
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
                authSearchStatus = authSearchStatus,
                onFindAuthKey = {
                    authSearchStatus = "Searching Download/wearablelog…"
                    scope.launch {
                        val found = withContext(Dispatchers.IO) { MiFitnessAuthKeyExtractor.find(context) }
                        authCandidates = found
                        authSearchStatus = when {
                            found.isEmpty() -> "No key found in Download/wearablelog. Use the manual fallback."
                            found.size == 1 -> "Auth key found in Mi Fitness export ✓"
                            else -> "Multiple keys found. Choose the matching entry."
                        }
                        if (found.size == 1) {
                            authKeyText = found.first().key
                            prefs.edit().putString("auth_key", found.first().key).apply()
                        }
                    }
                },
                onOpenAuthKeyHelp = { showAuthKeyHelp = true }
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
                        "share" -> Toast.makeText(context, "Project sharing will be wired to the package exporter.", Toast.LENGTH_SHORT).show()
                        "export" -> Toast.makeText(context, "Export packaging is the next compiler step.", Toast.LENGTH_SHORT).show()
                        "band" -> Toast.makeText(context, "Direct installation is not implemented yet; no false success shown.", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        FloatingLogButton(Modifier.align(Alignment.BottomEnd).padding(16.dp).zIndex(10f))
    }

    if (showAuthKeyHelp) AuthKeyInstructionsDialog(onDismiss = { showAuthKeyHelp = false })
    if (authCandidates.size > 1) AuthKeyCandidatesDialog(authCandidates, {
        authKeyText = it.key
        prefs.edit().putString("auth_key", it.key).apply()
        authCandidates = emptyList()
        authSearchStatus = "Auth key selected ✓"
    }, { authCandidates = emptyList() })
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
    authSearchStatus: String?,
    onFindAuthKey: () -> Unit,
    onOpenAuthKeyHelp: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("Connect your Xiaomi Band") }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connection", style = MaterialTheme.typography.headlineSmall)
                        Text(connectionInstruction(state))
                        Button(onClick = onFindAuthKey, modifier = Modifier.fillMaxWidth()) { Text("Get auth key from Mi Fitness") }
                        authSearchStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = onOpenAuthKeyHelp, Modifier.fillMaxWidth()) { Text("How to find it") }
                        HorizontalDivider()
                        Text("Manual fallback", style = MaterialTheme.typography.titleMedium)
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
                            Text(if (device.authenticated) "✓ Connected and authenticated" else if (device.connected) "Connected" else "Available")
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
    Scaffold(topBar = {
        TopAppBar(title = { Text(band.name) }, actions = { IconButton(onClick = onSettings) { Text("⚙", fontSize = 22.sp) } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Band information", style = MaterialTheme.typography.titleLarge)
                        Text(buildList {
                            band.model?.let { add("Model: $it") }
                            band.firmware?.let { add("Firmware: $it") }
                            band.hardware?.let { add("Hardware: $it") }
                            band.batteryPercentage?.let { add("Battery: $it%") }
                            band.serialNumber?.let { add("Serial: $it") }
                        }.joinToString("\n"))
                    }
                }
            }
            item {
                Text("Band screens", style = MaterialTheme.typography.titleLarge)
                Text("System/menu items reported by the Band. They are not watchface image files.", color = Color.Gray, fontSize = 12.sp)
            }
            if (band.displays.isEmpty()) item { Text("No Band screen metadata received yet.", color = Color.Gray) }
            else items(band.displays, key = { it.stableId }) { BandMenuItemCard(it) }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Watch faces", style = MaterialTheme.typography.titleLarge)
                Text("Only watchface inventory entries are editable. No demo face is substituted for a missing Band resource.", color = Color.Gray, fontSize = 12.sp)
            }
            if (band.watchfaces.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("No watchface inventory received", style = MaterialTheme.typography.titleMedium)
                            Text("Visual resources require the watchface download/resource protocol before a faithful preview can be shown.", color = Color.Gray, fontSize = 12.sp)
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
            Text("1. Open Mi Fitness → Profile → About this app.")
            Text("2. Repeatedly tap the Mi Fitness logo to create the wearable log export.")
            Text("3. The usual export is Download/wearablelog/<timestamp>log.zip.")
            Text("4. MIIT looks inside that ZIP and its log files for token/authKey/encryptKey values.")
            Text("5. Manual fallback remains available when Android does not expose the export automatically.")
        }
    }, confirmButton = { Button(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun AuthKeyCandidatesDialog(candidates: List<MiFitnessAuthKeyExtractor.Candidate>, onSelect: (MiFitnessAuthKeyExtractor.Candidate) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose the Mi Fitness key") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            candidates.forEachIndexed { index, candidate ->
                OutlinedButton(onClick = { onSelect(candidate) }, Modifier.fillMaxWidth()) { Text("Candidate ${index + 1}\n${candidate.source.take(90)}") }
            }
            Text("The complete key is kept hidden.", color = Color.Gray)
        }
    }, confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Miit settings") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Editor: device-aware watchface authoring")
            Text("Band values are read from runtime discovery")
            Text("Authentication keys stay on this phone")
        }
    }, confirmButton = { Button(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun FloatingLogButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }.pointerInput(Unit) {
        detectDragGestures { change, amount -> change.consume(); offset += amount }
    }) {
        FloatingActionButton(onClick = {
            val log = MiitTestLog.text(context)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Miit testing logs", log))
            Toast.makeText(context, "Testing log copied.", Toast.LENGTH_LONG).show()
        }, shape = RectangleShape) { Text("LOG", fontWeight = FontWeight.Bold) }
    }
}

private fun connectionInstruction(state: BandConnectionState): String = when (state) {
    BandConnectionState.Idle -> "Get the auth key from Mi Fitness first, or enter it manually, then scan for the Band."
    BandConnectionState.Scanning -> "Keep the Band nearby and awake, then select it from the list."
    BandConnectionState.Connecting -> "Complete Android pairing and wait for Xiaomi authentication."
    BandConnectionState.Connected -> "Bluetooth transport connected; authentication is continuing."
    BandConnectionState.AwaitingXiaomiBinding -> "Android pairing is complete; Xiaomi binding is continuing."
    BandConnectionState.Authenticating -> "Authenticating with the Band… please wait."
    BandConnectionState.Authenticated -> "Connection complete. Runtime Band data is being collected."
    BandConnectionState.Disconnected -> "The Band disconnected. Scan and connect again."
    BandConnectionState.Error -> "Connection failed. Check the auth key and try again."
}
