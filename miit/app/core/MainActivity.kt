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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.miit.app.band.AuthKeyParser
import com.miit.app.band.BandConnectionState
import com.miit.app.band.BandDevice
import com.miit.app.band.BandDisplay
import com.miit.app.band.BandScanner
import com.miit.app.band.MiitTestLog
import kotlin.math.roundToInt

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
    var connectedBand by remember { mutableStateOf<BandDevice?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showAuthKeyHelp by remember { mutableStateOf(false) }
    var editingDisplay by remember { mutableStateOf<BandDisplay?>(null) }

    val authenticatedDevice = devices.firstOrNull { it.authenticated }

    LaunchedEffect(authenticatedDevice) {
        if (authenticatedDevice != null) {
            connectedBand = authenticatedDevice
            screen = MiitScreen.BAND
        }
    }

    BackHandler(enabled = screen != MiitScreen.CONNECTION) {
        when (screen) {
            MiitScreen.EDITOR -> screen = MiitScreen.BAND
            MiitScreen.BAND -> Unit
            MiitScreen.CONNECTION -> Unit
        }
    }

    DisposableEffect(Unit) {
        onDispose { scanner.close() }
    }

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
                onOpenAuthKeyHelp = { showAuthKeyHelp = true }
            )

            MiitScreen.BAND -> {
                val band = connectedBand
                if (band != null) {
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
                display = editingDisplay,
                onBack = { screen = MiitScreen.BAND },
                onAction = { action ->
                    when (action) {
                        "save" -> {
                            prefs.edit().putString("last_display", editingDisplay?.stableId ?: "Custom display").apply()
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
                            "Display prepared for the active Xiaomi connection.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }

        FloatingLogButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .zIndex(10f)
        )
    }

    if (showAuthKeyHelp) {
        AuthKeyInstructionsDialog(onDismiss = { showAuthKeyHelp = false })
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
    onOpenAuthKeyHelp: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("Connect your Xiaomi Band") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connection", style = MaterialTheme.typography.headlineSmall)
                        Text(connectionInstruction(state))
                        OutlinedButton(onClick = onOpenAuthKeyHelp, Modifier.fillMaxWidth()) {
                            Text("How to find the auth key")
                        }
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

            if (devices.isNotEmpty()) {
                item { Text("Nearby Xiaomi bands", style = MaterialTheme.typography.titleLarge) }
                items(devices, key = { it.address }) { device ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    device.authenticated -> "✓ Connected and authenticated"
                                    device.connected -> "Connected"
                                    else -> "Available"
                                }
                            )
                            Text("Signal: ${device.rssi} dBm")
                            if (device.model != null || device.firmware != null) {
                                Text("${device.model ?: "Unknown model"} • ${device.firmware ?: "Firmware unavailable"}")
                            }
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
                actions = {
                    IconButton(onClick = onSettings) { Text("⚙", style = MaterialTheme.typography.titleLarge) }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(band.name, style = MaterialTheme.typography.titleLarge)

                        val info = buildList {
                            band.batteryPercentage?.let { add("🔋 ${it}%") }
                            band.charging?.let { if (it) add("⚡") }
                            band.model?.let { add("▣ $it") }
                            band.hardware?.let { add("⌁ $it") }
                            band.firmware?.let { add("ⓘ $it") }
                            band.countryVariant?.let { add("🌐 $it") }
                            band.heartRate?.let { add("♥ $it") }
                            band.serialNumber?.let { add("SN $it") }
                        }

                        if (info.isNotEmpty()) {
                            Text(info.joinToString("  •  "))
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Band displays", style = MaterialTheme.typography.titleLarge)
            }

            if (band.displays.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("No runtime display items received yet.", style = MaterialTheme.typography.titleMedium)
                            Text("The editor remains available while the Xiaomi initialization layer is being expanded.")
                        }
                    }
                }
            } else {
                items(
                    band.displays,
                    key = { display -> "${display.code ?: "display"}:${display.name ?: "unnamed"}" }
                ) { display ->
                    RuntimeDisplayCard(display = display, onEdit = onEdit)
                }
            }

            item {
                Button(onClick = onCustomDisplay, Modifier.fillMaxWidth()) {
                    Text("＋ Custom display")
                }
            }
        }
    }
}

@Composable
private fun RuntimeDisplayCard(display: BandDisplay, onEdit: (BandDisplay) -> Unit) {
    val name = display.name?.takeIf { it.isNotBlank() }
        ?: display.code?.takeIf { it.isNotBlank() }
        ?: "Unnamed display"

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                display.code?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                val flags = buildList {
                    if (display.active) add("Current")
                    if (display.inMoreSection) add("More")
                    if (display.canDelete) add("Removable")
                    if (display.disabled) add("Disabled")
                }
                if (flags.isNotEmpty()) Text(flags.joinToString(" • "))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { onEdit(display) },
                enabled = !display.disabled
            ) { Text("Edit") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    display: BandDisplay?,
    onBack: () -> Unit,
    onAction: (String) -> Unit
) {
    var selectedTool by remember { mutableStateOf("Select") }
    var menuExpanded by remember { mutableStateOf(false) }
    val tools = listOf("Select", "Text", "Image", "Shape", "Sticker", "Filter", "Font", "Align")

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text(display?.name ?: "Custom display") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("‹", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Text("⋮", style = MaterialTheme.typography.headlineMedium)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
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
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                Modifier.fillMaxWidth().weight(1f),
                tonalElevation = 2.dp
            ) {
                Column(
                    Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Canvas", style = MaterialTheme.typography.labelLarge)
                        Text("Band display", style = MaterialTheme.typography.labelMedium)
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(Modifier.width(174.dp).height(238.dp)) {
                            Column(
                                Modifier.fillMaxSize().padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "12:45",
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(10.dp))
                                Text("Tuesday  •  25 Aug")
                                Spacer(Modifier.height(18.dp))
                                Text("♥ 72   •   6,421")
                                Spacer(Modifier.height(12.dp))
                                Text(selectedTool, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Text("Tool: $selectedTool", style = MaterialTheme.typography.labelMedium)
                }
            }

            Divider()

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tools.forEach { tool ->
                    OutlinedButton(onClick = { selectedTool = tool }) {
                        Text(if (tool == selectedTool) "✓ $tool" else tool)
                    }
                }
            }

            Button(
                onClick = { onAction("save") },
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
            ) {
                Text("Save display")
            }
        }
    }
}

@Composable
private fun AuthKeyInstructionsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find your Xiaomi auth key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Open Gadgetbridge and open your Xiaomi Band device.")
                Text("2. Open the device's pairing or authentication details. The label can vary by Gadgetbridge version.")
                Text("3. Copy the 32-character hexadecimal authentication key into Miit.")
                Text("4. If your pairing flow uses a QR/key exchange, use the Gadgetbridge pairing screen to obtain the authentication value.")
                Text("The key is specific to each device. Do not share it publicly.")
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } }
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
                Text("Manage editor, fonts and AI provider preferences.")
                Text("Band")
                Text("Band-specific values are detected at runtime.")
                Text("Privacy")
                Text("Authentication keys stay on this device.")
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun FloatingLogButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    offset += amount
                }
            }
    ) {
        FloatingActionButton(
            onClick = {
                val log = MiitTestLog.text(context)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Miit testing logs", log))
                Toast.makeText(context, "Testing log copied.", Toast.LENGTH_LONG).show()
            },
            shape = RectangleShape
        ) {
            Text("LOG", fontWeight = FontWeight.Bold)
        }
    }
}

private fun connectionInstruction(state: BandConnectionState): String = when (state) {
    BandConnectionState.Idle -> "Enter your Xiaomi auth key, scan for the band, then tap Connect."
    BandConnectionState.Scanning -> "Keep the band nearby and awake, then select it from the list."
    BandConnectionState.Connecting -> "Complete Android's pairing prompt and wait for Xiaomi authentication."
    BandConnectionState.Connected -> "The Bluetooth transport is connected. Authentication is continuing."
    BandConnectionState.AwaitingXiaomiBinding -> "Android pairing is complete. Xiaomi binding is continuing."
    BandConnectionState.Authenticating -> "Authenticating with the band… please wait."
    BandConnectionState.Authenticated -> "Connection complete. Runtime band data will appear as it arrives."
    BandConnectionState.Disconnected -> "The band disconnected. Scan and connect again."
    BandConnectionState.Error -> "Connection failed. Check the auth key and try again."
}