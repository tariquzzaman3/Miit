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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.miit.app.band.AuthKeyParser
import com.miit.app.band.BandConnectionState
import com.miit.app.band.BandDevice
import com.miit.app.band.BandScanner
import com.miit.app.band.MiitTestLog

private enum class MiitScreen { CONNECTION, BAND, EDITOR }

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        MiitTestLog.add("Permissions result: ${result.entries.joinToString { "${it.key}=${it.value}" }}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MiitTestLog.add("App started")
        val requested = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
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
    var showSettings by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }
    var editingDisplay by remember { mutableStateOf<String?>(null) }

    val authenticatedDevice = devices.firstOrNull { it.authenticated }

    LaunchedEffect(authenticatedDevice?.address, connectionState) {
        when {
            authenticatedDevice != null && screen == MiitScreen.CONNECTION -> screen = MiitScreen.BAND
            authenticatedDevice == null &&
                connectionState != BandConnectionState.Authenticating &&
                connectionState != BandConnectionState.Connecting &&
                screen == MiitScreen.BAND -> screen = MiitScreen.CONNECTION
        }
    }

    DisposableEffect(Unit) {
        onDispose { scanner.close() }
    }

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
            onConnected = {
                if (authenticatedDevice != null) screen = MiitScreen.BAND
            },
            onOpenInstructions = { showInstructions = true }
        )

        MiitScreen.BAND -> {
            val band = authenticatedDevice
            if (band == null) {
                screen = MiitScreen.CONNECTION
            } else {
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
        }

        MiitScreen.EDITOR -> EditorScreen(
            displayName = editingDisplay,
            onBack = { screen = MiitScreen.BAND },
            onAction = { action ->
                when (action) {
                    "save" -> {
                        prefs.edit().putString("last_display", editingDisplay ?: "Custom display").apply()
                        Toast.makeText(context, "Display saved on this phone.", Toast.LENGTH_SHORT).show()
                    }
                    "share" -> {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Miit display: ${editingDisplay ?: "Custom display"}")
                        }
                        context.startActivity(Intent.createChooser(send, "Share display"))
                    }
                    "band" -> Toast.makeText(
                        context,
                        "Display prepared. Band display installation will use the active Xiaomi transport.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    if (showInstructions) {
        InstructionsDialog(onDismiss = { showInstructions = false })
    }
    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionScreen(
    scanner: BandScanner,
    devices: List<BandDevice>,
    state: BandConnectionState,
    authKeyText: String,
    onAuthKeyChange: (String) -> Unit,
    onConnected: () -> Unit,
    onOpenInstructions: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = { TopAppBar(title = { Text("Connect your Xiaomi Band") }) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Before connection", style = MaterialTheme.typography.headlineSmall)
                        Text(connectionInstruction(state))
                        OutlinedButton(onClick = onOpenInstructions, Modifier.fillMaxWidth()) {
                            Text("Connection instructions")
                        }
                        OutlinedTextField(
                            value = authKeyText,
                            onValueChange = onAuthKeyChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Xiaomi auth / pairing key") },
                            supportingText = { Text("32 hexadecimal characters. This value is device-specific and stays on the phone.") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    MiitTestLog.add("User tapped Scan for band")
                                    scanner.startScan()
                                },
                                enabled = state != BandConnectionState.Connecting &&
                                    state != BandConnectionState.Authenticating
                            ) {
                                Text(if (state == BandConnectionState.Scanning) "Scanning…" else "Scan for band")
                            }
                            if (state == BandConnectionState.Scanning) {
                                OutlinedButton(onClick = {
                                    MiitTestLog.add("User tapped Stop scan")
                                    scanner.stopScan()
                                }) { Text("Stop") }
                            }
                        }
                    }
                }
            }

            if (state == BandConnectionState.Authenticated) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Connection successful", style = MaterialTheme.typography.titleLarge)
                            Text("The Xiaomi SPPv2 session is authenticated.")
                            Button(onClick = onConnected, Modifier.fillMaxWidth()) { Text("Continue to band") }
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
                            Button(
                                onClick = {
                                    val key = AuthKeyParser.parse(authKeyText)
                                    if (key == null) {
                                        Toast.makeText(context, "Enter a valid 32-character hexadecimal auth key first.", Toast.LENGTH_LONG).show()
                                    } else {
                                        MiitTestLog.add("User tapped Connect: ${device.name} (${device.address})")
                                        scanner.connect(device, key)
                                    }
                                },
                                enabled = !device.connected && !device.authenticated
                            ) { Text("Connect") }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        val log = MiitTestLog.text(context)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Miit testing logs", log))
                        Toast.makeText(context, "Testing log copied.", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Copy testing logs") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandScreen(
    band: BandDevice,
    onSettings: () -> Unit,
    onEdit: (String) -> Unit,
    onCustomDisplay: () -> Unit
) {
    val displays = listOf("Current display", "Minimal digital", "Classic dashboard")
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(band.name)
                        Text("Connected & authenticated", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Text("⚙", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Band details", style = MaterialTheme.typography.titleMedium)
                    Text("Battery: ${band.batteryPercentage?.let { "$it%" } ?: "—"}")
                    Text("Version: ${band.firmware ?: "—"}")
                    Text("Country variant: ${band.countryVariant ?: "—"}")
                    Text("Model: ${band.model ?: "—"}")
                }
            }

            Text("Band displays", style = MaterialTheme.typography.titleLarge)
            Text(
                "Displays retrieved from the band will appear here. Editing is available for supported display projects.",
                style = MaterialTheme.typography.bodyMedium
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(displays) { display ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.padding(top = 4.dp)) {
                                Text(display, style = MaterialTheme.typography.titleMedium)
                                Text("Xiaomi Band display project")
                            }
                            Button(onClick = { onEdit(display) }) { Text("Edit") }
                        }
                    }
                }
            }
            Button(onClick = onCustomDisplay, Modifier.fillMaxWidth()) {
                Text("＋ Custom display")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    displayName: String?,
    onBack: () -> Unit,
    onAction: (String) -> Unit
) {
    var tool by remember { mutableStateOf("Select") }
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName ?: "Custom display") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) { Text("⋮", style = MaterialTheme.typography.headlineMedium) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Save on phone") },
                            onClick = { menuExpanded = false; onAction("save") }
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = { menuExpanded = false; onAction("share") }
                        )
                        DropdownMenuItem(
                            text = { Text("Set as band display") },
                            onClick = { menuExpanded = false; onAction("band") }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Editor", style = MaterialTheme.typography.titleLarge)
                    Text("Canvas preview", style = MaterialTheme.typography.titleMedium)
                    Card(Modifier.fillMaxWidth().weight(1f)) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                            Text("12:45", style = MaterialTheme.typography.displayLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("Tuesday  •  25 Aug")
                            Spacer(Modifier.height(16.dp))
                            Text("♥ 72   •   6,421 steps")
                        }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Select", "Text", "Image", "Shape", "AI", "Font").forEach { toolName ->
                            Button(onClick = { tool = toolName }) {
                                Text(if (tool == toolName) "✓ $toolName" else toolName)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstructionsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How to connect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Enter the Xiaomi pairing/auth key supplied for the band.")
                Text("2. Tap Scan for band and select the matching Xiaomi Smart Band.")
                Text("3. Allow Android's system pairing/association request when it appears.")
                Text("4. Wait while Android bonding, RFCOMM/SPP, session negotiation and Xiaomi authentication complete.")
                Text("5. Do not press Connect repeatedly during the pairing process.")
                Text("The auth key is device-specific. Miit must never hard-code another user's key.")
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Got it") } }
    )
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Miit settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("App")
                Text("Keep Xiaomi auth data on this device and never embed a user's key into the app.")
                Text("Band")
                Text("Communication is device/profile based; band-specific values are detected at runtime.")
                Text("Editor")
                Text("Manage local displays, fonts and AI provider settings here.")
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } }
    )
}

private fun connectionInstruction(state: BandConnectionState): String = when (state) {
    BandConnectionState.Idle -> "Enter the Xiaomi auth key, scan, select your band, then allow Android's pairing request."
    BandConnectionState.Scanning -> "Keep the band awake and nearby. Select it when it appears."
    BandConnectionState.Connecting -> "Android is bonding with the band. Wait for pairing to finish; do not press Connect repeatedly."
    BandConnectionState.Connected -> "Bluetooth transport is connected; Xiaomi authentication is continuing."
    BandConnectionState.AwaitingXiaomiBinding -> "Android pairing is complete. Miit is continuing the Xiaomi binding process."
    BandConnectionState.Authenticating -> "Xiaomi authentication is in progress. Keep the band nearby."
    BandConnectionState.Authenticated -> "Authentication succeeded. Continue to the band screen."
    BandConnectionState.Disconnected -> "The band disconnected. Scan and connect again."
    BandConnectionState.Error -> "Connection failed. Check the auth key and accept Android's pairing request, then try again."
}
