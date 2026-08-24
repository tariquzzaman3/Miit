package com.miit.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.miit.app.band.BandConnectionState
import com.miit.app.band.BandDevice
import com.miit.app.band.BandScanner
import com.miit.app.band.MiitTestLog

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
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissions.launch(requested)
        setContent { MiitApp() }
    }
}

@Composable
fun MiitApp() {
    val nav = rememberNavController()
    var selected by remember { mutableIntStateOf(0) }
    val routes = listOf("home", "editor", "settings")
    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Miit") }) },
            bottomBar = {
                NavigationBar {
                    listOf("Band", "Editor", "Settings").forEachIndexed { index, label ->
                        NavigationBarItem(selected = selected == index, onClick = { selected = index; nav.navigate(routes[index]) }, icon = { Text(listOf("⌚", "✎", "⚙")[index]) }, label = { Text(label) })
                    }
                }
            }
        ) { padding ->
            NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") { HomeScreen(onEditor = { selected = 1; nav.navigate("editor") }) }
                composable("editor") { EditorScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}

@Composable
private fun HomeScreen(onEditor: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scanner = remember { BandScanner(context) }
    val devices by scanner.devices.collectAsState()
    val state by scanner.state.collectAsState()
    val saved = remember { mutableStateListOf("My first watchface", "Minimal digital") }
    DisposableEffect(Unit) { onDispose { scanner.close() } }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Mi Band connection", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(connectionMessage(state))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { MiitTestLog.add("User tapped Scan for band"); scanner.startScan() }) { Text(if (state == BandConnectionState.Scanning) "Scanning…" else "Scan for band") }
                        if (state == BandConnectionState.Scanning) Button(onClick = { MiitTestLog.add("User tapped Stop scan"); scanner.stopScan() }) { Text("Stop") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val log = MiitTestLog.text(context)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Miit testing logs", log))
                            Toast.makeText(context, "Testing log copied. Paste it into ChatGPT.", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📋 Copy testing logs") }
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { MiitTestLog.clear(); Toast.makeText(context, "Testing log cleared", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth()) { Text("Clear testing logs") }
                }
            }
        }
        if (devices.isNotEmpty()) {
            item { Text("Detected bands", style = MaterialTheme.typography.titleLarge) }
            items(devices, key = { it.address }) { device -> BandDeviceCard(device = device, onConnect = { MiitTestLog.add("User tapped Connect: ${device.name} (${device.address})"); scanner.connect(device) }) }
        }
        item { Text("My displays", style = MaterialTheme.typography.titleLarge) }
        items(saved) { name -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); Button(onClick = onEditor) { Text("Edit") } } } }
        item { Button(onClick = onEditor, Modifier.fillMaxWidth()) { Text("＋ Create from scratch") } }
    }
}

@Composable
private fun BandDeviceCard(device: BandDevice, onConnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium)
            Text("Signal: ${device.rssi} dBm")
            Text("Address: ${device.address}")
            device.model?.let { Text("Model: $it") }
            device.firmware?.let { Text("Firmware: $it") }
            device.manufacturer?.let { Text("Manufacturer: $it") }
            Spacer(Modifier.height(6.dp))
            Button(onClick = onConnect) { Text("Connect") }
        }
    }
}

private fun connectionMessage(state: BandConnectionState): String = when (state) {
    BandConnectionState.Idle -> "Ready to scan for nearby Mi Band / Xiaomi Smart Band devices."
    BandConnectionState.Scanning -> "Scanning… keep the band nearby and awake."
    BandConnectionState.Connecting -> "Connecting to the selected band…"
    BandConnectionState.Connected -> "Connected. Reading available device information."
    BandConnectionState.Authenticating -> "Connected. Preparing authentication protocol."
    BandConnectionState.Authenticated -> "Authenticated and ready."
    BandConnectionState.Disconnected -> "Band disconnected."
    BandConnectionState.Error -> "Bluetooth operation failed. Check Bluetooth and permissions, then try again."
}

@Composable
private fun EditorScreen() {
    var tool by remember { mutableStateOf("Select") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Watchface editor", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth().weight(1f)) { Column(Modifier.fillMaxSize().padding(16.dp)) { Text("Canvas preview", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(12.dp)); Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp)) { Text("12:45", style = MaterialTheme.typography.displayLarge); Text("Tuesday  •  25 Aug"); Spacer(Modifier.height(20.dp)); Text("♥ 72   •   6,421 steps") } } }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Select", "Text", "Image", "Shape", "AI", "Font").forEach { t -> FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(t) }) } }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = {}) { Text("Save") }; Button(onClick = {}) { Text("Send to band") } }
    }
}

@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("AI providers", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(6.dp)); Text("Add your own API keys. Miit will keep provider configuration local to the device."); Spacer(Modifier.height(12.dp)); listOf("Local / offline AI", "OpenAI-compatible API", "Google AI-compatible API").forEach { Text("• $it") } } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Fonts", style = MaterialTheme.typography.titleMedium); Text("Use Android/device fonts, imported font files, and Google Fonts where licensing permits.") } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Compatibility", style = MaterialTheme.typography.titleMedium); Text("Device-specific communication and compiler modules are designed to be replaceable per band family and firmware format.") } }
    }
}
