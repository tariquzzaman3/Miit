package com.miit.app

import android.Manifest
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
                            found.isEmpty() -> "No key found in wearablelog. Use the manual fallback or inspect the exported ZIP."
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
                    onEdit = { display ->
                        editingDisplay = display
                        screen = MiitScreen.EDITOR
                    },
                    onCustomDisplay = {
                        editingDisplay = null
                        screen = MiitScreen.EDITOR
                    }
                )
            }

            MiitScreen.EDITOR -> EditorScreen(
                display = editingDisplay,
                device = connectedBand,
                onBack = { screen = MiitScreen.BAND },
                onAction = { action ->
                    when (action) {
                        "save" -> {
                            prefs.edit().putString("last_display", editingDisplay?.stableId ?: "Custom display").apply()
                            Toast.makeText(context, "Project saved on this phone.", Toast.LENGTH_SHORT).show()
                        }
                        "share" -> Toast.makeText(context, "Sharing will be wired to the project package.", Toast.LENGTH_SHORT).show()
                        "export" -> Toast.makeText(context, "Export packaging is the next compiler step.", Toast.LENGTH_SHORT).show()
                        "band" -> Toast.makeText(context, "Direct installation is not implemented yet; no false success shown.", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        FloatingLogButton(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).zIndex(10f)
        )
    }

    if (showAuthKeyHelp) AuthKeyInstructionsDialog(onDismiss = { showAuthKeyHelp = false })
    if (authCandidates.size > 1) {
        AuthKeyCandidatesDialog(
            candidates = authCandidates,
            onSelect = { candidate ->
                authKeyText = candidate.key
                prefs.edit().putString("auth_key", candidate.key).apply()
                authCandidates = emptyList()
                authSearchStatus = "Auth key selected ✓"
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
                        Text("Connection", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                        Text(connectionInstruction(state))
                        Button(onClick = onFindAuthKey, enabled = state != BandConnectionState.Connecting && state != BandConnectionState.Authenticating, modifier = Modifier.fillMaxWidth()) {
                            Text("Get auth key from Mi Fitness")
                        }
                        authSearchStatus?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = onOpenAuthKeyHelp, Modifier.fillMaxWidth()) { Text("How to find it from your phone") }
                        HorizontalDivider()
                        Text("Manual fallback", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = authKeyText,
                            onValueChange = onAuthKeyChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Xiaomi auth key") },
                            supportingText = { Text("32 hexadecimal characters. Stored only on this phone.") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scanner.startScan() }, enabled = state != BandConnectionState.Connecting && state != BandConnectionState.Authenticating) {
                                Text(if (state == BandConnectionState.Scanning) "Scanning…" else "Scan for band")
                            }
                            if (state == BandConnectionState.Scanning) OutlinedButton(onClick = { scanner.stopScan() }) { Text("Stop") }
                        }
                    }
                }
            }

            if (devices.isNotEmpty()) {
                item { Text("Nearby Xiaomi bands", style = androidx.compose.material3.MaterialTheme.typography.titleLarge) }
                items(devices, key = { it.address }) { device ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(device.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                            Text(when {
                                device.authenticated -> "✓ Connected and authenticated"
                                device.connected -> "Connected"
                                else -> "Available"
                            })
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(band.name) },
                actions = { IconButton(onClick = onSettings) { Text("⚙", fontSize = 22.sp) } }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Band information", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
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
                Text("Band screens", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                Text("These are menu/system items reported by the Band. They are metadata, not watchface image files.", color = Color.Gray, fontSize = 12.sp)
            }

            if (band.displays.isEmpty()) {
                item { Text("No Band screen metadata received yet.", color = Color.Gray) }
            } else {
                items(band.displays, key = { it.stableId }) { display -> BandMenuItemCard(display) }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Watch faces", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                Text("Only actual watchface inventory entries are editable here. MIIT will not substitute demo data for a Band resource.", color = Color.Gray, fontSize = 12.sp)
            }

            if (band.watchfaces.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("No watchface inventory received", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                            Text("The current connection layer can identify menu items; visual watchface resources will require the watchface download/resource protocol before they can be previewed faithfully.", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                items(band.watchfaces, key = { it.stableId }) { display -> RuntimeWatchfaceCard(display, onEdit) }
            }

            item { Button(onClick = onCustomDisplay, Modifier.fillMaxWidth()) { Text("＋ Create new watch face") } }
        }
    }
}

@Composable
private fun BandMenuItemCard(display: BandDisplay) {
    val name = display.name?.takeIf { it.isNotBlank() } ?: display.code?.takeIf { it.isNotBlank() } ?: "Unnamed screen"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            display.code?.takeIf { it.isNotBlank() }?.let { Text("Code: $it", color = Color.Gray, fontSize = 11.sp) }
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                display.code?.let { Text(it, color = Color.Gray, fontSize = 11.sp) }
                if (display.active) Text("Current watch face", color = Color(0xFF55D8C7), fontSize = 11.sp)
                OutlinedButton(onClick = { onEdit(display) }) { Text("Edit") }
            }
        }
    }
}

@Composable
private fun AuthKeyInstructionsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find the auth key from Mi Fitness") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("1. Keep your Band paired with the official Mi Fitness app.")
                Text("2. Open Mi Fitness → Profile → About this app.")
                Text("3. Repeatedly tap the Mi Fitness logo to create the wearable log export.")
                Text("4. The export normally appears under Download/wearablelog/ as a ZIP such as 1788456025701log.zip.")
                Text("5. MIIT first searches that wearablelog ZIP and its log files for token/authKey/encryptKey values.")
                Text("The Xiaomi account password is never requested by MIIT.")
            }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun AuthKeyCandidatesDialog(
    candidates: List<MiFitnessAuthKeyExtractor.Candidate>,
    onSelect: (MiFitnessAuthKeyExtractor.Candidate) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the Mi Fitness key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                candidates.forEachIndexed { index, candidate ->
                    OutlinedButton(onClick = { onSelect(candidate) }, Modifier.fillMaxWidth()) {
                        Text("Candidate ${index + 1}\n${candidate.source.take(90)}")
                    }
                }
                Text("The complete key is not displayed in the chooser.", color = Color.Gray)
            }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
